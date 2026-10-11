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

/**
 * Maps timestamps inside the media to moments on the machine's {@link System#nanoTime()} clock, so the player knows
 * when each video frame is due on screen and which audio is due to be heard.
 *
 * <p>
 * The clock is anchored by the first frame decoded after a reset: that frame's media timestamp is said to happen at
 * {@code startNs}, and every later frame is due that many microseconds after it. Pausing moves the anchor forward by
 * however long the pause lasted, so playback resumes exactly where it left off.
 * </p>
 *
 * <p>
 * The current time is always passed in rather than read here, which keeps this class free of hidden inputs and easy
 * to test. It is <b>not thread-safe</b>: today every call comes from the JavaFX thread. When decoding moves onto its
 * own thread, this is one of the critical regions that will need protecting.
 * </p>
 */
final class PlaybackClock {
    private static final long NOT_STARTED = -1;

    private long firstTimestampUs = NOT_STARTED; // Media timestamp of the anchor frame.
    private long logicalBaseUs; // Position in the media, as shown to the user, that the anchor frame represents.
    private long startNs; // The nanoTime() moment at which the anchor frame was due. Zero until anchored.
    private long pauseStartedNs; // The nanoTime() moment the current pause began. Zero when not paused.

    /**
     * Forgets the anchor, ready to be re-anchored by the next decoded frame. Call after opening or seeking.
     *
     * @param logicalTimestampUs the position in the media that playback is about to continue from
     * @param paused             whether playback is currently paused
     * @param nowNs              the current {@link System#nanoTime()}
     */
    void reset(long logicalTimestampUs, boolean paused, long nowNs) {
        firstTimestampUs = NOT_STARTED;
        logicalBaseUs = logicalTimestampUs;
        startNs = 0;
        pauseStartedNs = paused ? nowNs : 0;
    }

    /** Forgets the anchor and any pause, because playback has been closed. The logical base is left alone. */
    void stop() {
        firstTimestampUs = NOT_STARTED;
        startNs = 0;
        pauseStartedNs = 0;
    }

    /** True once a frame has anchored the clock since the last {@link #reset} or {@link #stop}. */
    boolean isAnchored() {
        return firstTimestampUs != NOT_STARTED;
    }

    /** True when the clock is anchored and so can answer questions about what is due when. */
    boolean isRunning() {
        return isAnchored() && startNs > 0;
    }

    /** Anchors the clock to the first decoded frame: this frame is due right now. */
    void anchor(long mediaTimestampUs, boolean paused, long nowNs) {
        firstTimestampUs = mediaTimestampUs;
        startNs = nowNs;
        if (paused) {
            pauseStartedNs = startNs;
        }
    }

    void pause(long nowNs) {
        pauseStartedNs = nowNs;
    }

    /** Pushes the anchor forward by the length of the pause, so no media time is skipped or repeated. */
    void resume(long nowNs) {
        if (pauseStartedNs > 0 && startNs > 0) {
            startNs += nowNs - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    /** Microseconds of media between the anchor frame and the given frame, never negative. */
    long relativeTimestampUs(long mediaTimestampUs) {
        return Math.max(0, mediaTimestampUs - firstTimestampUs);
    }

    /** The position in the media, as shown to the user, of the given frame. */
    long logicalTimestampUs(long mediaTimestampUs) {
        return logicalBaseUs + relativeTimestampUs(mediaTimestampUs);
    }

    /** The {@link System#nanoTime()} moment at which the given frame is due on screen. */
    long targetTimeNs(long mediaTimestampUs) {
        return startNs + relativeTimestampUs(mediaTimestampUs) * 1_000L;
    }

    /**
     * The media timestamp up to which audio should have been handed to the sound card by now, looking
     * {@code leadNs} into the future so the audio buffer never runs dry.
     */
    long dueMediaTimestampUs(long nowNs, long leadNs) {
        return firstTimestampUs + (nowNs + leadNs - startNs) / 1_000L;
    }
}
