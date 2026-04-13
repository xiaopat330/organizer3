/**
 * Federated search overlay — wires onto #search-input in the header.
 *
 * Phase 1: debounced fetch, grouped overlay panel, product-code shortcut.
 * Keyboard navigation and advanced mode toggle are deferred to a later pass.
 */

let _debounceTimer = null;
let _overlayEl     = null;

export function initSearch() {
    const input = document.getElementById('search-input');
    _overlayEl  = document.getElementById('search-overlay');
    if (!input || !_overlayEl) return;

    input.addEventListener('input', () => {
        clearTimeout(_debounceTimer);
        const q = input.value.trim();
        if (!q) { hideOverlay(); return; }

        // Product-code shortcut: if the value looks like ABP-123, try direct navigation
        if (/^[A-Z]+-\d+$/i.test(q)) {
            _debounceTimer = setTimeout(() => tryProductCodeNavigate(q), 250);
            return;
        }

        _debounceTimer = setTimeout(() => runSearch(q), 250);
    });

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') { hideOverlay(); input.blur(); }
    });

    document.addEventListener('click', (e) => {
        if (!_overlayEl.contains(e.target) && e.target !== input) {
            hideOverlay();
        }
    });
}

// ── Product-code shortcut ────────────────────────────────────────────────────

async function tryProductCodeNavigate(code) {
    try {
        const res = await fetch(`/api/titles/by-code/${encodeURIComponent(code.toUpperCase())}`);
        if (res.ok) {
            const titleData = await res.json();
            hideOverlay();
            document.getElementById('search-input').value = '';
            const { openTitleDetail } = await import('./title-detail.js');
            await openTitleDetail(titleData);
        } else {
            // Not a direct match — fall through to normal search
            runSearch(code);
        }
    } catch {
        runSearch(code);
    }
}

// ── Federated search ─────────────────────────────────────────────────────────

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

// ── Render ───────────────────────────────────────────────────────────────────

function renderOverlay(data) {
    const { actresses = [], titles = [], labels = [], companies = [] } = data;
    const hasResults = actresses.length || titles.length || labels.length || companies.length;

    if (!hasResults) {
        _overlayEl.innerHTML = '<div class="search-overlay-empty">no results</div>';
        showOverlay();
        return;
    }

    let html = '';

    if (actresses.length) {
        html += '<div class="search-group"><div class="search-group-label">Actresses</div>';
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
            html += `<div class="search-row search-actress-row" data-actress-id="${a.id}">`
                  + thumb
                  + `<span class="search-name tier-${tier}">${esc(a.canonicalName)}</span>`
                  + stageSub + alias + grade + count
                  + '</div>';
        }
        html += '</div>';
    }

    if (titles.length) {
        html += '<div class="search-group"><div class="search-group-label">Titles</div>';
        for (const t of titles) {
            const displayName = esc(t.titleEnglish || t.titleOriginal || t.code);
            const actress = t.actressName
                ? `<span class="search-meta">${esc(t.actressName)}</span>` : '';
            const year = t.releaseDate ? `<span class="search-meta">${t.releaseDate.substring(0, 4)}</span>` : '';
            html += `<div class="search-row search-title-row" data-title-code="${esc(t.code)}">`
                  + `<span class="search-code">${esc(t.code)}</span>`
                  + `<span class="search-name">${displayName}</span>`
                  + actress + year
                  + '</div>';
        }
        html += '</div>';
    }

    if (labels.length) {
        html += '<div class="search-group"><div class="search-group-label">Labels</div>';
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
        html += '<div class="search-group"><div class="search-group-label">Studios</div>';
        for (const c of companies) {
            html += `<div class="search-row search-company-row">`
                  + `<span class="search-name">${esc(c)}</span>`
                  + '</div>';
        }
        html += '</div>';
    }

    _overlayEl.innerHTML = html;
    showOverlay();
    wireRowClicks();
}

function wireRowClicks() {
    _overlayEl.querySelectorAll('.search-actress-row').forEach(el => {
        el.addEventListener('click', async () => {
            hideOverlay();
            document.getElementById('search-input').value = '';
            const id = parseInt(el.dataset.actressId, 10);
            const { openActressDetail } = await import('./actress-detail.js');
            await openActressDetail(id);
        });
    });

    _overlayEl.querySelectorAll('.search-title-row').forEach(el => {
        el.addEventListener('click', async () => {
            hideOverlay();
            document.getElementById('search-input').value = '';
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

    _overlayEl.querySelectorAll('.search-label-row').forEach(el => {
        el.addEventListener('click', async () => {
            hideOverlay();
            document.getElementById('search-input').value = '';
            const code = el.dataset.labelCode;
            // Drive the existing title search: pre-fill "LABEL-" and trigger input event
            const { showTitlesBrowse } = await import('./title-browse.js');
            showTitlesBrowse();
            const titleSearch = document.getElementById('title-search-input');
            if (titleSearch) {
                titleSearch.value = code + '-';
                titleSearch.dispatchEvent(new Event('input'));
                titleSearch.focus();
            }
        });
    });

    _overlayEl.querySelectorAll('.search-company-row').forEach(el => {
        el.addEventListener('click', async () => {
            hideOverlay();
            document.getElementById('search-input').value = '';
            const { selectTitleBrowseMode } = await import('./title-browse.js');
            selectTitleBrowseMode('studio');
        });
    });
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function showOverlay() { _overlayEl.style.display = 'block'; }
function hideOverlay()  { _overlayEl.style.display = 'none';  }

function esc(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
