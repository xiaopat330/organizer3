/* ─────────────────────────────────────────────────────────────────────
   unprocessed/actress-pane.js — actress assignment typeahead.

   Exported:
     mountActressPane(containerEl, editorState, isDuplicate, onChange) → { destroy }

   Responsibilities:
     - Render current actress list (star/remove controls)
     - Typeahead search against GET /api/unsorted/actresses/search?q=&limit=
     - Keyboard nav (↑↓ / Enter / Escape)
     - Match highlighting in suggestions
     - Inline create (+ Create "name" option)
     - Already-added guard
     - Primary-star toggle (≥1 guard on remove)
     - Duplicate-mode lock (visual + no interaction)
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/** Bold the matched substring in name. Case-insensitive. Returns HTML. */
function highlight(text, query) {
  if (!text) return '';
  const t = String(text);
  if (!query) return esc(t);
  const idx = t.toLowerCase().indexOf(query.toLowerCase());
  if (idx < 0) return esc(t);
  return esc(t.slice(0, idx))
       + `<strong class="un-actress-match">${esc(t.slice(idx, idx + query.length))}</strong>`
       + esc(t.slice(idx + query.length));
}

/**
 * Mount the actress pane into containerEl.
 *
 * @param {HTMLElement} containerEl
 * @param {object}      editorState  — from state.editorState (mutated in place)
 * @param {boolean}     isDuplicate
 * @param {Function}    onChange     — called after any mutation that may affect save/preview
 * @returns {{ destroy:Function, renderActresses:Function }}
 */
export function mountActressPane(containerEl, editorState, isDuplicate, onChange) {
  // ── Sentinel state ───────────────────────────────────────────────────
  let _sentinels   = [];     // Array of sentinel actress objects
  let _sentinelIds = new Set(); // Set of sentinel actress ids (numbers)

  // Fetch sentinels once on mount; render placeholder buttons after load.
  if (!isDuplicate) {
    fetch('/api/actresses?sentinel=true&limit=20')
      .then(r => r.ok ? r.json() : [])
      .then(data => {
        const arr = Array.isArray(data) ? data : (data.items || []);
        _sentinels   = arr.filter(a => a.isSentinel || a.is_sentinel);
        _sentinelIds = new Set(_sentinels.map(s => s.id));
        _renderSentinelButtons();
      })
      .catch(err => console.warn('[actress-pane] fetchSentinels failed', err));
  }

  // ── Build DOM ────────────────────────────────────────────────────────
  containerEl.innerHTML = `
    <div class="un-actress-pane">
      <div class="un-actress-label">Actresses</div>
      <ul class="un-actress-list" id="un-ap-list"></ul>
      <div class="un-actress-hint" id="un-ap-hint" style="display:none"></div>
      ${isDuplicate ? '' : `
        <div class="un-actress-search-wrap">
          <input type="text" class="un-actress-input" id="un-ap-input"
                 placeholder="Type a name to search or create…" autocomplete="off">
          <div class="un-actress-suggest" id="un-ap-suggest" style="display:none"></div>
        </div>
        <div class="un-actress-sentinel-row" id="un-ap-sentinel-row" style="display:none">
          <span class="un-actress-sentinel-label">Placeholders:</span>
        </div>
      `}
      ${isDuplicate ? '<div class="un-actress-dup-lock">Actress assignment locked (duplicate)</div>' : ''}
    </div>
  `;

  const listEl      = containerEl.querySelector('#un-ap-list');
  const hintEl      = containerEl.querySelector('#un-ap-hint');
  const inputEl     = containerEl.querySelector('#un-ap-input');
  const suggestEl   = containerEl.querySelector('#un-ap-suggest');
  const sentinelRow = containerEl.querySelector('#un-ap-sentinel-row');

  // ── State ────────────────────────────────────────────────────────────
  let searchSeq        = 0;
  let suggestHighlight = -1;
  let debounceTimer    = null;

  // ── Sentinel helpers ─────────────────────────────────────────────────
  /** True iff the current actress list consists solely of sentinels. */
  function _listIsSolelysentinel() {
    return editorState.actresses.length > 0
        && editorState.actresses.every(a => _sentinelIds.has(a.id));
  }

  /** Replace entire actress list with a single sentinel entry. */
  function _replacWithSentinel(sentinel) {
    editorState.actresses = [{
      id:            sentinel.id,
      canonicalName: sentinel.canonicalName,
      stageName:     sentinel.stageName || null,
      primary:       true,
      isNew:         false,
    }];
    hideSuggest();
    if (inputEl) inputEl.value = '';
    renderActresses();
    onChange?.();
  }

  /** Render the placeholder sentinel buttons (called once sentinels are fetched). */
  function _renderSentinelButtons() {
    if (!sentinelRow) return;
    // Remove any previously rendered buttons (re-entrant guard)
    sentinelRow.querySelectorAll('.un-actress-sentinel-btn').forEach(b => b.remove());
    _sentinels.forEach(s => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn btn-secondary btn-sm un-actress-sentinel-btn';
      btn.textContent = s.canonicalName;
      btn.addEventListener('click', () => _replacWithSentinel(s));
      sentinelRow.appendChild(btn);
    });
    if (_sentinels.length > 0) sentinelRow.style.display = 'flex';
  }

  // ── Render actress list ──────────────────────────────────────────────
  function renderActresses() {
    listEl.innerHTML = '';
    const count = editorState.actresses.length;
    editorState.actresses.forEach((a, idx) => {
      const li = document.createElement('li');
      li.className = 'un-actress-item';

      // Primary star button
      const starBtn = document.createElement('button');
      starBtn.type = 'button';
      starBtn.className = 'un-actress-star' + (a.primary ? ' active' : '');
      starBtn.textContent = a.primary ? '★' : '☆';
      starBtn.disabled = isDuplicate;
      starBtn.title = isDuplicate ? 'Locked (duplicate)'
                                  : (a.primary ? 'Primary actress' : 'Set as primary');
      starBtn.addEventListener('click', () => {
        if (!isDuplicate) setPrimary(idx);
      });

      // Name
      const nameEl = document.createElement('span');
      nameEl.className = 'un-actress-name';
      nameEl.textContent = a.canonicalName;

      // New badge
      if (a.isNew) {
        const badge = document.createElement('span');
        badge.className = 'un-actress-new-badge';
        badge.textContent = 'new';
        li.appendChild(starBtn);
        li.appendChild(nameEl);
        li.appendChild(badge);
      } else {
        li.appendChild(starBtn);
        li.appendChild(nameEl);
      }

      // Remove button
      const removeBtn = document.createElement('button');
      removeBtn.type = 'button';
      removeBtn.className = 'un-actress-remove';
      removeBtn.textContent = '×';
      removeBtn.disabled = isDuplicate || count <= 1;
      removeBtn.title = isDuplicate ? 'Locked (duplicate)'
                                    : (count <= 1 ? 'At least one actress required' : 'Remove');
      removeBtn.addEventListener('click', () => {
        if (!isDuplicate && count > 1) removeActress(idx);
      });
      li.appendChild(removeBtn);

      listEl.appendChild(li);
    });
    renderHint();
  }

  function renderHint() {
    if (isDuplicate) { hintEl.style.display = 'none'; return; }
    const count = editorState.actresses.length;
    const hasPrimary = editorState.actresses.some(a => a.primary);
    let msg = '';
    if (count === 0)    msg = 'At least one actress is required before you can save.';
    else if (!hasPrimary) msg = 'Mark a primary actress (★). The folder will be renamed after her.';
    if (msg) {
      hintEl.textContent = msg;
      hintEl.style.display = 'block';
    } else {
      hintEl.style.display = 'none';
    }
  }

  function setPrimary(idx) {
    editorState.actresses.forEach((a, i) => { a.primary = (i === idx); });
    renderActresses();
    onChange?.();
  }

  function removeActress(idx) {
    const removed = editorState.actresses[idx];
    editorState.actresses.splice(idx, 1);
    if (removed.primary && editorState.actresses.length > 0) {
      // Clear primary — user must explicitly re-pick
      editorState.actresses.forEach(a => { a.primary = false; });
    }
    renderActresses();
    onChange?.();
  }

  function addExisting(hit) {
    // If the picked actress is itself a sentinel, treat like a placeholder button.
    if (_sentinelIds.has(hit.id)) {
      const sentinel = _sentinels.find(s => s.id === hit.id) || hit;
      _replacWithSentinel(sentinel);
      return;
    }
    // If the list currently consists solely of sentinels, clear first
    // (real actress replaces placeholder).
    if (_listIsSolelysentinel()) {
      editorState.actresses = [];
    }
    const hadPrimary = editorState.actresses.some(a => a.primary);
    editorState.actresses.push({
      id:            hit.id,
      canonicalName: hit.canonicalName,
      stageName:     hit.stageName,
      primary:       !hadPrimary,
      isNew:         false,
    });
    if (inputEl) inputEl.value = '';
    hideSuggest();
    renderActresses();
    onChange?.();
  }

  function addDraft(name) {
    const trimmed = name.trim();
    if (!trimmed) return;
    // If the list currently consists solely of sentinels, clear first.
    if (_listIsSolelysentinel()) {
      editorState.actresses = [];
    }
    const hadPrimary = editorState.actresses.some(a => a.primary);
    editorState.actresses.push({
      id:            null,
      newName:       trimmed,
      canonicalName: trimmed,
      primary:       !hadPrimary,
      isNew:         true,
    });
    if (inputEl) inputEl.value = '';
    hideSuggest();
    renderActresses();
    onChange?.();
  }

  // ── Typeahead ────────────────────────────────────────────────────────
  if (!isDuplicate && inputEl && suggestEl) {
    inputEl.addEventListener('input', () => {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(runSearch, 150);
    });

    inputEl.addEventListener('keydown', e => {
      const items = Array.from(suggestEl.querySelectorAll('.un-actress-suggest-item:not(.un-suggest-disabled)'));
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (!items.length) return;
        suggestHighlight = (suggestHighlight + 1) % items.length;
        updateHighlight(items);
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (!items.length) return;
        suggestHighlight = (suggestHighlight - 1 + items.length) % items.length;
        updateHighlight(items);
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (suggestHighlight >= 0 && items[suggestHighlight]) items[suggestHighlight].click();
      } else if (e.key === 'Escape') {
        hideSuggest();
      }
    });
  }

  function updateHighlight(items) {
    suggestEl?.querySelectorAll('.un-actress-suggest-item').forEach(el => el.classList.remove('un-suggest-highlight'));
    if (items[suggestHighlight]) items[suggestHighlight].classList.add('un-suggest-highlight');
  }

  async function runSearch() {
    if (!inputEl) return;
    const q = inputEl.value.trim();
    if (q.length < 1) { hideSuggest(); return; }
    const seq = ++searchSeq;
    try {
      const res = await fetch(`/api/unsorted/actresses/search?q=${encodeURIComponent(q)}&limit=10`);
      if (seq !== searchSeq) return;  // stale response
      const hits = await res.json();
      renderSuggest(hits, q);
    } catch (err) {
      console.error('[actress-pane] search failed', err);
    }
  }

  function renderSuggest(hits, query) {
    if (!suggestEl) return;
    suggestEl.innerHTML = '';
    suggestHighlight = -1;

    const takenIds   = new Set(editorState.actresses.filter(a => a.id != null).map(a => a.id));
    const takenNames = new Set(editorState.actresses.map(a => a.canonicalName.toLowerCase()));

    hits.forEach(h => {
      const alreadyAdded = takenIds.has(h.id) || takenNames.has((h.canonicalName || '').toLowerCase());
      const item = document.createElement('div');
      item.className = 'un-actress-suggest-item' + (alreadyAdded ? ' un-suggest-disabled' : '');

      const thumb = h.coverUrl
          ? `<div class="un-suggest-thumb" style="background-image:url(${esc(h.coverUrl)})"></div>`
          : `<div class="un-suggest-thumb un-suggest-thumb-empty"></div>`;

      const stage = h.stageName
          ? `<span class="un-suggest-stage">${highlight(h.stageName, query)}</span>` : '';
      const alias = h.matchedAlias
          ? `<span class="un-suggest-alias">a.k.a. ${highlight(h.matchedAlias, query)}</span>` : '';
      const tier  = h.tier
          ? `<span class="un-suggest-tier un-tier-${esc((h.tier || '').toLowerCase())}">${esc(h.tier)}</span>` : '';
      const count = h.titleCount > 0
          ? `<span class="un-suggest-count">${h.titleCount}</span>` : '';
      const addedBadge = alreadyAdded
          ? `<span class="un-suggest-added">already added</span>` : '';

      item.innerHTML = thumb
          + '<div class="un-suggest-body">'
          +   `<div class="un-suggest-row1">`
          +     `<span class="un-suggest-name">${highlight(h.canonicalName, query)}</span>`
          +     stage + addedBadge
          +   `</div>`
          + (alias || tier || count
              ? `<div class="un-suggest-row2">${alias}${tier}${count}</div>`
              : '')
          + '</div>';

      if (!alreadyAdded) {
        item.addEventListener('click', () => addExisting(h));
      }
      suggestEl.appendChild(item);
    });

    // Inline create option (when no exact match and name not already added)
    const liveHits   = hits.filter(h => !takenIds.has(h.id) && !takenNames.has((h.canonicalName || '').toLowerCase()));
    const exactMatch = liveHits.some(h => (h.canonicalName || '').toLowerCase() === query.toLowerCase());
    if (!exactMatch && !takenNames.has(query.toLowerCase())) {
      const create = document.createElement('div');
      create.className = 'un-actress-suggest-item un-suggest-create';
      create.innerHTML = `<div class="un-suggest-thumb un-suggest-thumb-empty"></div>`
                       + `<div class="un-suggest-body"><span class="un-suggest-name">+ Create "${esc(query)}"</span></div>`;
      create.addEventListener('click', () => addDraft(query));
      suggestEl.appendChild(create);
    }

    if (suggestEl.children.length > 0) suggestEl.style.display = 'block';
    else hideSuggest();
  }

  function hideSuggest() {
    if (!suggestEl) return;
    suggestEl.style.display = 'none';
    suggestEl.innerHTML = '';
    suggestHighlight = -1;
  }

  // Close dropdown on outside click
  function onDocClick(e) {
    if (!suggestEl || !inputEl) return;
    if (!suggestEl.contains(e.target) && e.target !== inputEl) hideSuggest();
  }
  document.addEventListener('click', onDocClick);

  // Initial render
  renderActresses();

  // ── Public API ────────────────────────────────────────────────────────
  function destroy() {
    clearTimeout(debounceTimer);
    document.removeEventListener('click', onDocClick);
  }

  return { destroy, renderActresses };
}
