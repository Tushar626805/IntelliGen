/* IntelliGen shared UI helpers: toasts, confirm/prompt dialogs, empty/loading states.
   Native alert() is routed to a non-blocking toast so existing call sites keep working. */
(function () {
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    var ICONS = { success: 'fa-circle-check', error: 'fa-circle-exclamation', warning: 'fa-triangle-exclamation', info: 'fa-circle-info' };

    function inferType(msg) {
        var m = String(msg);
        if (/(fail|error|denied|invalid|broken|exception|unable|cannot|mismatch|dropped|rejected|not found)/i.test(m)) return 'error';
        if (/^(please|fill|add at least|enter)|(please |fill out|fill all)/i.test(m)) return 'warning';
        if (/(success|published|active|approved|updated|advanced|forward|revoked|smoothly|executed|submitted)/i.test(m)) return 'success';
        return 'info';
    }

    function toastHost() {
        var h = document.getElementById('ig-toasts');
        if (!h) {
            h = document.createElement('div');
            h.id = 'ig-toasts';
            h.className = 'ig-toasts';
            h.setAttribute('aria-live', 'polite');
            document.body.appendChild(h);
        }
        return h;
    }

    function toast(message, type, ms) {
        type = type || inferType(message);
        var el = document.createElement('div');
        el.className = 'ig-toast ig-toast-' + type;
        el.setAttribute('role', type === 'error' ? 'alert' : 'status');
        el.innerHTML = '<i class="fa-solid ' + ICONS[type] + ' ig-toast-icon"></i><div class="flex-1"></div>' +
            '<button type="button" class="ig-toast-close" aria-label="Dismiss"><i class="fa-solid fa-xmark"></i></button>';
        el.children[1].textContent = String(message);
        var remove = function () { if (el.parentNode) el.parentNode.removeChild(el); };
        el.querySelector('button').addEventListener('click', remove);
        toastHost().appendChild(el);
        setTimeout(remove, ms || (type === 'error' ? 7000 : 4500));
    }

    /* Generic dialog. mode 'confirm' resolves boolean, 'prompt' resolves string|null. */
    function dialog(opts) {
        return new Promise(function (resolve) {
            var overlay = document.createElement('div');
            overlay.className = 'ig-dialog-overlay';
            var prev = document.activeElement;
            var isPrompt = opts.mode === 'prompt';
            overlay.innerHTML =
                '<div class="ig-dialog" role="dialog" aria-modal="true" aria-labelledby="igDlgTitle">' +
                '<div class="ig-dialog-icon ' + (opts.danger ? 'danger' : '') + '"><i class="fa-solid ' + (opts.icon || (opts.danger ? 'fa-triangle-exclamation' : 'fa-circle-question')) + '"></i></div>' +
                '<h2 id="igDlgTitle"></h2><p></p>' +
                (isPrompt ? '<input class="field-input mt-4" id="igDlgInput" autocomplete="off" type="' + (opts.password ? 'password' : 'text') + '">' : '') +
                '<div class="ig-dialog-actions"><button type="button" class="btn btn-secondary" data-act="cancel"></button>' +
                '<button type="button" class="btn ' + (opts.danger ? 'btn-danger' : 'btn-primary') + '" data-act="ok"></button></div></div>';
            overlay.querySelector('h2').textContent = opts.title || 'Are you sure?';
            overlay.querySelector('p').textContent = opts.message || '';
            overlay.querySelector('[data-act=cancel]').textContent = opts.cancelText || 'Cancel';
            overlay.querySelector('[data-act=ok]').textContent = opts.confirmText || 'Confirm';
            if (isPrompt && opts.placeholder) overlay.querySelector('input').placeholder = opts.placeholder;
            document.body.appendChild(overlay);

            var input = overlay.querySelector('input');
            var done = function (ok) {
                document.removeEventListener('keydown', onKey, true);
                overlay.remove();
                if (prev && prev.focus) { try { prev.focus(); } catch (e) {} }
                resolve(isPrompt ? (ok ? input.value : null) : ok);
            };
            var onKey = function (e) {
                if (e.key === 'Escape') { e.stopPropagation(); done(false); }
                else if (e.key === 'Enter' && isPrompt) { e.preventDefault(); done(true); }
                else if (e.key === 'Tab') {
                    var f = overlay.querySelectorAll('button,input');
                    var first = f[0], last = f[f.length - 1];
                    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
                    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
                }
            };
            document.addEventListener('keydown', onKey, true);
            overlay.addEventListener('click', function (e) {
                if (e.target === overlay) done(false);
                var act = e.target.closest && e.target.closest('[data-act]');
                if (act) done(act.getAttribute('data-act') === 'ok');
            });
            (input || overlay.querySelector('[data-act=ok]')).focus();
        });
    }

    function empty(o) {
        return '<div class="empty-state' + (o.error ? ' is-error' : '') + '">' +
            '<div class="es-icon"><i class="fa-solid ' + (o.icon || 'fa-inbox') + '"></i></div>' +
            '<p class="es-title">' + esc(o.title) + '</p>' + (o.text ? '<p>' + esc(o.text) + '</p>' : '') +
            (o.retry ? '<button type="button" class="btn btn-secondary btn-sm" onclick="' + o.retry + '"><i class="fa-solid fa-rotate-right"></i> Try Again</button>' : '') +
            (o.action ? '<button type="button" class="btn btn-primary btn-sm" onclick="' + o.action.onclick + '"><i class="fa-solid ' + (o.action.icon || 'fa-plus') + '"></i> ' + esc(o.action.label) + '</button>' : '') +
            '</div>';
    }

    function skeletonCards(n) {
        var one = '<div class="skeleton-card"><div class="skeleton" style="height:14px;width:35%"></div>' +
            '<div class="skeleton mt-4" style="height:20px;width:80%"></div><div class="skeleton mt-3" style="height:12px;width:55%"></div>' +
            '<div class="skeleton mt-6" style="height:38px;width:100%"></div></div>';
        return new Array((n || 3) + 1).join(one);
    }

    // Escape closes any open page modal (create-exam forms are excluded so typed work is not lost by accident)
    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') return;
        document.querySelectorAll('.modal-overlay:not(.hidden)').forEach(function (m) {
            if (!/create/i.test(m.id)) m.classList.add('hidden');
        });
    });
    document.addEventListener('click', function (e) {
        if (e.target.classList && e.target.classList.contains('modal-overlay') && !/create/i.test(e.target.id)) {
            e.target.classList.add('hidden');
        }
    });

    window.IGUI = {
        esc: esc, toast: toast, empty: empty, skeletonCards: skeletonCards,
        confirm: function (o) { return dialog(Object.assign({ mode: 'confirm' }, o)); },
        prompt: function (o) { return dialog(Object.assign({ mode: 'prompt' }, o)); }
    };
    window.alert = function (m) { toast(m); };
})();


/* ==========================================================================
   Modal accessibility (applies to every .modal-overlay on every page)
   - the panel becomes a labelled, modal dialog for assistive technology
   - focus moves into the dialog when it opens and returns to the opener when it closes
   - Tab / Shift+Tab stay inside the open dialog
   Pages keep opening and closing modals by toggling the "hidden" class; nothing else about them changes.
   ========================================================================== */
(function () {
    'use strict';
    var counter = 0;
    var FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

    function panelOf(overlay) { return overlay.firstElementChild; }
    function isOpen(overlay) { return !overlay.classList.contains('hidden') && overlay.style.display !== 'none'; }
    function visible(el) { return !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length); }
    function focusables(panel) { return Array.prototype.filter.call(panel.querySelectorAll(FOCUSABLE), visible); }

    function setup(overlay) {
        if (overlay.__igModal) return;
        var panel = panelOf(overlay);
        if (!panel) return;
        overlay.__igModal = true;
        panel.setAttribute('role', 'dialog');
        panel.setAttribute('aria-modal', 'true');
        panel.setAttribute('tabindex', '-1');
        var heading = panel.querySelector('h1, h2, h3');
        if (heading) {
            if (!heading.id) heading.id = 'igModalTitle' + (++counter);
            panel.setAttribute('aria-labelledby', heading.id);
        }

        var opener = null, was = isOpen(overlay);
        new MutationObserver(function () {
            var now = isOpen(overlay);
            if (now && !was) {
                opener = document.activeElement && document.activeElement !== document.body ? document.activeElement : null;
                var fields = Array.prototype.filter.call(panel.querySelectorAll('input:not([type="hidden"]):not([type="file"]), select, textarea'), function (el) { return !el.disabled && visible(el); });
                var target = fields[0] || focusables(panel)[0] || panel;
                target.focus();
            } else if (!now && was) {
                if (opener && document.contains(opener) && typeof opener.focus === 'function') opener.focus();
                opener = null;
            }
            was = now;
        }).observe(overlay, { attributes: true, attributeFilter: ['class', 'style'] });
    }

    function setupAll(root) { Array.prototype.forEach.call((root || document).querySelectorAll('.modal-overlay'), setup); }

    function init() {
        setupAll();
        if (window.MutationObserver && document.body) {
            new MutationObserver(function (records) {
                records.forEach(function (r) {
                    Array.prototype.forEach.call(r.addedNodes, function (n) {
                        if (n.nodeType !== 1) return;
                        if (n.classList.contains('modal-overlay')) setup(n); else setupAll(n);
                    });
                });
            }).observe(document.body, { childList: true, subtree: true });
        }
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();

    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Tab') return;
        var open = Array.prototype.filter.call(document.querySelectorAll('.modal-overlay'), isOpen);
        if (!open.length) return;
        var panel = panelOf(open[open.length - 1]);
        if (!panel) return;
        var list = focusables(panel);
        if (!list.length) { e.preventDefault(); panel.focus(); return; }
        var first = list[0], last = list[list.length - 1], active = document.activeElement;
        if (!panel.contains(active)) { e.preventDefault(); first.focus(); }
        else if (e.shiftKey && (active === first || active === panel)) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && active === last) { e.preventDefault(); first.focus(); }
    });
})();
