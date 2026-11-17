"""
Real-browser checks (Chromium via Playwright) for IntelliGen's static pages against a MOCKED backend.

Run:  pip install playwright && playwright install chromium
      python docs/verification/browser/test_browser.py

Limits (be honest about them): CDN assets (Tailwind, Font Awesome, Google Fonts) are blocked so the run works offline.
Tailwind utility classes therefore do NOT apply, so this verifies behaviour, DOM structure, focus handling,
accessibility wiring and custom-CSS layout, but NOT the final Tailwind-styled appearance. Check that by eye.
"""
import json, mimetypes, os, re, sys
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[3]
STATIC = next(p for p in [ROOT/'springboot_backend/src/src/main/resources/static', ROOT/'src/src/main/resources/static',
                          ROOT/'src/main/resources/static', ROOT/'springboot_backend/src/main/resources/static'] if p.exists())
BASE = 'http://app.test'
passed = failed = 0
def ok(name, cond, extra=''):
    global passed, failed
    if cond: passed += 1; print('  PASS ', name)
    else: failed += 1; print('  FAIL ', name, extra)

state = {'auth_mode': 'ok', 'auth_calls': [], 'player_mode': 'ok', 'player_saves': [], 'player_submits': [], 'rich': False, 'ai_calls': [], 'ai_mode': 'ok', 'ai_polls': 0, 'slow_cfg': False, 'exam_results': {}, 'release': {}, 'release_puts': [], 'login': (200, {'role': 'TEACHER', 'username': 'T One', 'id': 1}), 'login_calls': [], 'unmatched': []}
RESULTS = [
    {'id': 1, 'examId': 101, 'examTitle': 'Released Exam', 'subjectName': 'Maths', 'marksObtained': 1, 'totalMarks': 4,
     'submissionTime': '2026-10-20T10:00:00', 'answersReleased': True},
    {'id': 2, 'examId': 102, 'examTitle': 'Open Exam', 'subjectName': 'Physics', 'marksObtained': 2, 'totalMarks': 4,
     'submissionTime': '2026-10-27T10:00:00', 'answersReleased': False}]
def q(i, t, o, c, m): return {'questionId': i, 'questionText': t, 'options': o, 'correctAnswer': c, 'yourAnswer': m,
                               'isCorrect': m == c, 'marks': 1, 'marksAwarded': 1 if m == c else 0}
REVIEW = {'examId': 101, 'examTitle': 'Released Exam', 'subjectName': 'Maths', 'marksObtained': 1, 'totalMarks': 4, 'released': True,
          'answersRecorded': True, 'questions': [q(1, 'Capital of France?', ['Paris', 'London'], 'Paris', 'Paris'),
                                                 q(2, 'Largest planet?', ['Mars', 'Jupiter'], 'Jupiter', 'Mars')]}

def handler(route, request):
    url = request.url
    if not url.startswith(BASE):
        return route.abort()                                   # CDNs blocked (offline sandbox)
    path = url[len(BASE):].split('?')[0]
    if path.startswith('/api/'):
        j = lambda body, status=200: route.fulfill(status=status, content_type='application/json', body=json.dumps(body))
        if path == '/api/auth/login':
            state['login_calls'].append(request.post_data)
            st, body = state['login']
            if body == 'HTML': return route.fulfill(status=st, content_type='text/html', body='<h1>Error</h1>')
            return j(body, st)
        if path in ('/api/auth/register', '/api/auth/forgot-password', '/api/auth/reset-password'):
            state['auth_calls'].append((path, json.loads(request.post_data or '{}'))); md = state['auth_mode']
            if md == 'abort': return route.abort()
            if md == 'html500': return route.fulfill(status=500, content_type='text/html', body='<h1>Server Error</h1>')
            if md == 'rate': return j({'error': 'Too many registration attempts. Please try again later.'}, 429)
            if md == 'error400': return j({'error': 'Email already registered.'}, 400)
            return j({'message': 'Request accepted.'})
        if path == '/api/auth/logout': return j({})
        if path == '/api/notifications':                       # same shape as NotificationController.list()
            return j({'unread': 1, 'items': [{'id': 1, 'kind': 'RESULT', 'title': 'Result Recorded', 'message': 'Your result is ready.',
                                              'link': 'student-exam.html', 'createdAt': '2026-10-27T10:00:00', 'read': False}]})
        if path == '/api/ai/jobs' and request.method == 'POST':
            state['ai_calls'].append('POST'); mode = state['ai_mode']
            if mode == 'upload_error': return j({'error': 'The PDF does not contain enough readable text. Scanned (image-only) PDFs are not supported.'}, 400)
            state['ai_polls'] = 0; return j({'jobId': 'j1'}, 202)
        if path == '/api/ai/jobs/j1':
            if request.method == 'DELETE': state['ai_calls'].append('DELETE'); return j({'status': 'cancelled'})
            state['ai_polls'] += 1; mode = state['ai_mode']; n = state['ai_polls']
            base = {'requested': 3, 'accepted': 0, 'chunksTotal': 0, 'chunksDone': 0}
            if mode == 'slow' or (mode == 'ok' and n == 1):
                return j({**base, 'status': 'running', 'stage': 'Waiting for another generation to finish'})
            if mode == 'fail': return j({**base, 'status': 'error', 'error': 'The AI model is not running. Start Ollama and try again.'})
            if mode == 'ok' and n == 2: return j({**base, 'status': 'running', 'stage': 'Writing questions', 'accepted': 1, 'chunksTotal': 2})
            return j({**base, 'status': 'done', 'stage': 'Done', 'accepted': 2, 'result': {'rejected': [], 'questions': [
                {'question': 'What is 2+2?', 'options': ['3', '4', '5', '6'], 'answer': '4', 'source_page': 3,
                 'source_excerpt': 'Two plus two equals four.', 'support': 'good', 'difficulty': 'medium', 'warnings': []},
                {'question': 'Which planet is largest?', 'options': ['Mars', 'Jupiter', 'Venus', 'Earth'], 'answer': 'Jupiter', 'source_page': 7,
                 'source_excerpt': 'Jupiter is a planet.', 'support': 'weak', 'difficulty': 'hard', 'warnings': ['The answer is only partly supported by the text.']}]}})
        m = re.fullmatch(r'/api/exam/(\d+)/answer-release', path)
        if m:
            eid = int(m.group(1)); st = state['release'].get(eid)
            if request.method == 'GET':
                return j(st) if st else j({'error': 'You do not have access to this exam.'}, 403)
            body = json.loads(request.post_data or '{}'); state['release_puts'].append((eid, body))
            if eid == 304: return j({'error': 'Answers can be released only after the exam window has closed and the submission grace period is over.'}, 409)
            st['released'] = body['released']; st['releasedAt'] = '2026-10-28T10:00:00' if body['released'] else None
            return j(st)
        m = re.fullmatch(r'/api/exam/results/exam/(\d+)', path)
        if m:
            sc = state['exam_results'].get(int(m.group(1)), [])
            if sc == 'HTML500': return route.fulfill(status=500, content_type='text/html', body='<h1>boom</h1>')
            if isinstance(sc, tuple): return j(sc[1], sc[0])
            return j(sc)
        m = re.fullmatch(r'/api/exam/(\d+)/analytics', path)
        if m: return j({'absent': 2, 'passed': 8, 'failed': 4}, 500 if int(m.group(1)) == 205 else 200)
        if state['rich']:
            key = path
            if re.fullmatch(r'/api/exam/\d+/hardest-questions', path): return j([[f'Which one is right? {HOSTILE}', 7], ['Second question', 4]])
            if path.startswith('/api/auth/principal/pending-teachers/'): return j([_teacher(5, approved=False)])
            if key in RICH: return j(RICH[key])
        m = re.fullmatch(r'/api/exam/student/(start|autosave|submit)/(\d+)', path)
        if m:
            kind = m.group(1); mode = state['player_mode']
            if kind == 'start':
                if mode == 'start403': return j({'error': 'This exam is not open for you right now.'}, 403)
                return j({'title': 'Maths <u id=xss>x</u> Quiz', 'subjectName': 'Maths', 'questions': [
                    {'id': 11, 'questionText': 'What is 2+2?', 'options': ['3', '4', '5', '6'], 'marks': 2},
                    {'id': 12, 'questionText': 'Capital of France?', 'options': ['Paris', 'London', "O'Brien & Sons", '<i>E</i>'], 'marks': 1},
                    {'id': 13, 'questionText': 'Largest planet?', 'options': ['Mars', 'Jupiter'], 'marks': 1}],
                    'attempt': {'seq': 0, 'answers': {'12': 'Paris'}, 'flagged': [13], 'remainingSeconds': 1800}})
            if kind == 'autosave': state['player_saves'].append(json.loads(request.post_data)); return j({'remainingSeconds': 1790})
            state['player_submits'].append(json.loads(request.post_data or '{}'))
            if mode == 'submit500' and len(state['player_submits']) == 1: return route.fulfill(status=500, content_type='text/plain', body='Error: The answers could not be recorded.')
            return j({})
        if path == '/api/exam/results/student/me': return j(RESULTS)
        if path.startswith('/api/exam/student/review/'): return j(REVIEW)
        if path.startswith('/api/exam/student/'): return j([])
        state['unmatched'].append(path); return j([])
    f = STATIC / (path.lstrip('/') or 'login.html')
    if not f.exists(): return route.fulfill(status=404, body='not found')
    if f.name == 'teacher-dashboard.html' and state['slow_cfg']:
        return route.fulfill(status=200, content_type='text/html', body=f.read_text(encoding='utf-8').replace('IGReview.mount({', 'IGReview.mount({ slowAfterMs: 2500,', 1))
    route.fulfill(status=200, content_type=mimetypes.guess_type(str(f))[0] or 'text/plain', body=f.read_bytes())

# Offline stand-in for the ONE Tailwind utility the shell relies on for behaviour (everything else is left unstyled on purpose).
SHIM = "window.Chart=window.Chart||function(){this.destroy=function(){}};document.addEventListener('DOMContentLoaded',()=>{const s=document.createElement('style');s.textContent='.hidden{display:none!important}';document.head.appendChild(s)})"

# ---------------------------------------------------------------- rich data for data-path rendering tests
HOSTILE = "O'Brien & Sons <u id=xss>x</u>"
def _q(i, t='Q'): return {'id': i, 'questionText': f'{t} {i} {HOSTILE}?', 'options': ['A', 'B', "C & D", '<i>E</i>'], 'correctAnswer': 'A', 'marks': 2, 'difficulty': 'medium'}
RICH_EXAMS = [
    {'id': 1, 'title': f'Maths Quiz {HOSTILE}', 'departmentName': 'CSE', 'durationMinutes': 30, 'startTime': '2026-10-20T09:00:00', 'endTime': '2026-10-20T10:00:00',
     'status': 'PUBLISHED', 'createdByTeacherUsername': 't1@c.edu', 'targetSemester': 3, 'visibleToStudents': True, 'subjectName': 'Maths', 'collegeName': 'C1', 'answersReleasedAt': None, 'questions': [_q(1), _q(2)]},
    {'id': 2, 'title': 'Physics Draft', 'departmentName': 'CSE', 'durationMinutes': 45, 'startTime': '2026-12-01T09:00:00', 'endTime': '2026-12-01T10:00:00',
     'status': 'DRAFT', 'createdByTeacherUsername': 't1@c.edu', 'targetSemester': 3, 'visibleToStudents': False, 'subjectName': 'Physics', 'collegeName': 'C1', 'answersReleasedAt': None, 'questions': [_q(3)]},
    {'id': 3, 'title': 'Chemistry Final', 'departmentName': 'CSE', 'durationMinutes': 60, 'startTime': '2026-09-01T09:00:00', 'endTime': '2026-09-01T11:00:00',
     'status': 'PUBLISHED', 'createdByTeacherUsername': 'other@c.edu', 'targetSemester': 5, 'visibleToStudents': True, 'subjectName': 'Chemistry', 'collegeName': 'C1', 'answersReleasedAt': '2026-09-02T09:00:00', 'questions': []}]
def _teacher(i, role='TEACHER', **kw):
    d = {'id': i, 'name': f'Teacher {i} {HOSTILE}', 'username': f't{i}@c.edu', 'collegeName': 'C1', 'branchName': 'Computer Science Engineering', 'employeeId': f'E{i}',
         'principal': False, 'approved': True, 'active': True, 'role': role, 'subjects': ['Maths', 'Physics']}
    d.update(kw); return d
def _student(i, **kw):
    d = {'id': i, 'name': f'Student {i} {HOSTILE}', 'username': f's{i}@c.edu', 'collegeName': 'C1', 'departmentName': 'CSE', 'rollNo': f'R{i}', 'approved': True, 'active': True, 'currentSemester': 3, 'prnNo': f'P{i}', 'academicYear': 2}
    d.update(kw); return d
RICH = {
    '/api/exam/all': RICH_EXAMS, '/api/hod/exams': RICH_EXAMS,
    '/api/exam/my-archives': [{'id': 1, 'examTitle': f'Old Exam {HOSTILE}', 'archivedByUsername': 't@c.edu', 'archivedAt': '2026-08-01T10:00:00'}],
    '/api/exam/all-archives': [{'id': 1, 'examTitle': f'Old Exam {HOSTILE}', 'archivedByUsername': 't@c.edu', 'archivedAt': '2026-08-01T10:00:00'}],
    '/api/hod/staff/active': [_teacher(1), _teacher(2, role='HOD')], '/api/hod/staff/pending': [_teacher(3, approved=False)],
    '/api/admin/students': [_student(1), _student(2, active=False)], '/api/admin/teachers': [_teacher(1), _teacher(2, principal=True, role='PRINCIPAL'), _teacher(3, active=False)],
    '/api/auth/admin/pending-principals': [_teacher(9, principal=True, approved=False, role='PRINCIPAL')],
    '/api/principal/teachers': [_teacher(1), _teacher(2, role='HOD')],
    '/api/principal/department-exceptions': [{'rollNo': 'R1', 'name': f'Student 1 {HOSTILE}', 'examTitle': 'Maths Quiz', 'score': '3/10', 'percentage': '30%', 'status': 'FAILED'},
                                             {'rollNo': 'R2', 'name': 'Student 2', 'examTitle': 'Maths Quiz', 'score': '0/10', 'percentage': '0%', 'status': 'ABSENT'}],
    '/api/exam/student/available': [{'id': 1, 'title': f'Maths Quiz {HOSTILE}', 'subjectName': 'Maths', 'durationMinutes': 20, 'startTime': '2026-10-01T09:00:00', 'endTime': '2026-12-31T09:00:00'},
                                    {'id': 4, 'title': 'Future Exam', 'subjectName': 'Chem', 'durationMinutes': 30, 'startTime': '2027-01-01T09:00:00', 'endTime': '2027-01-02T09:00:00'}],
    '/api/exam/student/missed': [{'id': 3, 'title': 'Old Exam', 'subjectName': 'Maths', 'endTime': '2026-10-01T09:00:00'}],
}
ENV_NOISE = re.compile(r'tailwind|ERR_FAILED|net::|Failed to load resource|fonts\.|fontawesome', re.I)

def new_page(ctx, errors):
    page = ctx.new_page()
    page.on('pageerror', lambda e: errors.append('pageerror: ' + str(e)))
    page.on('console', lambda m: errors.append('console: ' + m.text) if m.type == 'error' else None)
    return page

with sync_playwright() as pw:
    br = pw.chromium.launch()
    ctx = br.new_context(viewport={'width': 1280, 'height': 800})
    ctx.route('**/*', handler)
    ctx.add_init_script(SHIM)

    # ------------------------------------------------------------------ LOGIN
    print('\nLogin page (real DOM, mocked /api/auth/login)')
    errs = []; page = new_page(ctx, errs)
    page.goto(BASE + '/login.html'); page.wait_for_load_state('domcontentloaded')
    ok('exactly one visible H1: Sign In', page.locator('h1').count() == 1 and page.locator('h1').inner_text() == 'Sign In')
    page.click('#loginBtn')
    ok('empty submit shows field errors and sends no request', page.locator('#emailError').is_visible() and page.locator('#passwordError').is_visible() and not state['login_calls'])
    ok('invalid fields are flagged for assistive tech', page.get_attribute('#email', 'aria-invalid') == 'true' and page.get_attribute('#password', 'aria-invalid') == 'true')
    ok('focus moves to the first invalid field', page.evaluate('document.activeElement.id') == 'email')
    page.fill('#email', 'x@y.edu'); page.fill('#password', 'secret')
    page.click('#togglePw')
    ok('show/hide toggle reveals the password and updates its label', page.get_attribute('#password', 'type') == 'text' and page.get_attribute('#togglePw', 'aria-label') == 'Hide password' and page.get_attribute('#togglePw', 'aria-pressed') == 'true')
    page.click('#togglePw'); ok('toggle hides it again', page.get_attribute('#password', 'type') == 'password')

    state['login'] = (401, {'error': 'Invalid credentials.'}); page.click('#loginBtn'); page.wait_for_selector('#errorMsg:not([hidden])')
    ok('backend error text is displayed', 'Invalid credentials.' in page.inner_text('#errorText'))
    ok('button is usable again after a failure', page.is_enabled('#loginBtn') and page.inner_text('#loginBtn').strip() == 'Sign In')
    state['login'] = (429, {'error': 'Too many login attempts. Please wait a few minutes and try again.'}); page.click('#loginBtn'); page.wait_for_function("document.getElementById('errorText').textContent.includes('Too many')")
    ok('rate-limit message is displayed', True)
    state['login'] = (500, 'HTML'); page.click('#loginBtn'); page.wait_for_function("document.getElementById('errorText').textContent.includes('500')")
    ok('non-JSON server error gives a clear status message (not "connection failed")', 'status 500' in page.inner_text('#errorText'))

    state['login'] = (404, {'x': 1}); page.click('#loginBtn'); page.wait_for_function("document.getElementById('errorText').textContent.includes('not found')")
    ok('login: a 404 explains the likely cause (IDE preview) instead of a bare status code', 'IDE preview' in page.inner_text('#errorText') and 'localhost:8080/login.html' in page.inner_text('#errorText'))
    ok('login: the IDE-preview hint is hidden on a normal server address', page.evaluate("document.getElementById('ideHint').hidden") is True)
    ok('login: the right-hand panel offers Create an Account and lists the five roles (information only)', page.locator('.login-top-btn').inner_text() == 'Create an Account' and page.locator('.login-role-list li').count() == 5 and page.locator('.login-role-list button, .login-role-list a, .login-role-list select').count() == 0)
    ok('login: the roles section has its own H2 and the page still has exactly one H1', page.locator('h1').count() == 1 and page.inner_text('#rolesTitle') == 'One Sign-In for Every Role')
    ide = ctx.new_page()
    ctx.route('http://app.test:63342/**', lambda route, request: route.fulfill(status=404, body='no') if '/api/' in request.url else route.fulfill(status=200, content_type=mimetypes.guess_type(request.url.split('?')[0])[0] or 'text/html', body=(STATIC / (request.url.split('?')[0].split('63342/')[1] or 'login.html')).read_bytes()))
    ide.goto('http://app.test:63342/login.html'); ide.wait_for_selector('#loginBtn')
    ok('login: opened from the IDE preview port (63342) the page warns that sign-in cannot work there', ide.is_visible('#ideHint') and 'IDE preview' in ide.inner_text('#ideHint'))
    ide.close()
    for role, dest in [('ADMIN', 'admin-dashboard.html'), ('PRINCIPAL', 'principal-dashboard.html'), ('HOD', 'hod-dashboard.html'),
                       ('TEACHER', 'teacher-dashboard.html'), ('STUDENT', 'student-exam.html')]:
        state['login'] = (200, {'role': role, 'username': 'U', 'id': 5}); state['login_calls'].clear()
        p2 = new_page(ctx, []); p2.goto(BASE + '/login.html'); p2.fill('#email', 'x@y.edu'); p2.fill('#password', 'secret')
        p2.dblclick('#loginBtn')                                   # accidental double submit
        p2.wait_for_url('**/' + dest)
        ok(f'{role} is redirected to {dest}; double-click sent ONE request', len(state['login_calls']) == 1)
        if role == 'TEACHER':
            ok('request payload keeps the existing contract {username, password}', json.loads(state['login_calls'][0]) == {'username': 'x@y.edu', 'password': 'secret'})
            ok('currentUser stored for the dashboards', json.loads(p2.evaluate("localStorage.getItem('currentUser')"))['role'] == 'TEACHER')
        p2.close()

    page.goto(BASE + '/login.html?expired=1')
    ok('expired-session notice appears', 'session has ended' in page.inner_text('#errorText'))
    for w in (1280, 768, 375):
        page.set_viewport_size({'width': w, 'height': 800}); page.goto(BASE + '/login.html')
        ok(f'no horizontal overflow at {w}px', page.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth'))
    ok('no unexpected console/page errors on login', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))

    # ------------------------------------------------------------------ FORM LABELS (all auth pages)
    print('\nForm accessibility (every input has an accessible name; ids are unique)')
    ctx.add_init_script("localStorage.setItem('x','1')")
    for pg in ('login.html', 'register.html', 'forgot-password.html', 'reset-password.html?token=abc'):
        p3 = new_page(ctx, []); p3.goto(BASE + '/' + pg)
        bad = p3.evaluate("""() => [...document.querySelectorAll('input:not([type=hidden]),select,textarea')].filter(el => {
            const named = el.getAttribute('aria-label') || el.getAttribute('aria-labelledby') || (el.labels && el.labels.length);
            return !named; }).map(e => e.id || e.name)""")
        dup = p3.evaluate("""() => { const ids = [...document.querySelectorAll('[id]')].map(e => e.id); return ids.filter((x, i) => ids.indexOf(x) !== i) }""")
        ok(f'{pg}: all fields labelled', not bad, str(bad)); ok(f'{pg}: no duplicate ids', not dup, str(dup)); p3.close()

    # ------------------------------------------------------------------ STUDENT PAGE + SHELL
    print('\nStudent page, shell and answer review (real layout.js, ui.js, api.js)')
    ctx2 = br.new_context(viewport={'width': 1280, 'height': 800}); ctx2.route('**/*', handler); ctx2.add_init_script(SHIM)
    ctx2.add_init_script("localStorage.setItem('currentUser', JSON.stringify({role:'STUDENT',username:'Asha Rao',rollNo:'R7',id:9,collegeName:'C1',departmentName:'CS'}))")
    state['unmatched'].clear()                                  # only count calls made by the student page
    errs = []; sp = new_page(ctx2, errs); sp.goto(BASE + '/student-exam.html'); sp.wait_for_selector('#igSidebar')
    tb = sp.inner_text('.topbar')
    ok('top bar reads "Student Portal", not "STUDENT Portal"', 'Student Portal' in tb and 'STUDENT' not in tb.replace('Student Portal', ''), repr(tb[:60]))
    ok('sidebar brand reads "Student Workspace"', 'Student Workspace' in sp.inner_text('#igSidebar'))
    ok('context line uses Title Case ("Roll No. R7")', 'Roll No. R7' in tb)
    ok('skip link exists and is the first focusable element', sp.evaluate("document.querySelector('.skip-link').textContent") == 'Skip to Main Content')
    ok('active nav item is exposed with aria-current', sp.evaluate("document.querySelector('.nav-item[aria-current=page]')?.textContent.trim()") == 'Overview')
    sp.click('#tab-results'); sp.wait_for_selector('#pastResults .ui-card')
    ok('switching tabs moves aria-current to My Results', sp.evaluate("document.querySelector('.nav-item[aria-current=page]')?.textContent.trim()") == 'My Results')
    ok('released result shows Review Answers; unreleased one says the teacher releases answers', sp.locator('text=Review Answers').count() == 1 and 'once your teacher releases them' in sp.inner_text('#pastResults'))

    btn = sp.locator('text=Review Answers'); btn.focus(); btn.click(); sp.wait_for_selector('#reviewBody .rvw-q')
    ok('review dialog is a modal dialog labelled by its title', sp.get_attribute('#reviewBox', 'role') == 'dialog' and sp.get_attribute('#reviewBox', 'aria-modal') == 'true')
    ok('focus is moved into the dialog', sp.evaluate("document.getElementById('reviewBox').contains(document.activeElement)"))
    ok('correct answers are marked, wrong choice is marked', sp.locator('.rvw-opt.is-key').count() == 2 and sp.locator('.rvw-opt.is-yours-wrong').count() == 1)
    ok('page behind is locked from scrolling', sp.evaluate("document.body.style.overflow") == 'hidden')
    for _ in range(12): sp.keyboard.press('Tab')
    ok('Tab never leaves the dialog (focus trap, 12 presses)', sp.evaluate("document.getElementById('reviewBox').contains(document.activeElement)"))
    for _ in range(12): sp.keyboard.press('Shift+Tab')
    ok('Shift+Tab never leaves the dialog either', sp.evaluate("document.getElementById('reviewBox').contains(document.activeElement)"))
    sp.keyboard.press('Escape')
    ok('Escape closes the dialog', sp.locator('#reviewOverlay').is_hidden())
    ok('focus returns to the button that opened it', sp.evaluate("document.activeElement.textContent.includes('Review Answers')"))
    ok('page scrolling is restored', sp.evaluate("document.body.style.overflow") == '')

    # confirm dialog (ui.js) after the h3 -> h2 change
    sp.evaluate("() => { window.__c = IGUI.confirm({title: 'Delete This Exam?', message: 'This cannot be undone.', confirmText: 'Delete'}); }")
    sp.wait_for_selector('.ig-dialog')
    ok('IGUI.confirm renders with an h2 title', sp.inner_text('.ig-dialog h2') == 'Delete This Exam?')
    sp.click('.ig-dialog [data-act=cancel]'); ok('confirm dialog resolves false on cancel', sp.evaluate('window.__c') is False)

    # mobile shell
    sp.set_viewport_size({'width': 375, 'height': 800}); sp.goto(BASE + '/student-exam.html'); sp.wait_for_selector('#igMenuBtn')
    ok('mobile: menu button reports collapsed state', sp.get_attribute('#igMenuBtn', 'aria-expanded') == 'false')
    sp.click('#igMenuBtn')
    ok('mobile: opening sets aria-expanded and shows the sidebar', sp.get_attribute('#igMenuBtn', 'aria-expanded') == 'true' and 'open' in sp.get_attribute('#igSidebar', 'class'))
    sp.keyboard.press('Escape')
    ok('mobile: Escape closes the menu and returns focus to the button', 'open' not in sp.get_attribute('#igSidebar', 'class') and sp.evaluate("document.activeElement.id") == 'igMenuBtn')
    ok('mobile: no horizontal overflow', sp.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth'))
    real_errs = [e for e in errs if not ENV_NOISE.search(e)]
    ok('no unexpected console/page errors on the student page', not real_errs, str(real_errs[:4]))
    ok('every API call the page made was handled by the mock', not state['unmatched'], str(set(state['unmatched'])))

    # ------------------------------------------------------------------ RESULTS MODAL (teacher + principal)
    print('\nExam results modal (shared results-table.js on the real teacher and principal pages)')
    names = ['Zed Khan', 'Amy Lee', 'Bob Roy', 'Cy Das', 'Dee Sen', 'Eli Rao', 'Fay Jha', 'Gus Pal', 'Hal Ray', 'Ivy Nag', 'Jon Bose', 'Kim Dey']
    def fresh_results(): return {
        201: [{'id': i, 'studentName': n, 'rollNo': f'R{i + 1}', 'marksObtained': (i * 7) % 11, 'totalMarks': 10} for i, n in enumerate(names)]
             + [{'id': 99, 'studentName': 'No Marks', 'rollNo': 'R99', 'marksObtained': 0, 'totalMarks': 0}],
        202: [], 203: (403, {'error': 'You do not have access to this exam.'}), 204: 'HTML500',
        205: [{'id': 1, 'student': {'name': 'Nested Student', 'rollNo': 'N1'}, 'marksObtained': 5, 'totalMarks': 10}]}
    for page_name, role in (('teacher-dashboard.html', 'TEACHER'), ('principal-dashboard.html', 'PRINCIPAL')):
        state['exam_results'] = fresh_results()                 # each page starts from the same mock data
        cx = br.new_context(viewport={'width': 1280, 'height': 900}); cx.route('**/*', handler); cx.add_init_script(SHIM)
        cx.add_init_script("localStorage.setItem('currentUser', JSON.stringify({role:'%s',username:'Test User',id:3,collegeName:'C1',departmentName:'CSE',branchName:'CSE'}))" % role)
        errs = []; rp = new_page(cx, errs); rp.goto(BASE + '/' + page_name); rp.wait_for_selector('#igSidebar')
        tag = page_name.split('-')[0]
        run = lambda i: rp.evaluate(f"() => viewResults({i}, 'Sample Exam')")
        body = lambda: rp.inner_text('#resTableBody')
        # Tailwind's fixed/inset utilities are absent offline, so the modal is not a true overlay and a physical click can
        # land on the sidebar. Dispatching click() on the real button still exercises the page's real handler.
        hdr = lambda label: rp.evaluate("l => document.querySelector('th[data-label=\"' + l + '\"] .th-sort').click()", label)

        run(201)
        heads = rp.evaluate("[...document.querySelectorAll('#resultsModal thead th')].map(t => t.getAttribute('data-label'))")
        ok(f'{tag}: column headers are consistent (Roll No, Student Name, Score, Percentage)', heads == ['Roll No', 'Student Name', 'Score', 'Percentage'], str(heads))
        ok(f'{tag}: all 13 students are listed', rp.locator('#resTableBody tr').count() == 13)
        ok(f'{tag}: zero-mark student shows a dash, never NaN', 'NaN' not in body() and 'No Marks' in body())
        ok(f'{tag}: count line reads "Showing 13 of 13 students"', 'Showing 13 of 13 students' in rp.inner_text('#resCount'))
        ok(f'{tag}: table can scroll sideways on small screens', rp.evaluate("document.getElementById('resTableBody').closest('table').parentElement.classList.contains('overflow-x-auto')"))
        ok(f'{tag}: Export CSV is enabled when there are results', rp.is_enabled('#exportCsvBtn'))
        rp.fill('#resSearchBox', 'amy'); ok(f'{tag}: search by name narrows the list', rp.locator('#resTableBody tr').count() == 1 and 'Amy Lee' in body())
        ok(f'{tag}: count line follows the filter', 'Showing 1 of 13' in rp.inner_text('#resCount'))
        rp.fill('#resSearchBox', 'r99'); ok(f'{tag}: search by roll number works', 'No Marks' in body() and rp.locator('#resTableBody tr').count() == 1)
        rp.fill('#resSearchBox', 'zzzz'); ok(f'{tag}: no match shows a helpful message', 'No students match your search.' in body())
        rp.fill('#resSearchBox', '')
        hdr('Percentage')
        pct_first = rp.evaluate("document.querySelector('#resTableBody tr td:last-child').textContent.trim()")
        ok(f'{tag}: sorting by Percentage puts the highest first and exposes aria-sort', pct_first == '100%' and rp.get_attribute('th[data-label="Percentage"]', 'aria-sort') == 'descending', pct_first)
        ok(f'{tag}: missing percentage sorts last', rp.evaluate("[...document.querySelectorAll('#resTableBody tr')].pop().textContent.includes('No Marks')"))
        hdr('Percentage'); ok(f'{tag}: second click reverses the order', rp.get_attribute('th[data-label="Percentage"]', 'aria-sort') == 'ascending')
        hdr('Roll No'); ok(f'{tag}: sorting another column resets the previous one', rp.get_attribute('th[data-label="Percentage"]', 'aria-sort') == 'none')
        rp.focus('th[data-label="Student Name"] .th-sort'); rp.keyboard.press('Enter')
        ok(f'{tag}: column headers can be sorted from the keyboard', rp.get_attribute('th[data-label="Student Name"]', 'aria-sort') == 'ascending')

        run(202)
        ok(f'{tag}: empty exam shows a clear message', 'No submissions have been recorded for this exam yet.' in body())
        ok(f'{tag}: empty exam hides search and disables export', rp.locator('#resToolbar').is_hidden() and rp.is_disabled('#exportCsvBtn'))
        run(203)
        ok(f'{tag}: forbidden shows the server message, not developer text', 'You do not have access to this exam.' in body() and 'mapping' not in body() and 'console' not in body())
        ok(f'{tag}: error state offers Try Again', rp.locator('[data-retry]').count() == 1 and rp.is_disabled('#exportCsvBtn'))
        state['exam_results'][203] = state['exam_results'][205]; rp.evaluate("document.querySelector('[data-retry]').click()"); rp.wait_for_function("document.getElementById('resTableBody').textContent.includes('Nested Student')")
        ok(f'{tag}: Try Again reloads successfully (nested student shape works)', 'N1' in body())
        run(204); ok(f'{tag}: non-JSON server error still gives a readable message', 'The results could not be loaded.' in body() and 'boom' not in body())
        run(205)
        ok(f'{tag}: failed analytics does not hide the results', 'Nested Student' in body() and 'Statistics are unavailable right now.' in rp.inner_text('#absentBadge'))
        ok(f'{tag}: no unexpected console/page errors in the results flow', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))
        cx.close()

    # ------------------------------------------------------------------ ANSWER RELEASE (teacher + principal)
    print('\nAnswer release control (real answer-release.js on the teacher and principal results screens)')
    for page_name, role in (('teacher-dashboard.html', 'TEACHER'), ('principal-dashboard.html', 'PRINCIPAL')):
        state['release'] = {
            301: {'examId': 301, 'released': False, 'releasedAt': None, 'canRelease': True, 'examEndsAt': '2026-10-20T10:00:00', 'canManage': True},
            302: {'examId': 302, 'released': False, 'releasedAt': None, 'canRelease': False, 'examEndsAt': '2026-12-20T10:00:00', 'canManage': True},
            303: {'examId': 303, 'released': False, 'releasedAt': None, 'canRelease': True, 'examEndsAt': '2026-10-20T10:00:00', 'canManage': False},
            304: {'examId': 304, 'released': False, 'releasedAt': None, 'canRelease': True, 'examEndsAt': '2026-10-20T10:00:00', 'canManage': True}}
        state['release_puts'] = []
        cx = br.new_context(viewport={'width': 1280, 'height': 900}); cx.route('**/*', handler); cx.add_init_script(SHIM)
        cx.add_init_script("localStorage.setItem('currentUser', JSON.stringify({role:'%s',username:'Test User',id:3,collegeName:'C1',departmentName:'CSE',branchName:'CSE'}))" % role)
        errs = []; rp = new_page(cx, errs); rp.goto(BASE + '/' + page_name); rp.wait_for_selector('#igSidebar')
        tag = page_name.split('-')[0]
        box = lambda: rp.inner_text('#answerReleaseBox')
        click = lambda sel: rp.evaluate("s => document.querySelector(s).click()", sel)
        def open_exam(i):
            rp.evaluate(f"() => viewResults({i}, 'Sample Exam')"); rp.wait_for_selector('#answerReleaseBox h2')

        open_exam(301)
        ok(f'{tag}: panel is titled "Student Answer Review" and explains the state', 'Student Answer Review' in box() and 'not the correct answers yet' in box())
        ok(f'{tag}: closed exam offers Release Answers', 'Release Answers' in rp.inner_text('#answerReleaseBtn'))
        click('#answerReleaseBtn'); rp.wait_for_selector('.ig-dialog')
        ok(f'{tag}: releasing asks for confirmation and says what students will see', rp.inner_text('.ig-dialog h2') == 'Release Answers to Students?' and 'correct answer' in rp.inner_text('.ig-dialog p'))
        rp.click('.ig-dialog [data-act=cancel]'); rp.wait_for_timeout(300)
        ok(f'{tag}: cancelling sends nothing', not state['release_puts'] and 'Release Answers' in rp.inner_text('#answerReleaseBtn'))
        click('#answerReleaseBtn'); rp.wait_for_selector('.ig-dialog'); rp.click('.ig-dialog [data-act=ok]')
        rp.wait_for_function("document.getElementById('answerReleaseBox').textContent.includes('visible to students')")
        ok(f'{tag}: confirming sends exactly {{released: true}} once', state['release_puts'] == [(301, {'released': True})], str(state['release_puts']))
        ok(f'{tag}: panel switches to released state with Hide Answers', 'Hide Answers' in rp.inner_text('#answerReleaseBtn'))
        ok(f'{tag}: success toast is shown', 'Answers released to students.' in rp.inner_text('body'))
        click('#answerReleaseBtn'); rp.wait_for_selector('.ig-dialog')
        ok(f'{tag}: hiding is a destructive-style confirmation', rp.inner_text('.ig-dialog h2') == 'Hide Answers From Students?' and rp.locator('.ig-dialog .btn-danger').count() == 1)
        rp.click('.ig-dialog [data-act=ok]'); rp.wait_for_function("document.getElementById('answerReleaseBox').textContent.includes('cannot see') || document.getElementById('answerReleaseBox').textContent.includes('not the correct answers yet')")
        ok(f'{tag}: confirming hide sends {{released: false}}', state['release_puts'][-1] == (301, {'released': False}))

        open_exam(302)
        ok(f'{tag}: exam still open shows when answers can be released and no button', 'after the exam closes' in box() and rp.locator('#answerReleaseBtn').count() == 0)
        open_exam(303)
        ok(f'{tag}: user without manage rights sees status but no button', rp.locator('#answerReleaseBtn').count() == 0 and 'Student Answer Review' in box())
        open_exam(304); n_before = len(state['release_puts'])
        click('#answerReleaseBtn'); rp.wait_for_selector('.ig-dialog'); rp.click('.ig-dialog [data-act=ok]')
        rp.wait_for_function("document.body.innerText.includes('submission grace period is over')")
        ok(f'{tag}: a server refusal is shown to the user and the button works again', rp.is_enabled('#answerReleaseBtn') and len(state['release_puts']) == n_before + 1)
        open_exam(399); rp.wait_for_selector('#answerReleaseBox [role=alert]')
        ok(f'{tag}: failure to load the status is reported, not silent', 'You do not have access to this exam.' in box())
        ok(f'{tag}: no unexpected console/page errors in the release flow', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))
        cx.close()

    # ------------------------------------------------------------------ AI QUESTION GENERATION (teacher page)
    print('\nAI question generation (real teacher page + real ai-review.js, mocked /api/ai/jobs)')
    PDF = {'name': 'notes.pdf', 'mimeType': 'application/pdf', 'buffer': b'%PDF-1.4\n' + b'x' * 600}
    def ai_page(slow=False):
        state['slow_cfg'] = slow; state['ai_calls'].clear()
        cx = br.new_context(viewport={'width': 1280, 'height': 900}); cx.route('**/*', handler); cx.add_init_script(SHIM)
        cx.add_init_script("localStorage.setItem('currentUser', JSON.stringify({role:'TEACHER',username:'Test User',id:3,collegeName:'C1',departmentName:'CSE',branchName:'CSE'}))")
        errs = []; ap = new_page(cx, errs); ap.goto(BASE + '/teacher-dashboard.html'); ap.wait_for_selector('#igSidebar'); ap.wait_for_selector('#rvDifficulty', state='attached')
        ap.evaluate("() => openModal('createModal')")                 # the generator lives inside this modal
        return cx, ap, errs
    click = lambda ap, sel: ap.evaluate("s => document.querySelector(s).click()", sel)

    # --- form clarity
    state['ai_mode'] = 'ok'; cx, ap, errs = ai_page()
    ok('form: question count has a visible label tied to the field', ap.evaluate("document.querySelector('label[for=numQuestions]')?.textContent") == 'Number of Questions')
    ok('form: difficulty has a visible label tied to the select', ap.evaluate("document.querySelector('label[for=rvDifficulty]')?.textContent") == 'Difficulty')
    ok('form: button reads "Generate Questions"', ap.inner_text('#generateBtn').strip() == 'Generate Questions')
    form_text = ap.evaluate("document.getElementById('generateBtn').closest('.bg-brand-50').innerText")
    ok('form: states the real limits (20 MB, text-based PDFs, scanned unsupported)', '20 MB' in form_text and 'scanned' in form_text)
    ok('form: no wrong brand name and no "instantly" overpromise', 'IGen' not in form_text and 'instantly' not in form_text.lower())
    ok('form: says the teacher reviews every question before saving', 'review and edit every question' in form_text)
    ok('form: upload area is described by the help text', ap.get_attribute('.upload-area', 'aria-describedby') == 'uploadHelp')

    # --- success path
    ap.set_input_files('#pdfFile', PDF); ap.fill('#numQuestions', '3'); click(ap, '#generateBtn')
    ap.wait_for_selector('#rvBar')
    ok('progress: the bar has an accessible name', ap.get_attribute('#rvBar', 'aria-label') == 'Question generation progress')
    ap.wait_for_function("document.getElementById('rvStage') && document.getElementById('rvStage').textContent.includes('Waiting for another generation')")
    ok('progress: queued state is explained to the teacher', True)
    ok('progress: the generate button is locked while running', ap.is_disabled('#generateBtn') and 'Generating' in ap.inner_text('#generateBtn'))
    ap.wait_for_function("document.body.innerText.includes('AI-Drafted')", timeout=20000)
    body = ap.inner_text('body')
    ok('cards: both questions are labelled AI-Drafted', body.count('AI-Drafted') >= 2)
    ok('cards: match badges are Title Case and honest (Strong / Partial)', 'Text Match: Strong' in body and 'Text Match: Partial' in body)
    ok('cards: difficulty badges are capitalised (Medium, Hard), never raw lowercase', 'Medium' in body and 'Hard' in body and not ap.evaluate("[...document.querySelectorAll('.rv-plain')].some(e => e.textContent.trim() === 'medium' || e.textContent.trim() === 'hard')"))
    ok('cards: source page labels are Title Case', 'Source (Page 3)' in body and 'Source (Page 7)' in body)
    ok('cards: generating again is possible (button restored)', ap.is_enabled('#generateBtn') and ap.inner_text('#generateBtn').strip() == 'Generate Questions')
    ok('unsaved work: page reports unsaved AI questions', ap.evaluate("IGReview.isDirty()") is True)
    ok('unsaved work: leaving the page is blocked by the browser prompt', ap.evaluate("() => { const e = new Event('beforeunload', {cancelable: true}); window.dispatchEvent(e); return e.defaultPrevented; }") is True)
    ap.evaluate("IGReview.reset()")
    ok('unsaved work: after saving/clearing, leaving is no longer blocked', ap.evaluate("() => { const e = new Event('beforeunload', {cancelable: true}); window.dispatchEvent(e); return e.defaultPrevented; }") is False)
    ok('no unexpected console/page errors during a full generation', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))
    cx.close()

    # --- slow run, hint, cancel
    state['ai_mode'] = 'slow'; cx, ap, errs = ai_page(slow=True)
    ap.set_input_files('#pdfFile', PDF); click(ap, '#generateBtn'); ap.wait_for_selector('#rvBar')
    ok('slow hint: not shown at the start', ap.evaluate("document.getElementById('rvSlow').hidden") is True)
    ap.wait_for_function("!document.getElementById('rvSlow').hidden", timeout=15000)
    ok('slow hint: appears when nothing changes, and suggests fewer questions', 'taking longer than usual' in ap.inner_text('#rvSlow') and 'fewer questions' in ap.inner_text('#rvSlow'))
    click(ap, '#rvCancel'); ap.wait_for_function("!document.getElementById('rvBar')", timeout=8000)
    for _ in range(40):                                          # the DELETE is fire-and-forget; give the mock server time to log it
        if 'DELETE' in state['ai_calls']: break
        ap.wait_for_timeout(100)
    ok('cancel: the AI job is cancelled on the server', 'DELETE' in state['ai_calls'])
    ok('cancel: the form is usable again and nothing was added', ap.is_enabled('#generateBtn') and ap.evaluate("IGReview.count()") == 0 and ap.evaluate("IGReview.isDirty()") is False)
    cx.close()

    # --- model failure, then upload rejection
    state['ai_mode'] = 'fail'; cx, ap, errs = ai_page()
    ap.set_input_files('#pdfFile', PDF); click(ap, '#generateBtn'); ap.wait_for_selector('#rvRetry', timeout=15000)
    fb = ap.inner_text('.rv-failed')
    ok('failure: clear title, server reason and Try Again', 'Could Not Generate Questions' in fb and 'Start Ollama and try again.' in fb and 'Try Again' in fb)
    ok('failure: the alert is announced to assistive technology', ap.get_attribute('.rv-failed', 'role') == 'alert')
    ok('failure: the form is usable again', ap.is_enabled('#generateBtn'))
    cx.close()
    state['ai_mode'] = 'upload_error'; cx, ap, errs = ai_page()
    ap.set_input_files('#pdfFile', PDF); click(ap, '#generateBtn')
    ap.wait_for_function("document.body.innerText.includes('Scanned (image-only) PDFs are not supported.')", timeout=10000)
    ok('upload rejection: the server reason reaches the teacher (scanned PDF)', True)
    ok('upload rejection: form is usable again', ap.is_enabled('#generateBtn'))
    ok('no unexpected console/page errors in the failure paths', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))
    cx.close(); state['slow_cfg'] = False

    # ------------------------------------------------------------------ ACCESSIBILITY (modals, tables) + REFLOW
    print('\nAccessibility: dialogs, focus handling, table headers (real pages)')
    for page_name, role, modal_ids in (('teacher-dashboard.html', 'TEACHER', ['createModal', 'resultsModal', 'archiveModal', 'deleteModal']),
                                       ('principal-dashboard.html', 'PRINCIPAL', ['resultsModal', 'createExamModal', 'archiveModal', 'deleteModal']),
                                       ('hod-dashboard.html', 'HOD', ['createExamModal'])):
        cx = br.new_context(viewport={'width': 1280, 'height': 900}); cx.route('**/*', handler); cx.add_init_script(SHIM)
        cx.add_init_script("localStorage.setItem('currentUser', JSON.stringify({role:'%s',username:'Test User',id:3,collegeName:'C1',departmentName:'CSE',branchName:'CSE'}))" % role)
        errs = []; mp = new_page(cx, errs); mp.goto(BASE + '/' + page_name); mp.wait_for_selector('#igSidebar'); tag = page_name.split('-')[0]
        info = mp.evaluate("""() => [...document.querySelectorAll('.modal-overlay')].map(o => { const p = o.firstElementChild;
            const lab = p.getAttribute('aria-labelledby'); const h = lab && document.getElementById(lab);
            return {id: o.id, role: p.getAttribute('role'), modal: p.getAttribute('aria-modal'), name: h ? h.textContent.trim() : ''} })""")
        ok(f'{tag}: all {len(info)} modals are dialogs with aria-modal', all(i['role'] == 'dialog' and i['modal'] == 'true' for i in info) and len(info) == len(modal_ids), str(info))
        ok(f'{tag}: every dialog has an accessible name (from its heading)', all(i['name'] for i in info), str([i['id'] for i in info if not i['name']]))
        nav_btn = '#igSidebar .nav-item'
        simples = [m for m in modal_ids if 'create' not in m.lower() and m != 'resultsModal']
        if simples:
            simple = simples[-1]
            mp.focus(nav_btn)
            mp.evaluate(f"() => openModal('{simple}')")
            ok(f'{tag}: opening {simple} moves focus inside the dialog', mp.evaluate(f"document.getElementById('{simple}').contains(document.activeElement)"))
            for _ in range(14): mp.keyboard.press('Tab')
            ok(f'{tag}: Tab stays inside the dialog (14 presses)', mp.evaluate(f"document.getElementById('{simple}').contains(document.activeElement)"))
            for _ in range(14): mp.keyboard.press('Shift+Tab')
            ok(f'{tag}: Shift+Tab stays inside the dialog (14 presses)', mp.evaluate(f"document.getElementById('{simple}').contains(document.activeElement)"))
            mp.keyboard.press('Escape')
            ok(f'{tag}: Escape closes it and focus returns to the opener', mp.evaluate(f"document.getElementById('{simple}').classList.contains('hidden')") and mp.evaluate("document.activeElement.classList.contains('nav-item')"))
        create = [m for m in modal_ids if 'create' in m.lower()][0]
        mp.focus(nav_btn); mp.evaluate(f"() => openModal('{create}')")
        ok(f'{tag}: the create-exam form focuses its first field, not the close button', mp.evaluate(f"['INPUT','SELECT','TEXTAREA'].includes(document.activeElement.tagName) && document.getElementById('{create}').contains(document.activeElement)"))
        mp.evaluate(f"() => closeModal('{create}')")
        ok(f'{tag}: closing the create form returns focus to the opener', mp.evaluate("document.activeElement.classList.contains('nav-item')"))
        bad_th = mp.evaluate("[...document.querySelectorAll('th')].filter(t => !t.getAttribute('scope')).length")
        ok(f'{tag}: every table header cell declares scope', bad_th == 0, str(bad_th))
        ok(f'{tag}: no unexpected console/page errors', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))
        cx.close()

    print('\nResponsive reflow (no horizontal scrolling, incl. 320px / 400% zoom equivalent)')
    for name, role in (('login.html', None), ('register.html', None), ('forgot-password.html', None), ('reset-password.html?token=t', None),
                       ('admin-dashboard.html', 'ADMIN'), ('hod-dashboard.html', 'HOD'), ('principal-dashboard.html', 'PRINCIPAL'),
                       ('teacher-dashboard.html', 'TEACHER'), ('student-exam.html', 'STUDENT')):
        bad_w = []
        for w in (320, 375, 768, 1280):
            cx = br.new_context(viewport={'width': w, 'height': 800}); cx.route('**/*', handler); cx.add_init_script(SHIM)
            if role: cx.add_init_script("localStorage.setItem('currentUser', JSON.stringify({role:'%s',username:'Test User',rollNo:'R1',id:3,collegeName:'C',departmentName:'CSE',branchName:'CSE'}))" % role)
            rp = cx.new_page(); rp.goto(BASE + '/' + name); rp.wait_for_timeout(350)
            if rp.evaluate('document.documentElement.scrollWidth > document.documentElement.clientWidth + 1'): bad_w.append(w)
            cx.close()
        ok(f'{name.split("?")[0]}: no horizontal overflow at 320/375/768/1280px', not bad_w, f'overflows at {bad_w}')

    # ------------------------------------------------------------------ DATA-PATH RENDERING (rich data on every dashboard)
    print('\nData-path rendering: every dashboard and tab with realistic and hostile data')
    state['rich'] = True
    LEAK = re.compile(r'\b(undefined|NaN|\[object Object\])\b|\bnull\b')
    for page_name, role in (('admin-dashboard.html', 'ADMIN'), ('hod-dashboard.html', 'HOD'), ('principal-dashboard.html', 'PRINCIPAL'),
                            ('teacher-dashboard.html', 'TEACHER'), ('student-exam.html', 'STUDENT')):
        cx = br.new_context(viewport={'width': 1280, 'height': 900}); cx.route('**/*', handler); cx.add_init_script(SHIM)
        cx.add_init_script("localStorage.setItem('currentUser', JSON.stringify({role:'%s',username:'O\\'Brien & Sons',email:'t1@c.edu',rollNo:'R1',id:3,collegeName:'C1',departmentName:'CSE',branchName:'Computer Science Engineering'}))" % role)
        errs = []; dp = new_page(cx, errs); dp.goto(BASE + '/' + page_name); dp.wait_for_selector('#igSidebar'); dp.wait_for_timeout(600); tag = page_name.split('-')[0]
        navs = dp.evaluate("[...document.querySelectorAll('#igSidebar .sidebar-nav .nav-item')].filter(b => b.id).map(b => ({id: b.id, t: b.textContent.trim()}))")
        leaks, inj, dbl, lower, shown, saw = [], [], [], [], 0, set()
        for n in [None] + navs:
            if n: dp.evaluate("id => document.getElementById(id).click()", n['id']); dp.wait_for_timeout(350)
            txt = dp.evaluate("document.body.innerText")                  # whole page: top bar, sidebar and content
            shown += len(txt)
            if "O'Brien & Sons <u id=xss>x</u>" in txt: saw.add(n['t'] if n else 'initial')
            m = LEAK.findall(txt)
            if m: leaks.append((n['t'] if n else 'initial', m[:3]))
            if dp.evaluate("!!document.getElementById('xss')"): inj.append(n['t'] if n else 'initial')
            if re.search(r'&amp;|&#39;|&lt;|&gt;|&quot;', txt): dbl.append(n['t'] if n else 'initial')
            for e in dp.evaluate("[...document.querySelectorAll('#igContent h1, #igContent h2, #igContent h3, #igContent button, #igContent th')].filter(e => e.offsetWidth || e.offsetHeight).map(e => e.innerText.trim())"):
                if e and e[0].isalpha() and e[0].islower() and 'xss' not in e: lower.append(e[:40])
        ok(f'{tag}: rendered real data in {len(navs) + 1} views without leaking undefined/NaN/null', not leaks, str(leaks[:3]))
        ok(f'{tag}: hostile HTML in data is shown as text, never injected', not inj, str(inj))
        ok(f'{tag}: no double-escaped entities (&amp; &#39; &lt;) visible to users', not dbl, str(dbl))
        ok(f'{tag}: every visible heading, button and column title starts with a capital', not lower, str(sorted(set(lower))[:6]))
        ok(f'{tag}: the hostile record is displayed literally (proves the mock data was really rendered)', len(saw) >= 1, f'views showing it: {sorted(saw)}')
        ok(f'{tag}: the page produced visible content', shown > 200, str(shown))
        ok(f'{tag}: no JavaScript errors while rendering real data', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))
        cx.close()
    state['rich'] = False

    # ------------------------------------------------------------------ EXAM PLAYER (the page students take exams on)
    print('\nExam player (real exam-player.html against mocked start/autosave/submit)')
    def player(mode='ok'):
        state['player_mode'] = mode; state['player_saves'].clear(); state['player_submits'].clear()
        cx = br.new_context(viewport={'width': 1280, 'height': 900}); cx.route('**/*', handler); cx.add_init_script(SHIM)
        cx.add_init_script("localStorage.setItem('currentUser', JSON.stringify({role:'STUDENT',username:'Asha',email:'a@c.edu',rollNo:'R1',id:9}))")
        errs = []; pp = new_page(cx, errs); pp.goto(BASE + '/exam-player.html?id=7'); return cx, pp, errs
    beforeunload = lambda pp: pp.evaluate("() => { const e = new Event('beforeunload', {cancelable: true}); window.dispatchEvent(e); return e.defaultPrevented; }")

    cx, pp, errs = player(); pp.wait_for_selector('#q-0', timeout=10000)
    ok('player: exam title is shown as text, not injected HTML', 'Maths <u id=xss>x</u> Quiz' in pp.inner_text('#examTitle') and pp.evaluate("!document.getElementById('xss')"))
    ok('player: browser tab title is "<exam> | IntelliGen"', pp.title().endswith('| IntelliGen'))
    ok('player: all 3 questions and 3 palette buttons are rendered', pp.locator('section[id^=q-]').count() == 3 and pp.locator('.q-chip').count() == 3)
    ok('player: every option group is a radiogroup named by its question (screen readers)', pp.evaluate("""() => [...document.querySelectorAll('[role=radiogroup]')].every(g => {
        const l = document.getElementById(g.getAttribute('aria-labelledby')); return l && l.textContent.trim().length > 0 && g.querySelectorAll('input[type=radio]').length >= 2; })""") and pp.locator('[role=radiogroup]').count() == 3)
    ok('player: hostile option text is escaped (apostrophe, ampersand, HTML)', pp.evaluate("[...document.querySelectorAll('.option-body')].map(e => e.textContent).some(t => t.includes(\"O'Brien & Sons\"))") and pp.evaluate("![...document.querySelectorAll('.option-body i')].length"))
    ok('player: the answer saved on the server is restored (Paris)', pp.evaluate("document.querySelector('input[name=question_12]:checked')?.value") == 'Paris')
    ok('player: the flagged question is restored and exposed with aria-pressed', pp.get_attribute('#flag-13', 'aria-pressed') == 'true')
    ok('player: a "saved answer restored" notice is shown', 'saved answer restored' in pp.inner_text('body'))
    ok('player: the countdown starts from the server time (about 30 minutes)', bool(re.search(r'\b(29|30):\d\d\b', pp.inner_text('#timerPill'))))
    ok('player: leaving a live exam triggers the browser warning', beforeunload(pp) is True)

    pp.evaluate("() => { const r = document.querySelector('input[name=question_11][value=\"4\"]'); r.click(); }")
    pp.wait_for_function("window.__n = 0, true"); 
    for _ in range(40):
        if state['player_saves']: break
        pp.wait_for_timeout(100)
    ok('player: choosing an answer is autosaved with an increasing sequence number', bool(state['player_saves']) and state['player_saves'][-1]['seq'] >= 1 and state['player_saves'][-1]['answers'].get('11') == '4', str(state['player_saves'][-1:]))
    ok('player: the autosave keeps earlier answers too', state['player_saves'][-1]['answers'].get('12') == 'Paris')
    ok('player: the save indicator says Saved', 'Saved' in pp.inner_text('#saveStatus'))

    pp.evaluate("() => document.getElementById('submitExamBtn').click()"); pp.wait_for_selector('.ig-dialog')
    dlg = pp.inner_text('.ig-dialog')
    ok('submit: confirmation names the counts (1 of 3 unanswered, 1 flagged)', '1 of 3 questions are still unanswered' in dlg and '1 question is still flagged' in dlg, dlg[:200])
    ok('submit: dialog buttons are Title Case (Submit Answers / Keep Working)', 'Submit Answers' in dlg and 'Keep Working' in dlg)
    pp.click('.ig-dialog [data-act=cancel]'); pp.wait_for_timeout(400)
    ok('submit: Keep Working sends nothing', not state['player_submits'] and pp.is_enabled('#submitExamBtn'))
    pp.evaluate("() => document.getElementById('submitExamBtn').click()"); pp.wait_for_selector('.ig-dialog'); pp.click('.ig-dialog [data-act=ok]')
    pp.wait_for_function("document.querySelector('main').innerText.includes('Examination Submitted')", timeout=8000)
    ok('submit: sends exactly the answers map the server expects, once', len(state['player_submits']) == 1 and state['player_submits'][0] == {'11': '4', '12': 'Paris'}, str(state['player_submits']))
    ok('submit: the confirmation screen appears and leaving is no longer blocked', beforeunload(pp) is False)
    ok('player: no unexpected console/page errors in a full exam', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))
    cx.close()

    cx, pp, errs = player('start403'); pp.wait_for_selector('.empty-state.is-error', timeout=8000)
    ok('player: a refused start shows the server reason in a heading with a way back', 'This exam is not open for you right now.' in pp.inner_text('.empty-state h2') and 'Return to Dashboard' in pp.inner_text('.empty-state'))
    ok('player: no live-exam warning is armed when the exam never started', beforeunload(pp) is False)
    cx.close()

    cx, pp, errs = player('submit500'); pp.wait_for_selector('#q-0', timeout=10000)
    pp.evaluate("() => document.getElementById('submitExamBtn').click()"); pp.wait_for_selector('.ig-dialog'); pp.click('.ig-dialog [data-act=ok]')
    pp.wait_for_function("document.body.innerText.includes('The answers could not be recorded.')", timeout=8000)
    ok('submit failure: the server message is shown (without the "Error:" prefix) and nothing is lost', 'Error: The answers' not in pp.inner_text('body') and pp.evaluate("Object.keys(state.answers).length") >= 1)
    ok('submit failure: the button works again and reads "Submit Answers"', pp.is_enabled('#submitExamBtn') and 'Submit Answers' in pp.inner_text('#submitExamBtn'))
    pp.evaluate("() => document.getElementById('submitExamBtn').click()"); pp.wait_for_selector('.ig-dialog'); pp.click('.ig-dialog [data-act=ok]')
    pp.wait_for_function("document.querySelector('main').innerText.includes('Examination Submitted')", timeout=8000)
    ok('submit failure: a retry succeeds', len(state['player_submits']) == 2)
    cx.close(); state['player_mode'] = 'ok'

    # ------------------------------------------------------------------ AUTH PAGES: register, forgot and reset password
    print('\nRegister, Forgot Password and Reset Password (behaviour, real pages, mocked API)')
    def auth_page(name, mode='ok'):
        state['auth_mode'] = mode; state['auth_calls'].clear()
        cx = br.new_context(viewport={'width': 1280, 'height': 900}); cx.route('**/*', handler); cx.add_init_script(SHIM)
        errs = []; ap = new_page(cx, errs); ap.goto(BASE + '/' + name); return cx, ap, errs
    vis = lambda ap, sel: ap.evaluate("s => { const e = document.querySelector(s); return !!e && !e.classList.contains('hidden') && e.offsetParent !== null; }", sel)
    req = lambda ap, sel: ap.evaluate("s => document.querySelector(s).required", sel)

    cx, ap, errs = auth_page('register.html')
    ok('register: one H1 "Create Your Account"', ap.locator('h1').count() == 1 and ap.inner_text('h1') == 'Create Your Account')
    ok('register: role-specific fields are hidden until a role is chosen', not vis(ap, '#studentFields') and not vis(ap, '#teacherFields'))
    ap.select_option('#roleSelect', 'STUDENT')
    ok('register: STUDENT sees roll number and semester (required), not employee id', vis(ap, '#studentFields') and not vis(ap, '#teacherFields') and req(ap, '#regRoll') and req(ap, '#regSemester') and req(ap, '#regDept'))
    ap.select_option('#roleSelect', 'TEACHER')
    ok('register: TEACHER sees employee id and subjects (required id), not roll number', vis(ap, '#teacherFields') and not vis(ap, '#studentFields') and req(ap, '#regEmpId') and not req(ap, '#regRoll'))
    ap.select_option('#roleSelect', 'PRINCIPAL')
    ok('register: PRINCIPAL hides all role fields and department is not required', not vis(ap, '#dynamicFields') and not req(ap, '#regDept'))
    ok('register: password hint matches the backend rule (8 to 72)', 'Use 8 to 72 characters.' in ap.inner_text('#regPasswordHint') and ap.get_attribute('#regPassword', 'maxlength') == '72')
    ap.select_option('#roleSelect', 'STUDENT')
    for sel, val in (('#regName', 'Asha Rao'), ('#regEmail', 'asha@college.edu'), ('#regPassword', 'secret123'), ('#regCollege', 'City College'), ('#regDept', 'CSE'), ('#regRoll', 'R7')): ap.fill(sel, val)
    ap.select_option('#regSemester', index=1)
    ap.evaluate("() => document.getElementById('submitBtn').click()")
    ap.wait_for_function("!document.getElementById('successMsg').classList.contains('hidden')", timeout=8000)
    call = state['auth_calls'][0] if state['auth_calls'] else ('', {})
    ok('register: STUDENT payload keeps the existing contract', call[0] == '/api/auth/register' and set(call[1]) == {'name', 'username', 'password', 'collegeName', 'role', 'departmentName', 'rollNo', 'currentSemester'} and call[1]['role'] == 'STUDENT' and isinstance(call[1]['currentSemester'], int), str(call))
    ok('register: success message says the user is being sent to Sign In and the button stays locked', 'Redirecting to sign in...' in ap.inner_text('#successText') and ap.is_disabled('#submitBtn'))
    ap.wait_for_url('**/login.html', timeout=8000); ok('register: after success the user lands on the login page', True)
    cx.close()

    for mode, expect, label in (('error400', 'Email already registered.', 'server validation message'),
                                ('rate', 'Too many registration attempts. Please try again later.', 'rate-limit message'),
                                ('html500', 'status 500', 'non-JSON server error shows its status (not a fake connection failure)'),
                                ('abort', 'Could not reach the server.', 'network failure message without developer wording')):
        cx, ap, errs = auth_page('register.html', mode); ap.select_option('#roleSelect', 'PRINCIPAL')
        for sel, val in (('#regName', 'P'), ('#regEmail', 'p@c.edu'), ('#regPassword', 'secret123'), ('#regCollege', 'C')): ap.fill(sel, val)
        ap.evaluate("() => document.getElementById('submitBtn').click()")
        ap.wait_for_function("!document.getElementById('errorMsg').classList.contains('hidden')", timeout=8000)
        ok(f'register: {label}', expect in ap.inner_text('#errorText') and 'Spring Boot' not in ap.inner_text('#errorText'), ap.inner_text('#errorText'))
        ok(f'register ({mode}): the button is usable again and reads "Create Account"', ap.is_enabled('#submitBtn') and ap.inner_text('#submitBtn').strip() == 'Create Account')
        cx.close()

    cx, ap, errs = auth_page('forgot-password.html')
    ok('forgot: one H1 "Forgot Password?"', ap.inner_text('h1') == 'Forgot Password?' and ap.locator('h1').count() == 1)
    ap.evaluate("() => document.querySelector('#forgotForm button[type=submit]').click()")
    ok('forgot: an invalid email shows a field error, flags the field and sends nothing', ap.is_visible('#emailError') and ap.get_attribute('#email', 'aria-invalid') == 'true' and not state['auth_calls'])
    ap.fill('#email', 'asha@college.edu'); ap.evaluate("() => { const b = document.getElementById('submitBtn'); b.click(); b.click(); }")
    ap.wait_for_selector('#message:not([hidden])', timeout=6000)
    ok('forgot: a valid email sends exactly {email} once even on a double click', state['auth_calls'] == [('/api/auth/forgot-password', {'email': 'asha@college.edu'})], str(state['auth_calls']))
    ok('forgot: the success message is shown politely and the button is restored', 'Request accepted.' in ap.inner_text('#message') and 'auth-alert-success' in ap.get_attribute('#message', 'class') and ap.inner_text('#submitBtn').strip() == 'Send Reset Link')
    cx.close()
    cx, ap, errs = auth_page('forgot-password.html', 'abort'); ap.fill('#email', 'a@c.edu'); ap.evaluate("() => document.getElementById('submitBtn').click()")
    ap.wait_for_selector('#message:not([hidden])', timeout=6000)
    ok('forgot: a network failure is reported as an error alert', 'Could not reach the server' in ap.inner_text('#message') and 'auth-alert-error' in ap.get_attribute('#message', 'class'))
    cx.close()

    cx, ap, errs = auth_page('reset-password.html')
    ok('reset: without a token the page explains and locks the form', 'missing its token' in ap.inner_text('#message') and ap.is_disabled('#submitBtn'))
    cx.close()
    cx, ap, errs = auth_page('reset-password.html?token=abc123')
    ap.fill('#password', 'short'); ap.fill('#confirmPassword', 'short'); ap.evaluate("() => document.getElementById('submitBtn').click()")
    ok('reset: a too-short password is rejected on the field with no request', ap.is_visible('#passwordError') and not state['auth_calls'])
    ap.fill('#password', 'longenough1'); ap.fill('#confirmPassword', 'different22'); ap.evaluate("() => document.getElementById('submitBtn').click()")
    ok('reset: mismatched passwords are flagged on the confirmation field with no request', ap.is_visible('#confirmError') and ap.get_attribute('#confirmPassword', 'aria-invalid') == 'true' and not state['auth_calls'])
    ap.fill('#confirmPassword', 'longenough1'); ap.evaluate("() => document.getElementById('submitBtn').click()")
    ap.wait_for_function("document.getElementById('message').textContent.includes('sign in')", timeout=6000)
    ok('reset: success sends {token, password} and locks the form', state['auth_calls'] == [('/api/auth/reset-password', {'token': 'abc123', 'password': 'longenough1'})] and ap.evaluate("[...document.querySelectorAll('#resetForm input')].every(i => i.disabled)"), str(state['auth_calls']))
    ok('reset: no unexpected console/page errors', not [e for e in errs if not ENV_NOISE.search(e)], str([e for e in errs if not ENV_NOISE.search(e)][:3]))
    cx.close(); state['auth_mode'] = 'ok'
    br.close()

print(f'\n{passed + failed} checks run - ' + ('all passed' if not failed else f'{failed} FAILED'))
sys.exit(1 if failed else 0)
