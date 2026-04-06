package com.organizer3.command;

import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActressSearchCommandTest {

    private ActressRepository actressRepo;
    private TitleRepository titleRepo;
    private LabelRepository labelRepo;
    private ActressSearchCommand cmd;
    private SessionContext ctx;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        actressRepo = mock(ActressRepository.class);
        titleRepo   = mock(TitleRepository.class);
        labelRepo   = mock(LabelRepository.class);
        io          = mock(CommandIO.class);
        cmd         = new ActressSearchCommand(actressRepo, titleRepo, labelRepo);
        ctx         = new SessionContext();

        when(labelRepo.findAllAsMap()).thenReturn(Map.of());
    }

    // ── search routing ───────────────────────────────────────────────────────

    @Test
    void noArgs_printsUsage() {
        cmd.execute(new String[]{"actress"}, ctx, io);

        verify(io).println(contains("Usage:"));
        verifyNoInteractions(actressRepo, titleRepo, labelRepo);
    }

    @Test
    void searchMissingPrefix_printsUsage() {
        cmd.execute(new String[]{"actress", "find"}, ctx, io);

        verify(io).println(contains("Usage:"));
        verifyNoInteractions(actressRepo, titleRepo, labelRepo);
    }

    @Test
    void searchNoResults_printsNoMatchMessage() {
        when(actressRepo.searchByNamePrefix("xyz")).thenReturn(List.of());

        cmd.execute(new String[]{"actress", "find", "xyz"}, ctx, io);

        verify(io).println(contains("No actresses found"));
        verify(io, never()).pick(any());
    }

    @Test
    void searchSingleResult_showsDetailDirectly_withoutPicker() {
        Actress aya = actress(1L, "Aya Sazanami");
        when(actressRepo.searchByNamePrefix("ay")).thenReturn(List.of(aya));
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of());
        when(actressRepo.findAliases(1L)).thenReturn(List.of());

        cmd.execute(new String[]{"actress", "find", "ay"}, ctx, io);

        verify(io, never()).pick(any());
        verify(io).printlnAnsi(contains("Aya Sazanami"));
    }

    @Test
    void searchMultipleResults_invokesPicker() {
        List<Actress> results = List.of(actress(1L, "Aino Kishi"), actress(2L, "Hibiki Aizawa"));
        when(actressRepo.searchByNamePrefix("ai")).thenReturn(results);
        when(io.pick(anyList())).thenReturn(Optional.empty());

        cmd.execute(new String[]{"actress", "find", "ai"}, ctx, io);

        verify(io).pick(List.of("Aino Kishi", "Hibiki Aizawa"));
    }

    @Test
    void searchPickerSelection_showsDetail() {
        List<Actress> results = List.of(actress(1L, "Aino Kishi"), actress(2L, "Hibiki Aizawa"));
        Actress hibiki = actress(2L, "Hibiki Aizawa");
        when(actressRepo.searchByNamePrefix("ai")).thenReturn(results);
        when(io.pick(anyList())).thenReturn(Optional.of("Hibiki Aizawa"));
        when(actressRepo.resolveByName("Hibiki Aizawa")).thenReturn(Optional.of(hibiki));
        when(titleRepo.findByActress(2L)).thenReturn(List.of());
        when(actressRepo.findAliases(2L)).thenReturn(List.of());

        cmd.execute(new String[]{"actress", "find", "ai"}, ctx, io);

        verify(io).printlnAnsi(contains("Hibiki Aizawa"));
    }

    @Test
    void searchPickerCancelled_noDetail() {
        List<Actress> results = List.of(actress(1L, "Aino Kishi"), actress(2L, "Hibiki Aizawa"));
        when(actressRepo.searchByNamePrefix("ai")).thenReturn(results);
        when(io.pick(anyList())).thenReturn(Optional.empty());

        cmd.execute(new String[]{"actress", "find", "ai"}, ctx, io);

        verify(actressRepo, never()).resolveByName(any());
    }

    // ── direct name lookup ───────────────────────────────────────────────────

    @Test
    void directName_unknownActress_printsError() {
        when(actressRepo.resolveByName("Nobody")).thenReturn(Optional.empty());

        cmd.execute(new String[]{"actress", "Nobody"}, ctx, io);

        verify(io).println(contains("Unknown actress"));
    }

    @Test
    void directName_multiWord_joinsArgs() {
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.empty());

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(actressRepo).resolveByName("Aya Sazanami");
    }

    // ── detail display ───────────────────────────────────────────────────────

    @Test
    void detail_showsNameAndTier() {
        Actress aya = actress(1L, "Aya Sazanami", Actress.Tier.POPULAR, false);
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of());
        when(actressRepo.findAliases(1L)).thenReturn(List.of());

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(io).printlnAnsi(contains("Aya Sazanami"));
        verify(io).printlnAnsi(contains("POPULAR"));
    }

    @Test
    void detail_favoriteShowsStar() {
        Actress aya = actress(1L, "Aya Sazanami", Actress.Tier.GODDESS, true);
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of());
        when(actressRepo.findAliases(1L)).thenReturn(List.of());

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(io).printlnAnsi(contains("★"));
    }

    @Test
    void detail_showsActiveDateRange() {
        Actress aya = actress(1L, "Aya Sazanami");
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of(
                title("ABP-001", "ABP", LocalDate.of(2022, 3, 10)),
                title("ABP-002", "ABP", LocalDate.of(2023, 7, 15))
        ));
        when(actressRepo.findAliases(1L)).thenReturn(List.of());

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(io).println(contains("2024-01-01"));  // firstSeenAt
        verify(io).println(contains("2023-07-15"));  // latest title lastSeenAt
    }

    @Test
    void detail_titleCountIncludesAliases() {
        Actress aya = actress(1L, "Aya Sazanami");
        Actress aliasActress = actress(2L, "Haruka Suzumiya");
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of(title("ABP-001", null, null)));
        when(actressRepo.findAliases(1L)).thenReturn(List.of(new ActressAlias(1L, "Haruka Suzumiya")));
        when(actressRepo.findByCanonicalName("Haruka Suzumiya")).thenReturn(Optional.of(aliasActress));
        when(titleRepo.findByActress(2L)).thenReturn(List.of(
                title("MIDE-100", null, null),
                title("MIDE-101", null, null)
        ));

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(io).printlnAnsi(contains("3 titles"));
    }

    @Test
    void detail_titlesGroupedByCompany() {
        Actress aya = actress(1L, "Aya Sazanami");
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of(
                title("ABP-001", "ABP", null),
                title("MIDE-420", "MIDE", null)
        ));
        when(actressRepo.findAliases(1L)).thenReturn(List.of());
        when(labelRepo.findAllAsMap()).thenReturn(Map.of(
                "ABP",  new Label("ABP",  "S1",         "S1 No.1 Style"),
                "MIDE", new Label("MIDE", "Moody's Diva", "Moodyz")
        ));

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(io, atLeast(1)).println(contains("Product Number"));
        verify(io).printlnAnsi(contains("S1 No.1 Style"));
        verify(io).printlnAnsi(contains("Moodyz"));
        verify(io).printlnAnsi(contains("ABP-001"));
        verify(io).printlnAnsi(contains("MIDE-420"));
    }

    @Test
    void detail_showsLocationPath() {
        Actress aya = actress(1L, "Aya Sazanami");
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of(title("ABP-001", "ABP", null)));
        when(actressRepo.findAliases(1L)).thenReturn(List.of());

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(io).printlnAnsi(contains("/fake/ABP-001"));
    }

    @Test
    void detail_aliasSectionShown_whenAliasHasTitles() {
        Actress aya = actress(1L, "Aya Sazanami");
        Actress aliasActress = actress(2L, "Haruka Suzumiya");
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of());
        when(actressRepo.findAliases(1L)).thenReturn(List.of(new ActressAlias(1L, "Haruka Suzumiya")));
        when(actressRepo.findByCanonicalName("Haruka Suzumiya")).thenReturn(Optional.of(aliasActress));
        when(titleRepo.findByActress(2L)).thenReturn(List.of(title("MIDE-100", null, null)));

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(io).println(contains("Also known as"));
        verify(io).printlnAnsi(contains("Haruka Suzumiya"));
        verify(io).printlnAnsi(contains("MIDE-100"));
    }

    @Test
    void detail_aliasSectionOmitted_whenAliasHasNoTitles() {
        Actress aya = actress(1L, "Aya Sazanami");
        Actress aliasActress = actress(2L, "Haruka Suzumiya");
        when(actressRepo.resolveByName("Aya Sazanami")).thenReturn(Optional.of(aya));
        when(titleRepo.findByActress(1L)).thenReturn(List.of());
        when(actressRepo.findAliases(1L)).thenReturn(List.of(new ActressAlias(1L, "Haruka Suzumiya")));
        when(actressRepo.findByCanonicalName("Haruka Suzumiya")).thenReturn(Optional.of(aliasActress));
        when(titleRepo.findByActress(2L)).thenReturn(List.of());

        cmd.execute(new String[]{"actress", "Aya", "Sazanami"}, ctx, io);

        verify(io, never()).println(contains("Also known as"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Actress actress(long id, String canonicalName) {
        return actress(id, canonicalName, Actress.Tier.LIBRARY, false);
    }

    private static Actress actress(long id, String canonicalName, Actress.Tier tier, boolean favorite) {
        return Actress.builder()
                .id(id)
                .canonicalName(canonicalName)
                .tier(tier)
                .favorite(favorite)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title title(String code, String label, LocalDate lastSeenAt) {
        return new Title(null, code, null, label, null, "vol-a", "stars/library",
                null, Path.of("/fake/" + code), lastSeenAt != null ? lastSeenAt : LocalDate.of(2024, 1, 1), null);
    }
}
