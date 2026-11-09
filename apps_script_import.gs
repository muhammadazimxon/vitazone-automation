// Добавьте эту функцию в ваш Apps Script и вызовите из doPost(e):
//
//   const data = JSON.parse(e.postData.contents);
//   if (data.importRows && data.importRows.length) writeImport_(data.importRows);   // <-- новая строка
//   ... дальше ваш существующий код для data.shipments / data.payments ...
//
// Приложение шлёт importRows = строки A..K (A..I из отчёта 1С, J пусто, K = дата отчёта).
// Колонка J ("Отсрочка", вводится вручную) НЕ перезаписывается.
function writeImport_(rows) {
  const sh = SpreadsheetApp.getActive().getSheetByName('Импорт');
  const last = sh.getLastRow();
  if (last > 1) {
    sh.getRange(2, 1, last - 1, 9).clearContent();   // A..I
    sh.getRange(2, 11, last - 1, 1).clearContent();  // K
  }
  sh.getRange(2, 1, rows.length, 9).setValues(rows.map(r => r.slice(0, 9)));
  sh.getRange(2, 11, rows.length, 1).setValues(rows.map(r => [r[10]]));
}
