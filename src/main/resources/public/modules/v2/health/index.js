// health/index.js — Entry point for v2 Health workbench page.
// Two tabs: Library | Tags.
// Sync Health was merged into the Volumes page (commit a1226d7).
//
// Usage: mountHealth(document.getElementById('app-body'))

import { mountLibrary } from './library.js';
import { mountTags }    from './tags.js';

const TABS = [
  { id: 'library', label: 'Library' },
  { id: 'tags',    label: 'Tags'    },
];

// Track which tabs have been mounted (lazy: only mount on first visit).
const mounted = {};

export async function mountHealth(rootEl) {
  rootEl.innerHTML = `
    <div class="wb-page">
      <div class="wb-page-head">
        <h1 class="wb-page-title">Health</h1>
        <p class="wb-page-subtitle">Library diagnostics, tag maintenance, and reconciliation. <a class="hl-sync-link" href="/v2-volumes.html">See Sync Health inside Volumes →</a></p>
      </div>

      <div class="tabs" role="tablist">
        ${TABS.map((t, i) => `
          <button class="tab${i === 0 ? ' active' : ''}" role="tab" data-tab="${t.id}">
            ${t.label}
          </button>
        `).join('')}
      </div>

      ${TABS.map((t, i) => `
        <div class="tab-panel${i === 0 ? ' active' : ''}" data-panel="${t.id}"></div>
      `).join('')}
    </div>
  `;

  const panels = Object.fromEntries(
    TABS.map(t => [t.id, rootEl.querySelector(`[data-panel="${t.id}"]`)])
  );

  const activate = async (id) => {
    rootEl.querySelectorAll('.tab').forEach(b =>
      b.classList.toggle('active', b.dataset.tab === id)
    );
    rootEl.querySelectorAll('.tab-panel').forEach(p =>
      p.classList.toggle('active', p.dataset.panel === id)
    );
    location.hash = id;
    if (!mounted[id]) {
      mounted[id] = true;
      if (id === 'library') await mountLibrary(panels.library);
      if (id === 'tags')    await mountTags(panels.tags);
    }
  };

  rootEl.querySelector('.tabs').addEventListener('click', (e) => {
    const btn = e.target.closest('.tab');
    if (!btn) return;
    activate(btn.dataset.tab);
  });

  // Restore from URL hash on first load.
  const initial  = location.hash.replace('#', '');
  const startTab = TABS.find(t => t.id === initial)?.id || 'library';
  await activate(startTab);
}
