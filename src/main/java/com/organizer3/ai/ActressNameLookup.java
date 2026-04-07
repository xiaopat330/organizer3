package com.organizer3.ai;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a romanized actress name to its Japanese kanji/kana equivalent.
 */
public interface ActressNameLookup {

    /**
     * Returns the Japanese stage name for the given actress, using her product codes
     * and labels as supporting context for the lookup.
     */
    Optional<String> findJapaneseName(Actress actress, List<Title> titles);
}
