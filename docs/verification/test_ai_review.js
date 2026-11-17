// Run: node docs/verification/test_ai_review.js   (tests ai-review.js logic and its generate/poll/cancel flow with mocks)
const assert = require('assert');
const path = require('path');

// --- minimal fake browser ---------------------------------------------------------------------------------------
const elements = {};
global.document = { getElementById: (id) => elements[id] || null };
const toasts = [];
global.IGUI = { toast: (m, t) => toasts.push([t, m]), esc: (s) => String(s == null ? '' : s) };
let uploadCalls = [], fetchCalls = [], jobScript = [];
global.IGApi = { upload: async (url, form, opts) => { uploadCalls.push({ url, form, opts }); if (opts && opts.onProgress) opts.onProgress(0.5); return uploadReply; } };
let uploadReply;
global.fetch = async (url, init) => {
  fetchCalls.push({ url, method: (init && init.method) || 'GET' });
  if (init && init.method === 'DELETE') return { ok: true, json: async () => ({}) };
  const next = jobScript.shift();
  return { ok: next.ok !== false, status: next.status || 200, json: async () => next.body };
};
global.FormData = class { constructor() { this.d = {}; } append(k, v) { this.d[k] = v; } };

const pure = require(path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'static', 'ai-review.js'));
const R = global.IGReview;
let n = 0; const ok = (name, cond) => { n++; console.log((cond ? '  PASS  ' : '  FAIL  ') + name); if (!cond) process.exitCode = 1; };

const aiItem = (q, answer, extra) => Object.assign({ question: q, options: ['Alpha', 'Beta', 'Gamma', 'Delta'], answer, difficulty: 'medium',
  support: 'good', source_excerpt: 'Alpha is the first letter.', source_page: 2, warnings: [] }, extra || {});

(async () => {
  console.log('== answer matching never guesses');
  ok('exact match', pure.indexOfAnswer(['a', 'b', 'c', 'd'], 'c') === 2);
  ok('case/space insensitive', pure.indexOfAnswer(['Alpha', 'Beta'], '  beta ') === 1);
  ok('unknown answer -> -1 (teacher must choose)', pure.indexOfAnswer(['a', 'b'], 'z') === -1);
  ok('empty answer -> -1', pure.indexOfAnswer(['a', 'b'], '') === -1 && pure.indexOfAnswer(['a', 'b'], null) === -1);

  console.log('== AI question conversion');
  const q = pure.fromAi(aiItem('Which letter is first?', 'Alpha'));
  ok('fields mapped', q.questionText === 'Which letter is first?' && q.correctIndex === 0 && q.aiGenerated && q.sourcePage === 2 && q.support === 'good');
  ok('unresolved answer flagged and blocks export', pure.needsAttention(pure.fromAi(aiItem('Q one two three', 'Omega'))) && pure.fromAi(aiItem('Q one two three', 'Omega')).correctIndex === -1);
  ok('warning marks attention until reviewed', pure.needsAttention(pure.fromAi(aiItem('Q one two three', 'Alpha', { warnings: ['careful'] }))));
  const w = pure.fromAi(aiItem('Q one two three', 'Alpha', { warnings: ['careful'] })); w.reviewed = true;
  ok('reviewed clears the attention flag', !pure.needsAttention(w));
  ok('partial support needs attention', pure.needsAttention(pure.fromAi(aiItem('Q one two three', 'Alpha', { support: 'weak' }))));

  console.log('== validation');
  const good = pure.fromAi(aiItem('Which letter is first?', 'Alpha'));
  ok('valid question passes', pure.validate([good]).length === 0);
  const clone = (o, ch) => Object.assign(JSON.parse(JSON.stringify(o)), ch);
  ok('empty text', pure.validate([clone(good, { questionText: '  ' })]).length === 1);
  ok('empty option', pure.validate([clone(good, { options: ['a', '', 'c', 'd'] })]).length >= 1);
  ok('duplicate options', pure.validate([clone(good, { options: ['a', 'A', 'c', 'd'], correctIndex: 0 })]).some(p => /identical/.test(p.message)));
  ok('no correct answer', pure.validate([clone(good, { correctIndex: -1 })]).some(p => /correct answer/.test(p.message)));
  ok('option too long', pure.validate([clone(good, { options: ['a', 'x'.repeat(256), 'c', 'd'] })]).some(p => /255/.test(p.message)));
  ok('marks < 1', pure.validate([clone(good, { marks: 0 })]).some(p => /marks/.test(p.message)));
  ok('problems are numbered by question', pure.validate([good, clone(good, { correctIndex: -1 })])[0].message.startsWith('Question 2'));

  console.log('== save payload');
  const payload = pure.toPayload([good]);
  ok('correctAnswer is the OPTION TEXT of the chosen index', payload[0].correctAnswer === 'Alpha' && payload[0].options.length === 4);
  ok('AI provenance included', payload[0].aiGenerated === true && payload[0].sourcePage === 2 && payload[0].sourceExcerpt === 'Alpha is the first letter.');
  const manual = Object.assign(clone(good, {}), { aiGenerated: false });
  ok('manual questions carry no source fields', !('sourcePage' in pure.toPayload([manual])[0]));
  let threw = null; try { pure.toPayload([clone(good, { correctIndex: -1 }), clone(good, { questionText: '' })]); } catch (e) { threw = e; }
  ok('export throws with the first problem and a count of the rest', threw && /Question 1/.test(threw.message) && /1 more/.test(threw.message) && threw.problemIndex === 0);

  console.log('== saved questions (reopened drafts)');
  const saved = pure.fromSaved({ questionText: 'Q?', options: ['x', 'y', 'z', 'w'], correctAnswer: 'z', marks: 2, aiGenerated: true, sourcePage: 4, sourceExcerpt: 'e' });
  ok('answer index restored, marks kept, counts as reviewed', saved.correctIndex === 2 && saved.marks === 2 && saved.reviewed && !pure.needsAttention(saved));

  console.log('== file checks');
  ok('no file', !!pure.checkFile(null));
  ok('not a pdf', /PDF/.test(pure.checkFile({ name: 'a.docx', size: 10, type: '' })));
  ok('empty file', /empty/.test(pure.checkFile({ name: 'a.pdf', size: 0, type: 'application/pdf' })));
  ok('too large', /20 MB/.test(pure.checkFile({ name: 'a.pdf', size: 21 * 1024 * 1024, type: 'application/pdf' })));
  ok('fine', pure.checkFile({ name: 'Notes.PDF', size: 5000, type: 'application/pdf' }) === null);
  ok('wrong mime', /does not look/.test(pure.checkFile({ name: 'a.pdf', size: 5, type: 'text/html' })));

  console.log('== error text');
  ok('json error preferred', pure.errorText({ json: { error: 'Boom' }, status: 400 }) === 'Boom');
  ok('short plain text used, "Error:" stripped', pure.errorText({ text: 'Error: nope', status: 409 }) === 'nope');
  ok('html never shown', /HTTP 500/.test(pure.errorText({ text: '<html>x</html>', status: 500 })));

  console.log('== generate -> poll -> accept (mocked server)');
  elements.pdfFile = { files: [{ name: 'n.pdf', size: 1000, type: 'application/pdf' }] };
  elements.numQuestions = { value: '3' };
  elements.rvDifficulty = { value: 'hard' };
  uploadReply = { ok: true, status: 202, json: { jobId: 'job-1' } };
  jobScript = [
    { body: { status: 'running', stage: 'Writing questions (section 1 of 2)', requested: 3, accepted: 1, chunksTotal: 2, chunksDone: 0 } },
    { body: { status: 'done', stage: 'Finished', requested: 3, accepted: 2, chunksTotal: 2, chunksDone: 2,
      result: { questions: [aiItem('Which letter is first?', 'Alpha'), aiItem('Which letter is second?', 'Omega')],
                rejected: [{ question: 'bad', reason: 'It duplicated another question.' }],
                meta: { requested: 3, generated: 2, shortfall: 1, truncated: false } } } }
  ];
  R.reset();
  await R.generate();
  ok('upload sent difficulty, count and file', uploadCalls[0].url === '/api/ai/jobs' && uploadCalls[0].form.d.difficulty === 'hard' && uploadCalls[0].form.d.num === '3');
  ok('polled the job until done', fetchCalls.filter(c => c.url === '/api/ai/jobs/job-1').length === 2);
  ok('2 questions accepted', R.count() === 2);
  ok('the unresolved answer is flagged, not silently chosen', R.flaggedCount() === 1);
  let blocked = null; try { R.exportQuestions(); } catch (e) { blocked = e; }
  ok('cannot publish while an answer is unresolved', blocked && /Question 2/.test(blocked.message) && /correct answer/.test(blocked.message));
  ok('teacher is told the result', toasts.some(t => /2 questions ready/.test(t[1])));
  ok('not busy afterwards', R.isBusy() === false);

  console.log('== upload failure and server errors are shown, never swallowed');
  R.reset(); toasts.length = 0; uploadCalls = [];
  uploadReply = { ok: false, status: 503, json: { error: 'The AI service is not reachable.' } };
  await R.generate();
  ok('failed upload leaves no questions and is not busy', R.count() === 0 && !R.isBusy());

  console.log('== job error / lost job');
  uploadReply = { ok: true, status: 202, json: { jobId: 'job-2' } };
  jobScript = [{ body: { status: 'error', error: 'The AI model is not reachable.', requested: 3, accepted: 0 } }];
  await R.generate(); ok('error job leaves nothing behind', R.count() === 0 && !R.isBusy());
  jobScript = [{ ok: false, status: 404, body: { error: 'Job not found or expired.' } }];
  await R.generate(); ok('404 job is handled', R.count() === 0 && !R.isBusy());

  console.log('== cancel');
  uploadReply = { ok: true, status: 202, json: { jobId: 'job-3' } };
  fetchCalls = [];
  jobScript = [{ body: { status: 'running', stage: 'x', requested: 3, accepted: 0, chunksTotal: 1, chunksDone: 0 } }, { body: { status: 'running', stage: 'x', requested: 3, accepted: 0 } }];
  const p = R.generate();
  await new Promise(r => setTimeout(r, 300));
  R.cancel();
  await p;
  ok('cancel sends DELETE for the job and stops', fetchCalls.some(c => c.method === 'DELETE' && c.url === '/api/ai/jobs/job-3') && !R.isBusy() && R.count() === 0);

  console.log('== input validation before any request');
  uploadCalls = [];
  elements.numQuestions.value = '99'; await R.generate();
  ok('99 questions refused client-side', uploadCalls.length === 0);
  elements.numQuestions.value = '3'; elements.pdfFile.files = [{ name: 'x.txt', size: 5, type: 'text/plain' }]; await R.generate();
  ok('non-PDF refused client-side', uploadCalls.length === 0);

  // --- Phase F helpers
  const P = R.pure || R._pure || (R.__test || {});
  const H = P.cap ? P : (R.pure || {});
  if (H.cap) {
    ok('cap: lowercase difficulty becomes Title Case', H.cap('medium') === 'Medium' && H.cap('HARD') === 'HARD' && H.cap(' easy ') === 'Easy');
    ok('cap: empty and missing values give an empty string', H.cap('') === '' && H.cap(null) === '' && H.cap(undefined) === '');
    ok('stalled: false just before the threshold, true at it', H.stalled(1000, 1999, 1000) === false && H.stalled(1000, 2000, 1000) === true);
  } else { ok('Phase F helpers are exported', false); }

  console.log('\n' + n + ' checks run' + (process.exitCode ? ' - SOME FAILED' : ' - all passed'));
})();
