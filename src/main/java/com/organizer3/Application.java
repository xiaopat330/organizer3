package com.organizer3;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.organizer3.ai.ActressNameLookup;
import com.organizer3.backup.BackupScheduler;
import com.organizer3.backup.UserDataBackupService;
import com.organizer3.command.BackupCommand;
import com.organizer3.command.RestoreCommand;
import com.organizer3.ai.ClaudeActressNameLookup;
import com.organizer3.command.ActressNameCheckService;
import com.organizer3.command.ActressSearchCommand;
import com.organizer3.command.CheckNamesCommand;
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
import com.organizer3.command.LoadActressCommand;
import com.organizer3.command.HelpCommand;
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
import com.organizer3.media.ThumbnailService;
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

        // Config
        AppConfig.initialize(new OrganizerConfigLoader().load());
        OrganizerConfig config = AppConfig.get().volumes();

        // Database
        Path dbDir = Path.of(System.getProperty("user.home"), ".organizer3");
        Files.createDirectories(dbDir);
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + dbDir.resolve("organizer.db"));
        new SchemaInitializer(jdbi).initialize();
        new SchemaUpgrader(jdbi).upgrade();
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

        // Auto-backup scheduler (disabled when interval is 0 or unset)
        BackupScheduler backupScheduler = new BackupScheduler();
        if (autoBackupInterval > 0) {
            backupScheduler.start(backupService, backupPath, autoBackupInterval, snapshotCount);
        }

        // AV Stars commands
        AvStarsSyncOperation avStarsSyncOp = new AvStarsSyncOperation(avActressRepo, avVideoRepo, volumeRepo);
        AvFilenameParser avFilenameParser = new AvFilenameParser();
        commands.add(new AvSyncCommand(avStarsSyncOp));
        commands.add(new AvActressesCommand(avActressRepo));
        commands.add(new AvActressCommand(avActressRepo, avVideoRepo));
        commands.add(new AvFavoritesCommand(avActressRepo));
        commands.add(new AvCurateCommand(avActressRepo));
        commands.add(new AvMigrateActressCommand(avActressRepo));
        commands.add(new AvRenameActressCommand(avActressRepo));
        commands.add(new AvDeleteActressCommand(avActressRepo));
        commands.add(new AvParseFilenamesCommand(avVideoRepo, avFilenameParser));
        Path avHeadshotDir    = dataDir.resolve("av_headshots");
        Path avScreenshotDir  = dataDir.resolve("av_screenshots");
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

        // Thumbnail service — created early so commands can reference it
        int thumbnailInterval = config.thumbnailInterval() != null ? config.thumbnailInterval() : 8;
        ThumbnailService thumbnailService = new ThumbnailService(
                dataDir.resolve("thumbnails"), thumbnailInterval, WebServer.DEFAULT_PORT);
        commands.add(new PruneThumbnailsCommand(titleRepo, thumbnailService));
        commands.add(new ClearThumbnailsCommand(thumbnailService));

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
                            titleEffectiveTagsService, actressCompaniesService);
                case PARTITION ->
                    new PartitionSyncOperation(def.partitions(), titleRepo, videoRepo,
                            actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader,
                            titleEffectiveTagsService, actressCompaniesService);
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
                actressRepo, titleRepo, coverPath, volumeSmbPaths, labelRepo, nameLookup, stageNameBackup);
        SearchService searchService = new SearchService(actressRepo, titleRepo, labelRepo, coverPath, avActressRepo);

        // Video streaming + metadata
        SmbConnectionFactory smbConnectionFactory = new SmbConnectionFactory(config);
        VideoStreamService videoStreamService = new VideoStreamService(titleRepo, videoRepo, smbConnectionFactory);
        VideoProbe videoProbe = new VideoProbe(WebServer.DEFAULT_PORT);
        commands.add(new com.organizer3.command.ProbeVideosCommand(videoRepo, videoProbe::probe));
        com.organizer3.media.ProbeJobRunner probeJobRunner =
                new com.organizer3.media.ProbeJobRunner(videoRepo, videoProbe::probe);
        CommandDispatcher dispatcher = new CommandDispatcher(commands);

        WebServer webServer = new WebServer(browseService, actressBrowseService, coverPath.root(),
                videoStreamService, thumbnailService, videoProbe, watchHistoryRepo, titleRepo, searchService);
        webServer.registerAvRoutes(new AvBrowseService(avActressRepo, avVideoRepo, avScreenshotRepo, avVideoTagRepo),
                avHeadshotDir, smbConnectionFactory, avVideoRepo, avActressRepo,
                avScreenshotRepo, avScreenshotDir, avTagDefRepo, avScreenshotService);
        webServer.registerTerminal(new WebTerminalHandler(dispatcher, session));

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
                    .register(new com.organizer3.mcp.tools.FindStaleLocationsTool(jdbi))
                    .register(new com.organizer3.mcp.tools.ListActressesWithMisnamedFoldersTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindMisnamedFoldersForActressTool(jdbi, actressRepo))
                    .register(new com.organizer3.mcp.tools.ListMultiVideoTitlesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.AnalyzeTitleVideosTool(titleRepo, videoRepo))
                    .register(new com.organizer3.mcp.tools.FindDuplicateCandidatesTool(jdbi))
                    .register(new com.organizer3.mcp.tools.FindMultiCoverTitlesTool(session, jdbi))
                    .register(new com.organizer3.mcp.tools.FindMisfiledCoversTool(session, jdbi))
                    .register(new com.organizer3.mcp.tools.ScanTitleFolderAnomaliesTool(session, titleRepo, titleLocationRepo))
                    .register(new com.organizer3.mcp.tools.MountStatusTool(session))
                    .register(new com.organizer3.mcp.tools.ProbeVideosBatchTool(session, videoRepo, videoProbe::probe))
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
                    .register(new com.organizer3.mcp.tools.ReadTextFileTool(session));
            if (mcpConfig.mutationsAllowed()) {
                mcpTools.register(new com.organizer3.mcp.tools.MergeActressesTool(jdbi, actressRepo));
                mcpTools.register(new com.organizer3.mcp.tools.DeleteTitleTool(jdbi, titleRepo));
                log.info("MCP mutation tools enabled");
            }
            if (mcpConfig.mutationsAllowed() && mcpConfig.fileOpsAllowed()) {
                mcpTools.register(new com.organizer3.mcp.tools.TrashDuplicateCoverTool(session, jdbi, config));
                mcpTools.register(new com.organizer3.mcp.tools.MoveCoverToBaseTool(session, jdbi));
                log.info("MCP file-op tools enabled");
            }
            com.organizer3.mcp.McpServer mcpServer = new com.organizer3.mcp.McpServer(
                    mcpTools, mcpConfig, "organizer3", "0.1.0");
            webServer.registerMcp(mcpServer);
        } else {
            log.info("MCP server disabled via config");
        }

        webServer.start();

        OrganizerShell shell = new OrganizerShell(session, dispatcher);
        shell.run();

        webServer.stop();
        backupScheduler.stop();
        probeJobRunner.shutdown();
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
