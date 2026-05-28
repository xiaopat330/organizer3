// v1 Tools → Enrichment hub shell.
//
// A section-tabs screen with three subtabs: AI Assist · Workflow · Review.
//   - AI Assist → hosts the Phase 0 prototype slice (utilities-ai-assist.js).
//   - Workflow  → the v1 enrichment workflow surface.
//   - Review    → the Enrichment Review view. Its markup
//                 (#tools-enrichment-review-view) now lives permanently inside
//                 this hub's Review subview (#ehub-review-subview) in index.html,
//                 so the Review subtab simply shows/hides the subview in place
//                 and calls the review module's exported show/hide functions.

import { showAiAssistView, hideAiAssistView } from './utilities-ai-assist.js';
import { showEnrichmentReviewView, hideEnrichmentReviewView, focusReviewItem } from './utilities-enrichment-review.js';
import { showWorkflowView, hideWorkflowView } from './utilities-workflow/index.js';

const TABS = ['ai-assist', 'workflow', 'review'];
const DEFAULT_TAB = 'ai-assist';

let view = null;
let activeTab = null;

// Deep-link focus handed across from AI Assist's "pending apply" affordance.
// focusWorkflow(queueId) stashes the id; the next switch to the Workflow tab
// passes it to showWorkflowView, which scrolls + flashes that row once.
let pendingFocus = null;

// Deep-link focus for the Review subtab (Sources-Queue / enrich-panels links).
// focusReview(queueId) stashes the id; the next switch to the Review tab passes
// it to focusReviewItem, which scrolls that row into view and flashes it once.
let pendingReviewFocus = null;

function el(id) { return document.getElementById(id); }

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
    hideEnrichmentReviewView();
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
    await showEnrichmentReviewView();
    const focusId = pendingReviewFocus;
    pendingReviewFocus = null;
    if (focusId != null) focusReviewItem(focusId);
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
  // Stop all subtab work.
  hideAiAssistView();
  hideWorkflowView();
  hideEnrichmentReviewView();
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

// Deep-link entry point for the Review subtab: a caller (e.g. action.js's
// navigate-to-review-item handler from the Sources Queue) hands a reviewQueueId,
// then this switches to the Review tab which scrolls that row into view and
// flashes it. Works whether or not the hub is already open / on the Review tab.
export async function focusReview(queueId) {
  pendingReviewFocus = queueId;
  await switchTab('review');
}
