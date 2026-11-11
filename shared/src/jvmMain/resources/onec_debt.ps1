# Vitazone: чтение дебиторки напрямую из базы 1С через COM (V83.COMConnector).
# Держит одно соединение с базой, раз в IntervalSec секунд выполняет запрос к регистру "Дебиторка"
# и пишет результат в OutFile (JSON). Завершается сам, если закрыто приложение (ParentPid).
param(
  [Parameter(Mandatory=$true)][string]$ConnFile,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [int]$IntervalSec = 60,
  [int]$ParentPid = 0,
  [string]$ProgId = "V83.COMConnector"
)
$ErrorActionPreference = "Stop"
$inv = [Globalization.CultureInfo]::InvariantCulture
$utf8 = New-Object System.Text.UTF8Encoding($false)

# Порядок полей важен: значения читаются по номеру колонки (0..13)
$queryText = @'
ВЫБРАТЬ
    Т.МП.Наименование КАК MP,
    Т.ДокНомер.Наименование КАК Doc,
    Т.ДокНомер.ДатаСоставления КАК DocDate,
    Т.Контрагенты.Наименование КАК Kontr,
    ВЫРАЗИТЬ(Т.ДокНомер.Назначение КАК СТРОКА(300)) КАК Note,
    Т.InAll КАК InAll,
    Т.OutAll КАК OutAll,
    Т.InSale КАК InSale,
    Т.OutSale КАК OutSale,
    Т.InFin КАК InFin,
    Т.OutFin КАК OutFin,
    Т.InSaleFin КАК InSaleFin,
    Т.OutSaleFin КАК OutSaleFin,
    Т.Cnt КАК Cnt
ИЗ
    (ВЫБРАТЬ
        Д.МП КАК МП,
        Д.ДокНомер КАК ДокНомер,
        Д.Контрагенты КАК Контрагенты,
        СУММА(ВЫБОР КОГДА Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Приход) ТОГДА Д.ЗадолженностьСум ИНАЧЕ 0 КОНЕЦ) КАК InAll,
        СУММА(ВЫБОР КОГДА Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Расход) ТОГДА Д.ЗадолженностьСум ИНАЧЕ 0 КОНЕЦ) КАК OutAll,
        СУММА(ВЫБОР КОГДА Д.Регистратор ССЫЛКА Документ.Продажа И Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Приход) ТОГДА Д.ЗадолженностьСум ИНАЧЕ 0 КОНЕЦ) КАК InSale,
        СУММА(ВЫБОР КОГДА Д.Регистратор ССЫЛКА Документ.Продажа И Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Расход) ТОГДА Д.ЗадолженностьСум ИНАЧЕ 0 КОНЕЦ) КАК OutSale,
        СУММА(ВЫБОР КОГДА Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Приход) ТОГДА Д.СумФинанс ИНАЧЕ 0 КОНЕЦ) КАК InFin,
        СУММА(ВЫБОР КОГДА Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Расход) ТОГДА Д.СумФинанс ИНАЧЕ 0 КОНЕЦ) КАК OutFin,
        СУММА(ВЫБОР КОГДА Д.Регистратор ССЫЛКА Документ.Продажа И Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Приход) ТОГДА Д.СумФинанс ИНАЧЕ 0 КОНЕЦ) КАК InSaleFin,
        СУММА(ВЫБОР КОГДА Д.Регистратор ССЫЛКА Документ.Продажа И Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Расход) ТОГДА Д.СумФинанс ИНАЧЕ 0 КОНЕЦ) КАК OutSaleFin,
        КОЛИЧЕСТВО(*) КАК Cnt
    ИЗ
        РегистрНакопления.Дебиторка КАК Д
    ГДЕ
        Д.Активность
    СГРУППИРОВАТЬ ПО
        Д.МП,
        Д.ДокНомер,
        Д.Контрагенты) КАК Т
УПОРЯДОЧИТЬ ПО
    MP,
    DocDate
'@

# Значение колонки: сначала Get(номер), если пусто — по имени через IDispatch.
function Get-Col($sel, [int]$i, [string]$name) {
  $v = $null
  try { $v = $sel.Get($i) } catch { $v = $null }
  if ($null -eq $v) {
    try { $v = [System.__ComObject].InvokeMember($name, [Reflection.BindingFlags]::GetProperty, $null, $sel, $null) } catch { $v = $null }
  }
  return $v
}
function To-Num($v) { if ($null -eq $v) { return 0.0 } try { return [double]$v } catch { return 0.0 } }
function To-Str($v) { if ($null -eq $v) { return "" } return [string]$v }

# Атомарная запись результата; если приложение в этот момент читает файл — несколько попыток.
function Write-Result($obj) {
  $json = ConvertTo-Json -InputObject $obj -Depth 5 -Compress
  $tmp = "$OutFile.tmp"
  [IO.File]::WriteAllText($tmp, $json, $utf8)
  for ($k = 0; $k -lt 10; $k++) {
    try { Move-Item -LiteralPath $tmp -Destination $OutFile -Force; return } catch { Start-Sleep -Milliseconds 300 }
  }
}

$conn = $null
while ($true) {
  if ($ParentPid -gt 0 -and -not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { break }
  try {
    if ($null -eq $conn) {
      $connStr = [IO.File]::ReadAllText($ConnFile, [Text.Encoding]::UTF8).Trim()
      $connector = New-Object -ComObject $ProgId
      $conn = $connector.Connect($connStr)
    }
    $q = $conn.NewObject("Query")
    $q.Text = $queryText
    $sel = $q.Execute().Select()
    $rows = New-Object System.Collections.ArrayList
    while ($sel.Next()) {
      $d = Get-Col $sel 2 "DocDate"
      $ds = ""
      if ($d -is [DateTime] -and $d.Year -gt 1900) { $ds = $d.ToString("dd.MM.yyyy", $inv) }
      [void]$rows.Add([ordered]@{
        mp = (To-Str (Get-Col $sel 0 "MP")); doc = (To-Str (Get-Col $sel 1 "Doc")); date = $ds
        kontr = (To-Str (Get-Col $sel 3 "Kontr")); note = (To-Str (Get-Col $sel 4 "Note"))
        inAll = (To-Num (Get-Col $sel 5 "InAll")); outAll = (To-Num (Get-Col $sel 6 "OutAll"))
        inSale = (To-Num (Get-Col $sel 7 "InSale")); outSale = (To-Num (Get-Col $sel 8 "OutSale"))
        inFin = (To-Num (Get-Col $sel 9 "InFin")); outFin = (To-Num (Get-Col $sel 10 "OutFin"))
        inSaleFin = (To-Num (Get-Col $sel 11 "InSaleFin")); outSaleFin = (To-Num (Get-Col $sel 12 "OutSaleFin"))
        cnt = [int](To-Num (Get-Col $sel 13 "Cnt"))
      })
    }
    Write-Result ([ordered]@{ ok = $true; ts = (Get-Date).ToString("s"); rows = @($rows) })
  } catch {
    $conn = $null
    $msg = $_.Exception.Message
    try { Write-Result ([ordered]@{ ok = $false; ts = (Get-Date).ToString("s"); error = $msg }) } catch { }
  }
  Start-Sleep -Seconds $IntervalSec
}
