// Run: node docs/verification/test_student_page.js  (runs student-exam.html's real script against mocked API data)
const fs = require('fs'), path = require('path'), vm = require('vm');
const html = fs.readFileSync(path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'static', 'student-exam.html'), 'utf8');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).pop();

const els = {};
const el = (id) => els[id] || (els[id] = { id, innerHTML: '', innerText: '', value: '', classes: new Set(),
  classList: { toggle(c, on) { on ? els[id].classes.add(c) : els[id].classes.delete(c); }, add(c) { els[id].classes.add(c); }, remove(c) { els[id].classes.delete(c); } } });
const day = 86400000, iso = (ms) => new Date(Date.now() + ms).toISOString().slice(0, 19);
const exams = [
  { id: 1, title: 'Maths Quiz', subjectName: 'Maths', durationMinutes: 20, startTime: iso(-day), endTime: iso(day) },          // open, closes in 1 day
  { id: 2, title: 'Physics 1', subjectName: 'Physics', durationMinutes: 30, startTime: iso(-3 * day), endTime: iso(5 * day) },  // already completed -> hidden
  { id: 3, title: 'Old exam', subjectName: 'Maths', durationMinutes: 30, startTime: iso(-9 * day), endTime: iso(-day) },        // deadline passed -> not open
  { id: 4, title: 'Future exam', subjectName: 'Chem', durationMinutes: 30, startTime: iso(day), endTime: iso(2 * day) },        // upcoming, not startable
  { id: 5, title: '<img src=x onerror=alert(1)>', subjectName: 'Bio', durationMinutes: 30, startTime: iso(-day), endTime: iso(10 * day) }
];
const results = [
  { id: 1, examId: 2, examTitle: 'Physics 1', subjectName: 'Physics', marksObtained: 3, totalMarks: 10, submissionTime: iso(-3 * day) },
  { id: 2, examId: 9, examTitle: 'Physics 2', subjectName: 'Physics', marksObtained: 5, totalMarks: 10, submissionTime: iso(-2 * day) },
  { id: 3, examId: 8, examTitle: 'Maths 1', subjectName: 'Maths', marksObtained: 9, totalMarks: 10, submissionTime: iso(-1 * day) }
];
const missed = [{ id: 3, title: 'Old exam', subjectName: 'Maths', endTime: iso(-day) }];
const calls = [];
const ctx = {
  document: { getElementById: el }, window: { location: { href: '' } }, console, Promise, Date, Set, Math, JSON, Array, Object, Number, String, encodeURIComponent,
  localStorage: { getItem: () => JSON.stringify({ username: 'Asha Rao', rollNo: 'R7', departmentName: 'CS', collegeName: 'C1', role: 'STUDENT' }) },
  IGUI: { esc: (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])),
          empty: (o) => `<empty>${o.title}</empty>`, confirm: async () => true, skeletonCards: () => '' },
  IGLayout: { mount(cfg) { ctx.navIds = cfg.nav.map(n => n.id); } },
  fetch: async (url) => { calls.push(url); const body = url.includes('available') ? exams : url.includes('missed') ? missed : results; return { ok: true, json: async () => body }; }
};
ctx.window.location = ctx.window.location; vm.createContext(ctx);
vm.runInContext(script, ctx);
let n = 0; const ok = (name, c) => { n++; console.log((c ? '  PASS  ' : '  FAIL  ') + name); if (!c) process.exitCode = 1; };

setTimeout(() => {
  const v = (id) => String(els[id].innerText), h = (id) => els[id].innerHTML;
  ok('uses the shell with Overview / My exams / My results', JSON.stringify(ctx.navIds) === '["tab-overview","tab-exams","tab-results"]');
  ok('calls the real endpoints (no identity in URLs)', calls.some(u => u === '/api/exam/student/available') && calls.some(u => u === '/api/exam/student/missed') && calls.some(u => u === '/api/exam/results/student/me'));
  ok('welcome uses first name', v('welcomeTitle') === 'Welcome Back, Asha');
  ok('open-now counts only exams that are open and not completed (Maths Quiz + Bio = 2)', v('statOpen') === '2');
  ok('completed = 3, average = round((30+50+90)/3) = 57%', v('statCompleted') === '3' && v('statAverage') === '57%');
  ok('missed comes from the /missed endpoint', v('statMissed') === '1');
  const avail = h('availableExams');
  ok('completed exam (Physics 1) is not offered again', !/Physics 1/.test(avail));
  ok('exam past its deadline is not offered', !/Old exam/.test(avail));
  ok('missed exam is listed under Missed', /Old exam/.test(h('missedExams')));
  ok('future exam is listed as upcoming but cannot be started yet', /Future exam/.test(avail) && /Not Open Yet/.test(avail) && !/takeExam\(4\)/.test(avail));
  ok('only exams that are open get a Start button (1 and 5)', /takeExam\(1\)/.test(avail) && /takeExam\(5\)/.test(avail) && !/takeExam\([234]\)/.test(avail));
  ok('titles are escaped (no raw <img)', !/<img/i.test(avail + h('upcomingList')) && /&lt;img/.test(avail));
  ok('upcoming list is ordered by deadline (Maths first)', h('upcomingList').indexOf('Maths Quiz') >= 0 && h('upcomingList').indexOf('Maths Quiz') < h('upcomingList').indexOf('&lt;img'));
  ok('subject bars: weakest subject first with its average (Physics 40%)', h('subjectBars').indexOf('Physics') < h('subjectBars').indexOf('Maths') && /40%/.test(h('subjectBars')) && /90%/.test(h('subjectBars')));
  ok('trend chart is an accessible SVG with the pass mark', /<svg/.test(h('trendChart')) && /aria-label="Your scores over time: 30%, 50%, 90%"/.test(h('trendChart')) && /pass mark 40%/.test(h('trendChart')));
  const tips = h('suggestions');
  ok('suggests revising the weak subject using real numbers', /Revise <strong>Physics<\/strong>.*40%.*2 exams/.test(tips));
  ok('notes the improving trend (90% vs 40% earlier average)', /latest score \(90%\).*above.*\(40%\)/.test(tips));
  ok('mentions the missed exam', /missed 1 exam/.test(tips));
  ok('warns about the exam closing within 48 hours', /1 exam closes within 48 hours/.test(tips));
  els.resSearch = el('resSearch'); els.resSubject = el('resSubject');
  els.resSubject.value = 'Maths'; ctx.renderResults ? ctx.renderResults() : vm.runInContext('renderResults()', ctx);
  ok('results filter by subject', /Maths 1/.test(h('pastResults')) && !/Physics/.test(h('pastResults')));
  els.resSubject.value = ''; els.resSearch.value = 'physics 2'; vm.runInContext('renderResults()', ctx);
  ok('results search by title', /Physics 2/.test(h('pastResults')) && !/Physics 1/.test(h('pastResults')));
  els.resSearch.value = 'zzz'; vm.runInContext('renderResults()', ctx);
  ok('empty state when nothing matches', /No matching results/.test(h('pastResults')));
  const ymd = (ms) => { const d = new Date(Date.now() + ms); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
  els.resSearch.value = ''; els.resSubject.value = ''; el('resFrom'); el('resTo');
  els.resFrom.value = ymd(-2 * day); els.resTo.value = ''; vm.runInContext('renderResults()', ctx);
  ok('date filter "from" keeps results on/after that day (Physics 2, Maths 1)', /Physics 2/.test(h('pastResults')) && /Maths 1/.test(h('pastResults')) && !/Physics 1/.test(h('pastResults')));
  els.resFrom.value = ''; els.resTo.value = ymd(-2 * day); vm.runInContext('renderResults()', ctx);
  ok('date filter "to" keeps results on/before that day (Physics 1, Physics 2)', /Physics 1/.test(h('pastResults')) && /Physics 2/.test(h('pastResults')) && !/Maths 1/.test(h('pastResults')));
  els.resFrom.value = ymd(-day); els.resTo.value = ymd(-day); vm.runInContext('renderResults()', ctx);
  ok('single-day range works (Maths 1 only)', /Maths 1/.test(h('pastResults')) && !/Physics/.test(h('pastResults')));
  els.resFrom.value = ymd(5 * day); els.resTo.value = ''; vm.runInContext('renderResults()', ctx);
  ok('range with no results shows the empty state', /No matching results/.test(h('pastResults')));
  els.resFrom.value = ''; els.resTo.value = '';
  vm.runInContext("switchTab('results')", ctx);
  ok('tab switching shows only the chosen view', !els['view-results'].classes.has('hidden') && els['view-overview'].classes.has('hidden') && els['view-exams'].classes.has('hidden'));
  console.log('\n' + n + ' checks run' + (process.exitCode ? ' - SOME FAILED' : ' - all passed'));
}, 200);
