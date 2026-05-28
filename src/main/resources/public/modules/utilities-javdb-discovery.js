// Re-export shim. Implementation lives in ./utilities-javdb-discovery/.
// Split per spec/PROPOSAL_HOUSEKEEPING_2026_05.md §3 Phase 3 (PR-C).
export {
  showJavdbDiscoveryView,
  hideJavdbDiscoveryView,
  navigateToActressProfile,
} from './utilities-javdb-discovery/index.js';
