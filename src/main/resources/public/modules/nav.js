// ── History / navigation utilities ───────────────────────────────────────
// Centralises pushState / replaceState so all navigation goes through one
// place.  The _restoring flag prevents re-pushing during popstate replay.

let _restoring = false;

export function setRestoring(v) { _restoring = v; }

export function pushNav(state, hash) {
  if (_restoring) return;
  history.pushState(state, '', '#' + hash);
}

export function replaceNav(state, hash) {
  history.replaceState(state, '', '#' + hash);
}
