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
 * Where the decoder gets its frames from. This is the small slice of the FFmpeg grabber's interface that decoding
 * needs, pulled out so that {@link FrameDecoder} can be tested with a fake source and has no dependency on native
 * video libraries.
 *
 * <p>
 * A source is <b>not</b> thread-safe. After it has been handed to a {@link FrameDecoder} it must only be used by the
 * decoder's own thread.
 * </p>
 */
interface MediaSource extends AutoCloseable {
    /**
     * Decodes the next frame, which holds either video (image) or audio (samples). The returned frame may be reused
     * by the source on the next call, so callers must copy anything they want to keep. Returns null at the end of
     * the media.
     */
    Frame grab() throws Exception;

    /** Media timestamp, in microseconds, of the frame most recently returned by {@link #grab()}. */
    long timestampUs();

    /** Frame number of the frame most recently returned by {@link #grab()}. */
    int frameNumber();

    /** Moves so that the next {@link #grab()} returns the frame at (or just before) the given timestamp. */
    void seek(long timestampUs) throws Exception;

    @Override
    void close();
}
