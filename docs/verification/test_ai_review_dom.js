// Run: node docs/verification/test_ai_review_dom.js
// Renders the review UI into a recording fake DOM and checks the generated markup and edit handlers.
const path = require('path'); const fs = require('fs');
const elements = {}; const created = [];
class El {
  constructor(tag) { this.tag = tag; this.id = ''; this.className = ''; this.innerHTML = ''; this.value = ''; this.listeners = {}; this.attrs = {}; this.children = [];
    const set = new Set(); this.classes = set;
    this.classList = { add: (c) => set.add(c), remove: (c) => set.delete(c), toggle: (c, on) => (on === undefined ? (set.has(c) ? set.delete(c) : set.add(c)) : (on ? set.add(c) : set.delete(c))), contains: (c) => set.has(c) }; }
  addEventListener(t, fn) { (this.listeners[t] = this.listeners[t] || []).push(fn); }
  setAttribute(k, v) { this.attrs[k] = v; } getAttribute(k) { return this.attrs[k] === undefined ? null : this.attrs[k]; }
  insertAdjacentElement(pos, el) { if (el.id) elements[el.id] = el; created.push(el); return el; }
  appendChild(c) { this.children.push(c); c.parentNode = this; } removeChild(c) { this.children = this.children.filter(x => x !== c); }
  querySelector() { return null; } querySelectorAll() { return []; } scrollIntoView() {} focus() {}
}
global.document = { getElementById: (id) => elements[id] || null, createElement: (t) => new El(t) };
for (const id of ['pdfFile', 'numQuestions', 'resultsContainer', 'mcqList', 'qCountBadge', 'saveBtn', 'generateBtn', 'loadingIndicator', 'fileNameDisplay']) { elements[id] = new El('x'); elements[id].id = id; }
elements.pdfFile.parentElement = new El('div');
global.IGUI = { esc: (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])), toast() {}, confirm: async (o) => { global.__confirm = o; return true; } };
global.IGApi = {};
require(path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'static', 'ai-review.js'));
const R = global.IGReview;
let n = 0; const ok = (name, c) => { n++; console.log((c ? '  PASS  ' : '  FAIL  ') + name); if (!c) process.exitCode = 1; };
const fire = (id, type, target) => (elements[id].listeners[type] || []).forEach(fn => fn({ target }));
const tgt = (attrs, extra) => Object.assign({ getAttribute: (k) => (k in attrs ? String(attrs[k]) : null), closest: () => null }, extra);

R.mount({ onSaveDraft() {} });
ok('mount added difficulty select, draft button and summary bar', !!elements.rvDifficulty && !!elements.rvDraftBtn && !!elements.rvSummary);
ok('difficulty offers easy/medium/hard', /easy[\s\S]*medium[\s\S]*hard/.test(elements.rvDifficulty.innerHTML));
ok('list has delegated handlers', ['input', 'change', 'click'].every(t => (elements.mcqList.listeners[t] || []).length === 1));
ok('upload area is keyboard reachable', elements.pdfFile.parentElement.attrs.tabindex === '0' && elements.pdfFile.parentElement.attrs.role === 'button');
ok('mount is idempotent', (R.mount({}), (elements.mcqList.listeners.input || []).length === 1));

console.log('== rendering');
const evil = '<img src=x onerror=alert(1)>';
R.loadQuestions([{ questionText: 'Q ' + evil, options: ['a' + evil, 'b', 'c', 'd'], correctAnswer: 'b', marks: 1, aiGenerated: true, sourcePage: 3, sourceExcerpt: 'src ' + evil }]);
let html = elements.mcqList.innerHTML;
ok('user/AI text is HTML-escaped everywhere (no raw <img)', !/<img/i.test(html) && /&lt;img/.test(html));
R.loadQuestions([{ questionText: 'Q attr test', options: ['x" onfocus="evil()', 'b', 'c', 'd'], correctAnswer: 'b', marks: 1, aiGenerated: false }]);
ok('a quote in an option cannot break out of the value attribute', /value="x&quot; onfocus=&quot;evil\(\)"/.test(elements.mcqList.innerHTML) && !/onfocus="evil/.test(elements.mcqList.innerHTML));
R.loadQuestions([{ questionText: 'Q ' + evil, options: ['a' + evil, 'b', 'c', 'd'], correctAnswer: 'b', marks: 1, aiGenerated: true, sourcePage: 3, sourceExcerpt: 'src ' + evil }]);
html = elements.mcqList.innerHTML;
ok('radio for the correct option is checked', /data-o="1" checked/.test(html) && !/data-o="0" checked/.test(html));
ok('source with page number is shown', /Source \(Page 3\)/.test(html));
ok('container revealed and count badge set', !elements.resultsContainer.classes.has('hidden') && elements.qCountBadge.innerText === '1 Question');

const { execFileSync } = require('child_process');
const check = execFileSync('python3', ['-c', `
import sys
from html.parser import HTMLParser
class P(HTMLParser):
    def __init__(s): super().__init__(); s.stack=[]; s.err=[]; s.imgs=0; s.radios=0; s.void={'input','br','img','hr','meta','link'}
    def handle_starttag(s,t,a):
        if t=='img': s.imgs+=1
        if t=='input' and ('type','radio') in a: s.radios+=1
        if t not in s.void: s.stack.append(t)
    def handle_endtag(s,t):
        if t in s.void: return
        if not s.stack or s.stack[-1]!=t: s.err.append('mismatch '+t+' vs '+(s.stack[-1] if s.stack else None)); 
        else: s.stack.pop()
p=P(); p.feed(sys.stdin.read()); print(len(p.err), len(p.stack), p.imgs, p.radios)
`], { input: html }).toString().trim();
ok('markup is well-formed, has 4 radios and zero real <img> elements ("' + check + '")', check === '0 0 0 4');

console.log('== AI banner + warnings');
R.reset();
global.IGApi.upload = null;
// simulate acceptResult through the public path: use loadQuestions then check summary wording via a generated state
R.loadQuestions([{ questionText: 'Q1 long enough', options: ['a', 'b', 'c', 'd'], correctAnswer: 'a', marks: 1, aiGenerated: true }]);
ok('AI-drafted banner says checks do NOT verify correctness', /do <strong>not<\/strong> verify/.test(elements.rvSummary.innerHTML));
R.loadQuestions([{ questionText: 'Q1 long enough', options: ['a', 'b', 'c', 'd'], correctAnswer: 'zzz', marks: 1, aiGenerated: false }]);
ok('unresolved answer shows the red "choose it" notice', /could not be determined/.test(elements.mcqList.innerHTML) && R.flaggedCount() === 1);
ok('...and the card is highlighted', /rv-card rv-attn/.test(elements.mcqList.innerHTML));
ok('manual question has no AI banner', !/drafted by AI/.test(elements.rvSummary.innerHTML));

console.log('== editing changes state');
R.loadQuestions([{ questionText: 'Original question text', options: ['a', 'b', 'c', 'd'], correctAnswer: 'a', marks: 1, aiGenerated: false }]);
fire('mcqList', 'input', tgt({ 'data-i': 0, 'data-f': 'text' }, { value: 'Edited question text' }));
fire('mcqList', 'input', tgt({ 'data-i': 0, 'data-f': 'opt', 'data-o': 2 }, { value: 'changed C' }));
fire('mcqList', 'change', tgt({ 'data-i': 0, 'data-f': 'correct', 'data-o': 2 }));
fire('mcqList', 'change', tgt({ 'data-i': 0, 'data-f': 'marks' }, { value: '4' }));
const out = R.exportQuestions()[0];
ok('edited text, option, correct answer and marks reach the payload', out.questionText === 'Edited question text' && out.options[2] === 'changed C' && out.correctAnswer === 'changed C' && out.marks === 4);
fire('mcqList', 'change', tgt({ 'data-i': 0, 'data-f': 'marks' }, { value: '-3' }));
ok('negative marks are clamped to 1', R.exportQuestions()[0].marks === 1);
fire('mcqList', 'input', tgt({ 'data-i': 0, 'data-f': 'opt', 'data-o': 0 }, { value: 'b' }));
let err = null; try { R.exportQuestions(); } catch (e) { err = e; }
ok('making two options identical blocks saving', err && /identical/.test(err.message));
fire('mcqList', 'click', { closest: () => ({ getAttribute: (k) => (k === 'data-act' ? 'del' : '0') }) , getAttribute: () => '0' });
ok('delete removes the question', R.count() === 0);

console.log('== manual add');
Object.assign(elements, { manQText: Object.assign(new El('i'), { value: 'What is 2+2 exactly?' }), manOptA: Object.assign(new El('i'), { value: '3' }), manOptB: Object.assign(new El('i'), { value: '4' }),
  manOptC: Object.assign(new El('i'), { value: '5' }), manOptD: Object.assign(new El('i'), { value: '6' }), manCorrect: Object.assign(new El('i'), { value: 'B' }), manMarks: Object.assign(new El('i'), { value: '2' }) });
ok('manual question accepted', R.addManual() === true && R.count() === 1);
ok('stored with the correct option text and marks', (p => p.correctAnswer === '4' && p.marks === 2 && p.aiGenerated === false)(R.exportQuestions()[0]));
ok('form fields cleared after adding', elements.manQText.value === '' && elements.manCorrect.value === '');
elements.manCorrect.value = ''; ok('incomplete manual question refused', R.addManual() === false && R.count() === 1);

console.log('== publish confirmation wording');
R.loadQuestions([{ questionText: 'AI one is long', options: ['a', 'b', 'c', 'd'], correctAnswer: 'a', marks: 1, aiGenerated: true }]);
(async () => {
  await R.confirmPublish('Physics Quiz');
  ok('publish confirm names the exam and warns AI output is unverified', /Physics Quiz/.test(global.__confirm.message) && /drafted by AI/.test(global.__confirm.message) && /do not prove/.test(global.__confirm.message));
  console.log('\n' + n + ' checks run' + (process.exitCode ? ' - SOME FAILED' : ' - all passed'));
})();
