package com.organizer3.sync;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.covers.CoverPath;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitlePathHistoryRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.organizer3.sync.scanner.ScannerSupport.extractActressName;

/**
 * Single-folder registration: registers one manually-placed title folder on a mounted volume
 * into the DB (title_locations + videos) without a full-volume sync.
 *
 * <p>This is a scoped, additive operation. It NEVER calls:
 * <ul>
 *   <li>{@code videoRepo.deleteByVolume} / {@code titleLocationRepo.markStaleByVolume}</li>
 * </ul>
 * Those volume-wide destructive ops are the sole province of {@link FullSyncOperation}.
 *
 * <p>Guards enforced here:
 * <ol>
 *   <li>Refuses when the folder has no parseable JAV code (label/seqNum are null).</li>
 *   <li>Only infers and links a folder-name actress credit when the title is <em>newly
 *       created</em>; for existing titles, leaves cast untouched.</li>
 *   <li>Idempotent: deletes this title's videos on this volume under this folder path
 *       (scoped — does not touch other locations) then re-saves, so re-registering the
 *       same folder produces no duplicate rows.</li>
 *   <li>Keep-both: adding a second location for a title that already lives elsewhere is
 *       fine — the title_locations upsert handles it cleanly.</li>
 * </ol>
 */
@Slf4j
public class FolderRegistrar extends AbstractSyncOperation {

    private final VideoRepository videoRepo;
    private final TitleCodeParser codeParser = new TitleCodeParser();

    public FolderRegistrar(TitleRepository titleRepo,
                           VideoRepository videoRepo,
                           ActressRepository actressRepo,
                           VolumeRepository volumeRepo,
                           TitleLocationRepository titleLocationRepo,
                           TitleActressRepository titleActressRepo,
                           IndexLoader indexLoader,
                           TitleEffectiveTagsService titleEffectiveTagsService,
                           ActressCompaniesService actressCompaniesService,
                           CoverPath coverPath,
                           RevalidationPendingRepository revalidationPendingRepo,
                           SyncIdentityMatcher identityMatcher,
                           TitlePathHistoryRepository pathHistoryRepo) {
        super(titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo,
                indexLoader, titleEffectiveTagsService, actressCompaniesService, coverPath,
                revalidationPendingRepo, identityMatcher, TitleSyncObserver.NO_OP, pathHistoryRepo);
        this.videoRepo = videoRepo;
    }

    // ── SyncOperation contract — not used; this class is used via registerFolder() only ────────

    @Override
    public void execute(VolumeConfig volume, VolumeStructureDef structure,
                        VolumeFileSystem fs, SessionContext ctx, CommandIO io) {
        throw new UnsupportedOperationException(
                "FolderRegistrar is not a full sync operation; call registerFolder() instead.");
    }

    // ── Plan (dry-run) ────────────────────────────────────────────────────────────────────────

    /**
     * Returns a dry-run plan for registering the given folder path on the mounted volume.
     * No DB writes are performed.
     *
     * @param titleFolder  resolved absolute path to the title folder on the volume
     * @param volumeId     mounted volume id
     * @param partitionId  derived partition id
     * @param fs           volume filesystem
     * @return plan record, or a refused plan with {@code error} set
     */
    public RegistrationPlan plan(Path titleFolder, String volumeId, String partitionId,
                                 VolumeFileSystem fs) throws IOException {
        String folderName = titleFolder.getFileName().toString();
        TitleCodeParser.ParsedCode parsed = codeParser.parse(folderName);
        if (parsed.label() == null) {
            return RegistrationPlan.refused(parsed.code(),
                    "folder name '" + folderName + "' contains no parseable JAV code (no LABEL-NNN pattern found); "
                    + "refusing to create a name-as-code phantom title");
        }

        boolean isNewTitle = titleRepo.findByCode(parsed.code()).isEmpty();
        String inferred = extractActressName(folderName);
        boolean castInferred = isNewTitle && inferred != null;

        List<Path> videoFiles = listVideoFiles(titleFolder, fs);
        List<String> videoNames = videoFiles.stream()
                .map(p -> p.getFileName().toString())
                .toList();

        return RegistrationPlan.ok(parsed.code(), isNewTitle, partitionId,
                titleFolder.toString(), videoNames, castInferred, inferred);
    }

    // ── Commit ────────────────────────────────────────────────────────────────────────────────

    /**
     * Commits the registration of one title folder into the DB.
     *
     * <p>Steps:
     * <ol>
     *   <li>Parse and validate the folder name (refuse on no-code).</li>
     *   <li>If the title already exists, delete its videos scoped to this folder path on this
     *       volume (idempotency scoped to the one location, keeping other locations intact).</li>
     *   <li>Call {@link #saveTitleAndVideos} (inherited) to upsert the location row and
     *       insert video rows.</li>
     *   <li>If the title is newly created, resolve/create the actress and {@code linkAll}
     *       (credit-inference guard: skip for existing titles).</li>
     * </ol>
     *
     * @param titleFolder  resolved absolute path to the title folder on the volume
     * @param volumeId     mounted volume id
     * @param partitionId  derived partition id
     * @param fs           volume filesystem
     * @return the registration result
     */
    public RegistrationResult register(Path titleFolder, String volumeId, String partitionId,
                                       VolumeFileSystem fs) throws IOException {
        String folderName = titleFolder.getFileName().toString();
        TitleCodeParser.ParsedCode parsed = codeParser.parse(folderName);
        if (parsed.label() == null) {
            return RegistrationResult.refused(parsed.code(),
                    "folder name '" + folderName + "' contains no parseable JAV code (no LABEL-NNN pattern found); "
                    + "refusing to create a name-as-code phantom title");
        }

        boolean isNewTitle = titleRepo.findByCode(parsed.code()).isEmpty();

        // Idempotency: scoped delete of this title's existing videos under this folder
        // on this volume, so re-registration produces no duplicate rows.
        // Only needed when the title already exists (isNewTitle == false); for new titles
        // there are no videos yet. Either way, the location upsert handles itself.
        if (!isNewTitle) {
            var existingTitle = titleRepo.findByCode(parsed.code());
            existingTitle.ifPresent(t ->
                    videoRepo.deleteByTitleVolumeAndPathPrefix(
                            t.getId(), volumeId, titleFolder.toString()));
        }

        // Credit-inference guard: only pass actressId and linkAll for a new title.
        Long filingActressId = null;
        List<Long> castIds = List.of();
        String inferred = null;
        if (isNewTitle) {
            inferred = extractActressName(folderName);
            if (inferred != null) {
                Actress actress = resolveOrCreateActress(inferred, Actress.Tier.LIBRARY);
                filingActressId = actress.getId();
                castIds = List.of(filingActressId);
            }
        }
        // For existing titles: actressId = null → saveTitleAndVideos will NOT overwrite
        // the filing actress (findOrCreateByCode does not update actressId on conflict).
        // Cast is intentionally left untouched.

        Title title = saveTitleAndVideos(titleFolder, volumeId, partitionId, filingActressId, fs);
        titleActressRepo.linkAll(title.getId(), castIds);

        List<Path> videoFiles = listVideoFiles(titleFolder, fs);
        List<String> videoNames = videoFiles.stream()
                .map(p -> p.getFileName().toString())
                .toList();

        return RegistrationResult.ok(title.getId(), parsed.code(), isNewTitle,
                partitionId, titleFolder.toString(), videoNames, inferred != null && isNewTitle);
    }

    // ── Result types ──────────────────────────────────────────────────────────────────────────

    public record RegistrationPlan(
            String code,
            boolean refused,
            String refusalReason,
            boolean isNewTitle,
            String partitionId,
            String path,
            List<String> videosToRegister,
            boolean castInferred,
            String inferredActressName
    ) {
        static RegistrationPlan refused(String code, String reason) {
            return new RegistrationPlan(code, true, reason, false, null, null, List.of(), false, null);
        }

        static RegistrationPlan ok(String code, boolean isNewTitle, String partitionId,
                                   String path, List<String> videos,
                                   boolean castInferred, String inferredName) {
            return new RegistrationPlan(code, false, null, isNewTitle, partitionId, path,
                    videos, castInferred, inferredName);
        }
    }

    public record RegistrationResult(
            Long titleId,
            String code,
            boolean refused,
            String refusalReason,
            boolean isNewTitle,
            String partitionId,
            String path,
            List<String> videosRegistered,
            boolean castInferred
    ) {
        static RegistrationResult refused(String code, String reason) {
            return new RegistrationResult(null, code, true, reason, false, null, null, List.of(), false);
        }

        static RegistrationResult ok(long titleId, String code, boolean isNewTitle,
                                     String partitionId, String path,
                                     List<String> videos, boolean castInferred) {
            return new RegistrationResult(titleId, code, false, null, isNewTitle,
                    partitionId, path, videos, castInferred);
        }
    }
}
