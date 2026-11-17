/* Shared exam-results table for the teacher and principal dashboards.
   Adds: response checking, friendly errors with Retry, search, sortable columns, scroll-safe layout, no NaN percentages.
   Pure helpers (normalize / filterRows / sortRows) are exported for Node tests. */
(function (root) {
    'use strict';
    var PASS_MARK = 40;
    var COLUMNS = ['roll', 'name', 'marks', 'pct'];

    function esc(s) {
        if (root.IGUI && root.IGUI.esc) return root.IGUI.esc(s);
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; });
    }
    function pick() {
        for (var i = 0; i < arguments.length; i++) { var v = arguments[i]; if (v != null && String(v).trim() !== '') return String(v).trim(); }
        return '';
    }

    /** One backend row -> display row. Percentage is null (shown as a dash) when the exam has no marks. */
    function normalize(r) {
        var s = (r && r.student) || {};
        var total = Number(r && r.totalMarks) || 0, marks = Number(r && r.marksObtained) || 0;
        return {
            roll: pick(r && r.rollNo, s.rollNo, s.prnNo),
            name: pick(r && r.studentName, s.name) || 'Unknown Student',
            marks: marks, total: total,
            pct: total > 0 ? Math.round(marks / total * 100) : null
        };
    }

    function filterRows(rows, query) {
        var q = String(query || '').trim().toLowerCase();
        if (!q) return rows.slice();
        return rows.filter(function (r) { return (r.name + ' ' + r.roll).toLowerCase().indexOf(q) >= 0; });
    }

    /** Stable sort. Numbers (marks, pct) put missing values last in either direction; text sorts naturally (R2 before R10). */
    function sortRows(rows, key, dir) {
        var sign = dir === 'desc' ? -1 : 1;
        return rows.map(function (r, i) { return { r: r, i: i }; }).sort(function (a, b) {
            var x = a.r[key], y = b.r[key], c;
            if (key === 'marks' || key === 'pct') {
                if (x == null && y == null) c = 0; else if (x == null) return 1; else if (y == null) return -1; else c = (x - y) * sign;
            } else {
                c = String(x).localeCompare(String(y), undefined, { numeric: true, sensitivity: 'base' }) * sign;
            }
            return c || a.i - b.i;
        }).map(function (o) { return o.r; });
    }

    function rowHtml(r) {
        var pass = r.pct != null && r.pct >= PASS_MARK;
        var pctCell = r.pct == null ? '<span style="color:var(--muted)">&ndash;</span>' : esc(r.pct) + '%';
        return '<tr class="border-b border-slate-100 hover:bg-slate-50">' +
            '<td class="p-3 font-mono text-sm text-slate-500">' + (r.roll ? esc(r.roll) : 'N/A') + '</td>' +
            '<td class="p-3 font-medium text-slate-700">' + esc(r.name) + '</td>' +
            '<td class="p-3 text-right font-bold text-brand-600">' + esc(r.marks) + '/' + esc(r.total) + '</td>' +
            '<td class="p-3 text-right font-bold ' + (r.pct == null ? '' : (pass ? 'text-green-600' : 'text-red-600')) + '">' + pctCell + '</td></tr>';
    }

    function messageRow(html) { return '<tr><td colspan="4" class="text-center py-8 text-sm text-slate-500">' + html + '</td></tr>'; }

    /** GET json; throws an Error whose message is safe to show to the user. */
    function getJson(url) {
        return root.fetch(url).then(function (res) {
            return res.json().catch(function () { return null; }).then(function (body) {
                if (!res.ok) throw new Error((body && body.error) || (res.status === 403 ? 'You do not have access to this exam.' : 'The server could not complete the request.'));
                return body;
            });
        });
    }

    /* Loads results for an exam into #resTableBody. Resolves with the raw backend rows, or null if loading failed. */
    function load(examId) {
        var doc = root.document, tbody = doc.getElementById('resTableBody');
        var table = tbody.closest('table'), wrap = table.parentElement;
        wrap.classList.add('overflow-x-auto');
        var old = doc.getElementById('resToolbar'); if (old) old.remove();

        var state = { rows: [], q: '', key: null, dir: 'asc' };
        var bar = doc.createElement('div');
        bar.id = 'resToolbar'; bar.className = 'flex flex-wrap items-center gap-3 px-3 pt-2 pb-3';
        bar.innerHTML = '<input type="search" id="resSearchBox" class="field-input" style="max-width:18rem" placeholder="Search by name or roll number" aria-label="Search students by name or roll number">' +
            '<span id="resCount" class="text-xs" style="color:var(--muted)" role="status" aria-live="polite"></span>';
        wrap.insertAdjacentElement('beforebegin', bar);
        var search = bar.querySelector('input'), count = bar.querySelector('#resCount');
        bar.hidden = true;

        // Column headers become real buttons so they can be sorted by keyboard.
        var ths = table.querySelectorAll('thead th');
        COLUMNS.forEach(function (key, i) {
            var th = ths[i]; if (!th) return;
            var label = th.getAttribute('data-label') || th.textContent.trim();
            th.setAttribute('data-label', label);
            th.innerHTML = '<button type="button" class="th-sort" data-key="' + key + '">' + esc(label) + '<i class="fa-solid fa-sort" aria-hidden="true"></i></button>';
            th.setAttribute('aria-sort', 'none');
        });
        function paintHeaders() {
            COLUMNS.forEach(function (key, i) {
                var th = ths[i]; if (!th) return;
                var on = state.key === key;
                th.setAttribute('aria-sort', on ? (state.dir === 'asc' ? 'ascending' : 'descending') : 'none');
                th.querySelector('i').className = 'fa-solid ' + (on ? (state.dir === 'asc' ? 'fa-sort-up' : 'fa-sort-down') : 'fa-sort');
            });
        }
        function paint() {
            var shown = filterRows(state.rows, state.q);
            if (state.key) shown = sortRows(shown, state.key, state.dir);
            tbody.innerHTML = shown.length ? shown.map(rowHtml).join('')
                : messageRow(state.rows.length ? 'No students match your search.' : 'No submissions have been recorded for this exam yet.');
            count.textContent = state.rows.length ? ('Showing ' + shown.length + ' of ' + state.rows.length + (state.rows.length === 1 ? ' student' : ' students')) : '';
            paintHeaders();
        }
        search.addEventListener('input', function () { state.q = search.value; paint(); });
        table.querySelectorAll('.th-sort').forEach(function (b) {
            b.addEventListener('click', function () {
                var k = b.getAttribute('data-key');
                if (state.key === k) state.dir = state.dir === 'asc' ? 'desc' : 'asc'; else { state.key = k; state.dir = (k === 'marks' || k === 'pct') ? 'desc' : 'asc'; }
                paint();
            });
        });

        tbody.innerHTML = messageRow('<i class="fa-solid fa-spinner fa-spin mr-2" aria-hidden="true"></i>Loading Results...');
        return getJson('/api/exam/results/exam/' + encodeURIComponent(examId)).then(function (data) {
            if (!Array.isArray(data)) throw new Error('The server returned an unexpected response.');
            state.rows = data.map(normalize);
            bar.hidden = state.rows.length === 0;
            paint();
            return data;
        }).catch(function (err) {
            bar.hidden = true;
            tbody.innerHTML = messageRow('<span class="text-red-600 font-semibold">The results could not be loaded.</span><br>' + esc(err.message) +
                '<br><button type="button" class="btn btn-secondary btn-sm mt-3" data-retry>Try Again</button>');
            var retry = tbody.querySelector('[data-retry]');
            if (retry) retry.addEventListener('click', function () { load(examId); });
            return null;
        });
    }

    root.IGResults = { load: load, getJson: getJson };
    if (typeof module !== 'undefined' && module.exports) module.exports = { normalize: normalize, filterRows: filterRows, sortRows: sortRows, rowHtml: rowHtml, PASS_MARK: PASS_MARK };
})(typeof window !== 'undefined' ? window : globalThis);
