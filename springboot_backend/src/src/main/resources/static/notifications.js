/* IntelliGen notification bell (added to the shell's top bar by layout.js). Polls /api/notifications every 60 s. */
(function (root) {
    'use strict';
    function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }

    function timeAgo(iso, now) {
        var then = new Date(iso).getTime();
        if (isNaN(then)) return '';
        var s = Math.max(0, Math.round(((now == null ? Date.now() : now) - then) / 1000));
        if (s < 45) return 'just now';
        if (s < 3600) return Math.round(s / 60) + ' min ago';
        if (s < 86400) return Math.round(s / 3600) + ' h ago';
        if (s < 7 * 86400) return Math.round(s / 86400) + ' d ago';
        return new Date(then).toLocaleDateString();
    }

    function itemsHtml(items, now) {
        if (!items || !items.length) return '<p class="nt-empty">You are all caught up.</p>';
        return '<ul class="nt-ul">' + items.map(function (n) {
            return '<li><button type="button" class="nt-item' + (n.read ? '' : ' nt-unread') + '" data-id="' + esc(n.id) + '" data-link="' + esc(n.link || '') + '">' +
                '<span class="nt-dot" aria-hidden="true"></span><span class="nt-body"><span class="nt-title">' + esc(n.title) + (n.read ? '' : '<span class="sr-only"> (unread)</span>') + '</span>' +
                '<span class="nt-msg">' + esc(n.message) + '</span><span class="nt-time">' + esc(timeAgo(n.createdAt, now)) + '</span></span></button></li>';
        }).join('') + '</ul>';
    }

    function mount(topbar) {
        if (!topbar || topbar.querySelector('.notify') || typeof document === 'undefined') return;
        var chip = topbar.querySelector('.user-chip');
        if (chip) chip.classList.remove('ml-auto');
        var wrap = document.createElement('div');
        wrap.className = 'notify';
        wrap.style.marginLeft = 'auto';
        wrap.innerHTML = '<button type="button" class="nt-bell" aria-haspopup="true" aria-expanded="false" aria-label="Notifications"><i class="fa-regular fa-bell"></i><span class="nt-badge hidden" aria-hidden="true"></span></button>' +
            '<div class="nt-panel hidden" role="region" aria-label="Notifications"><div class="nt-head"><strong>Notifications</strong><button type="button" class="nt-markall">Mark All Read</button></div><div class="nt-list" aria-live="polite"></div></div>';
        topbar.insertBefore(wrap, chip || null);

        var bell = wrap.querySelector('.nt-bell'), badge = wrap.querySelector('.nt-badge'), panel = wrap.querySelector('.nt-panel'), list = wrap.querySelector('.nt-list'), markAll = wrap.querySelector('.nt-markall');

        function setOpen(open) {
            panel.classList.toggle('hidden', !open);
            bell.setAttribute('aria-expanded', open ? 'true' : 'false');
            if (open) load();
        }

        function paint(data) {
            var unread = Number(data.unread) || 0;
            badge.textContent = unread > 99 ? '99+' : String(unread);
            badge.classList.toggle('hidden', unread === 0);
            bell.setAttribute('aria-label', unread ? 'Notifications, ' + unread + ' unread' : 'Notifications');
            markAll.disabled = unread === 0;
            list.innerHTML = itemsHtml(data.items || []);
        }

        function load() {
            return fetch('/api/notifications').then(function (r) { return r.ok ? r.json() : null; }).then(function (d) { if (d) paint(d); }).catch(function () { /* offline: keep the last view */ });
        }

        bell.addEventListener('click', function (e) { e.stopPropagation(); setOpen(panel.classList.contains('hidden')); });
        markAll.addEventListener('click', function () { fetch('/api/notifications/read-all', { method: 'POST' }).then(load); });
        list.addEventListener('click', function (e) {
            var item = e.target.closest ? e.target.closest('.nt-item') : null;
            if (!item) return;
            var link = item.getAttribute('data-link');
            fetch('/api/notifications/' + encodeURIComponent(item.getAttribute('data-id')) + '/read', { method: 'POST' }).then(function () {
                if (link && link.indexOf('//') < 0 && link.charAt(0) !== '/' && link !== location.pathname.split('/').pop()) { location.href = link; } else { load(); }
            });
        });
        document.addEventListener('click', function (e) { if (!wrap.contains(e.target)) setOpen(false); });
        document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !panel.classList.contains('hidden')) { setOpen(false); bell.focus(); } });
        setInterval(function () { if (!document.hidden) load(); }, 60000);
        load();
    }

    root.IGNotify = { mount: mount };
    if (typeof module !== 'undefined' && module.exports) module.exports = { timeAgo: timeAgo, itemsHtml: itemsHtml };
})(typeof window !== 'undefined' ? window : globalThis);
