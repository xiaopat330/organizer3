// Lightweight overlay that shows a full-size cover image for a title code.
// Mounts/unmounts a single global modal element; click backdrop or Esc to close.

let _backdrop = null;
let _keyHandler = null;

function close() {
  if (_backdrop) {
    _backdrop.remove();
    _backdrop = null;
  }
  if (_keyHandler) {
    document.removeEventListener('keydown', _keyHandler);
    _keyHandler = null;
  }
}

/**
 * Opens the cover modal for `code` with image at `url`.
 * Caller must verify the URL exists before calling — non-clickable codes
 * should not invoke this.
 */
export function openCoverModal(code, url) {
  close();

  _backdrop = document.createElement('div');
  _backdrop.className = 'cover-modal-backdrop';
  _backdrop.innerHTML = `
    <div class="cover-modal">
      <div class="cover-modal-header">
        <span class="cover-modal-code">${code}</span>
        <button type="button" class="cover-modal-close" aria-label="Close">×</button>
      </div>
      <div class="cover-modal-body">
        <img class="cover-modal-img" src="${url}" alt="Cover for ${code}" />
      </div>
    </div>
  `;
  document.body.appendChild(_backdrop);

  _backdrop.addEventListener('click', (e) => {
    if (e.target === _backdrop) close();
  });
  _backdrop.querySelector('.cover-modal-close').addEventListener('click', close);
  // If image fails to load, swap in a friendly message.
  _backdrop.querySelector('.cover-modal-img').addEventListener('error', () => {
    const body = _backdrop.querySelector('.cover-modal-body');
    if (body) body.innerHTML = `<div class="cover-modal-empty">No cover available for ${code}.</div>`;
  });

  _keyHandler = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', _keyHandler);
}

/**
 * Resolves the visible title codes inside `containerEl` against
 * /api/covers/resolve-batch and upgrades the matching <span data-title-code>
 * elements to clickable. Idempotent — safe to call after every render.
 */
export async function activateClickableCodes(containerEl) {
  if (!containerEl) return;
  const spans = containerEl.querySelectorAll('span[data-title-code]:not(.cover-modal-resolved)');
  if (!spans.length) return;
  const codes = Array.from(new Set(
    Array.from(spans).map(s => s.dataset.titleCode).filter(c => c && c !== '—')
  ));
  if (!codes.length) {
    spans.forEach(s => s.classList.add('cover-modal-resolved'));
    return;
  }
  let map = {};
  try {
    const res = await fetch('/api/covers/resolve-batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ codes }),
    });
    if (res.ok) map = await res.json();
  } catch {
    // Swallow; codes stay unclickable on network failure.
  }
  spans.forEach(span => {
    span.classList.add('cover-modal-resolved');
    const code = span.dataset.titleCode;
    const url  = map[code];
    if (url) {
      span.classList.add('cover-modal-clickable');
      span.title = 'Show cover';
      span.addEventListener('click', (e) => {
        e.stopPropagation();
        openCoverModal(code, url);
      });
    }
  });
}
