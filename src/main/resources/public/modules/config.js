// ── App config ────────────────────────────────────────────────────────────
// Mutable config vars, populated on first load from /api/config.
// All values are exported as live bindings — importers always see the current value.

export let MAX_TOTAL            = 500;
export let MAX_RANDOM_TITLES    = 500;
export let MAX_RANDOM_ACTRESSES = 500;
export let EXHIBITION_VOLUMES   = '';
export let ARCHIVE_VOLUMES      = '';
export let THUMBNAIL_COLUMNS    = 5;

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
    })
    .catch(() => {});
}
