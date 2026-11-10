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

function Write-Result($obj) {
  $json = ConvertTo-Json -InputObject $obj -Depth 5 -Compress
  $tmp = "$OutFile.tmp"
  [IO.File]::WriteAllText($tmp, $json, $utf8)
  Move-Item -LiteralPath $tmp -Destination $OutFile -Force
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
      $d = $sel.DocDate
      $ds = ""
      if ($d -is [DateTime] -and $d.Year -gt 1900) { $ds = $d.ToString("dd.MM.yyyy", $inv) }
      [void]$rows.Add([ordered]@{
        mp = [string]$sel.MP; doc = [string]$sel.Doc; date = $ds
        kontr = [string]$sel.Kontr; note = [string]$sel.Note
        inAll = [double]$sel.InAll; outAll = [double]$sel.OutAll
        inSale = [double]$sel.InSale; outSale = [double]$sel.OutSale
        inFin = [double]$sel.InFin; outFin = [double]$sel.OutFin
        inSaleFin = [double]$sel.InSaleFin; outSaleFin = [double]$sel.OutSaleFin
        cnt = [int]$sel.Cnt
      })
    }
    Write-Result ([ordered]@{ ok = $true; ts = (Get-Date).ToString("s"); rows = @($rows) })
  } catch {
    $conn = $null
    Write-Result ([ordered]@{ ok = $false; ts = (Get-Date).ToString("s"); error = $_.Exception.Message })
  }
  Start-Sleep -Seconds $IntervalSec
}
