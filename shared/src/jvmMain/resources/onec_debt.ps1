# Vitazone: берёт дебиторку из 1С через COM (V83.COMConnector).
#
# Основной путь: запускает САМ отчёт «Дебиторка» (его схему компоновки) и отдаёт его строки как есть —
# «Приход», «Расход», «На конец» берутся из отчёта, ничего не пересчитывается.
# Запасной путь: если отчёт не запустился или не дал ни одной строки, читается регистр «Дебиторка»
# тем же запросом, что работал раньше (в ответе source = register, причина — в warn).
#
# Один проход: приложение запускает скрипт заново для каждой проверки (свежее подключение к базе).
#
# ВАЖНО про PowerShell: функция, которая «возвращает» коллекцию (таблицу значений, список колонок и т.п.),
# на самом деле выдаёт её ЭЛЕМЕНТЫ по одному. Пустая таблица превращается в $null, таблица из одной
# строки — в саму строку, а несколько значений — в массив. Поэтому:
#   * все обращения к COM возвращают значение через «return ,$x» (без разворачивания);
#   * COM-объект в аргументы передаётся как (,$obj), а не @($obj);
#   * результат не возвращается из функции, а кладётся в $script:Res и проверяется по типу.
param(
  [Parameter(Mandatory=$true)][string]$ConnFile,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [int]$IntervalSec = 45,
  [int]$ParentPid = 0,
  [string]$ProgId = "V83.COMConnector",
  [string]$Variant = ""
)
$ErrorActionPreference = "Stop"
# Номер версии скрипта: видно в журнале приложения (строка 🔎 1С: скрипт vN).
$ScriptVersion = "8"
$inv = [Globalization.CultureInfo]::InvariantCulture
$utf8 = New-Object System.Text.UTF8Encoding($false)

# --- обращение к COM-объектам 1С по имени (без разворачивания коллекций) ---
function P($o, $n) {
  if ($null -eq $o) { throw "нет объекта для свойства $n" }
  return ,([System.__ComObject].InvokeMember($n, [Reflection.BindingFlags]::GetProperty, $null, $o, $null))
}
function M($o, $n, [object[]]$a) {
  if ($null -eq $o) { throw "нет объекта для метода $n" }
  if ($null -eq $a) { $a = [object[]]@() }
  return ,([System.__ComObject].InvokeMember($n, [Reflection.BindingFlags]::InvokeMethod, $null, $o, $a))
}
function NewObj($c, $en, $ru) {
  try { return ,($c.NewObject($en)) } catch { return ,($c.NewObject($ru)) }
}
function To-Num($v) { if ($null -eq $v) { return 0.0 } try { return [double]$v } catch { return 0.0 } }
function To-Str($v) { if ($null -eq $v) { return "" } return ([string]$v).Trim() }

# Значение ячейки -> текст. Ссылки 1С (контрагент, документ, МП) приходят COM-объектами:
# у них [string] даёт «System.__ComObject», поэтому берём представление через String() из 1С.
function Cell-Str($c, $v) {
  if ($null -eq $v) { return "" }
  if ($v -is [DateTime]) { if ($v.Year -lt 1900) { return "" } else { return $v.ToString("dd.MM.yyyy", $inv) } }
  if ($v -is [bool]) { return "" }
  if ($v -is [string]) { return $v.Trim() }
  if ($v -is [ValueType]) { return ([string]$v).Trim() }
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
  [IO.File]::WriteAllText($OutFile, $json, $utf8)
}

# --- Основной путь: сам отчёт «Дебиторка» ---
$script:Res = $null
$script:Rows = $null
$script:Ix = @{}

function Col-Index($colNames, [string[]]$variants) {
  foreach ($want in $variants) {
    $w = $want.ToLower().Replace(" ", "")
    for ($j = 0; $j -lt $colNames.Count; $j++) {
      if ($colNames[$j].ToLower().Replace(" ", "") -eq $w) { return $j }
    }
  }
  return -1
}

function Raw($r, [string]$key) {
  $j = $script:Ix[$key]
  if ($j -lt 0) { return ,$null }
  return ,(M $r "Get" @($j))
}

# Обход дерева результата отчёта. Строки группировок (МП, контрагент) передают свои значения вниз;
# строка, где впервые появился «Док номер», — это строка документа: её и берём, глубже не идём.
function Walk($c, $coll, $inh) {
  $n = M $coll "Count" @()
  for ($i = 0; $i -lt $n; $i++) {
    $r = M $coll "Get" @($i)
    $h = $inh.Clone()
    foreach ($k in @("mp", "kontr", "date", "note")) {
      $s = Cell-Str $c (Raw $r $k)
      if ($s -ne "") { $h[$k] = $s }
    }
    $docRaw = Raw $r "doc"
    $doc = Cell-Str $c $docRaw
    if ($doc -ne "") {
      # Дата и назначение — если в отчёте нет таких колонок, берём из самого документа (справочника).
      if ($h["date"] -eq "" -and $null -ne $docRaw -and -not ($docRaw -is [ValueType]) -and -not ($docRaw -is [string])) {
        try { $h["date"] = Cell-Str $c (P $docRaw "ДатаСоставления") } catch { }
      }
      if ($h["note"] -eq "" -and $null -ne $docRaw -and -not ($docRaw -is [ValueType]) -and -not ($docRaw -is [string])) {
        try { $h["note"] = Cell-Str $c (P $docRaw "Назначение") } catch { }
      }
      [void]$script:Rows.Add([ordered]@{
        mp     = [string]$h["mp"]
        doc    = $doc
        date   = [string]$h["date"]
        kontr  = [string]$h["kontr"]
        note   = [string]$h["note"]
        prihod = To-Num (Raw $r "in")
        rashod = To-Num (Raw $r "out")
        dolg   = To-Num (Raw $r "end")
      })
      continue
    }
    $kids = $null
    try { $kids = P $r "Rows" } catch { $kids = $null }
    if ($null -ne $kids) { Walk $c $kids $h }
  }
}

function Run-Report($c) {
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

  # Параметры отчёта (период и т.п.) — только для журнала, ничего не меняем.
  $params = New-Object System.Collections.ArrayList
  try {
    $items = P (P $settings "DataParameters") "Items"
    $pc = M $items "Count" @()
    for ($i = 0; $i -lt $pc; $i++) {
      $it = M $items "Get" @($i)
      $pn = Cell-Str $c (P $it "Parameter")
      $pv = Cell-Str $c (P $it "Value")
      $pu = $false
      try { $pu = [bool](P $it "Use") } catch { }
      [void]$params.Add("$pn=" + $(if ($pv -ne "") { $pv } else { "—" }) + $(if ($pu) { "" } else { " (выкл)" }))
    }
  } catch { }

  $composer = NewObj $c "DataCompositionTemplateComposer" "КомпоновщикМакетаКомпоновкиДанных"
  # В разных версиях 1С/COM метод Type может отсутствовать, а NewObject для генератора — не работать.
  # Пробуем несколько вариантов, включая Execute без явного генератора (по умолчанию — коллекция значений).
  $template = $null
  $genErr = ""
  try {
    $genType = $null
    try { $genType = M $c "Type" @("DataCompositionValueCollectionTemplateGenerator") } catch { }
    if ($null -eq $genType) { try { $genType = M $c "Type" @("ГенераторМакетаКомпоновкиДанныхДляКоллекцииЗначений") } catch { } }
    if ($null -eq $genType) { try { $genType = M $c "Тип" @("ГенераторМакетаКомпоновкиДанныхДляКоллекцииЗначений") } catch { } }
    if ($null -ne $genType) { $template = M $composer "Execute" @($schema, $settings, $null, $null, $genType) }
  } catch { $genErr = $_.Exception.Message }
  if ($null -eq $template) {
    try { $template = M $composer "Execute" @($schema, $settings, $null, $null) } catch { throw "не удалось построить макет отчёта (Type: $genErr; Execute: " + $_.Exception.Message + ")" }
  }
  $proc = NewObj $c "DataCompositionProcessor" "ПроцессорКомпоновкиДанных"
  [void](M $proc "Initialize" @($template, $null, $null))
  # Дерево значений, а не таблица: отчёт с группировками (МП -> документ) в таблицу не выводится.
  $tree = NewObj $c "ValueTree" "ДеревоЗначений"
  $outp = NewObj $c "DataCompositionResultValueCollectionOutputProcessor" "ПроцессорВыводаРезультатаКомпоновкиДанныхВКоллекциюЗначений"
  [void](M $outp "SetObject" (,$tree))
  [void](M $outp "Output" (,$proc))

  $cols = P $tree "Columns"
  $cc = M $cols "Count" @()
  $colNames = New-Object System.Collections.ArrayList
  for ($j = 0; $j -lt $cc; $j++) { [void]$colNames.Add((To-Str (P (M $cols "Get" @($j)) "Name"))) }

  $script:Ix = @{
    'mp'    = Col-Index $colNames @("МП")
    'doc'   = Col-Index $colNames @("ДокНомер", "Док номер", "Договор")
    'date'  = Col-Index $colNames @("ДатаСоставления", "Дата составления", "ДокНомерДатаСоставления", "ДокНомер.ДатаСоставления")
    'kontr' = Col-Index $colNames @("Контрагенты", "Контрагент")
    'note'  = Col-Index $colNames @("ДокНомерНазначение", "ДокНомер.Назначение", "Назначение", "ДокНомерСтатус", "ДокНомер.Статус")
    'in'    = Col-Index $colNames @("Приход", "ПриходСум", "ЗадолженностьСумПриход")
    'out'   = Col-Index $colNames @("Расход", "РасходСум", "ЗадолженностьСумРасход")
    'end'   = Col-Index $colNames @("НаКонец", "На конец", "НаКонецСум", "Остаток", "ЗадолженностьСумКонечныйОстаток", "ЗадолженностьСумОстаток")
  }
  if ($script:Ix["doc"] -lt 0 -or $script:Ix["end"] -lt 0) {
    throw "в отчёте нет колонок «Док номер»/«На конец». Колонки отчёта: " + ($colNames -join ", ")
  }

  $script:Rows = New-Object System.Collections.ArrayList
  Walk $c (P $tree "Rows") @{ mp = ""; kontr = ""; date = ""; note = "" }
  if ($script:Rows.Count -eq 0) {
    throw "отчёт выполнился, но не дал ни одной строки с номером документа. Колонки: " + ($colNames -join ", ") +
      $(if ($params.Count -gt 0) { ". Параметры: " + ($params -join "; ") } else { "" })
  }

  $script:Res = [ordered]@{
    source = "report"; variant = $usedVariant
    variants = [object[]]$names.ToArray(); columns = [object[]]$colNames.ToArray(); params = [object[]]$params.ToArray()
    rows = [object[]]$script:Rows.ToArray()
  }
}

# --- Запасной путь: прямой запрос к регистру (так приложение работало до перехода на отчёт) ---
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

function Run-Register($c) {
  $q = $c.NewObject("Query")
  $q.Text = $fallbackQuery
  [void]$q.SetParameter("YearStart", (Get-Date -Month 1 -Day 1).Date)
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
  $script:Res = [ordered]@{
    source = "register"; variant = ""; variants = [object[]]@(); columns = [object[]]@(); params = [object[]]@()
    rows = [object[]]$rows.ToArray()
  }
}

# --- Один проход ---
try {
  $connStr = [IO.File]::ReadAllText($ConnFile, [Text.Encoding]::UTF8).Trim()
  $connector = New-Object -ComObject $ProgId
  $conn = $connector.Connect($connStr)

  $warn = ""
  $script:Res = $null
  try {
    Run-Report $conn
    if (-not ($script:Res -is [System.Collections.IDictionary])) { throw "отчёт не вернул результат" }
  } catch {
    $warn = "отчёт «Дебиторка» не дал данных (" + $_.Exception.Message + ") — данные взяты из регистра"
    $script:Res = $null
    Run-Register $conn
  }
  if (-not ($script:Res -is [System.Collections.IDictionary])) { throw "не удалось получить данные ни из отчёта, ни из регистра" }

  $out = $script:Res
  $out["ok"] = $true
  $out["ver"] = $ScriptVersion
  $out["ts"] = (Get-Date).ToString("s", $inv)
  if ($warn -ne "") { $out["warn"] = $warn }
  Write-Result $out
} catch {
  $msg = $_.Exception.Message
  if ($_.InvocationInfo -and $_.InvocationInfo.ScriptLineNumber) { $msg = "$msg (строка $($_.InvocationInfo.ScriptLineNumber))" }
  try { Write-Result ([ordered]@{ ok = $false; ver = $ScriptVersion; ts = (Get-Date).ToString("s", $inv); error = $msg }) } catch { }
}
