package com.organizer3.utilities.avstars;

import com.organizer3.avstars.iafd.IafdClient;
import com.organizer3.avstars.iafd.IafdFetchException;
import com.organizer3.avstars.iafd.IafdProfileParser;
import com.organizer3.avstars.iafd.IafdResolvedProfile;
import com.organizer3.avstars.iafd.IafdSearchParser;
import com.organizer3.avstars.iafd.IafdSearchResult;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Headless IAFD resolver used by the Utilities AV Stars screen. Extracted from
 * {@code AvResolveCommand} so the same pipeline can be driven from the CLI's interactive
 * prompt, from a web UI picker, and from MCP tools without duplication.
 *
 * <p>The CLI command still owns the interactive pick flow (it prompts via {@code CommandIO}).
 * This service owns everything around it: the search call, the profile fetch + parse, the
 * headshot download, and the DB persistence.
 */
@Slf4j
public final class IafdResolverService {

    private final AvActressRepository actressRepo;
    private final IafdClient iafdClient;
    private final IafdSearchParser searchParser;
    private final IafdProfileParser profileParser;
    private final Path headshotDir;

    public IafdResolverService(AvActressRepository actressRepo, IafdClient iafdClient,
                               IafdSearchParser searchParser, IafdProfileParser profileParser,
                               Path headshotDir) {
        this.actressRepo = actressRepo;
        this.iafdClient = iafdClient;
        this.searchParser = searchParser;
        this.profileParser = profileParser;
        this.headshotDir = headshotDir;
    }

    /**
     * Run a comprehensive IAFD search for the given name. Returns the parsed candidates or
     * throws if the search itself fails. Empty list is a valid result (means "no matches").
     */
    public List<IafdSearchResult> search(String name) throws IafdFetchException {
        String html = iafdClient.fetchSearch(name);
        return searchParser.parse(html);
    }

    /**
     * Apply a specific IAFD identity to an actress — fetch profile, download headshot, persist.
     * Idempotent: re-applying the same pick refetches and overwrites.
     */
    public ApplyResult apply(long actressId, String iafdUuid) throws IafdFetchException, IOException {
        Optional<AvActress> actressOpt = actressRepo.findById(actressId);
        if (actressOpt.isEmpty()) throw new IllegalArgumentException("Unknown av actress id: " + actressId);
        AvActress actress = actressOpt.get();

        String profileHtml = iafdClient.fetchProfile(iafdUuid);
        IafdResolvedProfile profile = profileParser.parse(iafdUuid, profileHtml);

        String headshotUrl = profile.getHeadshotUrl();
        String headshotPath = (headshotUrl != null && !headshotUrl.isBlank())
                ? downloadHeadshot(iafdUuid, headshotUrl)
                : null;

        actressRepo.updateIafdFields(actress.getId(), profile, headshotPath);
        log.info("IAFD resolved av_actress_id={} iafdId={} ({} titles)",
                actressId, iafdUuid, profile.getIafdTitleCount());
        return new ApplyResult(actress.getStageName(), iafdUuid, profile.getIafdTitleCount(),
                profile.getActiveFrom(), profile.getActiveTo(), headshotPath != null);
    }

    /**
     * Refresh an already-resolved actress — re-pulls the same IAFD id she has on file. No-op
     * if she has no iafd_id. Used as a convenience wrapper for "re-resolve" action.
     */
    public ApplyResult refresh(long actressId) throws IafdFetchException, IOException {
        AvActress actress = actressRepo.findById(actressId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown av actress id: " + actressId));
        if (actress.getIafdId() == null || actress.getIafdId().isBlank()) {
            throw new IllegalStateException("Actress " + actressId + " has no IAFD id to refresh");
        }
        return apply(actressId, actress.getIafdId());
    }

    private String downloadHeadshot(String uuid, String url) throws IafdFetchException, IOException {
        String ext = url.contains(".") ? url.substring(url.lastIndexOf('.')) : ".jpg";
        if (ext.contains("?")) ext = ext.substring(0, ext.indexOf('?'));
        if (ext.length() > 5) ext = ".jpg";
        Path target = headshotDir.resolve(uuid + ext);
        Files.createDirectories(headshotDir);
        byte[] bytes = iafdClient.fetchBytes(url);
        Files.write(target, bytes);
        return target.toString();
    }

    /** Outcome of an apply/refresh call — summary for logging and UI confirmation. */
    public record ApplyResult(String stageName, String iafdId, Integer iafdTitleCount,
                              Integer activeFrom, Integer activeTo, boolean headshotSaved) {}
}
