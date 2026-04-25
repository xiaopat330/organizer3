import { esc } from './utils.js';

// ── State ─────────────────────────────────────────────────────────────────

function createState() {
  return {
    actresses: [],
    selectedId: null,
    activeTab: 'titles',
    queuePollTimer: null,
  };
}

const state = createState();

// ── DOM refs ──────────────────────────────────────────────────────────────

const view         = document.getElementById('tools-javdb-discovery-view');
const queueBadge   = document.getElementById('jd-queue-badge');
const actressList  = document.getElementById('jd-actress-list');
const emptyMsg     = document.getElementById('jd-empty');
const panel        = document.getElementById('jd-actress-panel');
const subtabBtns   = panel?.querySelectorAll('.jd-subtab') ?? [];
const titlesView   = document.getElementById('jd-subview-titles');
const profileView  = document.getElementById('jd-subview-profile');

// ── Public API ─────────────────────────────────────────────────────────────

export async function showJavdbDiscoveryView() {
  view.style.display = '';
  await Promise.all([loadActresses(), refreshQueue()]);
  startQueuePoll();
}

export function hideJavdbDiscoveryView() {
  view.style.display = 'none';
  stopQueuePoll();
}

// ── Data loading ───────────────────────────────────────────────────────────

async function loadActresses() {
  try {
    const res = await fetch('/api/javdb/discovery/actresses');
    if (!res.ok) return;
    state.actresses = await res.json();
    renderActressList();
  } catch (_) { /* network error — ignore */ }
}

async function refreshQueue() {
  try {
    const res = await fetch('/api/javdb/discovery/queue');
    if (!res.ok) return;
    const { pending, inFlight, failed } = await res.json();
    const total = pending + inFlight + failed;
    if (total === 0) {
      queueBadge.style.display = 'none';
    } else {
      queueBadge.textContent = `${pending + inFlight} pending · ${failed} failed`;
      queueBadge.style.display = '';
    }
  } catch (_) { /* ignore */ }
}

function startQueuePoll() {
  stopQueuePoll();
  state.queuePollTimer = setInterval(refreshQueue, 10_000);
}

function stopQueuePoll() {
  if (state.queuePollTimer !== null) {
    clearInterval(state.queuePollTimer);
    state.queuePollTimer = null;
  }
}

// ── Actress list rendering ─────────────────────────────────────────────────

function renderActressList() {
  actressList.innerHTML = '';
  for (const a of state.actresses) {
    const li = document.createElement('li');
    li.className = 'jd-actress-item';
    li.dataset.id = a.id;

    const enrichedPct = a.totalTitles > 0
      ? Math.round((a.enrichedTitles / a.totalTitles) * 100)
      : 0;

    const statusDot = actressStatusDot(a.actressStatus);

    li.innerHTML = `
      <span class="jd-actress-name">${statusDot}${esc(a.canonicalName)}</span>
      <span class="jd-actress-counts">${a.enrichedTitles}/${a.totalTitles} (${enrichedPct}%)</span>
    `;
    li.addEventListener('click', () => selectActress(a.id));
    actressList.appendChild(li);
  }

  if (state.selectedId !== null) {
    highlightSelected(state.selectedId);
  }
}

function actressStatusDot(status) {
  if (status === 'fetched')   return '<span class="jd-dot jd-dot-fetched" title="Profile fetched"></span>';
  if (status === 'slug_only') return '<span class="jd-dot jd-dot-slug" title="Slug only"></span>';
  return '<span class="jd-dot jd-dot-none" title="No staging row"></span>';
}

function highlightSelected(id) {
  actressList.querySelectorAll('.jd-actress-item').forEach(li => {
    li.classList.toggle('selected', Number(li.dataset.id) === id);
  });
}

// ── Actress selection ──────────────────────────────────────────────────────

async function selectActress(id) {
  state.selectedId = id;
  highlightSelected(id);
  emptyMsg.style.display = 'none';
  panel.style.display = '';
  await renderActiveTab();
}

async function renderActiveTab() {
  if (state.activeTab === 'titles') {
    await renderTitlesTab();
  } else {
    await renderProfileTab();
  }
}

// ── Titles tab ─────────────────────────────────────────────────────────────

async function renderTitlesTab() {
  titlesView.style.display  = '';
  profileView.style.display = 'none';
  titlesView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/titles`);
    if (!res.ok) { titlesView.innerHTML = '<div class="jd-error">Failed to load titles.</div>'; return; }
    const titles = await res.json();
    if (titles.length === 0) {
      titlesView.innerHTML = '<div class="jd-empty-tab">No titles found.</div>';
      return;
    }
    titlesView.innerHTML = `
      <table class="jd-titles-table">
        <thead><tr>
          <th>Code</th><th>Status</th><th>Original Title</th><th>Release</th><th>Maker</th>
        </tr></thead>
        <tbody>${titles.map(titleRow).join('')}</tbody>
      </table>
    `;
  } catch (_) {
    titlesView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

function titleRow(t) {
  const status = t.status
    ? `<span class="jd-status jd-status-${esc(t.status)}">${esc(t.status)}</span>`
    : '<span class="jd-status jd-status-none">—</span>';
  return `<tr>
    <td class="jd-code">${esc(t.code)}</td>
    <td>${status}</td>
    <td>${t.titleOriginal ? esc(t.titleOriginal) : '—'}</td>
    <td>${t.releaseDate ? esc(t.releaseDate) : '—'}</td>
    <td>${t.maker ? esc(t.maker) : '—'}</td>
  </tr>`;
}

// ── Profile tab ────────────────────────────────────────────────────────────

async function renderProfileTab() {
  profileView.style.display = '';
  titlesView.style.display  = 'none';
  profileView.innerHTML = '<div class="jd-loading">Loading…</div>';
  try {
    const res = await fetch(`/api/javdb/discovery/actresses/${state.selectedId}/profile`);
    if (res.status === 404) {
      profileView.innerHTML = '<div class="jd-empty-tab">No staging profile yet.</div>';
      return;
    }
    if (!res.ok) { profileView.innerHTML = '<div class="jd-error">Failed to load profile.</div>'; return; }
    const p = await res.json();
    profileView.innerHTML = `
      <div class="jd-profile">
        ${p.avatarUrl ? `<img class="jd-avatar" src="${esc(p.avatarUrl)}" alt="avatar">` : ''}
        <dl class="jd-profile-fields">
          <dt>Slug</dt><dd>${p.javdbSlug ? esc(p.javdbSlug) : '—'}</dd>
          <dt>Status</dt><dd><span class="jd-status jd-status-${esc(p.status ?? 'none')}">${esc(p.status ?? '—')}</span></dd>
          <dt>Fetched at</dt><dd>${p.rawFetchedAt ? esc(p.rawFetchedAt) : '—'}</dd>
          <dt>Title count</dt><dd>${p.titleCount != null ? p.titleCount : '—'}</dd>
          <dt>Twitter</dt><dd>${p.twitterHandle ? esc(p.twitterHandle) : '—'}</dd>
          <dt>Instagram</dt><dd>${p.instagramHandle ? esc(p.instagramHandle) : '—'}</dd>
          ${p.nameVariantsJson ? `<dt>Name variants</dt><dd class="jd-variants">${esc(p.nameVariantsJson)}</dd>` : ''}
        </dl>
      </div>
    `;
  } catch (_) {
    profileView.innerHTML = '<div class="jd-error">Network error.</div>';
  }
}

// ── Subtab wiring ──────────────────────────────────────────────────────────

subtabBtns.forEach(btn => {
  btn.addEventListener('click', async () => {
    subtabBtns.forEach(b => b.classList.remove('selected'));
    btn.classList.add('selected');
    state.activeTab = btn.dataset.tab;
    if (state.selectedId !== null) await renderActiveTab();
  });
});
