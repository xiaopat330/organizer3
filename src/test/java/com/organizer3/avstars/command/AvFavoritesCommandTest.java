package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvFavoritesCommandTest {

    @Mock AvActressRepository actressRepo;
    @Mock SessionContext ctx;
    @Mock CommandIO io;

    AvFavoritesCommand command;

    @BeforeEach
    void setUp() {
        command = new AvFavoritesCommand(actressRepo);
    }

    @Test
    void nameAndDescriptionExposed() {
        assertEquals("av favorites", command.name());
        assertNotNull(command.description());
    }

    @Test
    void emptyFavoritesPrintsGuidanceMessage() {
        when(actressRepo.findFavorites()).thenReturn(List.of());

        command.execute(new String[]{"av favorites"}, ctx, io);

        verify(io).println(contains("No AV favorites"));
    }

    @Test
    void nonEmptyFavoritesPrintsTableAndCount() {
        AvActress a = AvActress.builder().id(1L).stageName("Asa Akira").folderName("Asa Akira")
                .volumeId("qnap_av").videoCount(52).totalSizeBytes(12L * 1024 * 1024 * 1024)
                .grade("SSS").favorite(true).build();
        when(actressRepo.findFavorites()).thenReturn(List.of(a));

        command.execute(new String[]{"av favorites"}, ctx, io);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(io, atLeast(3)).println(captor.capture());
        List<String> lines = captor.getAllValues();
        assertTrue(lines.stream().anyMatch(l -> l.contains("Asa Akira")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("[SSS]")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("1 favorite")));
    }
}
