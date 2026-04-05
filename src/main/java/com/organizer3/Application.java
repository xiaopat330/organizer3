package com.organizer3;

import com.organizer3.command.Command;
import com.organizer3.command.HelloCommand;
import com.organizer3.command.HelpCommand;
import com.organizer3.command.MountCommand;
import com.organizer3.command.ShutdownCommand;
import com.organizer3.command.SyncCommand;
import com.organizer3.config.AppConfig;
import com.organizer3.config.sync.StructureSyncConfig;
import com.organizer3.config.sync.SyncCommandDef;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.OrganizerConfigLoader;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.LocalFileSystem;
import com.organizer3.mount.MacOsCredentialLookup;
import com.organizer3.mount.OsSmbMounter;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.repository.jdbi.JdbiVolumeRepository;
import com.organizer3.shell.OrganizerShell;
import com.organizer3.shell.SessionContext;
import com.organizer3.sync.FullSyncOperation;
import com.organizer3.sync.IndexLoader;
import com.organizer3.sync.PartitionSyncOperation;
import com.organizer3.sync.SyncOperation;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Entry point. Wires all dependencies manually (no IoC container).
 *
 * This class is the composition root — the only place that knows about
 * all the concrete types. Everything else works against interfaces,
 * making each piece independently testable.
 */
public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

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

        // Repositories
        TitleRepository  titleRepo  = new JdbiTitleRepository(jdbi);
        VideoRepository  videoRepo  = new JdbiVideoRepository(jdbi);
        ActressRepository actressRepo = new JdbiActressRepository(jdbi);
        VolumeRepository  volumeRepo  = new JdbiVolumeRepository(jdbi);
        IndexLoader indexLoader = new IndexLoader(titleRepo, actressRepo);

        // Session
        SessionContext session = new SessionContext();

        // Commands
        List<Command> commands = new ArrayList<>();
        commands.add(new HelloCommand());
        commands.add(new ShutdownCommand());
        commands.add(new MountCommand(new MacOsCredentialLookup(), new OsSmbMounter(), indexLoader));

        // Sync commands — registered dynamically from syncConfig
        for (StructureSyncConfig structureSyncConfig : config.syncConfig()) {
            String structureType = structureSyncConfig.structureType();
            for (SyncCommandDef def : structureSyncConfig.commands()) {
                SyncOperation op = switch (def.operation()) {
                    case FULL ->
                        new FullSyncOperation(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
                    case PARTITION ->
                        new PartitionSyncOperation(def.partitions(), titleRepo, videoRepo,
                                actressRepo, volumeRepo, indexLoader);
                };
                commands.add(new SyncCommand(def.term(), Set.of(structureType), op));
            }
        }

        commands.add(new HelpCommand(commands));

        OrganizerShell shell = new OrganizerShell(session, commands);
        shell.run();

        log.info("Organizer3 exiting");
    }
}
