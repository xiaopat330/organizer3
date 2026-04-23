/**
 * Duplicate triage ranker — pure function, no DOM, no fetch.
 * Given a list of locations (each with attached video[] arrays),
 * picks the best one and explains why.
 *
 * Input shape per location:
 *   { volumeId, nasPath, videos: [{ fileSize, width, height, videoCodec, container, durationSec }] }
 *
 * Returns: { suggestedIndex: number|null, rationale: string }
 *   suggestedIndex null means signals are too ambiguous to pick.
 */

const CODEC_RANK = {
  // Higher = better
  hevc: 5, h265: 5,
  h264: 4, avc: 4,
  vp9: 3,
  mpeg4: 2, xvid: 2, divx: 2,
  mpeg2: 1,
  avi: 0, wmv: 0, 'windows media video v9': 0,
};

function codecScore(videoCodec) {
  if (!videoCodec) return -1;
  const k = videoCodec.toLowerCase();
  for (const [key, score] of Object.entries(CODEC_RANK)) {
    if (k.includes(key)) return score;
  }
  return 1; // unknown but present — better than nothing
}

function pixels(v) {
  if (v.width && v.height) return v.width * v.height;
  return 0;
}

function bestVideo(videos) {
  if (!videos || videos.length === 0) return null;
  return videos.reduce((best, v) => {
    const bp = pixels(best), vp = pixels(v);
    if (vp > bp) return v;
    if (vp === bp && codecScore(v.videoCodec) > codecScore(best.videoCodec)) return v;
    if (vp === bp && codecScore(v.videoCodec) === codecScore(best.videoCodec)) {
      return (v.fileSize || 0) > (best.fileSize || 0) ? v : best;
    }
    return best;
  });
}

function describeVideo(v) {
  if (!v) return 'no video';
  const parts = [];
  if (v.width && v.height) parts.push(`${v.width}×${v.height}`);
  if (v.videoCodec) parts.push(v.videoCodec.toUpperCase());
  if (v.fileSize) parts.push(fmtBytes(v.fileSize));
  return parts.join(' · ') || 'unknown';
}

function fmtBytes(b) {
  if (b >= 1e9) return (b / 1e9).toFixed(1) + ' GB';
  if (b >= 1e6) return (b / 1e6).toFixed(0) + ' MB';
  return (b / 1e3).toFixed(0) + ' KB';
}

export function rankLocations(locs) {
  if (locs.length < 2) return { suggestedIndex: locs.length === 1 ? 0 : null, rationale: '' };

  const scored = locs.map((loc, i) => {
    const bv = bestVideo(loc.videos);
    return {
      i, loc, bv,
      px:        pixels(bv || {}),
      codec:     codecScore(bv?.videoCodec),
      size:      bv?.fileSize || 0,
      count:     loc.videos?.length || 0,
      container: bv?.container?.toLowerCase() || '',
    };
  });

  // Sort: resolution desc, then codec desc, then size desc
  const sorted = [...scored].sort((a, b) => {
    if (b.px !== a.px) return b.px - a.px;
    if (b.codec !== a.codec) return b.codec - a.codec;
    return b.size - a.size;
  });

  const best = sorted[0];
  const runner = sorted[1];

  // Ambiguous only if ALL locations are identical on all signals
  const allIdentical = scored.every(s =>
    s.px === best.px && s.codec === best.codec && s.size === best.size &&
    s.count === best.count && s.container === best.container
  );
  if (allIdentical) {
    return { suggestedIndex: null, rationale: 'Copies appear identical — pick manually.' };
  }

  let rationale;
  if (best.px > runner.px && best.px > 0) {
    rationale = `${describeVideo(best.bv)} vs ${describeVideo(runner.bv)}`;
  } else if (best.codec > runner.codec) {
    rationale = `Better codec: ${describeVideo(best.bv)} vs ${describeVideo(runner.bv)}`;
  } else {
    rationale = `Larger file: ${describeVideo(best.bv)} vs ${describeVideo(runner.bv)}`;
  }

  return { suggestedIndex: best.i, rationale };
}
