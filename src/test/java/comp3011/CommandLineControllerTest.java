package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {
    @TempDir
    Path tempDir;

    private String video;

    @BeforeEach
    void createVideoFile() throws IOException {
        video = Files.createFile(tempDir.resolve("movies.mp4")).toString();
    }

    private static String capture(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString().replace("\r\n", "\n");
    }

    @Test
    void videoFileOnlyLaunchesWithDefaults() {
        CommandLineController c = new CommandLineController(new String[] { video });
        assertTrue(c.shouldLaunchApplication());
        assertFalse(c.isAudioRequested());
        assertFalse(c.isMaximiseRequested());
        assertNull(c.getDisplayId());
        assertEquals(0, c.getExitCode());
    }

    @Test
    void longBuiltInOptions() {
        CommandLineController c = new CommandLineController(
                new String[] { "--audio", "--maximise", "--monitor-2", video });
        assertTrue(c.isAudioRequested());
        assertTrue(c.isMaximiseRequested());
        assertEquals(2, c.getDisplayId());
    }

    @Test
    void stackedShortBuiltInOptionsIncludingDisplayDigit() {
        CommandLineController c = new CommandLineController(new String[] { "-ax1", video });
        assertTrue(c.isAudioRequested());
        assertTrue(c.isMaximiseRequested());
        assertEquals(1, c.getDisplayId());
        assertEquals(0, c.getExitCode());
    }

    @Test
    void videoFileMayComeBeforeOptions() {
        CommandLineController c = new CommandLineController(new String[] { video, "-a" });
        assertTrue(c.shouldLaunchApplication());
        assertTrue(c.isAudioRequested());
    }

    @Test
    void conflictingDisplaysAreAnError() {
        CommandLineController c = new CommandLineController(new String[] { "-12", video });
        assertFalse(c.shouldLaunchApplication());
        assertEquals(1, c.getExitCode());
    }

    @Test
    void unknownShortOptionIsAnError() {
        CommandLineController[] holder = new CommandLineController[1];
        capture(() -> holder[0] = new CommandLineController(new String[] { "-aq", video }));
        assertEquals("Unknown option: -q", holder[0].getErrorMessage());
        assertFalse(holder[0].shouldLaunchApplication());
    }

    @Test
    void unknownLongOptionIsAnError() {
        CommandLineController[] holder = new CommandLineController[1];
        capture(() -> holder[0] = new CommandLineController(new String[] { "--sparkle", video }));
        assertEquals("Unknown option: --sparkle", holder[0].getErrorMessage());
    }

    @Test
    void firstErrorIsTheOneReported() {
        CommandLineController[] holder = new CommandLineController[1];
        capture(() -> holder[0] = new CommandLineController(new String[] { "-q", "-z", video }));
        assertEquals("Unknown option: -q", holder[0].getErrorMessage());
    }

    @Test
    void missingFileIsAnError() {
        CommandLineController[] holder = new CommandLineController[1];
        capture(() -> holder[0] = new CommandLineController(new String[] { "-a" }));
        assertEquals("No video file specified.", holder[0].getErrorMessage());
        assertNull(holder[0].getVideoFile());
    }

    @Test
    void nonexistentFileIsAnError() {
        File missing = tempDir.resolve("nope.mp4").toFile();
        CommandLineController[] holder = new CommandLineController[1];
        capture(() -> holder[0] = new CommandLineController(new String[] { missing.getPath() }));
        assertTrue(holder[0].getErrorMessage().startsWith("File not found"));
    }

    @Test
    void helpWithoutFileIsNotAnError() {
        CommandLineController[] holder = new CommandLineController[1];
        capture(() -> holder[0] = new CommandLineController(new String[] { "-h" }));
        assertNull(holder[0].getErrorMessage());
        assertFalse(holder[0].shouldLaunchApplication());
    }
}
