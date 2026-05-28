// v1 Tools → Enrichment hub shell (Phase 0).
//
// A section-tabs screen with three subtabs: AI Assist · Workflow · Review.
//   - AI Assist → hosts the Phase 0 prototype slice (utilities-ai-assist.js).
//   - Workflow  → placeholder empty-state ("Coming in Phase 2").
//   - Review    → RE-HOMES the existing v1 Enrichment Review view. Its module
//                 (utilities-enrichment-review.js) is NOT modified; we only call
//                 its exported show/hide functions.
//
// Re-home mechanism: the #tools-enrichment-review-view div is physically nested
// inside #tools-javdb-discovery-view (legacy, must not edit). So instead of
// moving it in index.html, we REPARENT it at runtime into this hub's Review
// subview while the hub is showing the Review tab, then return it to its
// original parent when leaving. This keeps the legacy Sources → Review tab path
// working untouched.

import { showAiAssistView, hideAiAssistView } from './utilities-ai-assist.js';
import { showEnrichmentReviewView, hideEnrichmentReviewView } from './utilities-enrichment-review.js';
import { showWorkflowView, hideWorkflowView } from './utilities-workflow/index.js';

const TABS = ['ai-assist', 'workflow', 'review'];
const DEFAULT_TAB = 'ai-assist';

let view = null;
let activeTab = null;

// Deep-link focus handed across from AI Assist's "pending apply" affordance.
// focusWorkflow(queueId) stashes the id; the next switch to the Workflow tab
// passes it to showWorkflowView, which scrolls + flashes that row once.
let pendingFocus = null;

// Saved "home" location of the review div, captured the first time we borrow it.
let reviewHome = null;   // { parent, nextSibling }
let reviewBorrowed = false;

function el(id) { return document.getElementById(id); }

function borrowReviewDiv() {
  const reviewDiv = el('tools-enrichment-review-view');
  const host      = el('ehub-review-subview');
  if (!reviewDiv || !host || reviewBorrowed) return;
  reviewHome = { parent: reviewDiv.parentNode, nextSibling: reviewDiv.nextSibling };
  host.appendChild(reviewDiv);
  reviewBorrowed = true;
}

function returnReviewDiv() {
  if (!reviewBorrowed || !reviewHome) return;
  const reviewDiv = el('tools-enrichment-review-view');
  if (reviewDiv) {
    // hide before returning so the legacy discovery tab controls visibility itself
    hideEnrichmentReviewView();
    reviewHome.parent.insertBefore(reviewDiv, reviewHome.nextSibling);
  }
  reviewBorrowed = false;
  reviewHome = null;
}

function setActiveTabStyling(tab) {
  TABS.forEach(t => {
    const btn = el(`ehub-tab-${t}`);
    if (btn) btn.classList.toggle('selected', t === tab);
  });
}

async function switchTab(tab) {
  if (!TABS.includes(tab)) tab = DEFAULT_TAB;

  // Tear down whatever we're leaving.
  if (activeTab === 'ai-assist') hideAiAssistView();
  if (activeTab === 'review') {
    returnReviewDiv();
    const host = el('ehub-review-subview');
    if (host) host.style.display = 'none';
  }
  if (activeTab === 'workflow') {
    hideWorkflowView();
  }

  activeTab = tab;
  setActiveTabStyling(tab);

  if (tab === 'ai-assist') {
    await showAiAssistView();
  } else if (tab === 'workflow') {
    const focusId = pendingFocus;
    pendingFocus = null;
    await showWorkflowView({ focusId });
  } else if (tab === 'review') {
    const host = el('ehub-review-subview');
    if (host) host.style.display = '';
    borrowReviewDiv();
    await showEnrichmentReviewView();
  }
}

let wired = false;
function wireOnce() {
  if (wired) return;
  TABS.forEach(t => {
    const btn = el(`ehub-tab-${t}`);
    if (btn) btn.addEventListener('click', () => switchTab(t));
  });
  wired = true;
}

export async function showEnrichmentHubView() {
  view = view || el('tools-enrichment-hub-view');
  if (!view) return;
  wireOnce();
  view.style.display = '';
  activeTab = null;            // force a clean switch from a known state
  await switchTab(DEFAULT_TAB);
}

export function hideEnrichmentHubView() {
  // Stop all subtab work and restore the borrowed review div to its home.
  hideAiAssistView();
  hideWorkflowView();
  returnReviewDiv();
  const rh = el('ehub-review-subview');
  if (rh) rh.style.display = 'none';
  activeTab = null;
  if (view) view.style.display = 'none';
}

// Deep-link entry point: AI Assist (or any caller) hands a queueId, then this
// switches to the Workflow tab which scrolls that row into view and flashes it.
// Contract for Track A / serial-tail glue: call focusWorkflow(queueId) from
// inside the hub (the hub is already showing). Safe to call when the Workflow
// tab is already active — it re-switches and re-applies focus.
export async function focusWorkflow(queueId) {
  pendingFocus = queueId;
  await switchTab('workflow');
}
