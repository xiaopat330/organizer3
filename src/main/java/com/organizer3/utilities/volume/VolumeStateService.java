package com.organizer3.utilities.volume;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.model.Volume;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VolumeRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Produces the UI payload for the Volumes screen: merges config-declared volumes with
 * DB-backed state (last-synced, title count) and a (currently stubbed) list of health
 * indicators. Returns {@link VolumeStateDTO} records ready to be serialized as JSON.
 */
public final class VolumeStateService {

    private final VolumeRepository volumeRepo;
    private final TitleRepository titleRepo;
    private final StaleLocationsService staleLocations;

    public VolumeStateService(VolumeRepository volumeRepo, TitleRepository titleRepo,
                              StaleLocationsService staleLocations) {
        this.volumeRepo = volumeRepo;
        this.titleRepo = titleRepo;
        this.staleLocations = staleLocations;
    }

    public List<VolumeStateDTO> list() {
        // Config is the source of truth for which volumes exist; DB supplies per-volume facts.
        return AppConfig.get().volumes().volumes().stream()
                .filter(v -> !"avstars".equals(v.structureType()))
                .map(this::dtoFor)
                .toList();
    }

    public Optional<VolumeStateDTO> find(String volumeId) {
        return AppConfig.get().volumes().findById(volumeId).map(this::dtoFor);
    }

    private VolumeStateDTO dtoFor(VolumeConfig config) {
        LocalDateTime lastSynced = volumeRepo.findById(config.id())
                .map(Volume::getLastSyncedAt)
                .orElse(null);
        String lastSyncedIso = lastSynced == null ? null : lastSynced.toString();
        int titleCount = titleRepo.countByVolume(config.id());

        // Real health detection is being wired in one indicator at a time. First up: stale
        // locations — rows whose file wasn't observed during the last sync. More indicators
        // (missing thumbnails, probe failures, etc.) will append to this list.
        List<VolumeStateDTO.HealthIssue> health = new java.util.ArrayList<>();
        int staleCount = staleLocations.count(config.id());
        if (staleCount > 0) {
            health.add(new VolumeStateDTO.HealthIssue(
                    "warn", "stale_locations", staleCount,
                    staleCount + " stale location" + (staleCount == 1 ? "" : "s")));
        }

        return new VolumeStateDTO(
                config.id(),
                config.smbPath(),
                config.structureType(),
                lastSyncedIso,
                titleCount,
                "online",
                health);
    }
}
