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

import org.bytedeco.javacv.Frame;

/** The step that turns a freshly decoded video frame into one ready for display. For the player, that is applying the effects. */
@FunctionalInterface
interface FrameEffectsStage {
    /**
     * Applies the stage to the frame, in place. The frame belongs to the caller alone, so changing its pixels is safe.
     * May be called from several threads at once, each with a different frame, so implementations must be thread-safe.
     */
    void apply(Frame frame, InfoFrame info) throws Exception;
}
