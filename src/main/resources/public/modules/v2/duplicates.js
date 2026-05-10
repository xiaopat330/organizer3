/* ─────────────────────────────────────────────────────────────────────
   Wave 3 — Duplicates triage (workbench mode)
   Spec: spec/DESIGN_SYSTEM_PAGES.md §2.x  + mockup View 3
   Group-led layout: each title with multiple locations is one card;
   members shown side-by-side; per-member 3-state Keep/Trash/Variant
   wired to PUT /api/tools/duplicates/decisions.
   Deferred: bulk-apply ("execute trash for all 'TRASH'-decided"),
   diff-section (h.265 vs h.264 file sizes), confirm modal before action.
   ───────────────────────────────────────────────────────────────────── */

const PAGE_LIMIT = 24;

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
    console.warn('[duplicates] fetch failed:', url, e);
    return fallback;
  }
}

async function putDecision({ titleCode, volumeId, nasPath, decision }) {
  const r = await fetch('/api/tools/duplicates/decisions', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ titleCode, volumeId, nasPath, decision }),
  });
  return r.ok;
}

async function deleteDecision(titleCode, volumeId, nasPath) {
  const url = `/api/tools/duplicates/decisions/${encodeURIComponent(titleCode)}/${encodeURIComponent(volumeId)}?nasPath=${encodeURIComponent(nasPath)}`;
  const r = await fetch(url, { method: 'DELETE' });
  return r.ok;
}

function renderMember(titleCode, entry, decision) {
  const stateClass = decision ? decision.toLowerCase() : '';
  return `
    <div class="dup-member ${stateClass}" data-vol="${escapeHtml(entry.volumeId)}" data-path="${escapeHtml(entry.nasPath)}">
      ${decision ? `<span class="dup-member-state">${escapeHtml(decision)}</span>` : ''}
      <div class="dup-member-volume">${escapeHtml(entry.volumeId)}</div>
      <div class="dup-member-path" title="${escapeHtml(entry.nasPath)}">${escapeHtml(entry.locPath || entry.nasPath)}</div>
      <div class="dup-decision" role="radiogroup" aria-label="Decision for ${escapeHtml(titleCode)} on ${escapeHtml(entry.volumeId)}">
        <button data-d="KEEP"    class="${decision === 'KEEP' ? 'active' : ''}">Keep</button>
        <button data-d="TRASH"   class="${decision === 'TRASH' ? 'active' : ''}">Trash</button>
        <button data-d="VARIANT" class="${decision === 'VARIANT' ? 'active' : ''}">Variant</button>
      </div>
    </div>
  `;
}

function summarizeGroup(decisions) {
  const c = { KEEP: 0, TRASH: 0, VARIANT: 0, undecided: 0 };
  decisions.forEach(d => { c[d || 'undecided']++; });
  const parts = [];
  if (c.KEEP)      parts.push(`${c.KEEP} keep`);
  if (c.TRASH)     parts.push(`${c.TRASH} trash`);
  if (c.VARIANT)   parts.push(`${c.VARIANT} variant`);
  if (c.undecided) parts.push(`${c.undecided} undecided`);
  return parts.join(' · ');
}

function renderGroup(title, decisionsByPath) {
  const code  = title.code;
  const name  = title.titleEnglish || title.titleOriginalEn || title.titleOriginal || code;
  const entries = title.locationEntries || [];
  const decisions = entries.map(e => decisionsByPath[e.nasPath] || null);
  return `
    <article class="dup-group" data-code="${escapeHtml(code)}">
      <header class="dup-group-head">
        <span class="dup-group-code">${escapeHtml(code)}</span>
        <span class="dup-group-name">${escapeHtml(name)}</span>
        <span class="dup-group-count">${entries.length} copies</span>
      </header>
      <div class="dup-group-members">
        ${entries.map((e, i) => renderMember(code, e, decisions[i])).join('')}
      </div>
      <footer class="dup-group-footer">
        <span class="summary">${escapeHtml(summarizeGroup(decisions))}</span>
        <div class="actions">
          <!-- Reserved for future bulk-apply trigger -->
        </div>
      </footer>
    </article>
  `;
}

async function loadDecisionsFor(code) {
  const list = await fetchJson(`/api/titles/${encodeURIComponent(code)}/duplicate-decisions`, []);
  const byPath = {};
  for (const d of list) byPath[d.nasPath] = d.decision;
  return byPath;
}

export async function mountDuplicates(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <h1 class="wb-page-title">Duplicates triage</h1>
      <div class="wb-page-subtitle">Titles with more than one location across volumes. Decide per-member; nothing is destroyed until you apply.</div>

      <div class="filter-bar">
        <div class="filter-group" id="vol-chips">
          <span class="chip on" data-vol="">All volumes</span>
        </div>
        <div class="filter-spacer"></div>
        <div class="filter-meta" id="result-meta"></div>
      </div>

      <div id="groups"></div>

      <div class="grid-status" id="grid-status"><div class="shelf-loading">Loading…</div></div>
      <div id="sentinel" style="height:1px"></div>
    </div>
  `;

  const groupsEl = rootEl.querySelector('#groups');
  const status   = rootEl.querySelector('#grid-status');
  const meta     = rootEl.querySelector('#result-meta');
  const sentinel = rootEl.querySelector('#sentinel');
  const volChips = rootEl.querySelector('#vol-chips');

  const state = {
    volumeId: '',
    offset: 0,
    loading: false,
    exhausted: false,
    total: 0,
    seen: 0,
  };

  // Populate volume chips from existing volumes API (graceful fallback if missing)
  fetchJson('/api/tools/volumes', []).then(vols => {
    if (!Array.isArray(vols) || vols.length === 0) return;
    vols.forEach(v => {
      const id = v.id || v.volumeId || v;
      if (!id) return;
      const chip = document.createElement('span');
      chip.className = 'chip';
      chip.dataset.vol = id;
      chip.textContent = id;
      volChips.appendChild(chip);
    });
  });

  const reset = () => {
    state.offset = 0;
    state.exhausted = false;
    state.total = 0;
    state.seen = 0;
    groupsEl.innerHTML = '';
    meta.textContent = '';
  };

  const loadMore = async () => {
    if (state.loading || state.exhausted) return;
    state.loading = true;
    status.innerHTML = '<div class="shelf-loading">Loading…</div>';

    const url = `/api/tools/duplicates?offset=${state.offset}&limit=${PAGE_LIMIT}` +
                (state.volumeId ? `&volumeId=${encodeURIComponent(state.volumeId)}` : '');
    const data = await fetchJson(url, { titles: [], total: 0 });
    const titles = data.titles || [];
    state.total = data.total || titles.length;

    if (titles.length === 0 && state.seen === 0) {
      status.innerHTML = `
        <div class="empty-state">
          <div class="empty-state-title">No duplicate groups</div>
          <div class="empty-state-body">No titles currently have more than one location.</div>
        </div>`;
      state.exhausted = true;
      state.loading = false;
      return;
    }

    // Fetch decisions for each title in parallel, then render
    const decisionsArr = await Promise.all(titles.map(t => loadDecisionsFor(t.code)));
    const html = titles.map((t, i) => renderGroup(t, decisionsArr[i])).join('');
    groupsEl.insertAdjacentHTML('beforeend', html);

    state.seen   += titles.length;
    state.offset += titles.length;
    meta.textContent = `${state.seen} of ${state.total} groups`;

    if (titles.length < PAGE_LIMIT || state.seen >= state.total) {
      state.exhausted = true;
      status.innerHTML = `<div class="shelf-loading">End of results.</div>`;
    } else {
      status.innerHTML = '';
    }
    state.loading = false;
  };

  // Decision-button delegation: clicks anywhere in #groups
  groupsEl.addEventListener('click', async (e) => {
    const btn = e.target.closest('.dup-decision button');
    if (!btn) return;

    const memberEl = btn.closest('.dup-member');
    const groupEl  = btn.closest('.dup-group');
    if (!memberEl || !groupEl) return;

    const code     = groupEl.dataset.code;
    const volumeId = memberEl.dataset.vol;
    const nasPath  = memberEl.dataset.path;
    const decision = btn.dataset.d;

    // Toggle off if already active
    const currentlyActive = btn.classList.contains('active');
    const ok = currentlyActive
      ? await deleteDecision(code, volumeId, nasPath)
      : await putDecision({ titleCode: code, volumeId, nasPath, decision });

    if (!ok) {
      console.warn('[duplicates] decision update failed');
      return;
    }

    // Update local state
    const buttons = memberEl.querySelectorAll('.dup-decision button');
    buttons.forEach(b => b.classList.remove('active'));
    memberEl.classList.remove('keep', 'trash', 'variant');
    const stateBadge = memberEl.querySelector('.dup-member-state');
    if (currentlyActive) {
      // Cleared
      if (stateBadge) stateBadge.remove();
    } else {
      btn.classList.add('active');
      memberEl.classList.add(decision.toLowerCase());
      if (stateBadge) {
        stateBadge.textContent = decision;
      } else {
        const badge = document.createElement('span');
        badge.className = 'dup-member-state';
        badge.textContent = decision;
        memberEl.insertBefore(badge, memberEl.firstChild);
      }
    }

    // Update group summary line
    const memberStates = Array.from(groupEl.querySelectorAll('.dup-member')).map(m => {
      if (m.classList.contains('keep'))    return 'KEEP';
      if (m.classList.contains('trash'))   return 'TRASH';
      if (m.classList.contains('variant')) return 'VARIANT';
      return null;
    });
    const summaryEl = groupEl.querySelector('.dup-group-footer .summary');
    if (summaryEl) summaryEl.textContent = summarizeGroup(memberStates);
  });

  // Volume chip wiring
  volChips.addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    volChips.querySelectorAll('.chip').forEach(c => c.classList.remove('on'));
    chip.classList.add('on');
    state.volumeId = chip.dataset.vol;
    reset(); loadMore();
  });

  // Infinite scroll
  const io = new IntersectionObserver((entries) => {
    if (entries.some(e => e.isIntersecting)) loadMore();
  }, { rootMargin: '400px' });
  io.observe(sentinel);

  loadMore();
}
