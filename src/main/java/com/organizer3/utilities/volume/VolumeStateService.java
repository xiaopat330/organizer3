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

    public VolumeStateService(VolumeRepository volumeRepo, TitleRepository titleRepo) {
        this.volumeRepo = volumeRepo;
        this.titleRepo = titleRepo;
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
        int titleCount = titleRepo.countByVolume(config.id());

        // Health stubs: real detection (orphan covers, missing thumbs, probe failures)
        // will be wired up in a follow-up PR. For now the list is empty and the overall
        // status is "healthy". The UI renders the empty list as "All healthy".
        List<VolumeStateDTO.HealthIssue> health = List.of();

        return new VolumeStateDTO(
                config.id(),
                config.smbPath(),
                config.structureType(),
                lastSynced,
                titleCount,
                "online",
                health);
    }
}
