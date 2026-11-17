// Run: node docs/verification/test_phase6.js   (notifications, list filter, CSV export, question-bank helpers)
const path = require('path'); const S = path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'static');
let n = 0; const ok = (name, c) => { n++; console.log((c ? '  PASS  ' : '  FAIL  ') + name); if (!c) process.exitCode = 1; };
const N = require(path.join(S, 'notifications.js')), F = require(path.join(S, 'list-filter.js')), E = require(path.join(S, 'export.js')), R = require(path.join(S, 'ai-review.js'));

console.log('== notifications');
const now = Date.UTC(2026, 9, 25, 12, 0, 0), ago = (s) => new Date(now - s * 1000).toISOString();
ok('just now', N.timeAgo(ago(10), now) === 'just now');
ok('minutes / hours / days', N.timeAgo(ago(5 * 60), now) === '5 min ago' && N.timeAgo(ago(3 * 3600), now) === '3 h ago' && N.timeAgo(ago(2 * 86400), now) === '2 d ago');
ok('old ones show a date, bad input shows nothing', /\d/.test(N.timeAgo(ago(30 * 86400), now)) && N.timeAgo('garbage', now) === '');
ok('future timestamps never go negative', N.timeAgo(ago(-500), now) === 'just now');
const html = N.itemsHtml([{ id: 7, title: '<b>Hi</b>', message: '<img src=x onerror=alert(1)>', link: 'student-exam.html', read: false, createdAt: ago(60) },
                          { id: 8, title: 'Old', message: 'm', link: null, read: true, createdAt: ago(60) }], now);
ok('titles/messages are escaped', !/<img|<b>/.test(html) && /&lt;img/.test(html) && /&lt;b&gt;Hi/.test(html));
ok('unread items are marked (class + screen-reader text); read ones are not', /nt-unread[^>]*data-id="7"/.test(html) && /\(unread\)/.test(html) && !/nt-unread[^>]*data-id="8"/.test(html));
ok('link and id carried on the button', /data-id="7" data-link="student-exam.html"/.test(html));
ok('empty state', /all caught up/.test(N.itemsHtml([], now)) && /all caught up/.test(N.itemsHtml(null, now)));

console.log('== exam list filter');
ok('empty query matches everything', F.matches('Physics Midterm', '', false, ''));
ok('case-insensitive text search', F.matches('Physics Midterm by Dr Rao', 'MIDTERM', false, '') && !F.matches('Physics Midterm', 'chemistry', false, ''));
ok('search is trimmed', F.matches('Physics Midterm', '  physics ', false, ''));
ok('status DRAFT only matches drafts', F.matches('x', '', true, 'DRAFT') && !F.matches('x', '', false, 'DRAFT'));
ok('status PUBLISHED only matches non-drafts', F.matches('x', '', false, 'PUBLISHED') && !F.matches('x', '', true, 'PUBLISHED'));
ok('text and status combine', F.matches('Maths quiz', 'maths', true, 'DRAFT') && !F.matches('Maths quiz', 'physics', true, 'DRAFT'));

console.log('== CSV export');
ok('cells are quoted and inner quotes doubled', E.cell('say "hi"') === '"say ""hi"""');
['=HYPERLINK("http://evil")', '+1+1', '-2+3', '@SUM(A1)', '\tTAB', '\rCR'].forEach(f => ok('formula guard: ' + JSON.stringify(f).slice(0, 18), E.cell(f).startsWith('"\'')));
ok('real numbers are not altered (negative stays a number)', E.cell(-3) === '"-3"' && E.cell(42) === '"42"');
ok('null/undefined become empty cells', E.cell(null) === '""' && E.cell(undefined) === '""');
ok('commas and newlines stay inside the quotes', E.cell('a,b\nc') === '"a,b\nc"');
const rows = E.resultRows([
  { rollNo: 'R1', studentName: 'Asha', marksObtained: 8, totalMarks: 10, submissionTime: '2026-10-25T10:00:00' },
  { student: { name: '=cmd|x', prnNo: 'P9' }, marksObtained: 3, totalMarks: 10 },
  { rollNo: 'R3', studentName: 'Zero', marksObtained: 0, totalMarks: 0 },
  { rollNo: 'R4', studentName: 'Edge', marksObtained: 4, totalMarks: 10 }, { rollNo: 'R5', studentName: 'Edge2', marksObtained: 39, totalMarks: 100 }]);
ok('flat and nested (student.*) shapes both supported', rows[0][0] === 'R1' && rows[0][1] === 'Asha' && rows[1][0] === 'P9' && rows[1][1] === '=cmd|x');
ok('percentage and pass/fail use the 40% pass mark', rows[0][4] === 80 && rows[0][5] === 'Pass' && rows[3][5] === 'Pass' && rows[4][5] === 'Fail' && rows[1][5] === 'Fail');
ok('zero total marks gives 0%, never NaN', rows[2][4] === 0);
const csv = E.toCsv(E.HEADERS, rows);
ok('BOM for Excel, CRLF rows, header first', csv.charCodeAt(0) === 0xFEFF && csv.split('\r\n').length === 6 && csv.split('\r\n')[0].includes('"Roll no"'));
ok('a malicious student name is neutralised inside the file', csv.includes('"\'=cmd|x"') && !csv.includes(',"=cmd|x"'));
ok('filenames: unsafe characters replaced, never a path separator, always ends with -results.csv', ['Physics: Mid/Term <1>', '../../etc', 'a\\b', ''].every(x => { const f = E.safeFilename(x); return !/[\\/:<>]/.test(f) && f.endsWith('-results.csv') && f.length > 12; }));
ok('filename keeps readable words', E.safeFilename('Physics: Mid/Term <1>') === 'Physics_Mid_Term_1-results.csv');

console.log('== question bank helpers');
const existing = [{ questionText: '  What is velocity? ' }];
const incoming = [{ questionText: 'what is VELOCITY?' }, { questionText: 'What is a derivative?' }, { questionText: 'what is a derivative?' }, { questionText: '' }];
const fresh = R.newOnly(existing, incoming);
ok('already-present, duplicate-in-batch and empty questions are dropped', fresh.length === 1 && fresh[0].questionText === 'What is a derivative?');
const q = R.bankToQuestion({ questionText: 'Q?', options: ['a', 'b', 'c', 'd'], correctAnswer: 'c', marks: 2, aiGenerated: true, sourcePage: 3, sourceExcerpt: 'e' });
ok('bank question keeps answer, marks and provenance and counts as reviewed', q.correctIndex === 2 && q.marks === 2 && q.aiGenerated && q.reviewed && !R.needsAttention(q));
ok('a bank question with an unmatched answer is still flagged', R.needsAttention(R.bankToQuestion({ questionText: 'Q?', options: ['a', 'b'], correctAnswer: 'zzz' })));
console.log('\n' + n + ' checks run' + (process.exitCode ? ' - SOME FAILED' : ' - all passed'));
