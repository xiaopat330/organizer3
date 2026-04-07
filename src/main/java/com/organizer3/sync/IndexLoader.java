package com.organizer3.sync;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a {@link VolumeIndex} from the database for a given volume.
 *
 * <p>Called at mount time (to initialize the session index) and after each sync
 * (to reflect the updated DB state in memory).
 */
public class IndexLoader {

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;

    public IndexLoader(TitleRepository titleRepo, ActressRepository actressRepo) {
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
    }

    public VolumeIndex load(String volumeId) {
        List<Title> titles = titleRepo.findByVolume(volumeId);

        Set<Long> actressIds = titles.stream()
                .map(Title::getActressId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Actress> actresses = actressIds.stream()
                .flatMap(id -> actressRepo.findById(id).stream())
                .sorted()
                .toList();

        return new VolumeIndex(volumeId, titles, actresses);
    }
}
