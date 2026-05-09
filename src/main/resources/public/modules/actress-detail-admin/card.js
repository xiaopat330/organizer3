// Edit Card renderer — Phase 2b stub.
//
// Phase 2c expands this into the full §4 Edit Card (header + flags row +
// section stubs + Commit footer). For now we render just enough to prove
// the lifecycle pipeline works end-to-end.

import { esc } from '../utils.js';

export function renderCard(title) {
  const en = title.titleEnglish || title.titleOriginalEn;
  const ja = title.titleOriginal;
  const titleEn = en ? `<div class="admin-card-title">${esc(en)}</div>` : '';
  const titleJa = ja ? `<div class="admin-card-title-ja">${esc(ja)}</div>` : '';
  const rejected = title.rejected ? '<span class="admin-card-reject-badge">⨯ REJECTED</span>' : '';

  return `
    <div class="admin-edit-card${title.rejected ? ' admin-edit-card-rejected' : ''}" data-code="${esc(title.code)}">
      <div class="admin-card-header-stub">
        <span class="admin-card-code">${esc(title.code)}</span>
        ${rejected}
      </div>
      ${titleEn}
      ${titleJa}
      <div class="admin-card-body-stub">
        <em>edit card body — Phase 2c</em>
      </div>
    </div>
  `;
}
