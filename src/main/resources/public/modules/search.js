/**
 * Federated search — wires a search input + overlay pair into a working search panel.
 *
 * createSearch(inputEl, overlayEl, opts) is the factory used by both the header search
 * and the home portal search.  initSearch() wires it onto the header elements.
 *
 * opts:
 *   keyboardNav    — enable arrow-key / Enter navigation through overlay rows (default false)
 *   globalShortcut — register Cmd/Ctrl+K to focus the input (default false)
 */

import { esc } from './utils.js';
import { ICON_FAV_SM, ICON_BM_SM } from './icons.js';

export function createSearch(inputEl, overlayEl, opts = {}) {
    const { keyboardNav = false, globalShortcut = false, autoNavigate = true, twoColumn = false } = opts;

    let debounceTimer = null;
    let selectedIndex = -1;

    // ── Helpers ───────────────────────────────────────────────────────────────

    function showOverlay() { overlayEl.style.display = 'block'; }
    function hideOverlay()  { overlayEl.style.display = 'none'; selectedIndex = -1; }

    function getOverlayRows() {
        return Array.from(overlayEl.querySelectorAll('.search-row'));
    }

    function setSelectedIndex(idx) {
        const rows = getOverlayRows();
        if (!rows.length) return;
        selectedIndex = Math.max(0, Math.min(idx, rows.length - 1));
        rows.forEach((r, i) => r.classList.toggle('search-row-selected', i === selectedIndex));
        rows[selectedIndex].scrollIntoView({ block: 'nearest' });
    }

    // ── Product-code shortcut ─────────────────────────────────────────────────

    async function tryProductCodeNavigate(code) {
        const upper = code.toUpperCase();

        // 1. Try exact match — navigate immediately (header search only).
        if (autoNavigate) {
            try {
                const res = await fetch(`/api/titles/by-code/${encodeURIComponent(upper)}`);
                if (res.ok) {
                    const titleData = await res.json();
                    hideOverlay();
                    inputEl.value = '';
                    const { openTitleDetail } = await import('./title-detail.js');
                    await openTitleDetail(titleData);
                    return;
                }
            } catch { /* fall through to prefix search */ }
        }

        // 2. Prefix search — show results only if ≤ 10 matches exist.
        //    When autoNavigate is false, an exact code match returns exactly 1 result here,
        //    which is shown in the overlay for the user to click.
        try {
            const res = await fetch(`/api/titles/by-code-prefix?prefix=${encodeURIComponent(upper)}&limit=11`);
            if (!res.ok) return;
            const titles = await res.json();
            if (titles.length === 0 || titles.length > 10) { hideOverlay(); return; }
            renderOverlay({ actresses: [], titles, labels: [], companies: [] });
        } catch { /* ignore */ }
    }

    // ── Federated search ──────────────────────────────────────────────────────

    async function runSearch(q) {
        try {
            const res = await fetch(`/api/search?q=${encodeURIComponent(q)}&matchMode=contains`);
            if (!res.ok) return;
            const data = await res.json();
            renderOverlay(data);
        } catch {
            // ignore network errors silently
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    function renderOverlay(data) {
        const { actresses = [], titles = [], labels = [], companies = [] } = data;
        const hasResults = actresses.length || titles.length || labels.length || companies.length;

        if (!hasResults) {
            overlayEl.innerHTML = '<div class="search-overlay-empty">no results</div>';
            showOverlay();
            return;
        }

        let html = '';

        if (actresses.length) {
            html += '<div class="search-group search-group-actresses"><div class="search-group-label">Actresses</div>';
            for (const a of actresses) {
                const tier      = (a.tier || '').toLowerCase();
                const stageSub  = a.stageName
                    ? `<span class="search-stage-name">${esc(a.stageName)}</span>` : '';
                const alias = a.matchedAlias
                    ? `<span class="search-alias">a.k.a. ${esc(a.matchedAlias)}</span>` : '';
                const grade = a.grade
                    ? `<span class="search-grade">${esc(a.grade)}</span>` : '';
                const count = `<span class="search-count">${a.titleCount}</span>`;
                const thumb = a.coverUrl
                    ? `<img class="search-thumb" src="${esc(a.coverUrl)}" alt="" loading="lazy">`
                    : '<div class="search-thumb search-thumb-empty"></div>';
                const favIcon = a.favorite ? ICON_FAV_SM : '';
                const bmIcon  = a.bookmark  ? ICON_BM_SM  : '';
                html += `<div class="search-row search-actress-row" data-actress-id="${a.id}">`
                      + thumb
                      + `<span class="search-name tier-${tier}">${esc(a.canonicalName)}</span>`
                      + stageSub + alias + grade + count + favIcon + bmIcon
                      + '</div>';
            }
            html += '</div>';
        }

        if (titles.length) {
            html += '<div class="search-group search-group-titles"><div class="search-group-label">Titles</div>';
            for (const t of titles) {
                const displayName = t.titleEnglish || t.titleOriginal || '';
                const nameHtml = displayName ? `<span class="search-name">${esc(displayName)}</span>` : '';
                const actress = t.actressName
                    ? `<span class="search-meta">${esc(t.actressName)}</span>` : '';
                const year = t.releaseDate ? `<span class="search-meta">${t.releaseDate.substring(0, 4)}</span>` : '';
                const thumb = t.coverUrl
                    ? `<img class="search-thumb search-thumb-title" src="${esc(t.coverUrl)}" alt="" loading="lazy">`
                    : '<div class="search-thumb search-thumb-title search-thumb-empty"></div>';
                const inner = twoColumn
                    ? `<div class="search-title-stack">`
                        + `<span class="search-code">${esc(t.code)}</span>`
                        + nameHtml
                        + (actress || year
                            ? `<span class="search-title-byline">${actress}${actress && year ? ' · ' : ''}${year}</span>`
                            : '')
                        + `</div>`
                    : `<span class="search-code">${esc(t.code)}</span>` + nameHtml + actress + year;
                const favIcon = t.favorite ? ICON_FAV_SM : '';
                const bmIcon  = t.bookmark  ? ICON_BM_SM  : '';
                html += `<div class="search-row search-title-row" data-title-code="${esc(t.code)}">`
                      + thumb + inner + favIcon + bmIcon
                      + '</div>';
            }
            html += '</div>';
        }

        if (labels.length) {
            html += '<div class="search-group search-group-labels"><div class="search-group-label">Labels</div>';
            for (const l of labels) {
                const company = l.company ? `<span class="search-meta">${esc(l.company)}</span>` : '';
                html += `<div class="search-row search-label-row" data-label-code="${esc(l.code)}">`
                      + `<span class="search-code">${esc(l.code)}</span>`
                      + `<span class="search-name">${esc(l.labelName || l.code)}</span>`
                      + company
                      + '</div>';
            }
            html += '</div>';
        }

        if (companies.length) {
            html += '<div class="search-group search-group-studios"><div class="search-group-label">Studios</div>';
            for (const c of companies) {
                html += `<div class="search-row search-company-row">`
                      + `<span class="search-name">${esc(c)}</span>`
                      + '</div>';
            }
            html += '</div>';
        }

        selectedIndex = -1;
        overlayEl.innerHTML = twoColumn
            ? `<div class="search-col-wrap">${html}</div>`
            : html;
        showOverlay();
        wireRowClicks();
    }

    function wireRowClicks() {
        overlayEl.querySelectorAll('.search-actress-row').forEach(el => {
            el.addEventListener('click', async () => {
                hideOverlay();
                inputEl.value = '';
                const id = parseInt(el.dataset.actressId, 10);
                const { openActressDetail } = await import('./actress-detail.js');
                await openActressDetail(id);
            });
        });

        overlayEl.querySelectorAll('.search-title-row').forEach(el => {
            el.addEventListener('click', async () => {
                hideOverlay();
                inputEl.value = '';
                const code = el.dataset.titleCode;
                let titleData = { code };
                try {
                    const res = await fetch(`/api/titles/by-code/${encodeURIComponent(code)}`);
                    if (res.ok) titleData = await res.json();
                } catch { /* use bare code fallback */ }
                const { openTitleDetail } = await import('./title-detail.js');
                await openTitleDetail(titleData);
            });
        });

        overlayEl.querySelectorAll('.search-label-row').forEach(el => {
            el.addEventListener('click', async () => {
                hideOverlay();
                const code = el.dataset.labelCode;
                inputEl.value = code + '-';
                inputEl.dispatchEvent(new Event('input'));
                inputEl.focus();
            });
        });

        overlayEl.querySelectorAll('.search-company-row').forEach(el => {
            el.addEventListener('click', async () => {
                hideOverlay();
                inputEl.value = '';
                const { selectTitleBrowseMode } = await import('./title-browse.js');
                selectTitleBrowseMode('studio');
            });
        });
    }

    // ── Event wiring ──────────────────────────────────────────────────────────

    inputEl.addEventListener('input', () => {
        clearTimeout(debounceTimer);
        const q = inputEl.value.trim();
        if (!q) { hideOverlay(); return; }

        if (/^[A-Z]+-\d+$/i.test(q)) {
            debounceTimer = setTimeout(() => tryProductCodeNavigate(q), 250);
            return;
        }

        debounceTimer = setTimeout(() => runSearch(q), 250);
    });

    // Jump to the vertically-closest row in the opposite visual column.
    // Uses getBoundingClientRect to detect which column each row is in,
    // since CSS column-count is purely visual and doesn't affect DOM order.
    function navigateColumn(direction) {
        const rows = getOverlayRows();
        if (!rows.length || selectedIndex < 0) return;

        const rects = rows.map((r, i) => ({
            idx: i,
            rect: r.getBoundingClientRect(),
        }));

        const currentRect = rects[selectedIndex].rect;
        const currentCenterY = currentRect.top + currentRect.height / 2;

        // Find the x midpoint to split left vs right column
        const xs = rects.map(r => r.rect.left);
        const midX = (Math.min(...xs) + Math.max(...xs)) / 2;

        const inLeftCol = currentRect.left < midX;
        if (direction === 'right' && !inLeftCol) return;
        if (direction === 'left'  &&  inLeftCol) return;

        const targetRects = rects.filter(r =>
            direction === 'right' ? r.rect.left >= midX : r.rect.left < midX
        );
        if (!targetRects.length) return;

        // Pick the row whose vertical center is closest to the current row's center
        const closest = targetRects.reduce((best, r) => {
            const dist = Math.abs((r.rect.top + r.rect.height / 2) - currentCenterY);
            return dist < best.dist ? { idx: r.idx, dist } : best;
        }, { idx: targetRects[0].idx, dist: Infinity });

        setSelectedIndex(closest.idx);
    }

    if (keyboardNav) {
        inputEl.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') { hideOverlay(); inputEl.blur(); return; }
            if (overlayEl.style.display === 'none') return;
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                setSelectedIndex(selectedIndex + 1);
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                setSelectedIndex(selectedIndex - 1);
            } else if (e.key === 'ArrowRight' && twoColumn) {
                e.preventDefault();
                navigateColumn('right');
            } else if (e.key === 'ArrowLeft' && twoColumn) {
                e.preventDefault();
                navigateColumn('left');
            } else if (e.key === 'Enter' && selectedIndex >= 0) {
                e.preventDefault();
                getOverlayRows()[selectedIndex]?.click();
            }
        });
    } else {
        inputEl.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') { hideOverlay(); inputEl.blur(); }
        });
    }

    document.addEventListener('click', (e) => {
        if (!overlayEl.contains(e.target) && e.target !== inputEl) {
            hideOverlay();
        }
    });

    if (globalShortcut) {
        document.addEventListener('keydown', (e) => {
            if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                inputEl.focus();
                inputEl.select();
            }
        });
    }
}

// ── Header search init ────────────────────────────────────────────────────────

export function initSearch() {
    const input   = document.getElementById('search-input');
    const overlay = document.getElementById('search-overlay');
    if (!input || !overlay) return;
    createSearch(input, overlay, { keyboardNav: true, globalShortcut: true });
}
