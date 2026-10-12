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

/**
 * One thing handed from the decoder thread to the thread that shows the video. Every item is stamped with the
 * <i>generation</i> it belongs to. The generation goes up by one every time playback jumps to a new position, so the
 * receiving side can recognise (and throw away) items that were decoded for a position the user has since left.
 */
sealed interface DecodedItem {
    int generation();

    /** A video frame, fully prepared. The frame is a private copy owned by whoever holds this item. */
    record Video(int generation, int frameNumber, long timestampUs, Frame frame, long preparedAtNs)
            implements DecodedItem {
    }

    /** A chunk of audio, ready to be written to the sound card. */
    record Audio(int generation, long timestampUs, byte[] samples) implements DecodedItem {
    }

    /** There is nothing more to decode from the current position. */
    record EndOfStream(int generation) implements DecodedItem {
    }

    /** Decoding failed. The decoder does nothing more until it is told to seek or shut down. */
    record Failure(int generation, Exception cause) implements DecodedItem {
    }
}
