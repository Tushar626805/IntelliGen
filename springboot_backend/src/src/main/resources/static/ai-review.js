/*
 * IntelliGen - AI question generation + review module (shared by the Teacher, HOD and Principal pages).
 *
 *  - Generation runs as a background job with REAL progress (upload %, section x of y, questions accepted) and Cancel.
 *  - Nothing is guessed: if the AI's answer cannot be matched to an option, the teacher must choose it.
 *  - Every question is editable (text, options, correct answer, marks); sources and warnings are shown.
 *  - Drafts: save without publishing, reopen, edit, publish later. Students never see drafts.
 *  - AI output is NEVER presented as verified. The automatic checks are text-overlap heuristics only.
 *
 * Needs: api.js (IGApi), ui.js (IGUI) and the standard element ids used by the three dashboards
 * (pdfFile, numQuestions, generateBtn, loadingIndicator, resultsContainer, mcqList, qCountBadge, saveBtn, man*).
 */
(function (root) {
    'use strict';

    var MAX_FILE_BYTES = 20 * 1024 * 1024;
    var MAX_QUESTIONS = 30;
    var POLL_MS = 1500;
    var SLOW_AFTER_MS = 3 * 60 * 1000;   // no visible progress for this long -> tell the teacher it is slow

    // =====================================================================
    // Pure helpers (unit-tested in docs/verification/test_ai_review.js)
    // =====================================================================
    /** "medium" -> "Medium" (the AI service returns lowercase difficulty names). */
    function cap(s) { s = String(s == null ? '' : s).trim(); return s ? s.charAt(0).toUpperCase() + s.slice(1) : ''; }
    /** True when nothing has visibly changed for at least thresholdMs. */
    function stalled(lastChangeMs, nowMs, thresholdMs) { return nowMs - lastChangeMs >= thresholdMs; }

    function clean(s) { return String(s == null ? '' : s).trim(); }

    function indexOfAnswer(options, answer) {
        var target = clean(answer).toLowerCase();
        if (!target) return -1;
        for (var i = 0; i < options.length; i++) {
            if (clean(options[i]).toLowerCase() === target) return i;
        }
        return -1;   // never guess: the teacher has to choose
    }

    /** One question as returned by the Flask pipeline. */
    function fromAi(item) {
        var options = Array.isArray(item.options) ? item.options.map(clean) : [];
        return {
            questionText: clean(item.question || item.questionText),
            options: options,
            correctIndex: indexOfAnswer(options, item.answer != null ? item.answer : item.correctAnswer),
            marks: 1,
            aiGenerated: true,
            sourcePage: item.source_page == null ? null : Number(item.source_page),
            sourceExcerpt: clean(item.source_excerpt),
            support: item.support || '',
            warnings: Array.isArray(item.warnings) ? item.warnings.slice() : [],
            difficulty: item.difficulty || '',
            reviewed: false
        };
    }

    /** One question as stored by the backend (when reopening a draft). */
    function fromSaved(q) {
        var options = Array.isArray(q.options) ? q.options.map(clean) : [];
        return {
            questionText: clean(q.questionText),
            options: options,
            correctIndex: indexOfAnswer(options, q.correctAnswer),
            marks: q.marks > 0 ? q.marks : 1,
            aiGenerated: !!q.aiGenerated,
            sourcePage: q.sourcePage == null ? null : Number(q.sourcePage),
            sourceExcerpt: clean(q.sourceExcerpt),
            support: '',
            warnings: [],
            difficulty: '',
            reviewed: true     // it was saved by a teacher, so it already went through review
        };
    }

    /** Returns a list of human-readable problems (empty list = valid). */
    function validate(list) {
        var problems = [];
        list.forEach(function (q, i) {
            var label = 'Question ' + (i + 1) + ': ';
            if (!clean(q.questionText)) problems.push({ index: i, message: label + 'write the question text.' });
            var opts = q.options || [];
            if (opts.length < 2 || opts.some(function (o) { return !clean(o); })) {
                problems.push({ index: i, message: label + 'fill in every option.' });
            } else {
                var seen = {};
                var dup = opts.some(function (o) { var k = clean(o).toLowerCase(); if (seen[k]) return true; seen[k] = true; return false; });
                if (dup) problems.push({ index: i, message: label + 'two options are identical.' });
                if (opts.some(function (o) { return clean(o).length > 255; })) problems.push({ index: i, message: label + 'an option is longer than 255 characters.' });
            }
            if (!(q.correctIndex >= 0 && q.correctIndex < opts.length)) problems.push({ index: i, message: label + 'choose the correct answer.' });
            if (!(parseInt(q.marks, 10) >= 1)) problems.push({ index: i, message: label + 'marks must be at least 1.' });
        });
        return problems;
    }

    function needsAttention(q) {
        if (!(q.correctIndex >= 0)) return true;
        return !q.reviewed && ((q.warnings && q.warnings.length > 0) || q.support === 'weak');
    }

    function flaggedCount(list) { return list.filter(needsAttention).length; }

    /** Shape expected by the save endpoints. Throws if anything is invalid. */
    function toPayload(list) {
        var problems = validate(list);
        if (problems.length) {
            var err = new Error(problems[0].message + (problems.length > 1 ? ' (and ' + (problems.length - 1) + ' more to fix)' : ''));
            err.problemIndex = problems[0].index;
            throw err;
        }
        return list.map(function (q) {
            var payload = {
                questionText: clean(q.questionText),
                options: q.options.map(clean),
                correctAnswer: clean(q.options[q.correctIndex]),
                marks: parseInt(q.marks, 10) || 1,
                aiGenerated: !!q.aiGenerated
            };
            if (q.aiGenerated) {
                payload.sourcePage = q.sourcePage == null ? null : q.sourcePage;
                payload.sourceExcerpt = q.sourceExcerpt || null;
            }
            return payload;
        });
    }

    function checkFile(file) {
        if (!file) return 'Choose a PDF file first.';
        if (!/\.pdf$/i.test(file.name || '')) return 'Only PDF files are supported.';
        if (file.type && file.type !== 'application/pdf' && file.type !== 'application/octet-stream') return 'This does not look like a PDF file.';
        if (file.size === 0) return 'This file is empty.';
        if (file.size > MAX_FILE_BYTES) return 'This file is larger than 20 MB. Please upload a smaller PDF.';
        return null;
    }

    function errorText(res, fallback) {
        if (res && res.json && res.json.error) return res.json.error;
        if (res && res.text && res.text.length < 300 && !/^\s*</.test(res.text)) return res.text.replace(/^Error:\s*/, '');
        return fallback || ('The server answered with an error (HTTP ' + (res && res.status) + ').');
    }

    /** A question-bank entry becomes an editor question (already vetted by a teacher once, so it counts as reviewed). */
    function bankToQuestion(item) { return fromSaved(item); }

    /** Drops bank entries whose text is already in the exam being edited. */
    function newOnly(existing, incoming) {
        var seen = {};
        existing.forEach(function (q) { seen[clean(q.questionText).toLowerCase()] = true; });
        return incoming.filter(function (it) {
            var k = clean(it.questionText).toLowerCase();
            if (!k || seen[k]) return false;
            seen[k] = true;
            return true;
        });
    }

    var pure = {
        cap: cap, stalled: stalled,
        bankToQuestion: bankToQuestion, newOnly: newOnly,
        indexOfAnswer: indexOfAnswer, fromAi: fromAi, fromSaved: fromSaved, validate: validate, needsAttention: needsAttention,
        flaggedCount: flaggedCount, toPayload: toPayload, checkFile: checkFile, errorText: errorText
    };

    // =====================================================================
    // Browser part
    // =====================================================================
    var hasDom = typeof document !== 'undefined';
    var state = { questions: [], rejected: [], meta: null };
    var config = {};
    var mounted = false;
    var run = null;            // the generation in progress: { cancelled, controller, jobId, timer, started }
    var lastRemoved = null;

    function $(id) { return document.getElementById(id); }
    function esc(s) {
        if (root.IGUI && root.IGUI.esc) return root.IGUI.esc(s);
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; });
    }
    function toast(msg, type) { if (root.IGUI && root.IGUI.toast) root.IGUI.toast(msg, type || 'info'); }
    function mmss(seconds) { var m = Math.floor(seconds / 60), s = seconds % 60; return m + ':' + (s < 10 ? '0' : '') + s; }

    // ---------------------------------------------------------------- mounting (adds the new controls to the existing modal)
    function mount(options) {
        config = options || {};
        if (!hasDom || mounted) return;
        mounted = true;

        var qty = $('numQuestions');
        if (qty && !$('rvDifficulty')) {
            qty.min = 1; qty.max = MAX_QUESTIONS;
            if (!(qty.labels && qty.labels.length)) qty.setAttribute('aria-label', 'Number of questions');
            var sel = document.createElement('select');
            sel.id = 'rvDifficulty';
            sel.className = 'rv-select';
            sel.setAttribute('aria-label', 'Question difficulty');
            sel.title = 'Guides the AI. It does not guarantee the difficulty.';
            sel.innerHTML = '<option value="easy">Easy</option><option value="medium" selected>Medium</option><option value="hard">Hard</option>';
            if (qty.parentElement && qty.parentElement.classList.contains('rv-qty')) {
                var wrap = document.createElement('div');
                var lab = document.createElement('label');
                lab.className = 'field-label'; lab.htmlFor = 'rvDifficulty'; lab.textContent = 'Difficulty';
                wrap.appendChild(lab); wrap.appendChild(sel);
                qty.parentElement.insertAdjacentElement('afterend', wrap);
            } else {
                qty.insertAdjacentElement('afterend', sel);
            }
        }

        wireUpload();

        // Generated questions take minutes of local-model time to produce: warn before a refresh, tab close or navigation throws them away.
        if (root.addEventListener) {
            root.addEventListener('beforeunload', function (e) {
                if (dirty && state.questions.length > 0) { e.preventDefault(); e.returnValue = ''; }
            });
        }

        var save = $('saveBtn');
        if (save && !$('rvDraftBtn')) {
            var hint = document.createElement('p');
            hint.className = 'rv-hint';
            hint.innerHTML = '<i class="fa-solid fa-circle-info"></i> Publishing makes the exam visible to students straight away. Save a draft to keep working on it.';
            save.insertAdjacentElement('beforebegin', hint);
            var draft = document.createElement('button');
            draft.type = 'button';
            draft.id = 'rvDraftBtn';
            draft.className = 'btn btn-secondary w-full mt-3';
            draft.innerHTML = '<i class="fa-regular fa-floppy-disk"></i> Save as Draft (Not Visible to Students)';
            draft.addEventListener('click', function () { if (config.onSaveDraft) config.onSaveDraft(); });
            save.insertAdjacentElement('afterend', draft);
        }
        var gen = $('generateBtn');
        if (gen && !$('rvBankBtn')) {
            var bank = document.createElement('button');
            bank.type = 'button';
            bank.id = 'rvBankBtn';
            bank.className = 'btn btn-secondary btn-sm mt-3';
            bank.innerHTML = '<i class="fa-solid fa-book-bookmark"></i> Add from Question Bank';
            bank.addEventListener('click', function () { openBank(bank); });
            var host = gen.parentElement;
            if (host && host.parentElement) host.insertAdjacentElement('afterend', bank);
        }

        var list = $('mcqList');
        if (list && !$('rvSummary')) {
            var summary = document.createElement('div');
            summary.id = 'rvSummary';
            list.insertAdjacentElement('beforebegin', summary);
            list.addEventListener('input', onInput);
            list.addEventListener('change', onChange);
            list.addEventListener('click', onClick);
            list.classList.add('rv-list');
        }
        render();
    }

    function wireUpload() {
        var input = $('pdfFile');
        if (!input) return;
        var area = input.parentElement;
        if (area) {
            area.setAttribute('tabindex', '0');
            area.setAttribute('role', 'button');
            area.setAttribute('aria-label', 'Upload a PDF: click, press Enter, or drop a file here');
            if ($('uploadHelp')) area.setAttribute('aria-describedby', 'uploadHelp');
            area.addEventListener('keydown', function (e) { if (e.target === area && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); input.click(); } });
            ['dragenter', 'dragover'].forEach(function (ev) {
                area.addEventListener(ev, function (e) { e.preventDefault(); area.classList.add('rv-drag'); });
            });
            ['dragleave', 'drop'].forEach(function (ev) {
                area.addEventListener(ev, function (e) { e.preventDefault(); area.classList.remove('rv-drag'); });
            });
            area.addEventListener('drop', function (e) {
                var files = e.dataTransfer && e.dataTransfer.files;
                if (!files || !files.length) return;
                var problem = files.length > 1 ? 'Please drop one PDF at a time.' : checkFile(files[0]);
                if (problem) { toast(problem, 'error'); return; }
                try {
                    var dt = new DataTransfer();
                    dt.items.add(files[0]);
                    input.files = dt.files;
                    if (typeof root.updateFileName === 'function') root.updateFileName(input);
                } catch (err) { toast('Your browser could not accept the dropped file. Please click to choose it instead.', 'error'); }
            });
        }
        input.addEventListener('change', function () {
            var file = input.files && input.files[0];
            var problem = file ? checkFile(file) : null;
            if (problem) {
                toast(problem, 'error');
                input.value = '';
                var shown = $('fileNameDisplay');
                if (shown) shown.classList.add('hidden');
            }
        });
    }

    // ---------------------------------------------------------------- state changes
    var dirty = false;      // true while there are AI-generated or edited questions that have not been saved or published

    function reset() {
        if (run) cancelGeneration(true);
        dirty = false;
        state = { questions: [], rejected: [], meta: null };
        lastRemoved = null;
        hideProgress();
        if (hasDom) render();
    }

    function loadQuestions(saved) {
        dirty = false;
        state = { questions: (saved || []).map(fromSaved), rejected: [], meta: null };
        render();
    }

    function count() { return state.questions.length; }
    function flagged() { return flaggedCount(state.questions); }

    function exportQuestions() {
        try {
            return toPayload(state.questions);
        } catch (err) {
            if (hasDom && err.problemIndex != null) focusCard(err.problemIndex);
            throw err;
        }
    }

    /** Asks the teacher to confirm publishing; mentions AI questions that still carry warnings. */
    function confirmPublish(titleText) {
        var aiCount = state.questions.filter(function (q) { return q.aiGenerated; }).length;
        var msg = 'Students will be able to see "' + (titleText || 'this exam') + '" as soon as it is published. ';
        if (aiCount > 0) msg += aiCount + ' question' + (aiCount === 1 ? ' was' : 's were') + ' drafted by AI. The automatic checks do not prove an answer is correct, so make sure you have read each one. ';
        var f = flagged();
        if (f > 0) msg += f + ' question' + (f === 1 ? ' still has' : 's still have') + ' warnings you have not marked as reviewed.';
        if (root.IGUI && root.IGUI.confirm) {
            return root.IGUI.confirm({ title: 'Publish This Exam?', message: msg.trim(), confirmText: 'Publish Exam', cancelText: 'Keep Reviewing', icon: 'fa-paper-plane' });
        }
        return Promise.resolve(root.confirm(msg));
    }

    function addManual() {
        var text = clean(($('manQText') || {}).value);
        var opts = ['manOptA', 'manOptB', 'manOptC', 'manOptD'].map(function (id) { return clean(($(id) || {}).value); });
        var ref = clean(($('manCorrect') || {}).value);
        var marks = parseInt(($('manMarks') || {}).value, 10) || 1;
        if (!text || opts.some(function (o) { return !o; }) || !ref) {
            toast('Fill in the question, all 4 options and choose the correct answer.', 'error');
            return false;
        }
        var q = { questionText: text, options: opts, correctIndex: 'ABCD'.indexOf(ref), marks: marks, aiGenerated: false,
                  sourcePage: null, sourceExcerpt: '', support: '', warnings: [], difficulty: '', reviewed: true };
        var problems = validate([q]);
        if (problems.length) { toast(problems[0].message.replace('Question 1: ', ''), 'error'); return false; }
        state.questions.push(q);
        dirty = true;
        ['manQText', 'manOptA', 'manOptB', 'manOptC', 'manOptD', 'manCorrect'].forEach(function (id) { if ($(id)) $(id).value = ''; });
        if ($('manMarks')) $('manMarks').value = '1';
        render();
        toast('Question added.', 'success');
        return true;
    }

    function removeAt(i) {
        lastRemoved = { question: state.questions[i], index: i };
        dirty = true;
        state.questions.splice(i, 1);
        render();
        var bar = $('rvSummary');
        if (bar) {
            var undo = document.createElement('div');
            undo.className = 'rv-undo';
            undo.setAttribute('role', 'status');
            undo.textContent = 'Question removed. ';
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'rv-link';
            btn.textContent = 'Undo';
            btn.addEventListener('click', function () {
                if (lastRemoved) { state.questions.splice(lastRemoved.index, 0, lastRemoved.question); lastRemoved = null; dirty = true; render(); }
            });
            undo.appendChild(btn);
            bar.appendChild(undo);
            setTimeout(function () { if (undo.parentNode) undo.parentNode.removeChild(undo); }, 8000);
        }
    }

    // ---------------------------------------------------------------- rendering
    function supportBadge(q) {
        if (q.support === 'good') return '<span class="rv-badge rv-good" title="Automatic text-overlap check: the question and answer closely match a passage of your upload. This is not proof the answer is correct.">Text Match: Strong</span>';
        if (q.support === 'weak') return '<span class="rv-badge rv-warn" title="The question only partly matches your upload. Read the source excerpt.">Text Match: Partial</span>';
        return '';
    }

    function cardHtml(q, i) {
        var attention = needsAttention(q);
        var opts = (q.options || []).map(function (o, j) {
            return '<label class="rv-opt' + (q.correctIndex === j ? ' rv-correct' : '') + '">' +
                '<input type="radio" name="rv-correct-' + i + '" data-i="' + i + '" data-f="correct" data-o="' + j + '"' + (q.correctIndex === j ? ' checked' : '') +
                ' aria-label="Mark option ' + 'ABCDEF'.charAt(j) + ' as the correct answer">' +
                '<span class="rv-key">' + 'ABCDEF'.charAt(j) + '</span>' +
                '<input type="text" class="rv-opt-input" data-i="' + i + '" data-f="opt" data-o="' + j + '" value="' + esc(o) + '" maxlength="255" aria-label="Option ' + 'ABCDEF'.charAt(j) + '">' +
                '</label>';
        }).join('');

        var notes = '';
        if (!(q.correctIndex >= 0)) {
            notes += '<p class="rv-note rv-bad"><i class="fa-solid fa-circle-exclamation"></i> The correct answer could not be determined. Select it with the radio button.</p>';
        }
        (q.warnings || []).forEach(function (w) { notes += '<p class="rv-note rv-amber"><i class="fa-solid fa-triangle-exclamation"></i> ' + esc(w) + '</p>'; });

        var source = '';
        if (q.sourceExcerpt) {
            source = '<details class="rv-source"><summary>Source' + (q.sourcePage ? ' (Page ' + esc(q.sourcePage) + ')' : '') + '</summary><blockquote>' + esc(q.sourceExcerpt) + '</blockquote></details>';
        }
        var reviewBox = ((q.warnings && q.warnings.length) || q.support === 'weak')
            ? '<label class="rv-reviewed"><input type="checkbox" data-i="' + i + '" data-f="reviewed"' + (q.reviewed ? ' checked' : '') + '> I have checked this question</label>' : '';

        return '<div class="rv-card' + (attention ? ' rv-attn' : '') + '" id="rv-card-' + i + '" data-card="' + i + '">' +
            '<div class="rv-head"><span class="rv-num">Q' + (i + 1) + '</span>' +
            (q.aiGenerated ? '<span class="rv-badge rv-ai">AI-Drafted</span>' : '<span class="rv-badge rv-manual">Written by You</span>') +
            supportBadge(q) +
            (q.difficulty ? '<span class="rv-badge rv-plain">' + esc(cap(q.difficulty)) + '</span>' : '') +
            '<button type="button" class="rv-del" data-act="del" data-i="' + i + '" aria-label="Delete question ' + (i + 1) + '" title="Delete question"><i class="fa-solid fa-trash"></i></button></div>' +
            '<textarea class="rv-text" rows="2" data-i="' + i + '" data-f="text" aria-label="Question ' + (i + 1) + ' text">' + esc(q.questionText) + '</textarea>' +
            '<div class="rv-opts" role="radiogroup" aria-label="Options for question ' + (i + 1) + '">' + opts + '</div>' +
            notes + source +
            '<div class="rv-foot">' + reviewBox +
            '<label class="rv-marks">Marks <input type="number" min="1" max="100" value="' + esc(q.marks) + '" data-i="' + i + '" data-f="marks" aria-label="Marks for question ' + (i + 1) + '"></label></div>' +
            '</div>';
    }

    function summaryHtml() {
        var n = state.questions.length;
        var ai = state.questions.filter(function (q) { return q.aiGenerated; }).length;
        var f = flagged();
        var html = '';
        if (ai > 0) {
            html += '<div class="rv-banner"><i class="fa-solid fa-robot"></i><div><strong>' + ai + ' question' + (ai === 1 ? '' : 's') + ' drafted by AI.</strong> ' +
                'Automatic checks confirm the answer matches an option and appears in your upload. They do <strong>not</strong> verify that a question is correct. ' +
                'Read every question before publishing.</div></div>';
        }
        if (f > 0) html += '<div class="rv-banner rv-banner-amber"><i class="fa-solid fa-flag"></i><div><strong>' + f + ' question' + (f === 1 ? ' needs' : 's need') + ' your attention</strong> (highlighted below).</div></div>';
        var meta = state.meta;
        if (meta) {
            if (meta.shortfall > 0) html += '<div class="rv-banner rv-banner-amber"><i class="fa-solid fa-circle-info"></i><div>Only <strong>' + meta.generated + ' of ' + meta.requested + '</strong> questions passed the automatic checks. ' +
                'Try a PDF with more detailed text, or ask for fewer questions.</div></div>';
            if (meta.truncated) html += '<div class="rv-banner rv-banner-amber"><i class="fa-solid fa-circle-info"></i><div>Your document is long, so questions were drawn from ' + meta.sections_used + ' evenly spaced sections out of ' + meta.sections_total + '.</div></div>';
            (meta.notes || []).forEach(function (t) { html += '<div class="rv-banner rv-banner-amber"><i class="fa-solid fa-circle-info"></i><div>' + esc(t) + '</div></div>'; });
        }
        if (state.rejected.length) {
            html += '<details class="rv-rejected"><summary>' + state.rejected.length + ' draft' + (state.rejected.length === 1 ? ' was' : 's were') + ' discarded automatically</summary><ul>' +
                state.rejected.map(function (r) { return '<li><span class="rv-rej-q">' + esc(r.question) + '</span> <span class="rv-rej-why">' + esc(r.reason) + '</span></li>'; }).join('') + '</ul></details>';
        }
        return html;
    }

    function render() {
        if (!hasDom) return;
        var container = $('resultsContainer'), list = $('mcqList');
        if (!container || !list) return;
        var n = state.questions.length;
        if (n === 0 && !state.rejected.length && !state.meta) {
            container.classList.add('hidden');
            list.innerHTML = '';
            var s0 = $('rvSummary'); if (s0) s0.innerHTML = '';
            return;
        }
        container.classList.remove('hidden');
        var badge = $('qCountBadge');
        if (badge) badge.innerText = n + (n === 1 ? ' Question' : ' Questions');
        var summary = $('rvSummary');
        if (summary) summary.innerHTML = summaryHtml();
        list.innerHTML = n === 0
            ? '<div class="rv-empty">No questions yet. Generate them from a PDF or add one manually.</div>'
            : state.questions.map(cardHtml).join('');
    }

    function refreshAttention() {
        var summary = $('rvSummary');
        if (summary) summary.innerHTML = summaryHtml();
        state.questions.forEach(function (q, i) {
            var card = $('rv-card-' + i);
            if (card) card.classList.toggle('rv-attn', needsAttention(q));
        });
    }

    function focusCard(i) {
        var card = $('rv-card-' + i);
        if (!card) return;
        card.scrollIntoView({ behavior: 'smooth', block: 'center' });
        card.classList.add('rv-flash');
        setTimeout(function () { card.classList.remove('rv-flash'); }, 1800);
        var field = card.querySelector('textarea, input[type="text"]');
        if (field) field.focus({ preventScroll: true });
    }

    // ---------------------------------------------------------------- list events
    function indexOf(el) { return parseInt(el.getAttribute('data-i'), 10); }

    function onInput(e) {
        var t = e.target, f = t.getAttribute('data-f');
        if (!f) return;
        var q = state.questions[indexOf(t)];
        if (!q) return;
        dirty = true;
        if (f === 'text') q.questionText = t.value;
        else if (f === 'opt') q.options[parseInt(t.getAttribute('data-o'), 10)] = t.value;
    }

    function onChange(e) {
        var t = e.target, f = t.getAttribute('data-f');
        if (!f) return;
        var i = indexOf(t), q = state.questions[i];
        if (!q) return;
        dirty = true;
        if (f === 'correct') {
            q.correctIndex = parseInt(t.getAttribute('data-o'), 10);
            var card = $('rv-card-' + i);
            if (card) {
                card.querySelectorAll('.rv-opt').forEach(function (label, j) { label.classList.toggle('rv-correct', j === q.correctIndex); });
                var bad = card.querySelector('.rv-bad');
                if (bad) bad.parentNode.removeChild(bad);
            }
        } else if (f === 'marks') {
            q.marks = Math.max(1, parseInt(t.value, 10) || 1);
            t.value = q.marks;
        } else if (f === 'reviewed') {
            q.reviewed = t.checked;
        }
        refreshAttention();
    }

    function onClick(e) {
        var btn = e.target.closest ? e.target.closest('[data-act]') : null;
        if (btn && btn.getAttribute('data-act') === 'del') removeAt(indexOf(btn));
    }

    // ---------------------------------------------------------------- generation (job + progress + cancel)
    function progressBox() { return $('loadingIndicator'); }

    function showProgress() {
        var box = progressBox();
        if (!box) return;
        box.classList.remove('hidden');
        box.innerHTML =
            '<div class="rv-progress" role="status" aria-live="polite">' +
            '<div class="rv-prog-top"><span id="rvStage" class="rv-stage">Starting...</span><span id="rvElapsed" class="rv-elapsed">0:00</span></div>' +
            '<div class="rv-bar" role="progressbar" aria-label="Question generation progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0" id="rvBar"><span id="rvBarFill"></span></div>' +
            '<p id="rvSlow" class="rv-detail rv-slow" role="status" hidden>This is taking longer than usual. The AI model may be busy with another request. You can keep waiting, or cancel and try again with fewer questions.</p>' +
            '<p id="rvDetail" class="rv-detail">A local AI model writes the questions, so this can take a few minutes for longer documents. You can cancel at any time.</p>' +
            '<button type="button" class="btn btn-secondary btn-sm" id="rvCancel"><i class="fa-solid fa-xmark"></i> Cancel</button>' +
            '</div>';
        $('rvCancel').addEventListener('click', function () { cancelGeneration(false); });
    }

    function setProgress(stage, fraction, detail) {
        var sig = stage + '|' + Math.round(fraction * 100) + '|' + detail;
        if (run && run.sig !== sig) { run.sig = sig; run.lastChange = Date.now(); var slow = $('rvSlow'); if (slow) slow.hidden = true; }
        var s = $('rvStage'); if (s) s.textContent = stage;
        var fill = $('rvBarFill'), bar = $('rvBar');
        var pct = Math.max(0, Math.min(100, Math.round(fraction * 100)));
        if (fill) fill.style.width = pct + '%';
        if (bar) bar.setAttribute('aria-valuenow', String(pct));
        if (detail != null) { var d = $('rvDetail'); if (d) d.textContent = detail; }
    }

    function hideProgress() {
        if (!hasDom) return;
        var box = progressBox();
        if (box) { box.classList.add('hidden'); box.innerHTML = ''; }
    }

    function setBusy(busy) {
        ['generateBtn', 'saveBtn', 'rvDraftBtn'].forEach(function (id) { var b = $(id); if (b) b.disabled = busy; });
        var g = $('generateBtn');
        if (g) g.innerHTML = busy ? '<i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i> Generating...' : 'Generate Questions';
    }

    function showFailure(message) {
        var box = progressBox();
        if (!box) return;
        box.classList.remove('hidden');
        box.innerHTML = '<div class="rv-progress rv-failed" role="alert"><p class="rv-fail-title"><i class="fa-solid fa-circle-xmark"></i> Could Not Generate Questions</p>' +
            '<p class="rv-detail">' + esc(message) + '</p>' +
            '<button type="button" class="btn btn-secondary btn-sm" id="rvRetry"><i class="fa-solid fa-rotate-right"></i> Try Again</button></div>';
        $('rvRetry').addEventListener('click', function () { generate(); });
    }

    function cancelGeneration(silent) {
        if (!run) return;
        var r = run;
        r.cancelled = true;
        clearInterval(r.timer);
        if (r.controller) { try { r.controller.abort(); } catch (e) { /* already finished */ } }
        if (r.jobId) { fetch('/api/ai/jobs/' + encodeURIComponent(r.jobId), { method: 'DELETE' }).catch(function () { /* best effort */ }); }
        run = null;
        setBusy(false);
        hideProgress();
        if (!silent) toast('Generation cancelled.', 'info');
    }

    function pollJob(r) {
        return new Promise(function (resolve, reject) {
            var failures = 0;
            (function tick() {
                if (r.cancelled) { resolve(null); return; }
                fetch('/api/ai/jobs/' + encodeURIComponent(r.jobId))
                    .then(function (res) { return res.json().then(function (j) { return { res: res, j: j }; }); })
                    .then(function (x) {
                        if (r.cancelled) { resolve(null); return; }
                        failures = 0;
                        if (!x.res.ok) { reject(new Error((x.j && x.j.error) || 'Lost track of the generation job. The AI service may have restarted.')); return; }
                        var j = x.j;
                        var frac = j.requested > 0 ? Math.min(1, j.accepted / j.requested) : 0;
                        var detail = j.chunksTotal > 0 ? 'Section ' + Math.min(j.chunksTotal, j.chunksDone + 1) + ' of ' + j.chunksTotal + '. ' + j.accepted + ' of ' + j.requested + ' questions accepted so far.' : null;
                        setProgress(j.stage || 'Working...', 0.15 + 0.85 * frac, detail);
                        if (j.status === 'done') { resolve(j.result); return; }
                        if (j.status === 'error') { reject(new Error(j.error || 'Generation failed.')); return; }
                        if (j.status === 'cancelled') { resolve(null); return; }
                        setTimeout(tick, POLL_MS);
                    })
                    .catch(function () {
                        failures++;
                        if (failures >= 5) { reject(new Error('Lost the connection to the server while generating.')); return; }
                        setTimeout(tick, POLL_MS * 2);
                    });
            })();
        });
    }

    function generate() {
        if (run) return Promise.resolve();
        var input = $('pdfFile');
        var file = input && input.files && input.files[0];
        var problem = checkFile(file);
        if (problem) { toast(problem, 'error'); return Promise.resolve(); }
        var num = parseInt(($('numQuestions') || {}).value, 10);
        if (!(num >= 1 && num <= MAX_QUESTIONS)) { toast('Enter a number of questions between 1 and ' + MAX_QUESTIONS + '.', 'error'); return Promise.resolve(); }
        var difficulty = ($('rvDifficulty') || {}).value || 'medium';

        var r = { cancelled: false, controller: typeof AbortController !== 'undefined' ? new AbortController() : null, jobId: null, started: Date.now(), lastChange: Date.now(), sig: '', timer: null };
        run = r;
        setBusy(true);
        showProgress();
        setProgress('Uploading your PDF... 0%', 0);
        r.timer = setInterval(function () {
            var el = $('rvElapsed'); if (el) el.textContent = mmss(Math.floor((Date.now() - r.started) / 1000));
            var slow = $('rvSlow');
            if (slow && slow.hidden && stalled(r.lastChange, Date.now(), config.slowAfterMs || SLOW_AFTER_MS)) slow.hidden = false;
        }, 1000);

        var form = new FormData();
        form.append('file', file);
        form.append('num', String(num));
        form.append('difficulty', difficulty);

        return root.IGApi.upload('/api/ai/jobs', form, {
            signal: r.controller ? r.controller.signal : undefined,
            onProgress: function (f) { setProgress('Uploading your PDF... ' + Math.round(f * 100) + '%', f * 0.15); }
        }).then(function (up) {
            if (r.cancelled) return null;
            if (!up.ok) throw new Error(errorText(up, 'The upload failed.'));
            if (!up.json || !up.json.jobId) throw new Error('The server did not start a generation job.');
            r.jobId = up.json.jobId;
            setProgress('Reading the document...', 0.15);
            return pollJob(r);
        }).then(function (result) {
            if (r.cancelled || !result) return;
            acceptResult(result);
            hideProgress();
            var made = (result.questions || []).length;
            toast(made + ' question' + (made === 1 ? '' : 's') + ' ready for your review.', made > 0 ? 'success' : 'warning');
        }).catch(function (err) {
            if (r.cancelled) return;
            showFailure(err && err.message === 'network' ? 'Could not reach the server. Check your connection and try again.' : (err && err.message) || 'Something went wrong.');
        }).then(function () {
            clearInterval(r.timer);
            if (run === r) run = null;
            setBusy(false);
        });
    }

    function acceptResult(result) {
        (result.questions || []).forEach(function (item) { state.questions.push(fromAi(item)); });
        if ((result.questions || []).length) dirty = true;
        state.rejected = (state.rejected || []).concat(result.rejected || []);
        state.meta = result.meta || null;
        render();
        var first = state.questions.findIndex(needsAttention);
        if (first >= 0) setTimeout(function () { focusCard(first); }, 100);
    }

    // ---------------------------------------------------------------- question bank (reuse questions from earlier exams)
    function openBank(opener) {
        var overlay = document.createElement('div');
        overlay.className = 'qb-overlay';
        overlay.innerHTML = '<div class="qb-box" role="dialog" aria-modal="true" aria-label="Question bank">' +
            '<div class="qb-head"><strong class="mr-auto">Question bank</strong>' +
            '<input type="search" id="qbSearch" class="field-input" style="max-width:240px" placeholder="Search questions" aria-label="Search the question bank">' +
            '<select id="qbSubject" class="rv-select" aria-label="Filter by subject"><option value="">All Subjects</option></select>' +
            '<button type="button" id="qbClose" class="btn btn-ghost btn-sm" aria-label="Close"><i class="fa-solid fa-xmark"></i></button></div>' +
            '<div class="qb-list" id="qbList" aria-live="polite"><p class="rv-empty">Loading...</p></div>' +
            '<div class="qb-foot"><span id="qbCount" class="rv-hint" style="margin:0"></span><button type="button" id="qbAdd" class="btn btn-primary btn-sm" disabled>Add Selected</button></div></div>';
        document.body.appendChild(overlay);
        var items = [], timer = null;
        var search = overlay.querySelector('#qbSearch'), subject = overlay.querySelector('#qbSubject'), list = overlay.querySelector('#qbList'),
            count = overlay.querySelector('#qbCount'), add = overlay.querySelector('#qbAdd');

        function close() { document.removeEventListener('keydown', onKey); if (overlay.parentNode) overlay.parentNode.removeChild(overlay); if (opener && opener.focus) opener.focus(); }
        function onKey(e) { if (e.key === 'Escape') close(); }
        function selectedCount() { return list.querySelectorAll('input[type="checkbox"]:checked').length; }
        function refreshAdd() { var n = selectedCount(); add.disabled = n === 0; add.textContent = n ? 'Add ' + n + ' selected' : 'Add selected'; }

        function load() {
            var qs = '?q=' + encodeURIComponent(search.value.trim()) + '&subject=' + encodeURIComponent(subject.value);
            fetch('/api/exam/teacher/question-bank' + qs).then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(j.error || 'Could not load the question bank.'); return j; }); })
                .then(function (data) {
                    items = data.questions || [];
                    if (subject.options.length <= 1) {
                        (data.subjects || []).forEach(function (sj) { var o = document.createElement('option'); o.value = sj; o.textContent = sj; subject.appendChild(o); });
                    }
                    count.textContent = items.length + ' question' + (items.length === 1 ? '' : 's') + (items.length >= 200 ? ' (showing the first 200)' : '');
                    list.innerHTML = items.length ? items.map(function (it, i) {
                        return '<label class="qb-row"><input type="checkbox" data-i="' + i + '" aria-label="Select question ' + (i + 1) + '"><span><span class="qb-q">' + esc(it.questionText) + '</span>' +
                            '<span class="qb-meta" style="display:block">' + esc(it.subjectName || 'No subject') + ' &middot; ' + esc(it.examTitle || '') + ' &middot; ' + esc(it.marks || 1) + ' mark(s) &middot; answer: ' + esc(it.correctAnswer) + '</span></span></label>';
                    }).join('') : '<p class="rv-empty">No questions found. Questions from exams you or your branch created appear here.</p>';
                    refreshAdd();
                })
                .catch(function (err) { list.innerHTML = '<p class="rv-empty">' + esc(err.message) + '</p>'; });
        }

        overlay.querySelector('#qbClose').addEventListener('click', close);
        overlay.addEventListener('click', function (e) { if (e.target === overlay) close(); });
        document.addEventListener('keydown', onKey);
        list.addEventListener('change', refreshAdd);
        search.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(load, 300); });
        subject.addEventListener('change', load);
        add.addEventListener('click', function () {
            var picked = [];
            list.querySelectorAll('input[type="checkbox"]:checked').forEach(function (cb) { picked.push(items[parseInt(cb.getAttribute('data-i'), 10)]); });
            var fresh = newOnly(state.questions, picked);
            fresh.forEach(function (it) { state.questions.push(bankToQuestion(it)); });
            if (fresh.length) dirty = true;
            render();
            toast(fresh.length + ' question' + (fresh.length === 1 ? '' : 's') + ' added' + (picked.length > fresh.length ? ' (' + (picked.length - fresh.length) + ' already in this exam)' : '') + '.', fresh.length ? 'success' : 'info');
            close();
        });
        search.focus();
        load();
    }

    // ---------------------------------------------------------------- drafts (shared by the three exam lists)
    function api(path, method) {
        return fetch(path, { method: method || 'GET' }).then(function (res) {
            return res.json().catch(function () { return {}; }).then(function (j) {
                if (!res.ok) throw new Error(j.error || 'The server refused the request (HTTP ' + res.status + ').');
                return j;
            });
        });
    }

    function fetchDraft(id) { return api('/api/exam/teacher/' + encodeURIComponent(id) + '/draft'); }

    function publishDraft(id, afterFn) {
        var ask = root.IGUI && root.IGUI.confirm
            ? root.IGUI.confirm({ title: 'Publish This Draft?', message: 'Students will be able to see and take this exam. You cannot edit it after publishing. If it contains AI-drafted questions, make sure you have reviewed each one.', confirmText: 'Publish', cancelText: 'Not Yet', icon: 'fa-paper-plane' })
            : Promise.resolve(true);
        return ask.then(function (ok) {
            if (!ok) return false;
            return api('/api/exam/teacher/' + encodeURIComponent(id) + '/publish', 'PUT').then(function (j) {
                toast(j.message || 'Exam published.', 'success');
                if (typeof afterFn === 'function') afterFn();
                return true;
            }).catch(function (err) { toast(err.message, 'error'); return false; });
        });
    }

    function deleteDraft(id, afterFn) {
        var ask = root.IGUI && root.IGUI.confirm
            ? root.IGUI.confirm({ title: 'Delete This Draft?', message: 'The draft and its questions will be permanently deleted.', confirmText: 'Delete Draft', cancelText: 'Keep It', danger: true, icon: 'fa-trash' })
            : Promise.resolve(true);
        return ask.then(function (ok) {
            if (!ok) return false;
            return api('/api/exam/teacher/' + encodeURIComponent(id) + '/draft', 'DELETE').then(function (j) {
                toast(j.message || 'Draft deleted.', 'success');
                if (typeof afterFn === 'function') afterFn();
                return true;
            }).catch(function (err) { toast(err.message, 'error'); return false; });
        });
    }

    /** Buttons for a draft exam card. reloadFnName is the page's list-refresh function name. */
    function draftButtons(exam, reloadFnName) {
        var id = Number(exam.id);
        return '<div class="rv-draft-actions">' +
            '<button type="button" class="btn btn-secondary btn-sm" onclick="editDraft(' + id + ')"><i class="fa-solid fa-pen"></i> Edit</button>' +
            '<button type="button" class="btn btn-primary btn-sm" onclick="IGReview.publishDraft(' + id + ', ' + reloadFnName + ')"><i class="fa-solid fa-paper-plane"></i> Publish</button>' +
            '<button type="button" class="btn btn-ghost btn-sm" onclick="IGReview.deleteDraft(' + id + ', ' + reloadFnName + ')" aria-label="Delete draft"><i class="fa-solid fa-trash"></i></button>' +
            '</div>';
    }

    /** datetime-local inputs need "YYYY-MM-DDTHH:mm". */
    function toInputDateTime(value) { return value ? String(value).slice(0, 16) : ''; }

    var api_ = {
        mount: mount, reset: reset, generate: generate, addManual: addManual, render: render,
        loadQuestions: loadQuestions, exportQuestions: exportQuestions, count: count, flaggedCount: flagged, isDirty: function () { return dirty && state.questions.length > 0; },
        confirmPublish: confirmPublish, cancel: function () { cancelGeneration(false); },
        fetchDraft: fetchDraft, publishDraft: publishDraft, deleteDraft: deleteDraft, draftButtons: draftButtons,
        openBank: openBank, toInputDateTime: toInputDateTime, isBusy: function () { return !!run; },
        _pure: pure
    };

    root.IGReview = api_;
    if (typeof module !== 'undefined' && module.exports) module.exports = pure;
})(typeof window !== 'undefined' ? window : globalThis);
