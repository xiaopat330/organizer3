package com.organizer3.utilities.health.checks;

import com.organizer3.enrichment.ActressYaml;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnloadedYamlsCheckTest {

    @Test
    void reportsZeroWhenEveryYamlResolvesBySlug() throws Exception {
        ActressYamlLoader loader = mock(ActressYamlLoader.class);
        ActressRepository actresses = mock(ActressRepository.class);
        when(loader.listSlugs()).thenReturn(List.of("yuma_asami", "nana_ogura"));
        when(actresses.resolveByName(anyString())).thenReturn(Optional.of(actress()));

        LibraryHealthCheck.CheckResult result =
                new UnloadedYamlsCheck(loader, actresses).run();
        assertEquals(0, result.total());
    }

    @Test
    void flagsSlugThatDoesNotResolveAndHasNoProfileFallback() throws Exception {
        ActressYamlLoader loader = mock(ActressYamlLoader.class);
        ActressRepository actresses = mock(ActressRepository.class);
        when(loader.listSlugs()).thenReturn(List.of("missing_actress"));
        when(actresses.resolveByName(anyString())).thenReturn(Optional.empty());
        // No profile block → peek fallback contributes nothing.
        when(loader.peek("missing_actress")).thenReturn(new ActressYaml(null, List.of()));

        LibraryHealthCheck.CheckResult result =
                new UnloadedYamlsCheck(loader, actresses).run();
        assertEquals(1, result.total());
        assertEquals("missing_actress", result.rows().get(0).id());
    }

    /**
     * Rule 3 false-positive guard: a slug whose profile.name resolves to an existing actress
     * (via the canonical-name fallback) must NOT be flagged. Slug-to-name is not formal, so
     * this fallback path prevents spurious "unloaded" warnings for actresses whose DB name
     * differs from the filename slug.
     */
    @Test
    void slugMismatchButProfileNameResolvesIsNotFlagged() throws Exception {
        ActressYamlLoader loader = mock(ActressYamlLoader.class);
        ActressRepository actresses = mock(ActressRepository.class);
        when(loader.listSlugs()).thenReturn(List.of("yuma_asami_slug"));
        // Slug itself does not resolve.
        when(actresses.resolveByName("yuma_asami_slug")).thenReturn(Optional.empty());
        // But the YAML's profile.name → canonical "Yuma Asami" does resolve.
        var name = new ActressYaml.Name("Asami", "Yuma", null, null, null);
        var profile = new ActressYaml.Profile(name, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        when(loader.peek("yuma_asami_slug")).thenReturn(new ActressYaml(profile, List.of()));
        when(actresses.resolveByName("Yuma Asami")).thenReturn(Optional.of(actress()));

        LibraryHealthCheck.CheckResult result =
                new UnloadedYamlsCheck(loader, actresses).run();
        assertEquals(0, result.total(), "profile-name fallback must prevent flagging");
    }

    @Test
    void returnsEmptyWhenListSlugsThrows() throws Exception {
        ActressYamlLoader loader = mock(ActressYamlLoader.class);
        ActressRepository actresses = mock(ActressRepository.class);
        when(loader.listSlugs()).thenThrow(new java.io.IOException("classpath read failed"));

        LibraryHealthCheck.CheckResult result =
                new UnloadedYamlsCheck(loader, actresses).run();
        assertEquals(0, result.total());
    }

    private static Actress actress() {
        return Actress.builder().id(1L).canonicalName("Yuma Asami").build();
    }
}
