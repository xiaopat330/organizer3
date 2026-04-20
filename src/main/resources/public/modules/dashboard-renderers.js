// Pure DOM builders for dashboard sub-components. No module-level state; any
// side effect (click navigation, mode switching) flows through a callback arg
// so these can be unit-tested in isolation and reused across dashboards.

import { esc } from './utils.js';
import { renderStatsTiles } from './dashboard-panels.js';

// ── Leaderboards ──────────────────────────────────────────────────────

/**
 * Top-N labels leaderboard. Clicking a row invokes the provided callback
 * with the clicked label object.
 *
 * @param {Array} topLabels  — each: { code, labelName?, company?, score? }
 * @param {{ onRowClick?: (label) => void }} opts
 * @returns {HTMLElement}
 */
export function renderTopLabelsLeaderboard(topLabels, opts = {}) {
  const section = document.createElement('section');
  section.className = 'dashboard-section dashboard-top-labels';
  const header = document.createElement('div');
  header.className = 'dashboard-section-title';
  header.textContent = 'Top Labels';
  section.appendChild(header);

  const list = document.createElement('div');
  list.className = 'dashboard-leaderboard';

  const displayed = topLabels.slice(0, 5);
  const maxScore = displayed.reduce((m, l) => Math.max(m, l.score || 0), 0) || 1;
  displayed.forEach((lbl, i) => {
    const row = document.createElement('div');
    row.className = 'leaderboard-row';
    row.innerHTML = `
      <span class="leaderboard-rank">${i + 1}</span>
      <span class="leaderboard-code">${esc(lbl.code)}</span>
      <span class="leaderboard-name">${esc(lbl.labelName || '')}${lbl.company ? `<span class="leaderboard-company"> · ${esc(lbl.company)}</span>` : ''}</span>
      <span class="leaderboard-bar-wrap"><span class="leaderboard-bar" style="width:${Math.round((lbl.score / maxScore) * 100)}%"></span></span>
    `;
    if (opts.onRowClick) {
      row.addEventListener('click', () => opts.onRowClick(lbl));
    }
    list.appendChild(row);
  });
  section.appendChild(list);
  return section;
}

/**
 * Top-N actress-studio-groups leaderboard. Mirrors the top-labels layout.
 *
 * @param {Array} topGroups  — each: { slug, name, actressCount, score? }
 * @param {{ onRowClick?: (group) => void }} opts
 * @returns {HTMLElement}
 */
export function renderTopGroupsLeaderboard(topGroups, opts = {}) {
  const section = document.createElement('section');
  section.className = 'dashboard-section dashboard-top-groups';
  const header = document.createElement('div');
  header.className = 'dashboard-section-title';
  header.textContent = 'Top Groups';
  section.appendChild(header);

  const list = document.createElement('div');
  list.className = 'dashboard-leaderboard';
  const maxScore = topGroups.reduce((m, g) => Math.max(m, g.score || 0), 0) || 1;
  topGroups.forEach((g, i) => {
    const row = document.createElement('div');
    row.className = 'leaderboard-row leaderboard-row-clickable';
    row.title = `Open ${g.name} in Studio browser`;
    const countLabel = `${g.actressCount} ${g.actressCount === 1 ? 'actress' : 'actresses'}`;
    row.innerHTML = `
      <span class="leaderboard-rank">${i + 1}</span>
      <span class="leaderboard-name-cell">
        <span class="leaderboard-name">${esc(g.name)}</span>
        <span class="leaderboard-company">${countLabel}</span>
      </span>
      <span class="leaderboard-bar-wrap"><span class="leaderboard-bar" style="width:${Math.round((g.score / maxScore) * 100)}%"></span></span>
    `;
    if (opts.onRowClick) {
      row.addEventListener('click', () => opts.onRowClick(g));
    }
    list.appendChild(row);
  });
  section.appendChild(list);
  return section;
}

// ── Library-stats tiles ───────────────────────────────────────────────

/** Title-library stats tiles. */
export function renderTitleLibraryStats(stats) {
  const unseenPct = stats.totalTitles > 0
    ? Math.round((stats.unseen / stats.totalTitles) * 100)
    : 0;
  return renderStatsTiles({
    heading: 'Library',
    tiles: [
      { label: 'Titles',           value: stats.totalTitles.toLocaleString() },
      { label: 'Labels',           value: stats.totalLabels.toLocaleString() },
      { label: 'Unseen',           value: stats.unseen.toLocaleString() },
      { label: 'Unseen %',         value: `${unseenPct}%`, bar: unseenPct },
      { label: 'Added this month', value: stats.addedThisMonth.toLocaleString() },
      { label: 'Added this year',  value: stats.addedThisYear.toLocaleString() },
    ],
  });
}

/** Actress-library stats tiles. */
export function renderActressLibraryStats(stats) {
  const researchPct = stats.researchTotal > 0
    ? Math.round((stats.researchCovered / stats.researchTotal) * 100)
    : 0;
  return renderStatsTiles({
    heading: 'Library',
    tiles: [
      { label: 'Actresses',      value: stats.totalActresses.toLocaleString() },
      { label: 'Favorites',      value: stats.favorites.toLocaleString() },
      { label: 'Graded',         value: stats.graded.toLocaleString() },
      { label: 'Elites',         value: stats.elites.toLocaleString() },
      { label: 'New this month', value: stats.newThisMonth.toLocaleString() },
      { label: 'Researched',     value: `${researchPct}%`, bar: researchPct },
    ],
  });
}

// ── Research gaps ─────────────────────────────────────────────────────

/**
 * Actress research-gap list. Each row shows four filled/empty dots and
 * navigates via the provided callback on click.
 *
 * @param {Array} entries  — each: { actress, profileFilled, physicalFilled, biographyFilled, portfolioCovered }
 * @param {{ onRowClick?: (actress) => void }} opts
 * @returns {HTMLElement}
 */
export function renderResearchGapsList(entries, opts = {}) {
  const list = document.createElement('div');
  list.className = 'dashboard-research-gaps';
  entries.forEach(entry => {
    const a = entry.actress;
    const dots = [
      { filled: entry.profileFilled,    label: 'profile'   },
      { filled: entry.physicalFilled,   label: 'physical'  },
      { filled: entry.biographyFilled,  label: 'biography' },
      { filled: entry.portfolioCovered, label: 'portfolio' },
    ];
    const dotsHtml = dots.map(d => {
      const tip = `${d.label}: ${d.filled ? 'filled' : 'missing'}`;
      return `<span class="research-gap-dot ${d.filled ? 'filled' : 'empty'}" title="${tip}"></span>`;
    }).join('');
    const row = document.createElement('div');
    row.className = 'research-gap-row';
    row.innerHTML = `
      <span class="research-gap-name">${esc(a.canonicalName)}</span>
      <span class="research-gap-tier tier-${esc(a.tier)}">${esc(a.tier.toLowerCase())}</span>
      <span class="research-gap-dots">${dotsHtml}</span>
    `;
    if (opts.onRowClick) {
      row.addEventListener('click', () => opts.onRowClick(a));
    }
    list.appendChild(row);
  });
  return list;
}
