package com.organizer3;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.organizer3.ai.ActressNameLookup;
import com.organizer3.ai.ClaudeActressNameLookup;
import com.organizer3.command.ActressSearchCommand;
import com.organizer3.command.ActressesCommand;

import com.organizer3.command.Command;
import com.organizer3.command.FavoritesCommand;
import com.organizer3.command.LoadActressCommand;
import com.organizer3.command.HelloCommand;
import com.organizer3.command.HelpCommand;
import com.organizer3.command.MountCommand;
import com.organizer3.command.PruneCoversCommand;
import com.organizer3.command.RebuildCommand;
import com.organizer3.command.ScanCoversCommand;
import com.organizer3.command.ShutdownCommand;
import com.organizer3.command.SyncCommand;
import com.organizer3.command.UnmountCommand;
import com.organizer3.command.VolumesCommand;
import com.organizer3.covers.CoverPath;
import com.organizer3.config.AppConfig;
import com.organizer3.config.sync.StructureSyncConfig;
import com.organizer3.config.sync.SyncCommandDef;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.OrganizerConfigLoader;
import com.organizer3.db.LabelSeeder;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.SchemaUpgrader;
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
import com.organizer3.shell.OrganizerShell;
import com.organizer3.shell.SessionContext;
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
import com.organizer3.web.TitleBrowseService;
import com.organizer3.web.WebServer;
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
        new LabelSeeder(jdbi).seedIfEmpty();

        // Repositories
        TitleLocationRepository titleLocationRepo = new JdbiTitleLocationRepository(jdbi);
        TitleRepository        titleRepo        = new JdbiTitleRepository(jdbi, titleLocationRepo);
        VideoRepository        videoRepo        = new JdbiVideoRepository(jdbi);
        ActressRepository      actressRepo      = new JdbiActressRepository(jdbi);
        VolumeRepository       volumeRepo       = new JdbiVolumeRepository(jdbi);
        LabelRepository        labelRepo        = new JdbiLabelRepository(jdbi);
        TitleTagRepository     tagRepo          = new JdbiTitleTagRepository(jdbi);
        TitleActressRepository titleActressRepo = new JdbiTitleActressRepository(jdbi);
        IndexLoader indexLoader = new IndexLoader(titleRepo, actressRepo);

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
        commands.add(new HelloCommand());
        commands.add(new ShutdownCommand());
        commands.add(new MountCommand(new SmbjConnector(), indexLoader));
        commands.add(new UnmountCommand());
        commands.add(new VolumesCommand(volumeRepo));
        commands.add(new ActressesCommand(actressRepo, titleRepo));
        commands.add(new ActressSearchCommand(actressRepo, titleRepo, labelRepo, nameLookup));
        commands.add(new FavoritesCommand(actressRepo, titleRepo));

        ActressYamlLoader yamlLoader = new ActressYamlLoader(actressRepo, titleRepo, tagRepo);
        commands.add(new LoadActressCommand(yamlLoader));

        // Scanner registry — maps structure types to their filesystem scanners
        ScannerRegistry scannerRegistry = new ScannerRegistry(Map.of(
                "conventional", new ConventionalScanner(),
                "queue",        new QueueScanner(),
                "exhibition",   new ExhibitionScanner(),
                "sort_pool",    new SortPoolScanner(),
                "collections",  new CollectionsScanner()
        ));

        // Cover image commands
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        CoverPath coverPath = new CoverPath(projectRoot);
        commands.add(new ScanCoversCommand(titleRepo, volumeRepo, coverPath, scannerRegistry));
        commands.add(new PruneCoversCommand(titleRepo, coverPath));

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
                            volumeRepo, titleLocationRepo, titleActressRepo, indexLoader);
                case PARTITION ->
                    new PartitionSyncOperation(def.partitions(), titleRepo, videoRepo,
                            actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader);
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
        TitleBrowseService browseService = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo, titleActressRepo);
        Map<String, String> volumeSmbPaths = config.volumes().stream()
                .collect(java.util.stream.Collectors.toMap(VolumeConfig::id, VolumeConfig::smbPath));
        com.organizer3.web.StageNameBackupFile stageNameBackup = new com.organizer3.web.StageNameBackupFile(
                dbDir.resolve("stagenames.yaml"));
        ActressBrowseService actressBrowseService = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, volumeSmbPaths, labelRepo, nameLookup, stageNameBackup);
        WebServer webServer = new WebServer(browseService, actressBrowseService, coverPath.root());
        webServer.start();

        OrganizerShell shell = new OrganizerShell(session, commands);
        shell.run();

        webServer.stop();
        log.info("Organizer3 exiting");
    }
}
