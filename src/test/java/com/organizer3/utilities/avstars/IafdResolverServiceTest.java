package com.organizer3.utilities.avstars;

import com.organizer3.avstars.iafd.IafdClient;
import com.organizer3.avstars.iafd.IafdProfileParser;
import com.organizer3.avstars.iafd.IafdResolvedProfile;
import com.organizer3.avstars.iafd.IafdSearchParser;
import com.organizer3.avstars.iafd.IafdSearchResult;
import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.repository.AvActressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IafdResolverServiceTest {

    @TempDir Path tmp;
    private AvActressRepository actressRepo;
    private IafdClient iafdClient;
    private IafdSearchParser searchParser;
    private IafdProfileParser profileParser;
    private IafdResolverService svc;

    @BeforeEach
    void setup() {
        actressRepo = mock(AvActressRepository.class);
        iafdClient = mock(IafdClient.class);
        searchParser = mock(IafdSearchParser.class);
        profileParser = mock(IafdProfileParser.class);
        svc = new IafdResolverService(actressRepo, iafdClient, searchParser, profileParser, tmp);
    }

    @Test
    void searchDelegatesToClientAndParser() throws Exception {
        when(iafdClient.fetchSearch("Asa Akira")).thenReturn("<html/>");
        IafdSearchResult r = new IafdSearchResult(
                "abc", "Asa Akira", List.of(), null, null, null, null);
        when(searchParser.parse("<html/>")).thenReturn(List.of(r));

        List<IafdSearchResult> result = svc.search("Asa Akira");
        assertEquals(1, result.size());
        assertEquals("abc", result.get(0).uuid());
    }

    @Test
    void applyFetchesProfileAndHeadshotAndPersists() throws Exception {
        AvActress actress = AvActress.builder()
                .id(7L).stageName("Asa Akira").folderName("Asa Akira")
                .volumeId("qnap_av").build();
        when(actressRepo.findById(7L)).thenReturn(Optional.of(actress));
        when(iafdClient.fetchProfile("abc")).thenReturn("<profile/>");
        IafdResolvedProfile profile = IafdResolvedProfile.builder()
                .iafdId("abc").headshotUrl("https://example.com/asa.jpg")
                .iafdTitleCount(142).activeFrom(2006).build();
        when(profileParser.parse("abc", "<profile/>")).thenReturn(profile);
        when(iafdClient.fetchBytes("https://example.com/asa.jpg")).thenReturn(new byte[]{1, 2, 3});

        var r = svc.apply(7L, "abc");
        assertEquals("abc", r.iafdId());
        assertEquals(142, r.iafdTitleCount());
        assertTrue(r.headshotSaved());
        // Headshot file created under the tmp dir.
        assertTrue(Files.exists(tmp.resolve("abc.jpg")));
        // Repository.updateIafdFields was called with the parsed profile + headshot path.
        verify(actressRepo).updateIafdFields(eq(7L), eq(profile), contains("abc.jpg"));
    }

    @Test
    void applyWithNoHeadshotUrlSkipsDownload() throws Exception {
        AvActress actress = AvActress.builder().id(1L).stageName("X").folderName("X").volumeId("v").build();
        when(actressRepo.findById(1L)).thenReturn(Optional.of(actress));
        when(iafdClient.fetchProfile("id")).thenReturn("<x/>");
        when(profileParser.parse("id", "<x/>")).thenReturn(
                IafdResolvedProfile.builder().iafdId("id").headshotUrl(null).build());

        var r = svc.apply(1L, "id");
        assertFalse(r.headshotSaved());
        verify(iafdClient, never()).fetchBytes(anyString());
        verify(actressRepo).updateIafdFields(eq(1L), any(IafdResolvedProfile.class), isNull());
    }

    @Test
    void applyFailsWhenActressMissing() {
        when(actressRepo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> svc.apply(999L, "id"));
    }

    @Test
    void refreshFailsWhenActressHasNoIafdId() {
        AvActress a = AvActress.builder().id(1L).stageName("X").folderName("X").volumeId("v").iafdId(null).build();
        when(actressRepo.findById(1L)).thenReturn(Optional.of(a));
        assertThrows(IllegalStateException.class, () -> svc.refresh(1L));
    }
}
