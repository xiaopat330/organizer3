package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.mount.CredentialLookup;
import com.organizer3.mount.CredentialNotFoundException;
import com.organizer3.mount.MountException;
import com.organizer3.mount.SmbMounter;
import com.organizer3.shell.SessionContext;
import com.organizer3.sync.IndexLoader;
import com.organizer3.sync.VolumeIndex;

import java.io.PrintWriter;

/**
 * Mounts an SMB volume and activates it as the current session context.
 *
 * <p>Usage: {@code mount <volume-id>}
 *
 * <p>Mount is idempotent — calling it on an already-mounted volume simply reactivates it
 * as the session context without re-running {@code mount_smbfs}.
 *
 * <p>Volume config is read from {@link AppConfig#get()#volumes()} — no config injected
 * into the constructor.
 */
public class MountCommand implements Command {

    private final CredentialLookup credentialLookup;
    private final SmbMounter smbMounter;
    private final IndexLoader indexLoader;

    public MountCommand(CredentialLookup credentialLookup, SmbMounter smbMounter,
                        IndexLoader indexLoader) {
        this.credentialLookup = credentialLookup;
        this.smbMounter = smbMounter;
        this.indexLoader = indexLoader;
    }

    @Override
    public String name() {
        return "mount";
    }

    @Override
    public String description() {
        return "Mount a volume and activate it as the current context. Usage: mount <id>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, PrintWriter out) {
        if (args.length < 2) {
            out.println("Usage: mount <volume-id>");
            return;
        }

        OrganizerConfig config = AppConfig.get().volumes();
        String volumeId = args[1];
        VolumeConfig volume = config.findById(volumeId).orElse(null);
        if (volume == null) {
            out.println("Unknown volume: " + volumeId);
            out.println("Known volumes: " + config.volumes().stream()
                    .map(VolumeConfig::id)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)"));
            return;
        }

        if (smbMounter.isMounted(volume.mountPoint())) {
            out.println("Volume '" + volumeId + "' already mounted at " + volume.mountPoint());
            ctx.setMountedVolume(volume);
            return;
        }

        out.println("Mounting " + volume.smbPath() + " -> " + volume.mountPoint() + " ...");

        String password;
        try {
            password = credentialLookup.getPassword(volume.credentialsKey(), volume.username());
        } catch (CredentialNotFoundException e) {
            out.println("Error: " + e.getMessage());
            out.println("Add credentials with: security add-internet-password -s "
                    + volume.credentialsKey() + " -a " + volume.username() + " -w");
            return;
        }

        try {
            smbMounter.mount(volume, password);
        } catch (MountException e) {
            out.println("Mount failed: " + e.getMessage());
            return;
        }

        ctx.setMountedVolume(volume);
        loadIndex(volumeId, ctx, out);
        out.println("Mounted. Volume '" + volumeId + "' is now active.");
    }

    private void loadIndex(String volumeId, SessionContext ctx, PrintWriter out) {
        VolumeIndex index = indexLoader.load(volumeId);
        ctx.setIndex(index);
        if (index.titleCount() == 0) {
            out.println("No index found for volume '" + volumeId + "' — run sync-all to build it.");
        } else {
            out.println("Loaded index: " + index.titleCount() + " title(s), "
                    + index.actressCount() + " actress(es).");
        }
    }
}
