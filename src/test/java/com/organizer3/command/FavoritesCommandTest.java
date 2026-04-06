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

class FavoritesCommandTest {

    private ActressRepository actressRepo;
    private TitleRepository titleRepo;
    private FavoritesCommand cmd;
    private SessionContext ctx;
    private StringWriter output;
    private CommandIO io;

    @BeforeEach
    void setUp() {
        actressRepo = mock(ActressRepository.class);
        titleRepo = mock(TitleRepository.class);
        cmd = new FavoritesCommand(actressRepo, titleRepo);
        ctx = new SessionContext();
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
    }

    @Test
    void noFavorites_printsEmptyList() {
        when(actressRepo.findFavorites()).thenReturn(List.of());

        cmd.execute(new String[]{"favorites"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("FAVORITES"));
        assertTrue(out.contains("0 actresses"));
    }

    @Test
    void singleFavorite_singularLabel() {
        when(actressRepo.findFavorites()).thenReturn(List.of(actress(1L, "Yua Mikami")));
        when(titleRepo.countByActress(1L)).thenReturn(45);

        cmd.execute(new String[]{"favorites"}, ctx, io);

        assertTrue(output.toString().contains("1 actress)"));
    }

    @Test
    void outputIncludesActressNameAndCount() {
        when(actressRepo.findFavorites()).thenReturn(List.of(actress(1L, "Yua Mikami")));
        when(titleRepo.countByActress(1L)).thenReturn(45);

        cmd.execute(new String[]{"favorites"}, ctx, io);

        String out = output.toString();
        assertTrue(out.contains("Yua Mikami"));
        assertTrue(out.contains("45"));
    }

    @Test
    void listsSortedByTitleCountDescending() {
        Actress aya = actress(1L, "Aya Sazanami");
        Actress yua = actress(2L, "Yua Mikami");
        Actress hibiki = actress(3L, "Hibiki Otsuki");

        when(actressRepo.findFavorites()).thenReturn(List.of(aya, yua, hibiki));
        when(titleRepo.countByActress(1L)).thenReturn(22);
        when(titleRepo.countByActress(2L)).thenReturn(45);
        when(titleRepo.countByActress(3L)).thenReturn(30);

        cmd.execute(new String[]{"favorites"}, ctx, io);

        String out = output.toString();
        int ayaPos = out.indexOf("Aya Sazanami");
        int yuaPos = out.indexOf("Yua Mikami");
        int hibikiPos = out.indexOf("Hibiki Otsuki");

        // Expected order: Yua (45) > Hibiki (30) > Aya (22)
        assertTrue(yuaPos < hibikiPos);
        assertTrue(hibikiPos < ayaPos);
    }

    private static Actress actress(long id, String name) {
        return Actress.builder()
                .id(id)
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .favorite(true)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }
}
