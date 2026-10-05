/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Kali Atkinson 2948529
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                parseLongOption(arg);
            } else if (arg.startsWith("-") && arg.length() > 1) {
                // Short options may be stacked, e.g. -ax1, and are applied left to right.
                for (char shortName : arg.substring(1).toCharArray()) {
                    parseShortOption(shortName);
                }
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            setError("Usage: VideoPlayer [options] [video-file]");
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                setError("File not found: " + videoFile.getPath());
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                setError("No video file specified.");
            }
        }
    }

    private void parseLongOption(String arg) {
        switch (arg) {
            case "--help" -> helpRequested = true;
            case "--audio" -> audioRequested = true;
            case "--maximise" -> maximiseRequested = true;
            case "--monitor-1" -> setDisplayId(1);
            case "--monitor-2" -> setDisplayId(2);
            default -> setError("Unknown option: " + arg);
        }
    }

    private void parseShortOption(char shortName) {
        switch (shortName) {
            case 'h' -> helpRequested = true;
            case 'a' -> audioRequested = true;
            case 'x' -> maximiseRequested = true;
            case '1' -> setDisplayId(1);
            case '2' -> setDisplayId(2);
            default -> setError("Unknown option: -" + shortName);
        }
    }

    // Keep the first error reported; later ones are usually just consequences of it.
    private void setError(String message) {
        if (errorMessage == null) {
            errorMessage = message;
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            setError("Only one display option can be used");
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
    }
}
