/* Search + status filter for a grid of exam cards (client-side). Re-applies itself whenever the page re-renders the grid. */
(function (root) {
    'use strict';
    function matches(text, query, isDraft, status) {
        var q = (query || '').trim().toLowerCase();
        if (q && String(text).toLowerCase().indexOf(q) < 0) return false;
        if (status === 'DRAFT') return !!isDraft;
        if (status === 'PUBLISHED') return !isDraft;
        return true;
    }

    function mount(gridId) {
        var grid = document.getElementById(gridId);
        if (!grid || document.getElementById(gridId + 'Filter')) return;
        var bar = document.createElement('div');
        bar.id = gridId + 'Filter';
        bar.className = 'lf-bar';
        bar.innerHTML = '<input type="search" class="field-input lf-search" placeholder="Search exams by title, subject or teacher" aria-label="Search exams">' +
            '<select class="rv-select lf-status" aria-label="Filter by status"><option value="">All Exams</option><option value="PUBLISHED">Published</option><option value="DRAFT">Drafts</option></select>' +
            '<span class="lf-count" role="status" aria-live="polite"></span>';
        grid.insertAdjacentElement('beforebegin', bar);
        var search = bar.querySelector('.lf-search'), status = bar.querySelector('.lf-status'), count = bar.querySelector('.lf-count');

        function isCard(el) { return !(el.classList.contains('empty-state') || el.classList.contains('skeleton')); }
        function isDraft(card) {
            return Array.prototype.some.call(card.querySelectorAll('.badge'), function (b) { return b.textContent.trim() === 'DRAFT'; });
        }
        function apply() {
            var cards = Array.prototype.filter.call(grid.children, isCard), shown = 0;
            cards.forEach(function (card) {
                var ok = matches(card.textContent, search.value, isDraft(card), status.value);
                card.style.display = ok ? '' : 'none';
                if (ok) shown++;
            });
            count.textContent = !cards.length ? '' : shown === 0 ? 'No exams match your filters.' : 'Showing ' + shown + ' of ' + cards.length + ' exam' + (cards.length === 1 ? '' : 's');
        }
        search.addEventListener('input', apply);
        status.addEventListener('change', apply);
        if (typeof MutationObserver !== 'undefined') new MutationObserver(apply).observe(grid, { childList: true });
        apply();
    }

    root.IGFilter = { mount: mount };
    if (typeof module !== 'undefined' && module.exports) module.exports = { matches: matches };
})(typeof window !== 'undefined' ? window : globalThis);
