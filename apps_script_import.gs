// Vitazone: приём выгрузки дебиторки из 1С в лист "Импорт"
const IMPORT_SHEET = 'Импорт';

// Проверка из браузера: откройте URL развертывания — должен вернуться {"ok":true,...}
function doGet() {
  const sh = SpreadsheetApp.getActive().getSheetByName(IMPORT_SHEET);
  return json_({ ok: true, sheet: IMPORT_SHEET, sheetFound: !!sh, rows: sh ? Math.max(sh.getLastRow() - 1, 0) : 0 });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const data = JSON.parse(e.postData.contents);
    let imported = 0;

    if (data.importRows && data.importRows.length) {
      imported = writeImport_(data.importRows, data.reportDate);
    }

    // Если у вас уже есть обработка data.shipments / data.payments — оставьте её здесь.

    return json_({ success: true, imported: imported });
  } catch (err) {
    return json_({ success: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

// Строки: МП | Док номер | Дата составления | Тип / Контрагент | Док номер.Статус | Приход | Продажа | Долг  (A..H)
// Колонки I и J («Отсрочка, дней») не трогаем. Дата отчёта пишется в ячейку справа от «Дата отчёта:» в 1-й строке.
function writeImport_(rows, reportDate) {
  const sh = SpreadsheetApp.getActive().getSheetByName(IMPORT_SHEET);
  if (!sh) throw new Error('Нет листа "' + IMPORT_SHEET + '"');

  const last = sh.getLastRow();
  if (last > 1) sh.getRange(2, 1, last - 1, 8).clearContent();

  const num = v => (v === '' || v === null || isNaN(Number(v))) ? v : Number(v);
  const values = rows.map(r => [r[0], num(r[1]), r[2], r[3], r[4], num(r[5]), num(r[6]), num(r[7])]);
  sh.getRange(2, 1, values.length, 8).setValues(values);

  if (reportDate) {
    const hdr = sh.getRange(1, 1, 1, sh.getLastColumn()).getValues()[0];
    const idx = hdr.findIndex(h => String(h).toLowerCase().replace(/ё/g, 'е').indexOf('дата отчета') === 0);
    if (idx >= 0) sh.getRange(1, idx + 2).setValue(reportDate);
  }
  return values.length;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
