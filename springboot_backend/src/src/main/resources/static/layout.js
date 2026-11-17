/* IntelliGen sidebar dashboard shell.
   Pages put their content in <main id="igContent"> and call IGLayout.mount({...}).
   Nav items with an `id` keep working with each page's existing switchTab() (tab-active / tab-inactive). */
(function () {
    var esc = function (s) { return window.IGUI.esc(s); };

    function currentUser() {
        try { return JSON.parse(localStorage.getItem('currentUser')) || {}; } catch (e) { return {}; }
    }

    /* "STUDENT" -> "Student"; short acronyms such as "HOD" are kept as written. */
    function roleLabel(r) {
        r = String(r || '');
        return (r.length > 3 && r === r.toUpperCase()) ? r.charAt(0) + r.slice(1).toLowerCase() : r;
    }

    function logout() {
        if (window.IGApi) { window.IGApi.logout(); return; }
        localStorage.removeItem('currentUser');
        window.location.replace('login.html');
    }

    function navButton(i) {
        return '<button type="button" ' + (i.id ? 'id="' + i.id + '" ' : '') +
            'class="nav-item' + (i.id ? (i.active ? ' tab-active' : ' tab-inactive') : '') + '" ' +
            'onclick="' + i.onclick + '; IGLayout.closeMenu();">' +
            '<i class="fa-solid ' + i.icon + '" aria-hidden="true"></i><span>' + esc(i.label) + '</span></button>';
    }

    window.IGLayout = {
        logout: logout,
        closeMenu: function () {
            var s = document.getElementById('igSidebar'), c = document.getElementById('igScrim'), b = document.getElementById('igMenuBtn');
            var wasOpen = s && s.classList.contains('open');
            if (s) s.classList.remove('open');
            if (c) c.classList.add('hidden');
            if (b) { b.setAttribute('aria-expanded', 'false'); if (wasOpen && b.offsetParent !== null) b.focus(); }
        },
        openMenu: function () {
            var b = document.getElementById('igMenuBtn');
            document.getElementById('igSidebar').classList.add('open');
            document.getElementById('igScrim').classList.remove('hidden');
            if (b) b.setAttribute('aria-expanded', 'true');
            var first = document.querySelector('#igSidebar .nav-item');
            if (first) first.focus();
        },
        /* cfg: { role, icon, contextId, nav:[{id?,icon,label,onclick,active?}], tools:[{icon,label,onclick}] } */
        mount: function (cfg) {
            var u = currentUser();
            cfg.role = roleLabel(cfg.role);
            var name = u.username || u.name || u.email || 'User';
            var initial = String(name).trim().charAt(0).toUpperCase() || 'U';

            var side = document.createElement('aside');
            side.id = 'igSidebar';
            side.className = 'sidebar';
            side.setAttribute('aria-label', 'Sidebar');
            side.innerHTML =
                '<div class="sidebar-brand"><div class="brand-mark" aria-hidden="true"><i class="fa-solid fa-brain"></i></div>' +
                '<div><p class="text-white font-bold leading-tight">IntelliGen</p><p class="text-xs" style="color:#8ea2cc">' + esc(cfg.role) + ' Workspace</p></div></div>' +
                '<nav class="sidebar-nav" aria-label="Primary Navigation">' +
                '<p class="nav-section">Workspace</p>' + cfg.nav.map(navButton).join('') +
                ((cfg.tools && cfg.tools.length) ? '<p class="nav-section">Tools</p>' + cfg.tools.map(navButton).join('') : '') +
                '</nav>' +
                '<div class="sidebar-foot"><button type="button" class="nav-item" onclick="IGLayout.logout()"><i class="fa-solid fa-right-from-bracket" aria-hidden="true"></i><span>Log Out</span></button></div>';

            var scrim = document.createElement('div');
            scrim.id = 'igScrim';
            scrim.className = 'sidebar-scrim hidden';
            scrim.addEventListener('click', IGLayout.closeMenu);

            var main = document.createElement('div');
            main.className = 'app-main';
            main.innerHTML =
                '<header class="topbar">' +
                '<button type="button" id="igMenuBtn" class="topbar-menu" aria-label="Open Navigation Menu" aria-controls="igSidebar" aria-expanded="false" onclick="IGLayout.openMenu()"><i class="fa-solid fa-bars" aria-hidden="true"></i></button>' +
                '<div class="min-w-0"><p class="text-xs font-medium" style="color:var(--muted)">' + esc(cfg.role) + ' Portal</p>' +
                '<p id="' + cfg.contextId + '" class="text-sm font-semibold text-slate-800 truncate"></p></div>' +
                '<div class="user-chip ml-auto"><div class="text-right hidden sm:block"><p class="text-sm font-semibold text-slate-800 leading-tight">' + esc(name) + '</p>' +
                '<p class="text-xs" style="color:var(--muted)">' + esc(cfg.role) + '</p></div><div class="avatar">' + esc(initial) + '</div></div>' +
                '</header>';

            var content = document.getElementById('igContent');
            content.classList.add('page');
            content.setAttribute('tabindex', '-1');          // lets the skip link move focus into the content
            main.appendChild(content);

            var skip = document.createElement('a');
            skip.className = 'skip-link';
            skip.href = '#igContent';
            skip.textContent = 'Skip to Main Content';
            skip.addEventListener('click', function (e) { e.preventDefault(); content.focus(); });

            document.body.insertBefore(main, document.body.firstChild);
            document.body.insertBefore(scrim, document.body.firstChild);
            document.body.insertBefore(side, document.body.firstChild);
            document.body.insertBefore(skip, document.body.firstChild);

            // Pages switch tabs by toggling tab-active; mirror that to assistive technology as aria-current.
            var syncCurrent = function () {
                side.querySelectorAll('.nav-item').forEach(function (b) {
                    if (b.classList.contains('tab-active')) b.setAttribute('aria-current', 'page');
                    else b.removeAttribute('aria-current');
                });
            };
            syncCurrent();
            if (window.MutationObserver) new MutationObserver(syncCurrent).observe(side, { subtree: true, attributes: true, attributeFilter: ['class'] });
            document.addEventListener('keydown', function (e) {
                if (e.key === 'Escape' && side.classList.contains('open')) IGLayout.closeMenu();
            });
            if (window.IGNotify) window.IGNotify.mount(main.querySelector('.topbar'));
        }
    };
})();
