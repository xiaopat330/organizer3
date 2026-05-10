/* ─────────────────────────────────────────────────────────────────────
   Volume picker — reusable primitive for workbench surfaces scoped
   per-volume (Trash, Duplicates, Volumes, Sync Health, …).
   Compact trigger button + popover with search and item counts.
   Selection persists per-page in localStorage.
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
  } catch (e) { return fallback; }
}

/**
 * @param {object} opts
 * @param {HTMLElement} opts.rootEl — anchor element to mount the picker into
 * @param {string} opts.storageKey — localStorage key for selection persistence (e.g. 'v2.trash.volume')
 * @param {(volumeId: string|null) => void} opts.onChange — fires with '' for "All", or volume id
 * @param {(volumeId: string) => Promise<number|null>} [opts.getCount] — optional per-volume count fetcher
 * @param {string} [opts.allLabel] — label for "all volumes" entry (default "All volumes"). Pass null/'' to omit.
 * @param {string} [opts.volumesUrl] — endpoint returning volume list (default '/api/tools/volumes')
 */
export async function createVolumePicker(opts) {
  const {
    rootEl,
    storageKey,
    onChange,
    getCount = null,
    allLabel = 'All volumes',
    volumesUrl = '/api/tools/volumes',
  } = opts;
  if (!rootEl) throw new Error('createVolumePicker: rootEl required');

  // Build trigger + popover scaffold
  rootEl.innerHTML = `
    <div class="volpick">
      <button class="volpick-trigger" type="button" aria-haspopup="listbox" aria-expanded="false">
        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8">
          <ellipse cx="12" cy="5" rx="9" ry="3"/>
          <path d="M21 5v6c0 1.66-4 3-9 3s-9-1.34-9-3V5"/>
          <path d="M21 11v6c0 1.66-4 3-9 3s-9-1.34-9-3v-6"/>
        </svg>
        <span class="volpick-label">…</span>
        <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
      </button>
      <div class="volpick-popover" hidden role="listbox">
        <div class="volpick-search">
          <input type="text" placeholder="Search volumes…" autocomplete="off" spellcheck="false">
        </div>
        <div class="volpick-list"></div>
      </div>
    </div>
  `;

  const trigger  = rootEl.querySelector('.volpick-trigger');
  const labelEl  = rootEl.querySelector('.volpick-label');
  const popover  = rootEl.querySelector('.volpick-popover');
  const searchEl = rootEl.querySelector('.volpick-search input');
  const listEl   = rootEl.querySelector('.volpick-list');

  const vols = (await fetchJson(volumesUrl, [])) || [];
  const volumeIds = vols.map(v => (v.id || v.volumeId || v));

  // Counts: lazily resolved per-volume, then cached
  const counts = {};
  let totalCount = null;

  // Selection — restore from localStorage, default to '' (all)
  // When allLabel is falsy, "" isn't valid — auto-pick the first volume.
  let selected = '';
  try { selected = localStorage.getItem(storageKey) ?? ''; } catch (e) {}
  if (selected && !volumeIds.includes(selected)) selected = '';
  if (!selected && !allLabel && volumeIds.length > 0) selected = volumeIds[0];

  const renderTrigger = () => {
    labelEl.textContent = selected || (allLabel || 'All');
  };

  const renderList = (filter = '') => {
    const q = filter.trim().toLowerCase();
    const items = [];
    if (allLabel) {
      const total = totalCount != null ? totalCount : '';
      items.push(`
        <div class="volpick-item${selected === '' ? ' selected' : ''}" data-vol="">
          <span class="volpick-check">${selected === '' ? '✓' : ''}</span>
          <span class="volpick-name">${escapeHtml(allLabel)}</span>
          <span class="volpick-count">${escapeHtml(total === '' ? '' : String(total))}</span>
        </div>
      `);
    }
    for (const id of volumeIds) {
      if (q && !id.toLowerCase().includes(q)) continue;
      const c = counts[id];
      items.push(`
        <div class="volpick-item${selected === id ? ' selected' : ''}" data-vol="${escapeHtml(id)}">
          <span class="volpick-check">${selected === id ? '✓' : ''}</span>
          <span class="volpick-name">${escapeHtml(id)}</span>
          <span class="volpick-count">${c == null ? '' : escapeHtml(String(c))}</span>
        </div>
      `);
    }
    if (items.length === 0) {
      listEl.innerHTML = `<div class="volpick-empty">No matches.</div>`;
    } else {
      listEl.innerHTML = items.join('');
    }
  };

  // Lazy-load counts when popover opens
  const ensureCounts = async () => {
    if (!getCount) return;
    const pending = [];
    for (const id of volumeIds) {
      if (counts[id] == null) {
        pending.push(getCount(id).then(n => { counts[id] = (n == null ? null : n); }));
      }
    }
    await Promise.all(pending);
    totalCount = volumeIds.reduce((s, id) => s + (counts[id] || 0), 0);
    renderList(searchEl.value);
  };

  const open = async () => {
    popover.hidden = false;
    trigger.setAttribute('aria-expanded', 'true');
    renderList();
    requestAnimationFrame(() => searchEl.focus());
    ensureCounts();
  };
  const close = () => {
    popover.hidden = true;
    trigger.setAttribute('aria-expanded', 'false');
    searchEl.value = '';
  };

  trigger.addEventListener('click', () => {
    popover.hidden ? open() : close();
  });

  searchEl.addEventListener('input', () => renderList(searchEl.value));

  listEl.addEventListener('click', (e) => {
    const item = e.target.closest('.volpick-item');
    if (!item) return;
    const v = item.dataset.vol;
    selected = v;
    try { localStorage.setItem(storageKey, v); } catch (e) {}
    renderTrigger();
    close();
    onChange(v);
  });

  // Click-outside to close
  document.addEventListener('click', (e) => {
    if (popover.hidden) return;
    if (!rootEl.contains(e.target)) close();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !popover.hidden) close();
  });

  renderTrigger();

  // Fire initial selection so caller can load
  onChange(selected);

  return {
    getValue: () => selected,
    setValue: (v) => { selected = v; renderTrigger(); onChange(v); },
  };
}
