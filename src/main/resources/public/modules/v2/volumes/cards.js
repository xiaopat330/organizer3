// volumes/cards.js — Per-volume card grid rendering (overview section at top)
// Also includes the volume list-picker used in the left sidebar of the detail layout.

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

// Palette of distinct volume hues (matches legacy).
export const VOLUME_HUES = [
  '#60a5fa', '#4ade80', '#fbbf24', '#f472b6', '#a78bfa',
  '#34d399', '#fb923c', '#22d3ee', '#e879f9', '#facc15',
  '#2dd4bf', '#f87171',
];

export function hueFor(id) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
  return VOLUME_HUES[Math.abs(h) % VOLUME_HUES.length];
}

const STRUCTURE_ICON_PATHS = {
  queue:        '<path d="M22 12h-6l-2 3H10l-2-3H2"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
  conventional: '<path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>',
  exhibition:   '<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
  archive:      '<polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" y1="12" x2="14" y2="12"/>',
  avstars:      '<rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/>',
  _default:     '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v6c0 1.66 4 3 9 3s9-1.34 9-3V5"/><path d="M3 11v6c0 1.66 4 3 9 3s9-1.34 9-3v-6"/>',
};

export function volumeIconSVG(structureType, color, size = 22) {
  const paths = STRUCTURE_ICON_PATHS[structureType] || STRUCTURE_ICON_PATHS._default;
  return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="${color}" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`;
}

export function formatLastSynced(ts) {
  if (!ts) return 'Never synced';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return 'Never synced';
  const diff = Date.now() - d.getTime();
  const days = Math.floor(diff / 86400000);
  if (days >= 2) return `${days} days ago`;
  if (days === 1) return 'Yesterday';
  const hours = Math.floor(diff / 3600000);
  if (hours >= 1) return `${hours}h ago`;
  const mins = Math.floor(diff / 60000);
  if (mins >= 1) return `${mins}m ago`;
  return 'Just now';
}

export function badgeHTML(v) {
  if (v.status === 'offline') {
    return `<span class="vol-badge offline"><span class="vol-badge-dot"></span>offline</span>`;
  }
  const errors = (v.health || []).filter(h => h.level === 'error').length;
  const warns  = (v.health || []).filter(h => h.level === 'warn').length;
  if (errors > 0) return `<span class="vol-badge error"><span class="vol-badge-dot"></span>${errors}</span>`;
  if (warns > 0)  return `<span class="vol-badge warn"><span class="vol-badge-dot"></span>${warns}</span>`;
  return `<span class="vol-badge healthy"><span class="vol-badge-dot"></span>healthy</span>`;
}
