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

import java.util.function.Supplier;

/**
 * Describes one command-line selectable frame processor: its short and long option names, its help text, and a
 * factory that creates a fresh {@link FrameProcessor} each time the option appears on the command line.
 */
public record FrameProcessorOption(
        char shortName,
        String longName,
        String description,
        Supplier<FrameProcessor> factory) {

    public String longOption() {
        return "--" + longName;
    }

    public FrameProcessor create() {
        return factory.get();
    }
}
