package com.organizer3.web.routes;

import com.organizer3.smb.SmbConnectionFactory;
import io.javalin.Javalin;

/**
 * SMB pool admin routes. The deterministic backstop for the Wave-3 network-change teardown:
 * {@code POST /api/smb/reset} tears down + lazily re-establishes every pooled SMB connection
 * (see {@link SmbConnectionFactory#invalidateAll()}), for use right after switching VPN or when
 * the automatic all-hosts-down sensor misses. Also curl-smoke-testable.
 */
public class SmbRoutes {

    private final SmbConnectionFactory smbFactory;

    public SmbRoutes(SmbConnectionFactory smbFactory) {
        this.smbFactory = smbFactory;
    }

    public void register(Javalin app) {
        app.post("/api/smb/reset", ctx -> {
            smbFactory.invalidateAll();
            ctx.json(java.util.Map.of("reset", true));
        });
    }
}
