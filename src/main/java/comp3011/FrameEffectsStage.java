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

/** Gets a freshly decoded video frame ready for display, in place. For the player that means applying the effects. */
@FunctionalInterface
interface FrameEffectsStage {
    /**
     * Prepares the frame. The frame belongs to the caller alone, so changing its pixels is safe. Always called on the
     * decoder thread.
     */
    void prepare(Frame frame, InfoFrame info) throws Exception;
}
