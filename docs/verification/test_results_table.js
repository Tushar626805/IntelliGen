// Run: node docs/verification/test_results_table.js   (pure helpers of results-table.js)
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', '..');
const dir = ['springboot_backend/src/src/main/resources/static', 'src/src/main/resources/static', 'src/main/resources/static'].map(d => path.join(root, d)).find(d => fs.existsSync(path.join(d, 'results-table.js')));
const T = require(path.join(dir, 'results-table.js'));
let n = 0, bad = 0; const ok = (name, c) => { n++; if (!c) bad++; console.log((c ? '  PASS  ' : '  FAIL  ') + name); };

const flat = T.normalize({ rollNo: 'R10', studentName: 'Asha Rao', marksObtained: 3, totalMarks: 4 });
ok('flat backend fields are read', flat.roll === 'R10' && flat.name === 'Asha Rao' && flat.pct === 75);
const nested = T.normalize({ student: { name: 'Ben', prnNo: 'P9' }, marksObtained: 1, totalMarks: 3 });
ok('nested student shape falls back to name and PRN', nested.name === 'Ben' && nested.roll === 'P9' && nested.pct === 33);
ok('zero-mark exam gives no percentage instead of NaN', T.normalize({ marksObtained: 0, totalMarks: 0 }).pct === null);
ok('missing name becomes Unknown Student, missing roll stays empty', T.normalize({}).name === 'Unknown Student' && T.normalize({}).roll === '');
ok('string numbers from the server are handled', T.normalize({ marksObtained: '2', totalMarks: '4' }).pct === 50);

const rows = [
    { roll: 'R10', name: 'Zed', marks: 2, total: 4, pct: 50 }, { roll: 'R2', name: 'amy', marks: 4, total: 4, pct: 100 },
    { roll: '', name: 'Bob', marks: 0, total: 0, pct: null }, { roll: 'R1', name: 'Cy', marks: 2, total: 4, pct: 50 }];
ok('search matches name, case-insensitively', T.filterRows(rows, 'AMY').length === 1);
ok('search matches roll number', T.filterRows(rows, 'r1').map(r => r.name).join() === 'Zed,Cy');
ok('empty search returns everyone', T.filterRows(rows, '  ').length === 4);
ok('roll numbers sort naturally (R1, R2, R10)', T.sortRows(rows.filter(r => r.roll), 'roll', 'asc').map(r => r.roll).join() === 'R1,R2,R10');
ok('names sort case-insensitively', T.sortRows(rows, 'name', 'asc').map(r => r.name).join() === 'amy,Bob,Cy,Zed');
ok('percentage descending keeps missing values last', T.sortRows(rows, 'pct', 'desc').map(r => r.name).join() === 'amy,Zed,Cy,Bob');
ok('percentage ascending also keeps missing values last', T.sortRows(rows, 'pct', 'asc').map(r => r.name).pop() === 'Bob');
ok('sort is stable for equal values (Zed before Cy)', T.sortRows(rows, 'pct', 'desc').map(r => r.name).slice(1, 3).join() === 'Zed,Cy');
ok('sorting does not mutate the input', rows[0].name === 'Zed');
const h = T.rowHtml({ roll: '<b>R1</b>', name: '<img src=x onerror=alert(1)>', marks: 1, total: 4, pct: 25 });
ok('row HTML escapes names and roll numbers', !/<img|<b>/.test(h) && /&lt;img/.test(h));
ok('failing score is red, passing is green', /text-red-600/.test(h) && /text-green-600/.test(T.rowHtml({ roll: 'R', name: 'n', marks: 4, total: 4, pct: 100 })));
ok('missing percentage renders a dash, not NaN', !/NaN/.test(T.rowHtml({ roll: 'R', name: 'n', marks: 0, total: 0, pct: null })));
console.log(`\n${n} checks run - ${bad ? bad + ' FAILED' : 'all passed'}`); process.exit(bad ? 1 : 0);
