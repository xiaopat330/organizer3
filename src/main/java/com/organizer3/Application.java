package com.organizer3;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.organizer3.ai.ActressNameLookup;
import com.organizer3.backup.BackupScheduler;
import com.organizer3.backup.UserDataBackupService;
import com.organizer3.command.BackupCommand;
import com.organizer3.command.ExportAliasesCommand;
import com.organizer3.command.RestoreCommand;
import com.organizer3.ai.ClaudeActressNameLookup;
import com.organizer3.command.ActressNameCheckService;
import com.organizer3.command.ActressSearchCommand;
import com.organizer3.command.ActressMergeService;
import com.organizer3.command.CheckNamesCommand;
import com.organizer3.command.MergeActressCommand;
import com.organizer3.command.ErrorScanService;
import com.organizer3.command.ScanErrorsCommand;
import com.organizer3.avstars.command.AvActressCommand;
import com.organizer3.avstars.command.AvActressesCommand;
import com.organizer3.avstars.command.AvCurateCommand;
import com.organizer3.avstars.command.AvFavoritesCommand;
import com.organizer3.avstars.command.AvDeleteActressCommand;
import com.organizer3.avstars.command.AvMigrateActressCommand;
import com.organizer3.avstars.command.AvRenameActressCommand;
import com.organizer3.avstars.command.AvParseFilenamesCommand;
import com.organizer3.avstars.command.AvResolveCommand;
import com.organizer3.avstars.command.AvSyncCommand;
import com.organizer3.avstars.iafd.HttpIafdClient;
import com.organizer3.avstars.iafd.IafdProfileParser;
import com.organizer3.avstars.iafd.IafdSearchParser;
import com.organizer3.avstars.AvScreenshotService;
import com.organizer3.avstars.command.AvScreenshotsCommand;
import com.organizer3.avstars.command.AvTagsCommand;
import com.organizer3.avstars.sync.AvFilenameParser;
import com.organizer3.avstars.sync.AvTagYamlLoader;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.avstars.repository.AvTagDefinitionRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.avstars.repository.AvVideoTagRepository;
import com.organizer3.avstars.repository.jdbi.JdbiAvActressRepository;
import com.organizer3.avstars.repository.jdbi.JdbiAvScreenshotRepository;
import com.organizer3.avstars.repository.jdbi.JdbiAvTagDefinitionRepository;
import com.organizer3.avstars.repository.jdbi.JdbiAvVideoRepository;
import com.organizer3.avstars.repository.jdbi.JdbiAvVideoTagRepository;
import com.organizer3.avstars.sync.AvStarsSyncOperation;
import com.organizer3.avstars.web.AvBrowseService;
import com.organizer3.command.ActressesCommand;

import com.organizer3.command.Command;
import com.organizer3.command.FavoritesCommand;
import com.organizer3.command.EnrichActressCommand;
import com.organizer3.command.LoadActressCommand;
import com.organizer3.command.HelpCommand;
import com.organizer3.command.BackgroundThumbsCommand;
import com.organizer3.command.ClearThumbnailsCommand;
import com.organizer3.command.MountCommand;
import com.organizer3.command.PruneCoversCommand;
import com.organizer3.command.PruneThumbnailsCommand;
import com.organizer3.command.RebuildCommand;
import com.organizer3.command.ScanCoversCommand;
import com.organizer3.command.ShutdownCommand;
import com.organizer3.command.SyncCommand;
import com.organizer3.command.UnmountCommand;
import com.organizer3.command.VolumesCommand;
import com.organizer3.covers.CoverPath;
import com.organizer3.config.AppConfig;
import com.organizer3.config.alias.AliasLoader;
import com.organizer3.config.alias.AliasYamlEntry;
import com.organizer3.config.sync.StructureSyncConfig;
import com.organizer3.config.sync.SyncCommandDef;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.OrganizerConfigLoader;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.LabelSeeder;
import com.organizer3.db.TagSeeder;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.SchemaUpgrader;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.media.BackgroundThumbnailQueue;
import com.organizer3.media.BackgroundThumbnailWorker;
import com.organizer3.media.ThumbnailEvictor;
import com.organizer3.media.ThumbnailService;
import com.organizer3.media.UserActivityTracker;
import com.organizer3.media.VideoProbe;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.TitleTagRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiLabelRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiTitleTagRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.repository.jdbi.JdbiVolumeRepository;
import com.organizer3.repository.jdbi.JdbiWatchHistoryRepository;
import com.organizer3.repository.WatchHistoryRepository;
import com.organizer3.shell.CommandDispatcher;
import com.organizer3.shell.OrganizerShell;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.smb.SmbjConnector;
import com.organizer3.sync.FullSyncOperation;
import com.organizer3.sync.IndexLoader;
import com.organizer3.sync.PartitionSyncOperation;
import com.organizer3.sync.SyncOperation;
import com.organizer3.sync.scanner.CollectionsScanner;
import com.organizer3.sync.scanner.ConventionalScanner;
import com.organizer3.sync.scanner.QueueScanner;
import com.organizer3.sync.scanner.ScannerRegistry;
import com.organizer3.sync.scanner.ExhibitionScanner;
import com.organizer3.sync.scanner.SortPoolScanner;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.web.ActressBrowseService;
import com.organizer3.web.SearchService;
import com.organizer3.web.VideoStreamService;
import com.organizer3.web.TitleBrowseService;
import com.organizer3.web.StageNameBackupFile;
import com.organizer3.web.WebServer;
import com.organizer3.web.WebTerminalHandler;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Entry point. Wires all dependencies manually (no IoC container).
 *
 * This class is the composition root — the only place that knows about
 * all the concrete types. Everything else works against interfaces,
 * making each piece independently testable.
 */
@Slf4j
public class Application {
    public static void main(String[] args) throws IOException {
        log.info("Starting Organizer3");

        // Route FFmpeg/JavaCV native logs into SLF4J so they don't pollute stderr.
        com.organizer3.media.FFmpegSlf4jBridge.install();

        // Config
        AppConfig.initialize(new OrganizerConfigLoader().load());
        OrganizerConfig config = AppConfig.get().volumes();

        // Database
        Path dbDir = Path.of(System.getProperty("user.home"), ".organizer3");
        Files.createDirectories(dbDir);
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + dbDir.resolve("organizer.db"));
        new SchemaUpgrader(jdbi).upgrade();
        new SchemaInitializer(jdbi).initialize();
        new TagSeeder(jdbi).seedIfEmpty();
        TitleEffectiveTagsService titleEffectiveTagsService = new TitleEffectiveTagsService(jdbi);
        ActressCompaniesService   actressCompaniesService   = new ActressCompaniesService(jdbi);
        new LabelSeeder(jdbi, titleEffectiveTagsService).seedIfEmpty();

        // Repositories
        TitleLocationRepository titleLocationRepo = new JdbiTitleLocationRepository(jdbi);
        TitleRepository        titleRepo        = new JdbiTitleRepository(jdbi, titleLocationRepo);
        VideoRepository        videoRepo        = new JdbiVideoRepository(jdbi);
        ActressRepository      actressRepo      = new JdbiActressRepository(jdbi);
        VolumeRepository       volumeRepo       = new JdbiVolumeRepository(jdbi);
        LabelRepository        labelRepo        = new JdbiLabelRepository(jdbi);
        TitleTagRepository     tagRepo          = new JdbiTitleTagRepository(jdbi);
        TitleActressRepository titleActressRepo = new JdbiTitleActressRepository(jdbi);
        WatchHistoryRepository watchHistoryRepo = new JdbiWatchHistoryRepository(jdbi);
        IndexLoader indexLoader = new IndexLoader(titleRepo, actressRepo);

        // AV Stars repositories
        AvActressRepository      avActressRepo    = new JdbiAvActressRepository(jdbi);
        AvVideoRepository        avVideoRepo      = new JdbiAvVideoRepository(jdbi);
        AvScreenshotRepository   avScreenshotRepo = new JdbiAvScreenshotRepository(jdbi);
        AvTagDefinitionRepository avTagDefRepo    = new JdbiAvTagDefinitionRepository(jdbi);
        AvVideoTagRepository      avVideoTagRepo  = new JdbiAvVideoTagRepository(jdbi);

        // Seed actress aliases from aliases.yaml
        try (var aliasStream = Application.class.getResourceAsStream("/aliases.yaml")) {
            if (aliasStream != null) {
                List<AliasYamlEntry> aliasEntries = new AliasLoader().load(aliasStream);
                actressRepo.importFromYaml(aliasEntries);
                log.info("Alias seed loaded — entries={}", aliasEntries.size());
            } else {
                log.warn("aliases.yaml not found on classpath — skipping alias seed");
            }
        } catch (Exception e) {
            log.warn("Failed to load aliases.yaml: {}", e.getMessage(), e);
        }

        // Claude API (optional — gracefully disabled if ANTHROPIC_API_KEY is not set)
        ActressNameLookup nameLookup;
        try {
            AnthropicClient anthropicClient = AnthropicOkHttpClient.fromEnv();
            nameLookup = ClaudeActressNameLookup.create(anthropicClient);
            log.info("Claude API initialized — actress kanji lookup available");
        } catch (Exception e) {
            log.warn("ANTHROPIC_API_KEY not set — actress kanji lookup disabled");
            nameLookup = (actress, titles) -> java.util.Optional.empty();
        }

        // Session
        SessionContext session = new SessionContext();

        // Commands
        List<Command> commands = new ArrayList<>();
        commands.add(new ShutdownCommand());
        commands.add(new com.organizer3.command.ArmCommand());
        commands.add(new com.organizer3.command.TestCommand());
        SmbjConnector smbjConnector = new SmbjConnector();
        MountCommand mountCommand = new MountCommand(smbjConnector, indexLoader);
        commands.add(mountCommand);
        commands.add(new UnmountCommand());
        commands.add(new VolumesCommand(mountCommand, volumeRepo));
        commands.add(new ActressesCommand(actressRepo, titleRepo));
        commands.add(new ActressSearchCommand(actressRepo, titleRepo, labelRepo, nameLookup));
        commands.add(new FavoritesCommand(actressRepo, titleRepo));

        ActressYamlLoader yamlLoader = new ActressYamlLoader(actressRepo, titleRepo, tagRepo);
        commands.add(new LoadActressCommand(yamlLoader));
        commands.add(new CheckNamesCommand(actressRepo, new ActressNameCheckService()));
        ActressMergeService actressMergeService = new ActressMergeService(jdbi, titleLocationRepo, actressRepo);
        commands.add(new MergeActressCommand(actressRepo, actressMergeService));
        commands.add(new ScanErrorsCommand(actressRepo, new ErrorScanService()));

        // Scanner registry — maps structure types to their filesystem scanners
        ScannerRegistry scannerRegistry = new ScannerRegistry(Map.of(
                "conventional", new ConventionalScanner(),
                "queue",        new QueueScanner(),
                "exhibition",   new ExhibitionScanner(),
                "sort_pool",    new SortPoolScanner(),
                "collections",  new CollectionsScanner()
        ));

        // Data directory — config → env var → default
        Path dataDir = resolveDataDir(config);
        log.info("Data directory: {}", dataDir);

        // Backup service + commands
        com.organizer3.config.volume.BackupConfig backupCfg = config.backup();
        Path backupPath = dataDir.resolve("backups").resolve("user-data-backup.json");
        int autoBackupInterval = (backupCfg != null && backupCfg.autoBackupIntervalMinutes() != null)
                ? backupCfg.autoBackupIntervalMinutes() : 0;
        int snapshotCount = (backupCfg != null && backupCfg.snapshotCount() != null)
                ? backupCfg.snapshotCount() : 0;
        UserDataBackupService backupService = new UserDataBackupService(actressRepo, titleRepo, watchHistoryRepo, avActressRepo, avVideoRepo);
        commands.add(new BackupCommand(backupService, backupPath, snapshotCount));
        commands.add(new RestoreCommand(backupService, backupPath));
        commands.add(new ExportAliasesCommand(actressRepo, dataDir));

        // Auto-backup scheduler (disabled when interval is 0 or unset)
        BackupScheduler backupScheduler = new BackupScheduler();
        if (autoBackupInterval > 0) {
            backupScheduler.start(backupService, backupPath, autoBackupInterval, snapshotCount);
            log.info("Backup scheduler active — intervalMinutes={} snapshotsRetained={} path={}",
                    autoBackupInterval, snapshotCount, backupPath);
        } else {
            log.info("Backup scheduler disabled (autoBackupIntervalMinutes not set or 0)");
        }

        // AV Stars commands
        Path avHeadshotDir    = dataDir.resolve("av_headshots");
        Path avScreenshotDir  = dataDir.resolve("av_screenshots");
        com.organizer3.avstars.cleanup.AvArtifactCleaner avArtifactCleaner =
                new com.organizer3.avstars.cleanup.AvArtifactCleaner(avScreenshotDir, avHeadshotDir);
        AvStarsSyncOperation avStarsSyncOp = new AvStarsSyncOperation(avActressRepo, avVideoRepo, volumeRepo, avArtifactCleaner);
        AvFilenameParser avFilenameParser = new AvFilenameParser();
        commands.add(new AvSyncCommand(avStarsSyncOp));
        commands.add(new AvActressesCommand(avActressRepo));
        commands.add(new AvActressCommand(avActressRepo, avVideoRepo));
        commands.add(new AvFavoritesCommand(avActressRepo));
        commands.add(new AvCurateCommand(avActressRepo));
        commands.add(new AvMigrateActressCommand(avActressRepo));
        commands.add(new AvRenameActressCommand(avActressRepo));
        commands.add(new AvDeleteActressCommand(avActressRepo, avVideoRepo, avArtifactCleaner));
        commands.add(new AvParseFilenamesCommand(avVideoRepo, avFilenameParser));
        commands.add(new AvResolveCommand(avActressRepo, new HttpIafdClient(),
                new IafdSearchParser(), new IafdProfileParser(), avHeadshotDir));
        AvScreenshotService avScreenshotService = new AvScreenshotService(avScreenshotRepo, avScreenshotDir, WebServer.DEFAULT_PORT);
        commands.add(new AvScreenshotsCommand(avActressRepo, avVideoRepo, avScreenshotRepo, avScreenshotService));
        AvTagYamlLoader avTagYamlLoader = new AvTagYamlLoader(avTagDefRepo);
        commands.add(new AvTagsCommand(avTagDefRepo, avVideoTagRepo, avTagYamlLoader,
                dataDir.resolve("av_tags.yaml")));

        // Cover image commands
        CoverPath coverPath = new CoverPath(dataDir);
        commands.add(new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry));
        commands.add(new PruneCoversCommand(titleRepo, coverPath));

        // Organize-pipeline commands — title-folder timestamp correction
        com.organizer3.organize.TitleTimestampService titleTimestampService =
                new com.organizer3.organize.TitleTimestampService();
        com.organizer3.organize.FixTimestampsVolumeService fixTimestampsVolumeService =
                new com.organizer3.organize.FixTimestampsVolumeService(jdbi, titleTimestampService);
        commands.add(new com.organizer3.command.FixTitleTimestampsCommand(jdbi, titleTimestampService));
        commands.add(new com.organizer3.command.AuditTimestampsCommand(jdbi, titleTimestampService));

        // Organize-pipeline commands — phase 1: normalize filenames
        com.organizer3.organize.TitleNormalizerService titleNormalizerService =
                new com.organizer3.organize.TitleNormalizerService(config.mediaOrDefaults(), config.normalizeOrEmpty());
        commands.add(new com.organizer3.command.NormalizeTitleCommand(jdbi, titleNormalizerService));

        // Organize-pipeline commands — phase 2: restructure title folder
        com.organizer3.organize.TitleRestructurerService titleRestructurerService =
                new com.organizer3.organize.TitleRestructurerService(config.mediaOrDefaults());
        commands.add(new com.organizer3.command.RestructureTitleCommand(jdbi, titleRestructurerService));

        // Organize-pipeline commands — phase 3: sort title from queue → /stars/{tier}/{actress}/
        com.organizer3.organize.TitleSorterService titleSorterService =
                new com.organizer3.organize.TitleSorterService(
                        titleRepo, actressRepo, titleActressRepo, titleLocationRepo,
                        config.libraryOrDefaults(), titleTimestampService);
        commands.add(new com.organizer3.command.SortTitleCommand(jdbi, config, titleSorterService));

        // Organize-pipeline commands — phase 4: re-tier actress by current title count
        com.organizer3.organize.ActressClassifierService actressClassifierService =
                new com.organizer3.organize.ActressClassifierService(
                        actressRepo, titleRepo, titleLocationRepo, config.libraryOrDefaults());
        commands.add(new com.organizer3.command.ClassifyActressCommand(jdbi, config, actressClassifierService));

        // Organize-pipeline commands — composite: walk queue, run phases 1-3 per title, phase 4 per actress
        com.organizer3.organize.OrganizeVolumeService organizeVolumeService =
                new com.organizer3.organize.OrganizeVolumeService(
                        titleRepo, titleLocationRepo,
                        titleNormalizerService, titleRestructurerService,
                        titleSorterService, actressClassifierService);
        commands.add(new com.organizer3.command.OrganizeVolumeCommand(jdbi, config, organizeVolumeService));

        // Organize-pipeline commands — prep: raw videos in queue partition → (CODE)/<video|h265>/ skeletons
        com.organizer3.organize.FreshPrepService freshPrepService =
                new com.organizer3.organize.FreshPrepService(
                        config.normalizeOrEmpty(), config.mediaOrDefaults());
        commands.add(new com.organizer3.command.PrepFreshCommand(config, freshPrepService));

        // Organize-pipeline commands — audit: read-only classification of prepped skeletons by graduation readiness
        com.organizer3.organize.FreshAuditService freshAuditService =
                new com.organizer3.organize.FreshAuditService(config.mediaOrDefaults());
        commands.add(new com.organizer3.command.AuditFreshCommand(config, freshAuditService));

        // Thumbnail service — created early so commands can reference it
        int thumbnailInterval = config.thumbnailInterval() != null ? config.thumbnailInterval() : 8;
        ThumbnailService thumbnailService = new ThumbnailService(
                dataDir.resolve("thumbnails"), thumbnailInterval, WebServer.DEFAULT_PORT);
        commands.add(new PruneThumbnailsCommand(titleRepo, thumbnailService));
        commands.add(new ClearThumbnailsCommand(thumbnailService));

        // NAS availability monitor — probes SMB port 445 on each host so background tasks
        // can pause gracefully when a NAS is unreachable instead of flooding logs.
        com.organizer3.smb.NasAvailabilityMonitor nasMonitor =
                new com.organizer3.smb.NasAvailabilityMonitor(config);
        nasMonitor.start(); // synchronous initial probe — gives accurate state before background tasks start

        // Background thumbnail sync — see spec/PROPOSAL_BACKGROUND_THUMBNAILS.md
        UserActivityTracker activityTracker = new UserActivityTracker();
        BackgroundThumbnailQueue bgQueue = new BackgroundThumbnailQueue(jdbi);
        ThumbnailEvictor bgEvictor = new ThumbnailEvictor(jdbi, thumbnailService);
        BackgroundThumbnailWorker bgWorker = new BackgroundThumbnailWorker(
                config.backgroundThumbnailsOrDefaults(),
                bgQueue, thumbnailService, bgEvictor, videoRepo, activityTracker, nasMonitor);
        // Apply persisted enable-state (set via the web chip) over the YAML default, so the
        // user doesn't have to re-enable after every restart.
        com.organizer3.media.BgThumbnailsState bgThumbnailsState =
                new com.organizer3.media.BgThumbnailsState(dataDir);
        Boolean persisted = bgThumbnailsState.readEnabled();
        if (persisted != null) {
            bgWorker.setEnabled(persisted);
            log.info("Background thumbnails: applied persisted state enabled={} from {}",
                    persisted, dataDir.resolve("bg-thumbnails-state.json"));
        } else {
            log.info("Background thumbnails: no persisted state at {}, using config default enabled={}",
                    dataDir.resolve("bg-thumbnails-state.json"), bgWorker.isEnabled());
        }
        commands.add(new BackgroundThumbsCommand(bgWorker));

        // javdb enrichment runner — see spec/PROPOSAL_JAVDB_ENRICHMENT.md
        com.organizer3.javdb.JavdbConfig javdbConfig = config.javdbOrDefaults();
        com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.organizer3.javdb.HttpJavdbClient javdbClient = new com.organizer3.javdb.HttpJavdbClient(javdbConfig);
        com.organizer3.javdb.enrichment.JavdbStagingRepository javdbStagingRepo =
                new com.organizer3.javdb.enrichment.JavdbStagingRepository(jdbi, jsonMapper, dataDir);
        com.organizer3.javdb.enrichment.EnrichmentHistoryRepository enrichmentHistoryRepo =
                new com.organizer3.javdb.enrichment.EnrichmentHistoryRepository(jdbi, jsonMapper);
        com.organizer3.javdb.enrichment.JavdbEnrichmentRepository javdbEnrichmentRepo =
                new com.organizer3.javdb.enrichment.JavdbEnrichmentRepository(jdbi, jsonMapper, titleEffectiveTagsService, enrichmentHistoryRepo);
        com.organizer3.javdb.enrichment.EnrichmentQueue enrichmentQueue =
                new com.organizer3.javdb.enrichment.EnrichmentQueue(jdbi, javdbConfig);
        com.organizer3.javdb.enrichment.AutoPromoter autoPromoter =
                new com.organizer3.javdb.enrichment.AutoPromoter(jdbi);
        com.organizer3.javdb.enrichment.ActressAvatarStore avatarStore =
                new com.organizer3.javdb.enrichment.ActressAvatarStore(dataDir);
        com.organizer3.rating.RatingCurveRepository ratingCurveRepo =
                new com.organizer3.rating.JdbiRatingCurveRepository(jdbi);
        com.organizer3.rating.RatingScoreCalculator ratingScoreCalculator =
                new com.organizer3.rating.RatingScoreCalculator();
        com.organizer3.rating.RatingCurveRecomputer ratingCurveRecomputer =
                new com.organizer3.rating.RatingCurveRecomputer(jdbi, ratingCurveRepo, ratingScoreCalculator);
        com.organizer3.rating.EnrichmentGradeStamper enrichmentGradeStamper =
                new com.organizer3.rating.EnrichmentGradeStamper(ratingCurveRepo, ratingScoreCalculator, titleRepo);
        com.organizer3.javdb.enrichment.ProfileChainGate profileChainGate =
                new com.organizer3.javdb.enrichment.ProfileChainGate(jdbi, javdbConfig);
        com.organizer3.javdb.enrichment.RevalidationPendingRepository revalidationPendingRepo =
                new com.organizer3.javdb.enrichment.RevalidationPendingRepository(jdbi);
        com.organizer3.javdb.enrichment.RevalidationService revalidationService =
                new com.organizer3.javdb.enrichment.RevalidationService(jdbi);
        com.organizer3.config.volume.EnrichmentConfig enrichmentConfig = config.enrichmentOrDefaults();
        com.organizer3.javdb.enrichment.RevalidationCronScheduler revalidationCronScheduler =
                new com.organizer3.javdb.enrichment.RevalidationCronScheduler(
                        revalidationService, revalidationPendingRepo,
                        enrichmentConfig.revalidationCronOrDefaults().drainBatchSizeOrDefault(),
                        enrichmentConfig.revalidationCronOrDefaults().safetyNetBatchSizeOrDefault());
        com.organizer3.javdb.enrichment.JavdbActressFilmographyRepository filmographyRepo =
                new com.organizer3.javdb.enrichment.JdbiJavdbActressFilmographyRepository(jdbi, revalidationPendingRepo);
        com.organizer3.javdb.enrichment.FilmographyBackupWriter filmographyBackupWriter =
                new com.organizer3.javdb.enrichment.FilmographyBackupWriter(dataDir);
        com.organizer3.javdb.enrichment.JavdbSlugResolver slugResolver =
                new com.organizer3.javdb.enrichment.JavdbSlugResolver(javdbClient, filmographyRepo, javdbConfig, filmographyBackupWriter);
        com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository enrichmentReviewQueueRepo =
                new com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository(jdbi);
        com.organizer3.javdb.enrichment.CastMatcher castMatcher =
                new com.organizer3.javdb.enrichment.CastMatcher(actressRepo);
        com.organizer3.javdb.enrichment.EnrichmentRunner enrichmentRunner =
                new com.organizer3.javdb.enrichment.EnrichmentRunner(
                        javdbConfig, javdbClient, slugResolver,
                        new com.organizer3.javdb.enrichment.JavdbExtractor(),
                        new com.organizer3.javdb.enrichment.JavdbProjector(jsonMapper),
                        javdbStagingRepo, javdbEnrichmentRepo,
                        enrichmentQueue, titleRepo, actressRepo, autoPromoter, avatarStore,
                        enrichmentGradeStamper, ratingCurveRecomputer, profileChainGate, titleActressRepo,
                        enrichmentReviewQueueRepo, castMatcher, jdbi, revalidationPendingRepo);
        new com.organizer3.javdb.enrichment.EnrichmentProvenanceBackfillTask(jdbi).run();
        commands.add(new EnrichActressCommand(actressRepo, titleRepo, enrichmentQueue));

        // Sync commands — registered dynamically from syncConfig.
        // Group by term so that a term shared across structure types (e.g. sync all)
        // produces a single command that accepts all of those types.
        Map<String, SyncCommandDef> defByTerm = new HashMap<>();
        Map<String, Set<String>> structureTypesByTerm = new HashMap<>();
        for (StructureSyncConfig structureSyncConfig : config.syncConfig()) {
            String structureType = structureSyncConfig.structureType();
            for (SyncCommandDef def : structureSyncConfig.commands()) {
                defByTerm.putIfAbsent(def.term(), def);
                structureTypesByTerm.computeIfAbsent(def.term(), k -> new HashSet<>()).add(structureType);
            }
        }
        Command syncAllCommand = null;
        for (Map.Entry<String, SyncCommandDef> entry : defByTerm.entrySet()) {
            String term = entry.getKey();
            SyncCommandDef def = entry.getValue();
            SyncOperation op = switch (def.operation()) {
                case FULL ->
                    new FullSyncOperation(scannerRegistry, titleRepo, videoRepo, actressRepo,
                            volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                            titleEffectiveTagsService, actressCompaniesService, coverPath,
                            revalidationPendingRepo);
                case PARTITION ->
                    new PartitionSyncOperation(def.partitions(), titleRepo, videoRepo,
                            actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                            titleEffectiveTagsService, actressCompaniesService, coverPath,
                            revalidationPendingRepo);
            };
            SyncCommand syncCmd = new SyncCommand(term, structureTypesByTerm.get(term), op);
            commands.add(syncCmd);
            if ("sync all".equals(term)) syncAllCommand = syncCmd;
        }

        if (syncAllCommand != null) {
            ScanCoversCommand scanCovers = (ScanCoversCommand) commands.stream()
                    .filter(c -> "sync covers".equals(c.name()))
                    .findFirst().orElseThrow();
            commands.add(new RebuildCommand(syncAllCommand, scanCovers));
        }

        commands.add(new HelpCommand(commands));

        // Web server (read-only browsing)
        Map<String, String> volumeSmbPaths = config.volumes().stream()
                .filter(v -> !"avstars".equals(v.structureType()))
                .collect(Collectors.toMap(VolumeConfig::id, VolumeConfig::smbPath));
        TitleBrowseService browseService = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, titleActressRepo, watchHistoryRepo, volumeSmbPaths);
        StageNameBackupFile stageNameBackup = new StageNameBackupFile(
                dbDir.resolve("stagenames.yaml"));
        ActressBrowseService actressBrowseService = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, volumeSmbPaths, labelRepo, nameLookup, stageNameBackup, jdbi);
        SearchService searchService = new SearchService(actressRepo, titleRepo, labelRepo, coverPath, avActressRepo);

        // Video streaming + metadata
        SmbConnectionFactory smbConnectionFactory = new SmbConnectionFactory(config, nasMonitor);
        VideoStreamService videoStreamService = new VideoStreamService(titleRepo, videoRepo, smbConnectionFactory);
        VideoProbe videoProbe = new VideoProbe(WebServer.DEFAULT_PORT);
        commands.add(new com.organizer3.command.ProbeVideosCommand(videoRepo, videoProbe::probe));
        commands.add(new com.organizer3.command.BackfillSizesCommand(videoRepo));
        com.organizer3.media.ProbeJobRunner probeJobRunner =
                new com.organizer3.media.ProbeJobRunner(videoRepo, videoProbe::probe);
        CommandDispatcher dispatcher = new CommandDispatcher(commands);

        WebServer webServer = new WebServer(browseService, actressBrowseService, coverPath.root(),
                videoStreamService, thumbnailService, videoProbe, watchHistoryRepo, titleRepo, searchService);
        AvBrowseService avBrowseService = new AvBrowseService(avActressRepo, avVideoRepo, avScreenshotRepo, avVideoTagRepo);
        webServer.registerAvRoutes(avBrowseService,
                avHeadshotDir, smbConnectionFactory, avVideoRepo, avActressRepo,
                avScreenshotRepo, avScreenshotDir, avTagDefRepo, avScreenshotService);
        webServer.registerTerminal(new WebTerminalHandler(dispatcher, session));
        webServer.registerActivityTracker(activityTracker);
        webServer.registerActressMerge(
                new com.organizer3.web.routes.ActressMergeRoutes(jdbi, actressRepo));
        webServer.registerAvatarRoutes(
                new com.organizer3.web.routes.AvatarRoutes(dataDir.resolve("actress-avatars")));

        // In-app log viewer (Tools → Logs). Path matches logback.xml's RollingFileAppender.
        webServer.registerLogRoutes(
                new com.organizer3.web.routes.LogRoutes(java.nio.file.Paths.get("logs/organizer3.log")));

        // Utilities — maintenance UI (Tools → Volumes, ...). See spec/PROPOSAL_UTILITIES.md.
        java.util.Map<String, com.organizer3.command.Command> commandsByName = new java.util.LinkedHashMap<>();
        for (com.organizer3.command.Command c : commands) {
            commandsByName.put(c.name(), c);
        }
        com.organizer3.utilities.volume.StaleLocationsService staleLocationsService =
                new com.organizer3.utilities.volume.StaleLocationsService(jdbi);
        com.organizer3.utilities.volume.VolumeStateService volumeStateService =
                new com.organizer3.utilities.volume.VolumeStateService(volumeRepo, titleRepo, staleLocationsService, jdbi);
        com.organizer3.utilities.task.volume.SyncVolumeTask syncVolumeTask =
                new com.organizer3.utilities.task.volume.SyncVolumeTask(() ->
                        new com.organizer3.utilities.task.CommandInvoker(
                                commandsByName, new com.organizer3.shell.SessionContext()));
        com.organizer3.utilities.task.volume.CleanStaleLocationsTask cleanStaleLocationsTask =
                new com.organizer3.utilities.task.volume.CleanStaleLocationsTask(staleLocationsService);
        // Actress data — catalog + load tasks.
        com.organizer3.utilities.actress.ActressYamlCatalogService actressCatalogService =
                new com.organizer3.utilities.actress.ActressYamlCatalogService(yamlLoader, actressRepo);
        com.organizer3.utilities.task.actress.LoadActressTask loadActressTask =
                new com.organizer3.utilities.task.actress.LoadActressTask(yamlLoader);
        com.organizer3.utilities.task.actress.LoadAllActressesTask loadAllActressesTask =
                new com.organizer3.utilities.task.actress.LoadAllActressesTask(yamlLoader);
        com.organizer3.utilities.task.actress.SyncYamlGradesTask syncYamlGradesTask =
                new com.organizer3.utilities.task.actress.SyncYamlGradesTask(yamlLoader);

        // Backup & restore — catalog + tasks.
        com.organizer3.utilities.backup.BackupCatalogService backupCatalogService =
                new com.organizer3.utilities.backup.BackupCatalogService(backupService, backupPath);
        com.organizer3.utilities.task.backup.BackupNowTask backupNowTask =
                new com.organizer3.utilities.task.backup.BackupNowTask(
                        backupService, backupPath, snapshotCount);
        com.organizer3.utilities.task.backup.RestoreSnapshotTask restoreSnapshotTask =
                new com.organizer3.utilities.task.backup.RestoreSnapshotTask(
                        backupService, backupCatalogService);

        // Local-covers cleanup — shared service used by both the Library Health check and the
        // bulk delete task so they evaluate the same predicate.
        com.organizer3.utilities.covers.OrphanedCoversService orphanedCoversService =
                new com.organizer3.utilities.covers.OrphanedCoversService(coverPath, titleRepo);
        com.organizer3.utilities.task.covers.CleanOrphanedCoversTask cleanOrphanedCoversTask =
                new com.organizer3.utilities.task.covers.CleanOrphanedCoversTask(orphanedCoversService);

        // Library Health — pluggable check list; add new checks by appending below.
        java.util.List<com.organizer3.utilities.health.LibraryHealthCheck> healthChecks =
                java.util.List.of(
                        new com.organizer3.utilities.health.checks.StaleLocationsCheck(jdbi),
                        new com.organizer3.utilities.health.checks.OrphanedCoversCheck(orphanedCoversService),
                        new com.organizer3.utilities.health.checks.TitlesWithoutCoversCheck(titleRepo, coverPath),
                        new com.organizer3.utilities.health.checks.UnloadedYamlsCheck(yamlLoader, actressRepo),
                        new com.organizer3.utilities.health.checks.UnresolvedAliasesCheck(jdbi),
                        new com.organizer3.utilities.health.checks.DuplicateCodesCheck(jdbi),
                        new com.organizer3.utilities.health.checks.LowConfidenceEnrichmentCheck(jdbi),
                        new com.organizer3.utilities.health.checks.EnrichmentReviewQueueCheck(jdbi),
                        new com.organizer3.utilities.health.checks.StaleFilmographyCacheCheck(jdbi));
        com.organizer3.utilities.health.LibraryHealthReportStore healthReportStore =
                new com.organizer3.utilities.health.LibraryHealthReportStore(
                        dataDir.resolve("library-health-report.json"));
        com.organizer3.utilities.health.LibraryHealthService libraryHealthService =
                new com.organizer3.utilities.health.LibraryHealthService(healthChecks, healthReportStore);
        com.organizer3.utilities.task.health.ScanLibraryTask scanLibraryTask =
                new com.organizer3.utilities.task.health.ScanLibraryTask(libraryHealthService);

        // AV Stars Utilities — curation screen over existing AV pipeline.
        com.organizer3.utilities.avstars.AvStarsCatalogService avStarsCatalog =
                new com.organizer3.utilities.avstars.AvStarsCatalogService(avActressRepo, avVideoRepo);
        com.organizer3.utilities.avstars.IafdResolverService iafdResolver =
                new com.organizer3.utilities.avstars.IafdResolverService(
                        avActressRepo, new HttpIafdClient(),
                        new IafdSearchParser(), new IafdProfileParser(), avHeadshotDir);
        com.organizer3.utilities.task.avstars.ResolveIafdTask resolveIafdTask =
                new com.organizer3.utilities.task.avstars.ResolveIafdTask(iafdResolver);
        com.organizer3.utilities.task.avstars.RenameAvActressTask renameAvActressTask =
                new com.organizer3.utilities.task.avstars.RenameAvActressTask(avActressRepo);
        com.organizer3.utilities.task.avstars.DeleteAvActressTask deleteAvActressTask =
                new com.organizer3.utilities.task.avstars.DeleteAvActressTask(avActressRepo, avVideoRepo, avArtifactCleaner);
        com.organizer3.utilities.task.avstars.ParseFilenamesTask parseFilenamesTask =
                new com.organizer3.utilities.task.avstars.ParseFilenamesTask(avVideoRepo, avFilenameParser);

        com.organizer3.repository.DuplicateDecisionRepository dupDecisionRepo =
                new com.organizer3.repository.jdbi.JdbiDuplicateDecisionRepository(jdbi);
        com.organizer3.utilities.task.duplicates.ExecuteDuplicateTrashTask executeDuplicateTrashTask =
                new com.organizer3.utilities.task.duplicates.ExecuteDuplicateTrashTask(
                        dupDecisionRepo, titleLocationRepo, config, smbConnectionFactory, jdbi);

        com.organizer3.repository.MergeCandidateRepository mergeCandidateRepo =
                new com.organizer3.repository.jdbi.JdbiMergeCandidateRepository(jdbi);
        com.organizer3.utilities.task.duplicates.DetectMergeCandidatesTask detectMergeCandidatesTask =
                new com.organizer3.utilities.task.duplicates.DetectMergeCandidatesTask(mergeCandidateRepo, jdbi);
        com.organizer3.utilities.task.duplicates.ExecuteMergeTask executeMergeTask =
                new com.organizer3.utilities.task.duplicates.ExecuteMergeTask(mergeCandidateRepo, jdbi);

        com.organizer3.trash.TrashService trashService = new com.organizer3.trash.TrashService();
        com.organizer3.trash.TrashSweepScheduler trashSweepScheduler =
                new com.organizer3.trash.TrashSweepScheduler(trashService, smbConnectionFactory, config, nasMonitor);
        com.organizer3.utilities.task.trash.TrashScheduleTask trashScheduleTask =
                new com.organizer3.utilities.task.trash.TrashScheduleTask(trashService, smbConnectionFactory);
        com.organizer3.utilities.task.trash.TrashRestoreTask trashRestoreTask =
                new com.organizer3.utilities.task.trash.TrashRestoreTask(trashService, smbConnectionFactory);
        com.organizer3.utilities.task.trash.TrashUnscheduleTask trashUnscheduleTask =
                new com.organizer3.utilities.task.trash.TrashUnscheduleTask(trashService, smbConnectionFactory);

        // Organize pipeline tasks — preview (dryRun) + execute for each phase action.
        java.util.function.Supplier<com.organizer3.utilities.task.CommandInvoker> organizeInvokerFactory =
                () -> new com.organizer3.utilities.task.CommandInvoker(
                        commandsByName, new com.organizer3.shell.SessionContext());
        com.organizer3.utilities.task.organize.PrepPreviewTask prepPreviewTask =
                new com.organizer3.utilities.task.organize.PrepPreviewTask(
                        freshPrepService, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.PrepTask prepTask =
                new com.organizer3.utilities.task.organize.PrepTask(
                        freshPrepService, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeNormalizePreviewTask organizeNormalizePreviewTask =
                new com.organizer3.utilities.task.organize.OrganizeNormalizePreviewTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeNormalizeTask organizeNormalizeTask =
                new com.organizer3.utilities.task.organize.OrganizeNormalizeTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeRestructurePreviewTask organizeRestructurePreviewTask =
                new com.organizer3.utilities.task.organize.OrganizeRestructurePreviewTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeRestructureTask organizeRestructureTask =
                new com.organizer3.utilities.task.organize.OrganizeRestructureTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeSortPreviewTask organizeSortPreviewTask =
                new com.organizer3.utilities.task.organize.OrganizeSortPreviewTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeSortTask organizeSortTask =
                new com.organizer3.utilities.task.organize.OrganizeSortTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeClassifyPreviewTask organizeClassifyPreviewTask =
                new com.organizer3.utilities.task.organize.OrganizeClassifyPreviewTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeClassifyTask organizeClassifyTask =
                new com.organizer3.utilities.task.organize.OrganizeClassifyTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeAllPreviewTask organizeAllPreviewTask =
                new com.organizer3.utilities.task.organize.OrganizeAllPreviewTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.OrganizeAllTask organizeAllTask =
                new com.organizer3.utilities.task.organize.OrganizeAllTask(
                        organizeVolumeService, jdbi, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.FixTimestampsPreviewTask fixTimestampsPreviewTask =
                new com.organizer3.utilities.task.organize.FixTimestampsPreviewTask(
                        fixTimestampsVolumeService, config, organizeInvokerFactory);
        com.organizer3.utilities.task.organize.FixTimestampsTask fixTimestampsTask =
                new com.organizer3.utilities.task.organize.FixTimestampsTask(
                        fixTimestampsVolumeService, config, organizeInvokerFactory);

        com.organizer3.utilities.task.rating.RecomputeRatingCurveTask recomputeRatingCurveTask =
                new com.organizer3.utilities.task.rating.RecomputeRatingCurveTask(ratingCurveRecomputer);

        com.organizer3.utilities.task.javdb.EnrichmentClearMismatchedTask enrichmentClearMismatchedTask =
                new com.organizer3.utilities.task.javdb.EnrichmentClearMismatchedTask(
                        jdbi, slugResolver, enrichmentQueue, revalidationPendingRepo);

        com.organizer3.utilities.task.TaskRegistry taskRegistry =
                new com.organizer3.utilities.task.TaskRegistry(
                        java.util.List.of(syncVolumeTask, cleanStaleLocationsTask,
                                loadActressTask, loadAllActressesTask, syncYamlGradesTask,
                                backupNowTask, restoreSnapshotTask,
                                scanLibraryTask, cleanOrphanedCoversTask,
                                resolveIafdTask, renameAvActressTask, deleteAvActressTask, parseFilenamesTask,
                                executeDuplicateTrashTask, detectMergeCandidatesTask, executeMergeTask,
                                trashScheduleTask, trashRestoreTask, trashUnscheduleTask,
                                prepPreviewTask, prepTask,
                                organizeNormalizePreviewTask, organizeNormalizeTask,
                                organizeRestructurePreviewTask, organizeRestructureTask,
                                organizeSortPreviewTask, organizeSortTask,
                                organizeClassifyPreviewTask, organizeClassifyTask,
                                organizeAllPreviewTask, organizeAllTask,
                                fixTimestampsPreviewTask, fixTimestampsTask,
                                recomputeRatingCurveTask, enrichmentClearMismatchedTask));
        com.organizer3.utilities.task.TaskRunner taskRunner =
                new com.organizer3.utilities.task.TaskRunner(taskRegistry);
        webServer.registerUtilities(new com.organizer3.web.routes.UtilitiesRoutes(
                volumeStateService, staleLocationsService, actressCatalogService, yamlLoader,
                backupCatalogService, backupService, libraryHealthService, orphanedCoversService,
                ratingCurveRepo, taskRegistry, taskRunner));
        webServer.registerAvStars(new com.organizer3.web.routes.AvStarsRoutes(
                avStarsCatalog, avBrowseService, iafdResolver));
        webServer.registerTrash(new com.organizer3.web.routes.TrashRoutes(
                trashService, smbConnectionFactory, taskRegistry, taskRunner));

        webServer.registerDuplicateDecisions(
                new com.organizer3.web.routes.DuplicateDecisionsRoutes(dupDecisionRepo));
        webServer.registerMergeCandidates(
                new com.organizer3.web.routes.MergeCandidatesRoutes(mergeCandidateRepo));

        webServer.registerJavdbDiscovery(new com.organizer3.web.routes.JavdbDiscoveryRoutes(
                new com.organizer3.web.JavdbDiscoveryService(jdbi, enrichmentRunner),
                new com.organizer3.web.JavdbEnrichmentActionService(titleRepo, enrichmentQueue, enrichmentRunner,
                        javdbStagingRepo, avatarStore)));

        webServer.registerTitleDiscovery(new com.organizer3.web.routes.TitleDiscoveryRoutes(
                new com.organizer3.web.TitleDiscoveryService(jdbi, config, profileChainGate, enrichmentQueue)));

        webServer.registerBgThumbnails(new com.organizer3.web.routes.BgThumbnailsRoutes(
                bgWorker, bgThumbnailsState));

        // Title-detail tag editor (direct + label-implied state, save direct tags).
        webServer.registerTitleTagEditor(
                new com.organizer3.web.routes.TitleTagEditorRoutes(jdbi, titleRepo, titleEffectiveTagsService));

        // Title Editor — metadata preparation for fully-structured titles in the unsorted volume.
        // See spec/PROPOSAL_TITLE_EDITOR.md.
        final String UNSORTED_VOLUME_ID = "unsorted";
        com.organizer3.repository.UnsortedEditorRepository unsortedRepo =
                new com.organizer3.repository.jdbi.JdbiUnsortedEditorRepository(jdbi);
        com.organizer3.web.UnsortedEditorService unsortedEditorService =
                new com.organizer3.web.UnsortedEditorService(unsortedRepo, actressRepo, coverPath,
                        smbConnectionFactory, UNSORTED_VOLUME_ID);
        com.organizer3.web.CoverWriteService coverWriteService =
                new com.organizer3.web.CoverWriteService(smbConnectionFactory, coverPath, UNSORTED_VOLUME_ID);
        com.organizer3.web.ImageFetcher imageFetcher = new com.organizer3.web.ImageFetcher();
        webServer.registerUnsortedEditor(new com.organizer3.web.routes.UnsortedEditorRoutes(
                unsortedEditorService, coverWriteService, imageFetcher, coverPath));

        // MCP (Model Context Protocol) server — read-only diagnostic tools mounted on
        // the existing Javalin instance. See spec/PROPOSAL_MCP_SERVER.md.
        com.organizer3.mcp.McpConfig mcpConfig = AppConfig.get().volumes().mcp() != null
                ? AppConfig.get().volumes().mcp()
                : com.organizer3.mcp.McpConfig.defaults();
        com.organizer3.mcp.ReadOnlyDb mcpRoDb = null;
        if (mcpConfig.isEnabled()) {
            mcpRoDb = new com.organizer3.mcp.ReadOnlyDb(dbDir.resolve("organizer.db"));
            com.organizer3.mcp.ToolRegistry mcpTools = new com.organizer3.mcp.ToolRegistry()
                    .register(new com.organizer3.mcp.tools.ListVolumesTool(session))
                    .register(new com.organizer3.mcp.tools.GetStatsTool(jdbi))
                    .register(new com.organizer3.mcp.tools.DescribeSchemaTool())
                    .register(new com.organizer3.mcp.tools.ReadLogTool(java.nio.file.Paths.get("logs/organizer3.log")))
                    .register(new com.organizer3.mcp.tools.LookupActressTool(actressRepo, titleRepo))
                    .register(new com.organizer3.mcp.tools.LookupTitleTool(
                            titleRepo, titleActressRepo, actressRepo, videoRepo))
                    .register(new com.organizer3.mcp.tools.ListTitlesForActressTool(actressRepo, titleRepo))
                    .register(new com.organizer3.mcp.tools.FindSimilarActressesTool(actressRepo))
                    .register(new com.organizer3.mcp.tools.FindNameOrderVariantsTool(actressRepo))
                    .register(new com.organizer3.mcp.tools.FindSuspectCreditsTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindAliasConflictsTool(actressRepo))
                    .register(new com.organizer3.mcp.tools.FindLoneTitlesTool(actressRepo))
                    .register(new com.organizer3.mcp.tools.FindOrphanTitlesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindDuplicateBaseCodesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindLabelMismatchesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindEnrichmentCastMismatchesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.BackfillActressSlugsFromCastTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindStaleLocationsTool(jdbi))
                    .register(new com.organizer3.mcp.tools.ListActressesWithMisnamedFoldersTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindMisnamedFoldersForActressTool(jdbi, actressRepo))
                    .register(new com.organizer3.mcp.tools.ListMultiVideoTitlesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.AnalyzeTitleVideosTool(titleRepo, videoRepo))
                    .register(new com.organizer3.mcp.tools.FindDuplicateCandidatesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindSizeVariantTitlesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.ListDuplicateDecisionsTool(dupDecisionRepo))
                    .register(new com.organizer3.mcp.tools.ListMergeCandidatesTool(mergeCandidateRepo))
                    .register(new com.organizer3.mcp.tools.FindMultiCoverTitlesTool(session, jdbi))
                    .register(new com.organizer3.mcp.tools.FindMisfiledCoversTool(session, jdbi))
                    .register(new com.organizer3.mcp.tools.ScanTitleFolderAnomaliesTool(session, titleRepo, titleLocationRepo))
                    .register(new com.organizer3.mcp.tools.MountStatusTool(session))
                    .register(new com.organizer3.mcp.tools.ProbeVideosBatchTool(session, videoRepo, videoProbe::probe))
                    .register(new com.organizer3.mcp.tools.ProbeSizeVariantsBatchTool(videoRepo, videoProbe::probe))
                    .register(new com.organizer3.mcp.tools.BackfillSizesBatchTool(session, videoRepo))
                    .register(new com.organizer3.mcp.tools.StartProbeJobTool(session, probeJobRunner))
                    .register(new com.organizer3.mcp.tools.ProbeJobStatusTool(probeJobRunner))
                    .register(new com.organizer3.mcp.tools.CancelProbeJobTool(probeJobRunner));
            if (mcpConfig.networkOpsAllowed()) {
                mcpTools.register(new com.organizer3.mcp.tools.MountVolumeTool(session, smbjConnector, indexLoader));
                mcpTools.register(new com.organizer3.mcp.tools.UnmountVolumeTool(session));
                log.info("MCP network-op tools enabled");
            }
            mcpTools
                    .register(new com.organizer3.mcp.tools.SqlQueryTool(mcpRoDb))
                    .register(new com.organizer3.mcp.tools.SqlTablesTool(mcpRoDb))
                    .register(new com.organizer3.mcp.tools.SqlSchemaTool(mcpRoDb))
                    .register(new com.organizer3.mcp.tools.ListDirectoryTool(session))
                    .register(new com.organizer3.mcp.tools.ReadTextFileTool(session))
                    .register(new com.organizer3.mcp.tools.AuditFreshSkeletonsTool(session, config, freshAuditService))
                    .register(new com.organizer3.mcp.tools.ExportAliasesTool(actressRepo, dataDir))
                    .register(new com.organizer3.mcp.tools.ListTrashItemsTool(trashService, smbConnectionFactory))
                    .register(new com.organizer3.mcp.tools.ListTaskSpecsTool(taskRegistry))
                    .register(new com.organizer3.mcp.tools.GetTaskRunStatusTool(taskRunner))
                    .register(new com.organizer3.mcp.tools.ExportFilmographyBackupTool(filmographyBackupWriter, filmographyRepo))
                    .register(new com.organizer3.mcp.tools.ArchiveFilmographyBackupsTool(filmographyBackupWriter));
            if (mcpConfig.mutationsAllowed()) {
                mcpTools.register(new com.organizer3.mcp.tools.RefreshFilmographyTool(slugResolver));
                mcpTools.register(new com.organizer3.mcp.tools.EvictFilmographyTool(slugResolver));
                mcpTools.register(new com.organizer3.mcp.tools.ImportFilmographyBackupTool(filmographyBackupWriter, filmographyRepo, slugResolver));
                mcpTools.register(new com.organizer3.mcp.tools.SetActressAliasesTool(actressRepo));
                mcpTools.register(new com.organizer3.mcp.tools.MergeActressesTool(jdbi, actressRepo));
                mcpTools.register(new com.organizer3.mcp.tools.DeleteTitleTool(jdbi, titleRepo, enrichmentHistoryRepo));
                mcpTools.register(new com.organizer3.mcp.tools.RevalidateEnrichmentTool(revalidationService, revalidationPendingRepo));
                mcpTools.register(new com.organizer3.mcp.tools.SetDuplicateDecisionTool(dupDecisionRepo));
                mcpTools.register(new com.organizer3.mcp.tools.DecideMergeCandidateTool(mergeCandidateRepo));
                mcpTools.register(new com.organizer3.mcp.tools.ExecuteMergesTool(taskRunner));
                mcpTools.register(new com.organizer3.mcp.tools.ScheduleTrashDeletionTool(taskRunner));
                mcpTools.register(new com.organizer3.mcp.tools.CancelTaskRunTool(taskRunner));
                mcpTools.register(new com.organizer3.mcp.tools.StartTaskTool(taskRegistry, taskRunner));
                log.info("MCP mutation tools enabled");
            }
            if (mcpConfig.mutationsAllowed() && mcpConfig.fileOpsAllowed()) {
                mcpTools.register(new com.organizer3.mcp.tools.TrashDuplicateCoverTool(session, jdbi, config));
                mcpTools.register(new com.organizer3.mcp.tools.TrashDuplicateVideoTool(session, jdbi, config, videoRepo));
                mcpTools.register(new com.organizer3.mcp.tools.MoveCoverToBaseTool(session, jdbi));
                mcpTools.register(new com.organizer3.mcp.tools.SandboxWriteTestTool(session, config));
                mcpTools.register(new com.organizer3.mcp.tools.FixTitleTimestampsTool(session, jdbi, titleTimestampService));
                mcpTools.register(new com.organizer3.mcp.tools.AuditVolumeTimestampsTool(session, jdbi, titleTimestampService));
                mcpTools.register(new com.organizer3.mcp.tools.NormalizeTitleTool(session, jdbi, titleNormalizerService));
                mcpTools.register(new com.organizer3.mcp.tools.RestructureTitleTool(session, jdbi, titleRestructurerService));
                mcpTools.register(new com.organizer3.mcp.tools.SortTitleTool(session, jdbi, config, titleSorterService));
                mcpTools.register(new com.organizer3.mcp.tools.ClassifyActressTool(session, jdbi, config, actressClassifierService));
                mcpTools.register(new com.organizer3.mcp.tools.OrganizeVolumeTool(session, jdbi, config, organizeVolumeService));
                mcpTools.register(new com.organizer3.mcp.tools.PrepFreshVideosTool(session, config, freshPrepService));
                mcpTools.register(new com.organizer3.mcp.tools.RenameActressFoldersTool(session, actressRepo, actressMergeService));
                mcpTools.register(new com.organizer3.mcp.tools.MoveActressFolderToAttentionTool(session, actressRepo, actressMergeService, java.time.Clock.systemUTC()));
                mcpTools.register(new com.organizer3.mcp.tools.ExecuteDuplicateTrashTool(taskRunner));
                mcpTools.register(new com.organizer3.mcp.tools.RestoreTrashedTool(taskRunner));
                log.info("MCP file-op tools enabled");
            }
            com.organizer3.mcp.McpServer mcpServer = new com.organizer3.mcp.McpServer(
                    mcpTools, mcpConfig, "organizer3", "0.1.0");
            webServer.registerMcp(mcpServer);
        } else {
            log.info("MCP server disabled via config");
        }

        webServer.start();
        bgWorker.start();
        if (javdbConfig.enabledOrDefault()) enrichmentRunner.start();
        trashSweepScheduler.start(24);
        if (enrichmentConfig.revalidationCronOrDefaults().enabledOrDefault()) {
            revalidationCronScheduler.start(enrichmentConfig.revalidationCronOrDefaults().intervalHoursOrDefault());
        }

        OrganizerShell shell = new OrganizerShell(session, dispatcher);
        shell.run();

        webServer.stop();
        bgWorker.stop();
        enrichmentRunner.stop();
        trashSweepScheduler.stop();
        revalidationCronScheduler.stop(10);
        backupScheduler.stop();
        probeJobRunner.shutdown();
        thumbnailService.shutdown();
        smbConnectionFactory.shutdown();
        nasMonitor.stop();
        if (mcpRoDb != null) mcpRoDb.close();
        log.info("Organizer3 exiting");
    }

    /**
     * Resolves the data directory from (in priority order):
     * 1. {@code ORGANIZER_DATA_DIR} environment variable
     * 2. {@code dataDir} field in organizer-config.yaml
     * 3. Default: {@code ./data} relative to working directory
     */
    private static Path resolveDataDir(OrganizerConfig config) {
        String envDir = System.getenv("ORGANIZER_DATA_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir).toAbsolutePath();
        }
        String configDir = config.dataDir();
        if (configDir != null && !configDir.isBlank()) {
            return Path.of(configDir).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.dir"), "data");
    }
}
