package com.organizer3.avatars;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.model.TitleSortSpec;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Manages user-curated actress avatar images.
 * Coordinates between {@link CustomAvatarStore} (I/O) and {@link ActressRepository} (DB).
 */
@Slf4j
@RequiredArgsConstructor
public class CustomAvatarService {

    /** Returned by {@link #setCustomAvatar} on success. */
    public record AvatarResult(String localAvatarUrl, boolean hasCustomAvatar) {}

    /** One cover tile for the avatar picker. */
    public record TitleCover(long titleId, String label, String code, String coverUrl) {}

    private final CustomAvatarStore store;
    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final CoverPath coverPath;

    /**
     * Validates, stores, and records a custom avatar for the actress.
     *
     * @return empty if the actress does not exist; otherwise the resolved avatar URL
     * @throws IllegalArgumentException if the image fails validation (not square, too small, etc.)
     * @throws IOException              if the file cannot be written
     */
    public Optional<AvatarResult> setCustomAvatar(long actressId, byte[] bytes) throws IOException {
        if (actressRepo.findById(actressId).isEmpty()) {
            return Optional.empty();
        }
        String rel = store.save(actressId, bytes);
        actressRepo.setCustomAvatarPath(actressId, rel);
        log.info("Custom avatar set for actress {} → {}", actressId, rel);
        return Optional.of(new AvatarResult("/" + rel, true));
    }

    /**
     * Removes the custom avatar for the actress. Idempotent — safe to call when none exists.
     * DB is cleared first so concurrent readers never see a dangling path.
     *
     * @return empty if the actress does not exist; {@code Optional.of(true)} on success
     */
    public Optional<Boolean> clearCustomAvatar(long actressId) {
        if (actressRepo.findById(actressId).isEmpty()) {
            return Optional.empty();
        }
        actressRepo.clearCustomAvatarPath(actressId);
        store.delete(actressId);
        log.info("Custom avatar cleared for actress {}", actressId);
        return Optional.of(true);
    }

    /**
     * Returns cover tiles for all titles the actress appears in that have a local cover image.
     * Ordered by release date descending.
     *
     * @return empty if the actress does not exist
     */
    public Optional<List<TitleCover>> listTitleCovers(long actressId) {
        if (actressRepo.findById(actressId).isEmpty()) {
            return Optional.empty();
        }
        List<Title> titles = titleRepo.findByActressPaged(
                actressId, Integer.MAX_VALUE, 0, TitleSortSpec.DEFAULT);

        List<TitleCover> covers = titles.stream()
                .flatMap(t -> coverPath.find(t)
                        .map(p -> new TitleCover(
                                t.getId(),
                                t.getLabel(),
                                t.getCode(),
                                "/covers/" + t.getLabel().toUpperCase() + "/" + p.getFileName()))
                        .stream())
                .toList();

        return Optional.of(covers);
    }
}
