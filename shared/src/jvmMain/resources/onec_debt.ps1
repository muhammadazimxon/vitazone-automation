# Vitazone: берёт дебиторку из 1С через COM (V83.COMConnector).
#
# Основной путь: запускает САМ отчёт «Дебиторка» (его схему компоновки) и отдаёт его результат
# как есть — колонки «Приход», «Расход», «На конец» берутся из отчёта, ничего не пересчитывается.
# Запасной путь: если отчёт запустить не удалось, читает регистр «Дебиторка» напрямую,
# чтобы приложение не осталось совсем без данных (в ответе source = register).
#
# Один проход: приложение запускает скрипт заново для каждой проверки (свежее подключение к базе).
param(
  [Parameter(Mandatory=$true)][string]$ConnFile,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [int]$IntervalSec = 45,
  [int]$ParentPid = 0,
  [string]$ProgId = "V83.COMConnector",
  [string]$Variant = ""
)
$ErrorActionPreference = "Stop"
$inv = [Globalization.CultureInfo]::InvariantCulture
$utf8 = New-Object System.Text.UTF8Encoding($false)

# --- обращение к COM-объектам 1С по имени ---
function P($o, $n) { return [System.__ComObject].InvokeMember($n, [Reflection.BindingFlags]::GetProperty, $null, $o, $null) }
function M($o, $n, $a) { return [System.__ComObject].InvokeMember($n, [Reflection.BindingFlags]::InvokeMethod, $null, $o, $a) }
function NewObj($c, $en, $ru) { try { return $c.NewObject($en) } catch { return $c.NewObject($ru) } }
function To-Num($v) { if ($null -eq $v) { return 0.0 } try { return [double]$v } catch { return 0.0 } }
function To-Str($v) { if ($null -eq $v) { return "" } return ([string]$v).Trim() }

function Cell-Str($c, $v) {
  if ($null -eq $v) { return "" }
  if ($v -is [DateTime]) { if ($v.Year -lt 1900) { return "" } else { return $v.ToString("dd.MM.yyyy", $inv) } }
  if ($v -is [bool]) { return "" }
  try { return ([string]$v).Trim() } catch { }
  try { return ([string]($c.String($v))).Trim() } catch { return "" }
}

# Запись результата: сначала во временный файл, потом подменой — приложение не прочитает половину.
function Write-Result($obj) {
  $json = ConvertTo-Json -InputObject $obj -Depth 6 -Compress
  $tmp = "$OutFile.tmp"
  [IO.File]::WriteAllText($tmp, $json, $utf8)
  for ($k = 0; $k -lt 10; $k++) {
    try { Move-Item -LiteralPath $tmp -Destination $OutFile -Force; return } catch { Start-Sleep -Milliseconds 300 }
  }
}

# --- Основной путь: сам отчёт «Дебиторка» ---
function Report-Rows($c) {
  $mgr = P (P $c "Reports") "Дебиторка"
  $rep = M $mgr "Create" @()
  $schema = M $rep "GetTemplate" @("ОсновнаяСхемаКомпоновкиДанных")

  $names = New-Object System.Collections.ArrayList
  $settings = $null
  $usedVariant = "(по умолчанию)"
  try {
    $vs = P $schema "SettingVariants"
    $n = M $vs "Count" @()
    for ($i = 0; $i -lt $n; $i++) {
      $v = M $vs "Get" @($i)
      $nm = To-Str (P $v "Name")
      $pr = To-Str (P $v "Presentation")
      $title = if ($pr) { $pr } else { $nm }
      [void]$names.Add($title)
      if ($Variant -ne "" -and ($Variant -eq $nm -or $Variant -eq $pr)) {
        $settings = P $v "Settings"
        $usedVariant = $title
      }
    }
  } catch { }
  if ($null -eq $settings) { $settings = P $schema "DefaultSettings" }

  $composer = NewObj $c "DataCompositionTemplateComposer" "КомпоновщикМакетаКомпоновкиДанных"
  $genType = $c.Type("ГенераторМакетаКомпоновкиДанныхДляКоллекцииЗначений")
  $template = M $composer "Execute" @($schema, $settings, $null, $null, $genType)
  $proc = NewObj $c "DataCompositionProcessor" "ПроцессорКомпоновкиДанных"
  M $proc "Initialize" @($template, $null, $null) | Out-Null
  $tz = NewObj $c "ValueTable" "ТаблицаЗначений"
  $outp = NewObj $c "DataCompositionResultValueCollectionOutputProcessor" "ПроцессорВыводаРезультатаКомпоновкиДанныхВКоллекциюЗначений"
  M $outp "SetObject" @($tz) | Out-Null
  M $outp "Output" @($proc) | Out-Null

  # Колонки отчёта -> колонки листа «Импорт». Ищем по имени, лишние колонки игнорируем.
  $cols = P $tz "Columns"
  $cc = M $cols "Count" @()
  $colNames = @()
  for ($j = 0; $j -lt $cc; $j++) { $colNames += (To-Str (P (M $cols "Get" @($j)) "Name")) }

  function Idx($variants) {
    foreach ($want in $variants) {
      for ($j = 0; $j -lt $colNames.Length; $j++) {
        if ($colNames[$j].ToLower().Replace(" ", "") -eq $want.ToLower().Replace(" ", "")) { return $j }
      }
    }
    return -1
  }
  $iMp     = Idx @("МП")
  $iDoc    = Idx @("ДокНомер", "Док номер", "Договор")
  $iDate   = Idx @("ДатаСоставления", "Дата составления", "ДокНомерДатаСоставления")
  $iKontr  = Idx @("Контрагенты", "Контрагент")
  $iNote   = Idx @("ДокНомерНазначение", "Док номер.Назначение", "Назначение", "ДокНомерСтатус", "Док номер.Статус")
  $iIn     = Idx @("Приход", "ПриходСум", "Приход Сум")
  $iOut    = Idx @("Расход", "РасходСум", "Расход Сум")
  $iEnd    = Idx @("НаКонец", "На конец", "НаКонецСум", "Остаток")
  if ($iDoc -lt 0 -or $iEnd -lt 0) {
    throw "В отчёте нет колонок «Док номер»/«На конец». Колонки: " + ($colNames -join ", ")
  }

  $rows = New-Object System.Collections.ArrayList
  $rc = M $tz "Count" @()
  for ($i = 0; $i -lt $rc; $i++) {
    $r = M $tz "Get" @($i)
    $doc = Cell-Str $c (M $r "Get" @($iDoc))
    if ($doc -eq "") { continue }   # строки группировок и итогов отчёта пропускаем
    [void]$rows.Add([ordered]@{
      mp     = if ($iMp   -ge 0) { Cell-Str $c (M $r "Get" @($iMp))   } else { "" }
      doc    = $doc
      date   = if ($iDate -ge 0) { Cell-Str $c (M $r "Get" @($iDate)) } else { "" }
      kontr  = if ($iKontr -ge 0) { Cell-Str $c (M $r "Get" @($iKontr)) } else { "" }
      note   = if ($iNote -ge 0) { Cell-Str $c (M $r "Get" @($iNote)) } else { "" }
      prihod = if ($iIn   -ge 0) { To-Num (M $r "Get" @($iIn))  } else { 0.0 }
      rashod = if ($iOut  -ge 0) { To-Num (M $r "Get" @($iOut)) } else { 0.0 }
      dolg   = To-Num (M $r "Get" @($iEnd))
    })
  }
  return [ordered]@{ source = "report"; variant = $usedVariant; variants = @($names); columns = @($colNames); rows = @($rows) }
}

# --- Запасной путь: прямой запрос к регистру ---
$fallbackQuery = @'
ВЫБРАТЬ
    Т.МП.Наименование КАК MP,
    Т.ДокНомер.Наименование КАК Doc,
    Т.ДокНомер.ДатаСоставления КАК DocDate,
    Т.Контрагенты.Наименование КАК Kontr,
    ВЫРАЗИТЬ(Т.ДокНомер.Назначение КАК СТРОКА(300)) КАК Note,
    Т.Prihod КАК Prihod,
    Т.Rashod КАК Rashod,
    Т.Dolg КАК Dolg
ИЗ
    (ВЫБРАТЬ
        Д.МП КАК МП,
        Д.ДокНомер КАК ДокНомер,
        Д.Контрагенты КАК Контрагенты,
        СУММА(ВЫБОР КОГДА Д.Период >= &YearStart И Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Приход) ТОГДА Д.ЗадолженностьСум ИНАЧЕ 0 КОНЕЦ) КАК Prihod,
        СУММА(ВЫБОР КОГДА Д.Период >= &YearStart И Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Расход) ТОГДА Д.ЗадолженностьСум ИНАЧЕ 0 КОНЕЦ) КАК Rashod,
        СУММА(ВЫБОР КОГДА Д.ВидДвижения = ЗНАЧЕНИЕ(ВидДвиженияНакопления.Приход) ТОГДА Д.ЗадолженностьСум ИНАЧЕ -Д.ЗадолженностьСум КОНЕЦ) КАК Dolg
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

function Register-Rows($c) {
  $q = $c.NewObject("Query")
  $q.Text = $fallbackQuery
  $q.SetParameter("YearStart", (Get-Date -Month 1 -Day 1).Date)
  $sel = $q.Execute().Select()
  $rows = New-Object System.Collections.ArrayList
  while ($sel.Next()) {
    $d = $sel.Get(2)
    $ds = ""
    if ($d -is [DateTime] -and $d.Year -gt 1900) { $ds = $d.ToString("dd.MM.yyyy", $inv) }
    [void]$rows.Add([ordered]@{
      mp = (To-Str $sel.Get(0)); doc = (To-Str $sel.Get(1)); date = $ds
      kontr = (To-Str $sel.Get(3)); note = (To-Str $sel.Get(4))
      prihod = (To-Num $sel.Get(5)); rashod = (To-Num $sel.Get(6)); dolg = (To-Num $sel.Get(7))
    })
  }
  return [ordered]@{ source = "register"; variant = ""; variants = @(); columns = @(); rows = @($rows) }
}

# --- Один проход ---
try {
  $connStr = [IO.File]::ReadAllText($ConnFile, [Text.Encoding]::UTF8).Trim()
  $connector = New-Object -ComObject $ProgId
  $conn = $connector.Connect($connStr)

  $warn = ""
  $res = $null
  try {
    $res = Report-Rows $conn
  } catch {
    $warn = "отчёт не запустился (" + $_.Exception.Message + "), читаю регистр"
    $res = Register-Rows $conn
  }
  $res.Insert("ok", $true)
  $res.Insert("ts", (Get-Date).ToString("s"))
  if ($warn -ne "") { $res.Insert("warn", $warn) }
  Write-Result $res
} catch {
  try { Write-Result ([ordered]@{ ok = $false; ts = (Get-Date).ToString("s"); error = $_.Exception.Message }) } catch { }
}
