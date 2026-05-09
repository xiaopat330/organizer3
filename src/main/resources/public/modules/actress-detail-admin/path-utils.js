// Shared path-rendering helpers for admin-tab folder/location displays.
//
// .local is mDNS-only (resolved on macOS by default; Windows/Linux need extra
// services). Strip it on non-mac clients so the copied path is more likely to
// resolve in the user's file manager.

const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const IS_WIN = /Win/.test(navigator.platform || navigator.userAgent);

export function displayPath(rawPath) {
  if (!rawPath) return '';
  if (IS_MAC) return rawPath;
  // Non-mac: strip mDNS .local suffix (Bonjour-only).
  let p = rawPath.replace(/\.local(?=\/|$)/g, '');
  // Windows: Explorer wants UNC backslashes. //srv/share/path → \\srv\share\path
  if (IS_WIN) p = p.replace(/\//g, '\\');
  return p;
}

// navigator.clipboard requires a secure context (HTTPS or localhost). LAN
// clients hitting the server over plain HTTP fall back to the legacy textarea
// + execCommand path so copy still works on Windows/Linux browsers.
function copyToClipboard(text) {
  if (navigator.clipboard && window.isSecureContext) {
    return navigator.clipboard.writeText(text);
  }
  return new Promise((resolve, reject) => {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '0';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    ta.setSelectionRange(0, text.length);
    try {
      const ok = document.execCommand('copy');
      document.body.removeChild(ta);
      ok ? resolve() : reject(new Error('execCommand returned false'));
    } catch (e) {
      document.body.removeChild(ta);
      reject(e);
    }
  });
}

export function installPathClickToCopy(el, rawPath, copiedClass = 'admin-path-copied') {
  if (!el || !rawPath) return;
  const text = displayPath(rawPath);
  el.classList.add('admin-path-copyable');
  el.title = `Click to copy: ${text}`;
  el.addEventListener('click', (e) => {
    e.stopPropagation();
    copyToClipboard(text).then(() => {
      el.classList.add(copiedClass);
      setTimeout(() => el.classList.remove(copiedClass), 1200);
    }).catch(err => {
      console.warn('Path copy failed:', err);
    });
  });
}
