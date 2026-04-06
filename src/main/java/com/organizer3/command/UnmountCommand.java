package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;

/**
 * Disconnects from the currently active volume and clears the session context.
 *
 * <p>Usage: {@code unmount}
 *
 * <p>Closes the SMB connection and clears the active volume, connection, and index from
 * the session. The OS-level SMB mount is intentionally left intact — the application
 * never unmounts at the OS level.
 */
public class UnmountCommand implements Command {

    @Override
    public String name() {
        return "unmount";
    }

    @Override
    public String description() {
        return "Disconnect from the current volume and clear the session context.";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (!ctx.isConnected() && ctx.getMountedVolume() == null) {
            io.println("No volume is currently mounted.");
            return;
        }

        String volumeId = ctx.getMountedVolumeId();

        VolumeConnection connection = ctx.getActiveConnection();
        if (connection != null) {
            connection.close();
            ctx.setActiveConnection(null);
        }

        ctx.setMountedVolume(null);
        ctx.setIndex(null);

        io.println("Disconnected from volume '" + volumeId + "'.");
    }
}
