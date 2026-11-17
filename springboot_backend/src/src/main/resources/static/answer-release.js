/* Teacher / HOD / principal control: release the correct answers to students, or hide them again.
   Talks to GET/PUT /api/exam/{id}/answer-release. The server enforces who may do this and that it can only happen
   after the exam window has closed; this panel just reflects what the server says and asks before changing anything. */
(function (root) {
    'use strict';
    function esc(s) { return root.IGUI && root.IGUI.esc ? root.IGUI.esc(s) : String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
    function when(iso) { var d = iso ? new Date(iso) : null; return d && !isNaN(d) ? d.toLocaleString() : ''; }

    /** Pure: what the panel should show for a status object from the server. */
    function describe(s) {
        if (!s) return { tone: 'error', text: 'The answer-release status could not be loaded.', action: null };
        if (s.released) {
            return { tone: 'success', text: 'Answers are visible to students who submitted this exam' + (s.releasedAt ? ' (released ' + when(s.releasedAt) + ')' : '') + '.',
                     action: s.canManage ? { release: false, label: 'Hide Answers', icon: 'fa-eye-slash' } : null };
        }
        if (!s.canRelease) {
            return { tone: 'info', text: 'Answers can be released after the exam closes' + (s.releasableFrom || s.examEndsAt ? ' (from ' + when(s.releasableFrom || s.examEndsAt) + ')' : '') + '.', action: null };
        }
        return { tone: 'neutral', text: 'Students can see their score but not the correct answers yet.',
                 action: s.canManage ? { release: true, label: 'Release Answers', icon: 'fa-unlock' } : null };
    }

    function render(box, status, onAct) {
        var d = describe(status);
        box.innerHTML = '<h2 class="font-bold text-slate-700 mb-2">Student Answer Review</h2>' +
            '<p class="ar-note ar-' + d.tone + '" role="status">' + esc(d.text) + '</p>' +
            (d.action ? '<button type="button" id="answerReleaseBtn" class="btn ' + (d.action.release ? 'btn-primary' : 'btn-secondary') + ' btn-sm w-full mt-3">' +
                '<i class="fa-solid ' + d.action.icon + '" aria-hidden="true"></i> ' + esc(d.action.label) + '</button>' : '');
        var b = box.querySelector('#answerReleaseBtn');
        if (b) b.addEventListener('click', function () { onAct(d.action.release, b); });
    }

    function request(examId, init) {
        return root.fetch('/api/exam/' + encodeURIComponent(examId) + '/answer-release', init).then(function (res) {
            return res.json().catch(function () { return null; }).then(function (body) {
                if (!res.ok) throw new Error((body && body.error) || 'The server could not complete the request.');
                return body;
            });
        });
    }

    /** Fills #answerReleaseBox for the given exam. */
    function mount(examId) {
        var box = root.document.getElementById('answerReleaseBox');
        if (!box) return Promise.resolve(null);
        box.innerHTML = '<p class="ar-note ar-neutral">Loading Answer Review Status...</p>';

        function show(status) {
            render(box, status, function (release, btn) {
                var ask = release
                    ? { title: 'Release Answers to Students?', message: 'Every student who submitted this exam will be able to see each question, the correct answer and the option they chose. They will be notified. You can hide the answers again later.', confirmText: 'Release Answers' }
                    : { title: 'Hide Answers From Students?', message: 'Students will no longer be able to open the answer review for this exam.', confirmText: 'Hide Answers', danger: true };
                root.IGUI.confirm(ask).then(function (yes) {
                    if (!yes) return;
                    btn.disabled = true;
                    request(examId, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ released: release }) })
                        .then(function (status) { show(status); root.IGUI.toast(release ? 'Answers released to students.' : 'Answers hidden from students.', 'success'); })
                        .catch(function (err) { btn.disabled = false; root.IGUI.toast(err.message, 'error'); });
                });
            });
        }
        return request(examId).then(function (status) { show(status); return status; })
            .catch(function (err) { box.innerHTML = '<h2 class="font-bold text-slate-700 mb-2">Student Answer Review</h2><p class="ar-note ar-error" role="alert">' + esc(err.message) + '</p>'; return null; });
    }

    root.IGAnswerRelease = { mount: mount, describe: describe };
    if (typeof module !== 'undefined' && module.exports) module.exports = { describe: describe };
})(typeof window !== 'undefined' ? window : globalThis);
