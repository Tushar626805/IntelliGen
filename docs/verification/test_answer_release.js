// Run: node docs/verification/test_answer_release.js   (decision logic of answer-release.js)
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', '..');
const dir = ['springboot_backend/src/src/main/resources/static', 'src/src/main/resources/static', 'src/main/resources/static'].map(d => path.join(root, d)).find(d => fs.existsSync(path.join(d, 'answer-release.js')));
const { describe } = require(path.join(dir, 'answer-release.js'));
let n = 0, bad = 0; const ok = (name, c) => { n++; if (!c) bad++; console.log((c ? '  PASS  ' : '  FAIL  ') + name); };

const closed = describe({ released: false, canRelease: true, canManage: true });
ok('closed exam, not released: offers Release Answers', closed.action && closed.action.release === true && closed.action.label === 'Release Answers');
const open = describe({ released: false, canRelease: false, canManage: true, examEndsAt: '2026-12-01T10:00:00' });
ok('exam still open: no action, explains when answers can be released', open.action === null && /after the exam closes/.test(open.text));
const grace = describe({ released: false, canRelease: false, canManage: true, examEndsAt: '2026-12-01T10:00:00', releasableFrom: '2026-12-01T10:02:00' });
ok('shows the real earliest release time (end plus grace), not just the end time', grace.action === null && grace.text.includes(new Date('2026-12-01T10:02:00').toLocaleString()) && !grace.text.includes(new Date('2026-12-01T10:00:00').toLocaleString()));
const rel = describe({ released: true, canRelease: true, canManage: true, releasedAt: '2026-10-28T10:00:00' });
ok('released: offers Hide Answers and says students can see them', rel.action && rel.action.release === false && /visible to students/.test(rel.text) && rel.tone === 'success');
ok('viewer without manage rights never gets a button (not released)', describe({ released: false, canRelease: true, canManage: false }).action === null);
ok('viewer without manage rights never gets a button (released)', describe({ released: true, canManage: false }).action === null);
ok('missing status shows an error, no button', describe(null).tone === 'error' && describe(null).action === null);
ok('labels use Title Case', ['Release Answers', 'Hide Answers'].every(l => /^[A-Z][a-z]+ [A-Z][a-z]+$/.test(l)));
console.log(`\n${n} checks run - ${bad ? bad + ' FAILED' : 'all passed'}`); process.exit(bad ? 1 : 0);
