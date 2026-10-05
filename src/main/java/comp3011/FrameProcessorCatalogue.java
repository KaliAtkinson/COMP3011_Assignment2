/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for the frame processors selectable from the command line. The parser and the help text are
 * both driven from this list, so supporting a new effect means adding exactly one entry here (open/closed principle).
 * The order of entries is the order they appear in the help text.
 */
public final class FrameProcessorCatalogue {
    private static final List<FrameProcessorOption> OPTIONS = List.of(
            new FrameProcessorOption('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
            new FrameProcessorOption('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
            new FrameProcessorOption('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
            new FrameProcessorOption('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
            new FrameProcessorOption('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
            new FrameProcessorOption('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
            new FrameProcessorOption('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
            new FrameProcessorOption('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
            new FrameProcessorOption('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
            new FrameProcessorOption('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
            new FrameProcessorOption('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new));

    private FrameProcessorCatalogue() {
    }

    public static List<FrameProcessorOption> options() {
        return OPTIONS;
    }

    public static Optional<FrameProcessorOption> findByShortName(char shortName) {
        return OPTIONS.stream().filter(option -> option.shortName() == shortName).findFirst();
    }

    public static Optional<FrameProcessorOption> findByLongOption(String longOption) {
        return OPTIONS.stream().filter(option -> option.longOption().equals(longOption)).findFirst();
    }
}
