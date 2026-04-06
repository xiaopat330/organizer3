package com.organizer3.command;

import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActressesCommandTest {

    private ActressRepository actressRepo;
    private TitleRepository titleRepo;
    private ActressesCommand cmd;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        actressRepo = mock(ActressRepository.class);
        titleRepo = mock(TitleRepository.class);
        cmd = new ActressesCommand(actressRepo, titleRepo);
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    @Test
    void noArgs_printsUsage() {
        cmd.execute(new String[]{"actresses"}, ctx, io);

        assertTrue(output.toString().contains("Usage:"));
        verifyNoInteractions(actressRepo, titleRepo);
    }

    @Test
    void unknownTier_printsError() {
        cmd.execute(new String[]{"actresses", "megastar"}, ctx, io);

        assertTrue(output.toString().contains("Unknown tier"));
        verifyNoInteractions(actressRepo, titleRepo);
    }

    @Test
    void tierCaseInsensitive_acceptsLowercase() {
        when(actressRepo.findByTier(Actress.Tier.GODDESS)).thenReturn(List.of());

        cmd.execute(new String[]{"actresses", "goddess"}, ctx, io);

        verify(actressRepo).findByTier(Actress.Tier.GODDESS);
    }

    @Test
    void emptyTier_printsHeaderAndCount() {
        when(actressRepo.findByTier(Actress.Tier.MINOR)).thenReturn(List.of());

        cmd.execute(new String[]{"actresses", "minor"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("MINOR"));
        assertTrue(out.contains("0 actresses"));
    }

    @Test
    void listsSortedByTitleCountDescending() {
        Actress aya = actress(1L, "Aya Sazanami");
        Actress yua = actress(2L, "Yua Mikami");
        Actress hibiki = actress(3L, "Hibiki Otsuki");

        when(actressRepo.findByTier(Actress.Tier.POPULAR)).thenReturn(List.of(aya, yua, hibiki));
        when(titleRepo.countByActress(1L)).thenReturn(22);
        when(titleRepo.countByActress(2L)).thenReturn(45);
        when(titleRepo.countByActress(3L)).thenReturn(30);

        cmd.execute(new String[]{"actresses", "popular"}, ctx, io);

        String out = output.toString();
        int ayaPos = out.indexOf("Aya Sazanami");
        int yuaPos = out.indexOf("Yua Mikami");
        int hibikiPos = out.indexOf("Hibiki Otsuki");

        // Expected order: Yua (45) > Hibiki (30) > Aya (22)
        assertTrue(yuaPos < hibikiPos);
        assertTrue(hibikiPos < ayaPos);
    }

    @Test
    void singleActress_singularLabel() {
        when(actressRepo.findByTier(Actress.Tier.GODDESS)).thenReturn(List.of(actress(1L, "Yua Mikami")));
        when(titleRepo.countByActress(1L)).thenReturn(127);

        cmd.execute(new String[]{"actresses", "goddess"}, ctx, io);

        assertTrue(output.toString().contains("1 actress)"));
    }

    @Test
    void outputIncludesActressNameAndCount() {
        when(actressRepo.findByTier(Actress.Tier.GODDESS)).thenReturn(List.of(actress(1L, "Yua Mikami")));
        when(titleRepo.countByActress(1L)).thenReturn(127);

        cmd.execute(new String[]{"actresses", "goddess"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("Yua Mikami"));
        assertTrue(out.contains("127"));
    }

    private static Actress actress(long id, String name) {
        return Actress.builder()
                .id(id)
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }
}
