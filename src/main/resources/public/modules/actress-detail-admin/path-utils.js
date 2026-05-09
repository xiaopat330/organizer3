// Shared path-rendering helpers for admin-tab folder/location displays.
//
// .local is mDNS-only (resolved on macOS by default; Windows/Linux need extra
// services). Strip it on non-mac clients so the copied path is more likely to
// resolve in the user's file manager.

const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

export function displayPath(rawPath) {
  if (!rawPath) return '';
  return IS_MAC ? rawPath : rawPath.replace(/\.local(?=\/|$)/g, '');
}

export function installPathClickToCopy(el, rawPath, copiedClass = 'admin-path-copied') {
  if (!el || !rawPath) return;
  const text = displayPath(rawPath);
  el.classList.add('admin-path-copyable');
  el.title = `Click to copy: ${text}`;
  el.addEventListener('click', (e) => {
    e.stopPropagation();
    if (!navigator.clipboard) return;
    navigator.clipboard.writeText(text).then(() => {
      el.classList.add(copiedClass);
      setTimeout(() => el.classList.remove(copiedClass), 1200);
    });
  });
}
