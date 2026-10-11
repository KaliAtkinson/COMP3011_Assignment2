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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybackClockTest {
    private static final long FRAME_US = 41_667;
    private static final long START_NS = 10_000_000_000L;

    private PlaybackClock anchoredClock() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(0, false, START_NS);
        clock.anchor(2_000_000, false, START_NS);
        return clock;
    }

    @Test
    void newClockIsNotAnchoredOrRunning() {
        PlaybackClock clock = new PlaybackClock();
        assertFalse(clock.isAnchored());
        assertFalse(clock.isRunning());
    }

    @Test
    void anchoringStartsTheClock() {
        PlaybackClock clock = anchoredClock();
        assertTrue(clock.isAnchored());
        assertTrue(clock.isRunning());
    }

    @Test
    void anchorFrameIsDueAtTheMomentItWasAnchored() {
        assertEquals(START_NS, anchoredClock().targetTimeNs(2_000_000));
    }

    @Test
    void laterFramesAreDueByTheirMediaTimeDifference() {
        PlaybackClock clock = anchoredClock();
        assertEquals(START_NS + 3 * FRAME_US * 1_000L, clock.targetTimeNs(2_000_000 + 3 * FRAME_US));
    }

    @Test
    void framesBeforeTheAnchorAreTreatedAsDueAtTheAnchor() {
        PlaybackClock clock = anchoredClock();
        assertEquals(0, clock.relativeTimestampUs(1_900_000));
        assertEquals(START_NS, clock.targetTimeNs(1_900_000));
    }

    @Test
    void logicalTimestampIsTheSeekPositionPlusTimePlayedSinceTheAnchor() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(7_000_000, false, START_NS);
        clock.anchor(2_000_000, false, START_NS);
        assertEquals(7_000_000, clock.logicalTimestampUs(2_000_000));
        assertEquals(7_000_000 + 5 * FRAME_US, clock.logicalTimestampUs(2_000_000 + 5 * FRAME_US));
    }

    @Test
    void pausingThenResumingPushesTheAnchorForwardByTheLengthOfThePause() {
        PlaybackClock clock = anchoredClock();
        clock.pause(START_NS + 500_000_000L);
        clock.resume(START_NS + 2_500_000_000L); // Paused for two seconds.
        assertEquals(START_NS + 2_000_000_000L, clock.targetTimeNs(2_000_000));
    }

    @Test
    void resumeWithoutAPauseChangesNothing() {
        PlaybackClock clock = anchoredClock();
        clock.resume(START_NS + 5_000_000_000L);
        assertEquals(START_NS, clock.targetTimeNs(2_000_000));
    }

    @Test
    void resumeBeforeAnchoringChangesNothing() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(0, true, 100);
        clock.resume(900);
        clock.anchor(2_000_000, false, START_NS);
        assertEquals(START_NS, clock.targetTimeNs(2_000_000));
    }

    @Test
    void anchoringWhilePausedStartsThePauseAtTheAnchor() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(0, true, 100);
        clock.anchor(2_000_000, true, 500);
        clock.resume(900); // 400ns of pause since the anchor.
        assertEquals(900, clock.targetTimeNs(2_000_000));
    }

    @Test
    void audioDueTimeLooksAheadByTheLead() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(0, false, START_NS);
        clock.anchor(1_000_000, false, START_NS);
        // 50ms after the anchor plus a 30ms lead is 80ms of media beyond the anchor frame.
        assertEquals(1_080_000, clock.dueMediaTimestampUs(START_NS + 50_000_000L, 30_000_000L));
    }

    @Test
    void resetForgetsTheAnchor() {
        PlaybackClock clock = anchoredClock();
        clock.reset(3_000_000, false, START_NS);
        assertFalse(clock.isAnchored());
        assertFalse(clock.isRunning());
    }

    @Test
    void stopForgetsTheAnchorButKeepsTheLogicalBase() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(5_000_000, false, START_NS);
        clock.anchor(2_000_000, false, START_NS);
        clock.stop();
        assertFalse(clock.isAnchored());
        assertFalse(clock.isRunning());

        clock.anchor(9_000_000, false, START_NS);
        assertEquals(5_000_000, clock.logicalTimestampUs(9_000_000));
    }
}
