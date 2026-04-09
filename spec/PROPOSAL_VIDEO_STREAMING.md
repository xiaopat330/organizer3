# Proposal: In-Browser Video Streaming

## Motivation

The web UI already displays titles with metadata, covers, locations, and actress info. The natural next step is being able to watch videos directly from the browser — turning organizer3 from a catalog into a personal media player for the library.

This is a single-user, LAN-only feature. No auth, no concurrent streams, no CDN. Just pipe bytes from SMB to the browser.

---

## 1. Title Detail Page

The title detail page already exists as a two-column layout: left column (25%, fixed) shows the cover image, Japanese/English titles, product code, cast, label, date, tags, grade, and location; right column (75%, scrollable) is currently empty.

This proposal fills the right column with video content. The fully built-out view includes:

- **Video file list** — one entry per `Video` record, showing filename, size, and a Play button; later phases add duration, resolution, codec, bitrate, and conversion badges (see §7)
- **Thumbnail strip** — preview screenshots extracted from the video (see §4)
- **Inline video player** — HTML5 `<video>` element with native browser controls (seek, volume, fullscreen, playback speed)
- **"More from this actress"** — a row of related title cards below the player (see §8.3)

**Phase 1 implements only:** video file discovery, the inline player per video, and 4 preview thumbnails per video — see §10 for the full phasing. Later phases add seek preview, theater mode, resume playback, etc.

### Video File Discovery

Videos are discovered on-demand when the user first visits a title's detail page:

1. Resolve the title's SMB path from `TitleLocation` + volume config
2. List the title folder via SMB — scan known video subfolders first (`video/`, `h265/`, etc.), then the root
3. Filter by video file extensions (`.mp4`, `.mkv`, `.avi`, `.wmv`, `.mov`, `.m4v`, `.ts`)
4. Persist discovered files to the `videos` DB table so subsequent visits are instant
5. Return the list of video files with their IDs, filenames, and sizes

A title may have multiple video files (multi-part releases). Each is shown separately with its own thumbnail strip and player, separated by horizontal dividers.

### Right Column Layout

```
┌─────────────────────────────────────────────┐
│  Video 1: ABP-123.mp4  (4.2 GB)            │
│                                             │
│  [thumb1] [thumb2] [thumb3] [thumb4]        │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │                                     │    │
│  │         <video> player              │    │
│  │                                     │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│  Video 2: ABP-123b.mp4  (1.1 GB)           │
│                                             │
│  [thumb1] [thumb2] [thumb3] [thumb4]        │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │                                     │    │
│  │         <video> player              │    │
│  │                                     │    │
│  └─────────────────────────────────────┘    │
│                                             │
└─────────────────────────────────────────────┘
```

Later phases add seek bar hover preview (§8.8), resume playback (§8.1), theater mode (§8.6), and "more from this actress" (§8.3).

---

## 2. Streaming Endpoint

### Route

```
GET /api/stream/{videoId}
```

### Behavior

1. Look up `Video` by ID → get `volumeId`, `path`
2. Look up volume config → get SMB path, server credentials
3. Open an smbj connection to the volume's share
4. Read the file, write bytes to the HTTP response
5. Close the SMB connection when done

### Range Request Support (Critical)

Browsers use HTTP Range requests for video playback — this is what enables seeking, and the `<video>` element won't work properly without it. The endpoint must:

- Respond to `Range: bytes=0-` with `206 Partial Content` + `Content-Range` header
- Support arbitrary byte-range requests for seeking
- Return `Content-Length` and `Accept-Ranges: bytes` on all responses
- smbj supports random-access reads (`DiskShare.openFile()` with read at offset), so this maps directly

### Connection Management

The streaming endpoint opens its own smbj connection per request — it doesn't reuse the shell's `SessionContext` connection. This keeps the web layer decoupled from shell state. A volume doesn't need to be mounted in the shell to stream from it.

For a single-user setup, one connection at a time is fine. No connection pool needed initially. If connection setup latency becomes noticeable (~1-2s per request), a simple connection cache keyed by volume ID could be added later.

### Content Type

Return `Content-Type` based on file extension:

| Extension | MIME Type |
|-----------|-----------|
| mp4, m4v | video/mp4 |
| mkv | video/x-matroska |
| avi | video/x-msvideo |
| wmv | video/x-ms-wmv |
| mov | video/quicktime |

### Browser Playback Compatibility

The HTML5 `<video>` element natively plays:
- **MP4 (H.264)** — works everywhere
- **MP4 (H.265/HEVC)** — works in Safari; Chrome/Firefox support varies by OS

Does **not** natively play: MKV, AVI, WMV, RMVB, and others.

For unsupported containers, see §3 (transmuxing/transcoding).

---

## 3. Format Handling Strategy

The library has mixed formats: MKV (HEVC), MP4, AVI, and others. Three tiers of handling:

### Tier 1: Direct Playback (no processing)
- MP4 with H.264 — just stream bytes, browser plays natively

### Tier 2: Transmuxing (fast, no re-encoding)
- MKV containing H.264 or HEVC → remux to MP4 on the fly or as a cached pre-process
- This repackages the same video/audio streams into an MP4 container
- Very fast (seconds, not minutes) — no quality loss, minimal CPU
- Requires JavaCV (see §5)

### Tier 3: Transcoding (slow, CPU-intensive)
- AVI, WMV, RMVB, and other legacy containers/codecs → transcode to H.264 MP4
- Full decode + re-encode — CPU-heavy, takes minutes per file
- Should only happen once per file; the result is cached permanently (see §6)

### Detection Logic

When a stream is requested:

```
1. Check cache — if a browser-ready MP4 exists, serve it directly
2. Check extension/container:
   a. MP4 → stream directly (Tier 1)
   b. MKV → transmux to MP4, cache, then serve (Tier 2)
   c. AVI/WMV/other → transcode to MP4, cache, then serve (Tier 3)
```

### JavaCV for Tier 2 and 3

JavaCV (Java wrapper around FFmpeg's native libraries) handles both transmuxing and transcoding. It ships as Maven JARs — no external `ffmpeg` binary needed. See §5.

**Note:** Tier 2 and 3 are enhancements. The MVP could start with Tier 1 only (direct MP4 streaming) and add format conversion later.

### Streaming Transmux

For Tier 2 (MKV → MP4), rather than waiting for the full transmux to complete before serving, the implementation pipes the JavaCV output stream directly to the HTTP response while simultaneously writing to the local cache. Playback begins within seconds. The cache write catches up in the background; once complete, all subsequent plays are served from cache with full seek support. This eliminates the "wait N minutes then play" problem for large 4K files.

For Tier 3 (full transcode), the transcode must complete before playback — it cannot be streamed mid-encode reliably. The server returns a `202 Accepted` response with a status URL; the browser polls until the transcode is done, then plays from cache.

### Viewing Experience by Format (90-Minute Film)

A practical breakdown of first-play and repeat-play experience across common library formats.

| Resolution | Codec | Container | Tier | Typical Size | First Play | Repeat Play |
|-----------|-------|-----------|------|-------------|-----------|------------|
| 4K (3840×2160) | H.264 | MP4 | 1 | 15–40 GB | Instant | Instant |
| 4K (3840×2160) | HEVC | MKV | 2 | 8–20 GB | Starts in ~5s* | Instant |
| 1080p (1920×1080) | H.264 | MP4 | 1 | 4–10 GB | Instant | Instant |
| 1080p (1920×1080) | HEVC | MKV | 2 | 2–6 GB | Starts in ~5s* | Instant |
| 720p (1280×720) | H.264 | MP4 | 1 | 1.5–5 GB | Instant | Instant |
| 720p (1280×720) | HEVC | MKV | 2 | 1–3 GB | Starts in ~5s* | Instant |
| 480p / DVD | DivX/XviD | AVI | 3 | 0.7–2 GB | 15–30 min** | Instant |
| 480p / DVD | MPEG2 | VOB | 3 | 3–7 GB | 20–40 min** | Instant |

*Streaming transmux: playback begins as soon as enough of the output stream is buffered (a few seconds). Seeking is limited to the buffered portion during the first play. Once the full transmux is cached, seeking is unlimited in all subsequent plays.

**Full transcode must complete before playback starts. The browser polls a status endpoint. A single-user background job handles it. Once cached, never transcoded again.

**Seek bar behavior:**
- **Tier 1 (MP4 direct):** Always instant — Range request to any byte offset in the file.
- **Tier 2 (streaming transmux, first play):** Instant within buffered portion; seeking ahead of the buffer forces a brief wait.
- **Tier 2 (repeat play from cache):** Always instant — served from local SSD via `RandomAccessFile`.
- **Tier 3 (cached transcode):** Always instant.

**Browser compatibility:** H.264 is universally supported. HEVC works natively in Safari and in Chrome on macOS via VideoToolbox hardware acceleration. Firefox on macOS does not support HEVC natively — if cross-browser HEVC support matters, the transmux step can force H.264 re-encoding, at the cost of a longer first-play delay.

---

## 4. Video Thumbnails (Preview Screenshots)

### Generation

For each video file, extract N frames at evenly-spaced intervals (e.g., 8-12 screenshots across the duration). Using JavaCV:

```java
FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputStream);
grabber.start();
long duration = grabber.getLengthInTime(); // microseconds
for (int i = 0; i < N; i++) {
    long timestamp = duration * i / N;
    grabber.setTimestamp(timestamp);
    Frame frame = grabber.grabImage();
    // convert to BufferedImage, write as JPG
}
```

### Storage

```
data/thumbnails/
  <videoId>/
    thumb_01.jpg
    thumb_02.jpg
    ...
```

Generated lazily on first view of the title detail page. Stored permanently — never regenerated unless explicitly requested.

### API

```
GET /api/videos/{videoId}/thumbnails
```

Returns a JSON array of thumbnail URLs. If thumbnails don't exist yet, triggers generation (synchronous on first request — a few seconds) then returns the URLs.

### Display

Shown as a horizontal scrollable strip on the title detail page, above the video player. Gives a visual preview of the content before committing to watching. Clicking a thumbnail seeks the player to that timestamp.

### Seek Preview Thumbnails

A denser thumbnail set generated alongside the preview strip — one frame every ~10 seconds (≈540 frames for a 90-minute film). These power the seek bar hover preview: as the user moves the cursor over the seek bar, the nearest pre-generated frame appears in a floating tooltip above it, just like YouTube's seek preview.

Storage: ~10 KB per JPEG × 540 frames ≈ 5 MB per video. Generated in the same JavaCV pass as the preview strip thumbnails — incremental cost is low.

```
data/thumbnails/
  <videoId>/
    thumb_01.jpg          # preview strip (8–12 frames)
    ...
    seek_0000.jpg         # dense seek thumbnails (one per ~10s)
    seek_0010.jpg
    seek_0020.jpg
    ...
```

---

## 5. JavaCV Dependency

### What It Is

JavaCV is a Java wrapper around FFmpeg and other native multimedia libraries. It bundles the native FFmpeg binaries as platform-specific JARs via JavaCPP. No external installation needed — `gradle build` pulls everything in.

### Gradle Dependencies

```groovy
// JavaCV (FFmpeg wrapper — frame grabbing, transmuxing, transcoding)
implementation 'org.bytedeco:javacv-platform:1.5.10'
```

This single dependency pulls in:
- `javacv` — Java API
- `javacpp` — native bridge
- `ffmpeg` — platform-specific native libraries (macOS arm64 in your case)

### Size Impact

The `javacv-platform` artifact includes native binaries for all platforms (~500MB total in the dependency cache). To keep the build lean, you can scope it to macOS arm64 only:

```groovy
implementation('org.bytedeco:javacv:1.5.10')
implementation('org.bytedeco:ffmpeg:7.1-1.5.10:macosx-arm64')
implementation('org.bytedeco:ffmpeg:7.1-1.5.10') { artifact { classifier = 'macosx-arm64' } }
```

This cuts it down to ~80-100MB of native libs.

### What It's Used For

| Use Case | JavaCV API |
|----------|-----------|
| Extract thumbnails | `FFmpegFrameGrabber` → seek to timestamp → grab frame |
| Get video metadata (duration, resolution, codec) | `FFmpegFrameGrabber.getLengthInTime()`, `.getVideoCodec()`, etc. |
| Transmux MKV→MP4 | `FFmpegFrameGrabber` (input) + `FFmpegFrameRecorder` (output), copy streams |
| Transcode AVI→MP4 | Same, but with codec specification on the recorder |

### Alternative: JCodec (Pure Java)

Not recommended for this project. JCodec only supports H.264/MP4 — it cannot read MKV, AVI, WMV, or decode HEVC. Given the mixed-format library, it would only work for a subset of files.

---

## 6. LRU Video Cache

### Purpose

Avoid hitting SMB on every playback. Cache video files (or their transmuxed/transcoded versions) on local disk.

### Storage

```
data/video-cache/
  <videoId>.mp4          # cached/converted video
  <videoId>.meta         # small metadata file (original size, access time, source format)
```

### Eviction

- Fixed size cap (configurable, e.g., 50GB)
- LRU eviction: when the cache exceeds the cap, delete the least-recently-accessed file(s) until under the limit
- Access time is updated on every stream request (touch the `.meta` file)

### Cache Flow

```
Stream request for videoId
  → Cache hit?
      YES → serve from local disk (fast, supports Range natively via RandomAccessFile)
      NO  → pull from SMB, process if needed (transmux/transcode), write to cache, then serve
```

### Why It Matters

- **First play:** streams from SMB with possible transmux delay. Fine on LAN, but not instant for large files.
- **Repeat play:** served from local SSD. Instant seek, zero SMB latency.
- **Transmuxed/transcoded files:** only processed once, cached result served on all future plays.
- Range request handling is simpler from local files (standard `RandomAccessFile` seek) versus proxying ranges through smbj.

---

## 7. Video Metadata Endpoint

A nice companion to the streaming and thumbnail features. When JavaCV is available, the grabber can extract metadata in milliseconds:

```
GET /api/videos/{videoId}/info
```

Returns:
```json
{
  "filename": "ABP-123.mkv",
  "duration": "01:58:23",
  "durationSeconds": 7103,
  "resolution": "1920x1080",
  "width": 1920,
  "height": 1080,
  "videoCodec": "hevc",
  "audioCodec": "aac",
  "audioChannels": 2,
  "container": "mkv",
  "bitrate": "8400 kbps",
  "frameRate": "23.976 fps",
  "fileSize": "4.2 GB",
  "fileSizeBytes": 4509715660,
  "tier": 2,
  "playable": true,
  "requiresConversion": false
}
```

`playable` indicates whether the browser can play this format directly without any conversion. `requiresConversion` flags files that need transmuxing (Tier 2) or transcoding (Tier 3) before playback. `tier` is the numeric tier (1/2/3) from §3, useful for UI badging. `durationSeconds` and `fileSizeBytes` are machine-readable companions to the display strings.

The video file list on the title detail page surfaces the key fields inline — one row per file:

```
ABP-123.mkv    01:58:23    1920×1080    hevc    8.4 Mbps    [▶ Play]
ABP-123b.mp4   00:12:44     720×480    h264    2.1 Mbps    [▶ Play]
```

Files requiring conversion show a small badge ("transmux" or "transcode") so the user knows a first-play delay is expected.

---

## 8. UX Enhancements

The following viewer experience improvements build on the core streaming capability. Each is independent and can be added after the core phases without restructuring.

### 8.1 Resume Playback

Store the current playback position in `localStorage` per `videoId`. When the user returns to a title they previously started, offer to resume from where they left off — or resume automatically with a toast notification showing the timestamp. Lost only if browser data is cleared.

- Zero server-side changes
- ~20 lines of JS
- Powers the "Continue Watching" section (§8.5)

### 8.2 Picture-in-Picture

Built into the HTML5 `<video>` element in Chrome and Safari. The browser's native controls include a PiP button. The user pops the video into a floating overlay and continues browsing the title grid while watching. **Nothing to implement** — works out of the box.

### 8.3 "More from This Actress"

A row of title cards below the player showing other titles featuring the same actress. Uses the existing actress-title relationship in the DB — `findTitlesByActress()` already exists on `ActressBrowseService`. Encourages browsing within an actress's catalog without leaving the player context.

### 8.4 Watch History

A `watch_history` DB table recording `(title_id, watched_at)`. Updated when a video starts playing. Surfaced as:
- A "Watched" badge on title cards in the browse grid
- A "Last watched: March 12" timestamp on the title detail page
- A browse filter to hide or show-only watched titles

### 8.5 Continue Watching

A section at the top of the home browse page showing titles started but not finished (resume position > 5% and < 90% of duration). Driven entirely by `localStorage` resume data from §8.1. Disappears when a title is marked finished. No server changes needed.

### 8.6 Theater Mode

A button on the title detail page that expands the video player to fill most of the viewport, collapses and dims the metadata section above it, and adds a dark overlay. A second click restores the normal layout. Pure CSS class toggle — the kind of interaction that makes "I've decided to watch this" feel intentional.

### 8.7 Watchlist

A "Watch Later" button on title cards and the title detail page. Stores `title_id` in a `watchlist` DB table. A browse filter shows only watchlisted titles. Complements the actress-level favorites that already exist — this is title-level "watch later."

### 8.8 Thumbnail Seek Preview

When the user hovers over the seek bar, a small frame thumbnail appears above the cursor showing the video content at that timestamp — the same feature YouTube has on its progress bar.

**Implementation:**
- Dense thumbnail set generated alongside the preview strip: one frame every ~10 seconds (≈540 frames for a 90-minute film), stored as `seek_NNNN.jpg` (see §4)
- On hover over the seek bar `<input type="range">`, compute the timestamp from cursor position, look up the nearest pre-generated thumbnail URL, display in a floating `<div>` above the bar
- Storage: ~5 MB per video for the dense set; marginal given thumbnails are already being generated

This is one of the features that most distinguishes a polished media player from a basic one. Since the thumbnail generation pass already exists, adding the dense seek set in the same pass is low incremental cost.

---

## 9. New Components Summary

| Component | Package | Phase | Responsibility |
|-----------|---------|-------|----------------|
| `VideoStreamService` | `web` | 1 | Video discovery (SMB dir scan → DB), streaming (Range support), thumbnail orchestration |
| `SmbConnectionFactory` | `smb` | 1 | Opens smbj connections for the web layer (decoupled from shell's SessionContext) |
| `ThumbnailService` | `media` | 1 | Generate preview thumbnails via JavaCV, cache to local disk |
| `VideoCache` | `cache` | 2 | LRU disk cache: store, retrieve, evict cached video files |
| `VideoProbe` | `media` | 2 | Extract metadata (duration, codec, resolution) via JavaCV |
| `Transmuxer` | `media` | 3 | MKV→MP4 transmuxing via JavaCV |
| `Transcoder` | `media` | 3 | Legacy format→MP4 transcoding via JavaCV |

All wired in `Application.java` per the existing pattern. No Spring, no DI framework.

---

## 10. Phased Implementation

### Phase 1 — MVP: Streaming + Discovery + Thumbnails
1. **Video discovery endpoint** — `GET /api/titles/{code}/videos` — SMB directory scan of the title folder (video subfolders first, then root), filtered by video extensions, persisted to `videos` table, returned as JSON
2. **Streaming endpoint** — `GET /api/stream/{videoId}` — direct SMB-to-HTTP byte proxying with full Range request support (smbj random-access reads → HTTP 206 Partial Content). No local caching; plays immediately from SMB
3. **Thumbnail endpoint** — `GET /api/videos/{videoId}/thumbnails` — 4 preview screenshots per video at evenly-spaced intervals, generated via JavaCV from SMB, cached to local disk as JPEGs. Generated asynchronously on first request; UI shows placeholders until ready
4. **Title detail right column** — for each video: filename + size header, thumbnail strip (4 images), inline `<video>` player with `src` pointing at the streaming endpoint, horizontal divider between videos
- **Validates the core concept end-to-end: browse → discover → preview → play**

### Phase 2 — Cache + Thumbnails + Metadata
- LRU video cache on local disk
- Video metadata endpoint (duration, resolution, codec, bitrate, audio)
- Metadata displayed inline in the video file list with conversion badge
- Thumbnail extraction via JavaCV: preview strip (8–12 frames) + dense seek set (~1/10s)
- Thumbnail strip on title detail page; seek bar hover preview (§8.8)

### Phase 3 — Format Conversion
- Streaming transmux (MKV→MP4) via JavaCV — playback starts in seconds
- Background transcode (AVI/WMV→MP4) via JavaCV with 202 + poll pattern
- Conversion results stored in cache; never repeated
- `tier` and `requiresConversion` surfaced in video info endpoint

### Phase 4 — UX Enhancements (I)
- Resume playback via `localStorage` (§8.1)
- "Continue Watching" section on home page (§8.5)
- "More from This Actress" row on title detail page (§8.3)
- Theater mode (§8.6)
- Picture-in-Picture (§8.2) — already works, just document it

### Phase 5 — UX Enhancements (II)
- Watch history DB table + "Watched" badge on title cards (§8.4)
- Watchlist / Watch Later button (§8.7)
- Background thumbnail pre-generation for all titles (shell command or startup job)
- Cache management shell command (show usage, force eviction, clear all)

---

## 11. Risks and Considerations

| Risk | Mitigation |
|------|-----------|
| JavaCV native libs are large (~100MB mac-only, ~500MB all-platform) | Scope to macOS arm64 only; acceptable for a personal project |
| Transcoding is CPU-intensive for legacy formats | Cache means it only happens once per file; single-user so no contention |
| SMB connection per stream request adds ~1-2s latency | Cache eliminates this for repeat views; acceptable for first play on LAN |
| HEVC playback support varies by browser | Safari handles it; Chrome on macOS added support in recent versions; transmux to MP4 container helps |
| Disk space for cache | Configurable cap with LRU eviction; 50GB is reasonable for a personal setup |
