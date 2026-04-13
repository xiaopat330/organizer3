// ── App config ────────────────────────────────────────────────────────────
// Mutable config vars, populated on first load from /api/config.
// All values are exported as live bindings — importers always see the current value.

export let MAX_TOTAL            = 500;
export let MAX_RANDOM_TITLES    = 500;
export let MAX_RANDOM_ACTRESSES = 500;
export let EXHIBITION_VOLUMES   = '';
export let ARCHIVE_VOLUMES      = '';
export let THUMBNAIL_COLUMNS    = 5;

function applyCoverCrop(pct) {
  const fraction = pct / 100;
  const width    = (100 / fraction).toFixed(2) + '%';
  const offset   = (-(100 / fraction - 100)).toFixed(2) + '%';
  const ratioW   = (fraction * 17).toFixed(4);
  const root     = document.documentElement;
  root.style.setProperty('--cover-crop-width',  width);
  root.style.setProperty('--cover-crop-offset', offset);
  root.style.setProperty('--cover-card-ratio',  `${ratioW} / 12`);
}

export function initConfig() {
  fetch('/api/config')
    .then(r => r.json())
    .then(cfg => {
      const name = cfg.appName || 'organizer3';
      document.getElementById('app-name').textContent = name.toLowerCase();
      document.title = name;
      if (cfg.maxBrowseTitles)    MAX_TOTAL            = cfg.maxBrowseTitles;
      if (cfg.maxRandomTitles)    MAX_RANDOM_TITLES    = cfg.maxRandomTitles;
      if (cfg.maxRandomActresses) MAX_RANDOM_ACTRESSES = cfg.maxRandomActresses;
      if (cfg.exhibitionVolumes)  EXHIBITION_VOLUMES   = cfg.exhibitionVolumes.join(',');
      if (cfg.archiveVolumes)     ARCHIVE_VOLUMES      = cfg.archiveVolumes.join(',');
      if (cfg.thumbnailColumns)   THUMBNAIL_COLUMNS    = cfg.thumbnailColumns;
      applyCoverCrop(cfg.coverCropPercent || 47);
    })
    .catch(() => {});
}
