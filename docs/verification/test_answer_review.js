// Run: node docs/verification/test_answer_review.js
// Runs student-exam.html's real script against mocked API data and checks the answer review feature.
const fs = require('fs'), path = require('path'), vm = require('vm');

function findStatic() {                         // works for src/src/main and src/main layouts
    const root = path.join(__dirname, '..', '..');
    for (const c of ['springboot_backend/src/src/main/resources/static', 'src/src/main/resources/static', 'src/main/resources/static', 'springboot_backend/src/main/resources/static']) {
        const p = path.join(root, c, 'student-exam.html'); if (fs.existsSync(p)) return p;
    }
    throw new Error('student-exam.html not found');
}
const html = fs.readFileSync(findStatic(), 'utf8');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).pop();

const els = {};
const el = (id) => els[id] || (els[id] = { id, innerHTML: '', innerText: '', value: '', classes: new Set(['hidden']),
    classList: { toggle(c, on) { on ? els[id].classes.add(c) : els[id].classes.delete(c); }, add(c) { els[id].classes.add(c); }, remove(c) { els[id].classes.delete(c); } } });
const day = 86400000, iso = (ms) => new Date(Date.now() + ms).toISOString().slice(0, 19);

const results = [
    { id: 1, examId: 101, examTitle: 'Released Exam', subjectName: 'Maths', marksObtained: 1, totalMarks: 4, submissionTime: iso(-3 * day), answersReleased: true },
    { id: 2, examId: 102, examTitle: 'Still Open Exam', subjectName: 'Physics', marksObtained: 2, totalMarks: 4, submissionTime: iso(-day), answersReleased: false },
    { id: 3, examId: 103, examTitle: 'Legacy Exam', subjectName: 'Chem', marksObtained: 3, totalMarks: 4, submissionTime: iso(-9 * day), answersReleased: true }
];
const q = (id, text, options, correct, mine, marks = 1) => ({ questionId: id, questionText: text, options, correctAnswer: correct, yourAnswer: mine,
    isCorrect: !!mine && mine.trim().toLowerCase() === correct.trim().toLowerCase(), marks, marksAwarded: (!!mine && mine.trim().toLowerCase() === correct.trim().toLowerCase()) ? marks : 0 });
const reviews = {
    101: { examId: 101, examTitle: 'Released Exam', subjectName: 'Maths', marksObtained: 1, totalMarks: 4, released: true, answersRecorded: true, questions: [
        q(1, 'Capital of France?', ['Paris', 'London', 'Rome'], 'Paris', 'Paris'),
        q(2, 'Largest planet?', ['Mars', 'Jupiter', 'Venus'], 'Jupiter', 'Mars'),
        q(3, 'Unanswered one?', ['A1', 'B1'], 'B1', null),
        q(4, '<img src=x onerror=alert(1)> trick?', ['<script>alert(2)</script>', 'safe'], 'Not listed option', 'safe')
    ] },
    102: { examId: 102, examTitle: 'Still Open Exam', subjectName: 'Physics', marksObtained: 2, totalMarks: 4, released: false, message: 'Your teacher has not released the correct answers yet.' },
    103: { examId: 103, examTitle: 'Legacy Exam', subjectName: 'Chem', marksObtained: 3, totalMarks: 4, released: true, answersRecorded: false, questions: [
        q(7, 'Legacy question?', ['Yes', 'No'], 'Yes', null) ] }
};
const calls = [];
const ctx = {
    document: { getElementById: el }, window: { location: { href: '' } }, console, Promise, Date, Set, Math, JSON, Array, Object, Number, String, encodeURIComponent, isNaN,
    localStorage: { getItem: () => JSON.stringify({ username: 'Asha Rao', rollNo: 'R7', departmentName: 'CS', collegeName: 'C1', role: 'STUDENT' }) },
    IGUI: { esc: (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])),
            empty: (o) => `<empty>${o.title}|${o.text || ''}</empty>`, confirm: async () => true, skeletonCards: () => '<skeleton>' },
    IGLayout: { mount(cfg) { ctx.navIds = cfg.nav.map(n => n.id); } },
    fetch: async (url) => {
        calls.push(url);
        const m = url.match(/\/student\/review\/(\d+)$/);
        if (m) {
            const id = Number(m[1]);
            if (id === 104) return { ok: false, status: 404, json: async () => ({ error: 'No submitted result was found for this exam.' }) };
            if (id === 105) throw new Error('network down');
            return { ok: true, status: 200, json: async () => reviews[id] };
        }
        const body = url.includes('available') ? [] : url.includes('missed') ? [] : results;
        return { ok: true, status: 200, json: async () => body };
    }
};
vm.createContext(ctx);
vm.runInContext(script, ctx);
let n = 0, bad = 0; const ok = (name, c) => { n++; if (!c) bad++; console.log((c ? '  PASS  ' : '  FAIL  ') + name); };
const body = () => els.reviewBody.innerHTML, summary = () => els.reviewSummary.innerHTML;
const count = (s, re) => (s.match(re) || []).length;

setTimeout(async () => {
    const list = els.pastResults.innerHTML;
    ok('released result offers a Review Answers button wired to its exam id', /onclick="openReview\(101\)"[^>]*>[\s\S]*?Review Answers/.test(list));
    ok('unreleased result has no review button and says the teacher releases answers', !/openReview\(102\)/.test(list) && /once your teacher releases them/.test(list));
    ok('review button count equals released results (2)', count(list, /openReview\(/g) === 2);

    await vm.runInContext('openReview(101)', ctx);
    ok('review requests the dedicated endpoint for that exam only', calls.includes('/api/exam/student/review/101'));
    ok('dialog is shown', !els.reviewOverlay.classes.has('hidden'));
    ok('title is Title Case', els.reviewTitle.innerText === 'Answer Review');
    ok('subtitle names exam and subject', els.reviewSub.innerText.includes('Released Exam') && els.reviewSub.innerText.includes('Maths'));
    ok('summary shows score 1/4', />1\/4</.test(summary()));
    ok('summary counts: 1 correct, 2 incorrect, 1 not answered', /rvw-stat">1<\/span><span class="rvw-stat-label">Correct/.test(summary()) && /rvw-stat">2<\/span><span class="rvw-stat-label">Incorrect/.test(summary()) && /rvw-stat">1<\/span><span class="rvw-stat-label">Not Answered/.test(summary()));
    ok('all four questions are rendered with semantic h3 headings', count(body(), /<h3>Question \d<\/h3>/g) === 4);
    ok('correct option is tagged Correct Answer (Paris, Jupiter, B1)', count(body(), /Correct Answer<\/span>/g) === 3);
    ok('wrong choice is tagged Your Answer in the wrong style (Mars)', /rvw-opt is-yours-wrong[^>]*>[\s\S]*?Mars[\s\S]*?rvw-tag-wrong[^>]*>[\s\S]*?Your Answer/.test(body()));
    ok('right choice is tagged Your Answer in the correct style (Paris)', /Paris<\/span><span class="rvw-tag rvw-tag-correct"><i[^>]*><\/i> Correct Answer<\/span><span class="rvw-tag rvw-tag-correct"><i[^>]*><\/i> Your Answer/.test(body()));
    ok('skipped question says so', /Not Answered<\/span>/.test(body()) && /You did not answer this question\./.test(body()));
    ok('answer key missing from the options is still shown', /Correct answer:<\/strong> Not listed option/.test(body()));
    ok('marks awarded are shown per question', /1\/1 Mark/.test(body()) && /0\/1 Mark/.test(body()));
    ok('question text and options are HTML-escaped (no raw <img or <script)', !/<img /.test(body()) && !/<script>alert/.test(body()) && /&lt;img/.test(body()));

    vm.runInContext("setReviewFilter('wrong')", ctx);
    ok('Incorrect or Skipped filter hides the correct question', !/Question 1<\/h3>/.test(body()) && /Question 2<\/h3>/.test(body()) && count(body(), /<article/g) === 3);
    ok('active filter is exposed with aria-pressed', /aria-pressed="true"[^>]*onclick="setReviewFilter\('wrong'\)"/.test(els.reviewFilter.innerHTML));
    vm.runInContext("setReviewFilter('all')", ctx);
    ok('All Questions filter restores everything', count(body(), /<article/g) === 4);

    await vm.runInContext('openReview(102)', ctx);
    ok('unreleased review shows a holding message from the server', /Answers Are Not Available Yet/.test(body()) && /Your teacher has not released the correct answers yet\./.test(body()));
    ok('unreleased review exposes no questions, options or key', !/<article/.test(body()) && !/Correct Answer/.test(body()) && summary() === '' && els.reviewFilter.innerHTML === '');

    await vm.runInContext('openReview(103)', ctx);
    ok('legacy result explains that saved answers are missing', /No saved answers were found/.test(summary()));
    ok('legacy result never labels questions Incorrect', !/>\s*Incorrect\s*</.test(body()) && !/badge-red/.test(body()) && /Answer Not Recorded/.test(body()));
    ok('legacy result keeps the real score and hides the filter', />3\/4</.test(summary()) && els.reviewFilter.innerHTML === '');

    await vm.runInContext('openReview(104)', ctx);
    ok('server error message is shown via the error state', /Could Not Load Answer Review\|No submitted result was found for this exam\./.test(body()));
    await vm.runInContext('openReview(105)', ctx);
    ok('network failure is handled', /Could Not Load Answer Review\|network down/.test(body()));

    vm.runInContext('closeReview()', ctx);
    ok('closing hides the dialog', els.reviewOverlay.classes.has('hidden'));
    ok('closing drops the loaded answer key from memory', vm.runInContext('review.data', ctx) === null);

    console.log(`\n${n} checks run - ${bad ? bad + ' FAILED' : 'all passed'}`);
    process.exit(bad ? 1 : 0);
}, 60);
