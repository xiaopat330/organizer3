package com.organizer3.trash;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTest {

    @Test
    void rootReflectsConfiguredFolder() {
        Sandbox s = new Sandbox(new TrashTest.InMemoryFS(), "_sandbox");
        assertEquals(Path.of("/_sandbox"), s.root());
    }

    @Test
    void resolveReturnsNestedPath() {
        Sandbox s = new Sandbox(new TrashTest.InMemoryFS(), "_sandbox");
        assertEquals(Path.of("/_sandbox/tests/xyz"), s.resolve("tests/xyz"));
        assertEquals(Path.of("/_sandbox"), s.resolve(""));
        assertEquals(Path.of("/_sandbox"), s.resolve(null));
    }

    @Test
    void ensureExistsCreatesSandboxRoot() throws IOException {
        TrashTest.InMemoryFS fs = new TrashTest.InMemoryFS();
        Sandbox s = new Sandbox(fs, "_sandbox");
        assertFalse(fs.exists(Path.of("/_sandbox")));
        s.ensureExists();
        assertTrue(fs.exists(Path.of("/_sandbox")));
    }

    @Test
    void rejectsEmptySandboxFolder() {
        assertThrows(IllegalArgumentException.class, () -> new Sandbox(new TrashTest.InMemoryFS(), ""));
        assertThrows(IllegalArgumentException.class, () -> new Sandbox(new TrashTest.InMemoryFS(), null));
    }
}
