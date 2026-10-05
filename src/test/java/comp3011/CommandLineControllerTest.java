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
import java.util.List;

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

    private static String shorts(CommandLineController controller) {
        StringBuilder sb = new StringBuilder();
        controller.getRequestedProcessors().forEach(o -> sb.append(o.shortName()));
        return sb.toString();
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

    @Test
    void noProcessorsByDefault() {
        CommandLineController c = new CommandLineController(new String[] { video });
        assertTrue(c.getRequestedProcessors().isEmpty());
        assertTrue(c.createFrameProcessors().isEmpty());
    }

    @Test
    void longOptionsKeepCommandLineOrderIncludingRepeats() {
        CommandLineController c = new CommandLineController(new String[] {
                "--jitter-frames", "--flicker-frames", "--dust-frames", "--dust-frames", "--number-frames", video });
        assertEquals("jfddn", shorts(c));
    }

    @Test
    void stackedShortProcessorOptions() {
        assertEquals("jfddn", shorts(new CommandLineController(new String[] { "-jfddn", video })));
    }

    @Test
    void separateShortProcessorOptions() {
        assertEquals("jfddn", shorts(new CommandLineController(new String[] { "-j", "-f", "-d", "-d", "-n", video })));
    }

    @Test
    void mixedLongShortAndStacked() {
        assertEquals("jfddn", shorts(new CommandLineController(
                new String[] { "-jf", "--dust-frames", "-d", "--number-frames", video })));
    }

    @Test
    void processorsMayBeStackedWithBuiltInFlags() {
        CommandLineController c = new CommandLineController(new String[] { "-ax1w", video });
        assertTrue(c.isAudioRequested());
        assertEquals(1, c.getDisplayId());
        assertEquals("w", shorts(c));
    }

    @Test
    void videoFileMayComeBeforeProcessorOptions() {
        assertEquals("nsb", shorts(new CommandLineController(new String[] { video, "-ns", "-b" })));
    }

    @Test
    void everyProcessorLetterInHelpExampleIsAccepted() {
        CommandLineController c = new CommandLineController(new String[] { "-nssnfwyvdjmbp", video });
        assertEquals("nssnfwyvdjmbp", shorts(c));
        assertEquals(13, c.createFrameProcessors().size());
    }

    @Test
    void createdProcessorsMatchRequestedTypesAndRepeatsAreSeparateInstances() {
        CommandLineController c = new CommandLineController(new String[] { "-wyw", video });
        List<FrameProcessor> processors = c.createFrameProcessors();
        assertEquals(3, processors.size());
        assertTrue(processors.get(0) instanceof FrameBlackAndWhiter);
        assertTrue(processors.get(1) instanceof FrameYellower);
        assertTrue(processors.get(2) instanceof FrameBlackAndWhiter);
        assertTrue(processors.get(0) != processors.get(2), "repeats must be separate instances");
    }

    @Test
    void unknownProcessorOptionInStackIsAnError() {
        CommandLineController[] holder = new CommandLineController[1];
        capture(() -> holder[0] = new CommandLineController(new String[] { "-nq", video }));
        assertEquals("Unknown option: -q", holder[0].getErrorMessage());
    }

    @Test
    void helpTextMatchesSpecification() {
        String out = capture(() -> new CommandLineController(new String[] { "--help" }));
        String expected = """
                Usage: VideoPlayer [options] [video-file]

                Options:
                  -h, --help         Show this help message
                  -a, --audio        Play audio
                  -x, --maximise     Open the player maximised
                  -1, --monitor-1    Open the player on display 1
                  -2, --monitor-2    Open the player on display 2

                Frame processors:
                  -n, --number-frames    Render the frame number onto each frame
                  -s, --scratch-frames   Render vertical film scratches
                  -f, --flicker-frames   Randomly dim frames
                  -w, --black-and-white  Convert frames to black and white
                  -y, --yellow-frames    Apply a warmer colour temperature
                  -v, --vignette-frames  Darken the frame edges
                  -d, --dust-frames      Render dust and hair marks
                  -j, --jitter-frames    Randomly displace frames by a few pixels
                  -m, --mottle-frames    Add cloudy emulsion mottling
                  -b, --bleed-frames     Bleed light into frames
                  -p, --pepper-frames    Pepper frames with dark spots/blotches
                Frame processors are applied in command-line order and may be repeated.
                Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,
                converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.
                """;
        assertEquals(expected, out);
    }
}
