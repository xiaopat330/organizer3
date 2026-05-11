/* ─────────────────────────────────────────────────────────────────────
   Wave 3 — Pending Kanji (workbench mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md (workbench surfaces sweep)
   Lists kanji strings seen in enrichment that don't yet have a
   canonical English translation. Suggestion column indicates
   translation status.
   Deferred: per-row "translate now" + fuzzy-candidate picker (those
   need the modal primitive in real use).
   ───────────────────────────────────────────────────────────────────── */

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[c]));
}

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[pending-kanji] fetch failed:', url, e);
    return fallback;
  }
}

function timeAgo(iso) {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  const diff = (Date.now() - then) / 1000;
  if (diff < 60)        return `${Math.floor(diff)}s ago`;
  if (diff < 3600)      return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400)     return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

function suggestionPill(s) {
  if (!s || !s.status) return `<span class="pk-sug pk-sug--missing">missing</span>`;
  const status = String(s.status).toLowerCase();
  const isReady = status === 'ready';
  const modCls = isReady ? 'pk-sug--ready' : 'pk-sug--missing';
  const romaji = s.romaji ? ` · ${escapeHtml(s.romaji)}` : '';
  return `<span class="pk-sug ${modCls}">${escapeHtml(status)}${romaji}</span>`;
}

export async function mountPendingKanji(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">Pending Kanji</h1>
      <div class="dis-kpi-strip" id="pk-kpi-strip">— kanji · — ready · — missing</div>

      <div class="pk-filter-bar">
        <button class="btn sm" id="btn-refresh">
          <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
          </svg>
          Refresh
        </button>
        <input class="form-input" id="kanji-search" placeholder="Filter…">
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <div id="content"><div class="shelf-loading">Loading…</div></div>
    </div>
  `;

  const content  = rootEl.querySelector('#content');
  const meta     = rootEl.querySelector('#result-meta');
  const search   = rootEl.querySelector('#kanji-search');
  const kpiStrip = rootEl.querySelector('#pk-kpi-strip');
  let allRows    = [];

  const renderTable = (rows) => {
    if (rows.length === 0) {
      const q = search.value.trim();
      const msg = q
        ? `No kanji matching "${escapeHtml(q)}"`
        : 'No pending kanji<br>All seen kanji strings have a canonical translation.';
      content.innerHTML = `<div class="dis-empty">${msg}</div>`;
      return;
    }
    content.innerHTML = `
      <div class="wb-table-wrap">
        <table class="wb-table">
          <thead><tr>
            <th>Kanji</th>
            <th style="width:70px" class="num">Seen</th>
            <th style="width:130px">First seen</th>
            <th style="width:280px">Suggestion</th>
          </tr></thead>
          <tbody>
            ${rows.map(r => `
              <tr>
                <td class="mono" style="font-size:14px">${escapeHtml(r.kanji)}</td>
                <td class="num">${escapeHtml(String(r.count ?? 0))}</td>
                <td class="mono">${escapeHtml(timeAgo(r.oldestSeen))}</td>
                <td>${suggestionPill(r.suggestion)}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    `;
  };

  const applyFilter = () => {
    const q = search.value.trim().toLowerCase();
    const filtered = q
      ? allRows.filter(r => r.kanji.toLowerCase().includes(q) || (r.suggestion?.romaji || '').toLowerCase().includes(q))
      : allRows;
    renderTable(filtered);
    meta.textContent = `${filtered.length}${q ? ` of ${allRows.length}` : ''} kanji`;
    // Update KPI strip from full dataset (not filtered)
    if (kpiStrip) {
      const total   = allRows.length;
      const ready   = allRows.filter(r => r.suggestion && String(r.suggestion.status).toLowerCase() === 'ready').length;
      const missing = total - ready;
      kpiStrip.textContent = `${total} kanji · ${ready} ready · ${missing} missing`;
    }
  };

  const load = async () => {
    content.innerHTML = `<div class="shelf-loading">Loading…</div>`;
    meta.textContent = 'Loading…';
    allRows = (await fetchJson('/api/curation/pending-kanji', [])) || [];
    // Sort by count descending — most frequent first
    allRows.sort((a, b) => (b.count || 0) - (a.count || 0));
    applyFilter();
  };

  rootEl.querySelector('#btn-refresh').addEventListener('click', load);
  search.addEventListener('input', applyFilter);
  load();
}
