/*
 * Playback instrumentation for Adelaide University COMP3011 Assignment 2.
 * Opt-in only; uses no background thread and never changes the playback clock or frame queues.
 * All methods are called by the JavaFX application thread.
 */
package comp3011;

/**
 * Diagnostic counters to distinguish slow preparation, decoder starvation, and JavaFX pulse/callback delays.
 * Enable in PowerShell with $env:COMP3011_TRACE_PLAYBACK = "1".
 *
 * These are measurements of frames *passed to the view*, not proof that the compositor put each frame on screen.
 */
final class PlaybackDiagnostics {
    private static final long REPORT_NS = 5_000_000_000L;
    private static final long SLOW_PULSE_NS = 50_000_000L;
    private static final long NANOS_PER_MS = 1_000_000L;

    private long windowStartNs;
    private long previousPulseNs;
    private long lastShownTimestampUs = -1;
    private int lastShownNumber = -1;
    private long pulses;
    private long slowPulses;
    private long maxPulseGapNs;
    private long displayed;
    private long frameNumberGaps;
    private long missingNumbers;
    private long lateAtView;
    private long lateAtPreparation;
    private long maxLatenessNs;
    private long maxConvertNs;
    private long maxNotifyNs;
    private long totalConvertNs;
    private long totalNotifyNs;
    private long emptyAndOverduePulses;
    private long emptyDecoderPulses;
    private long headPendingPulses;
    private long headCompletePulses;

    static boolean enabled() {
        return "1".equals(System.getenv("COMP3011_TRACE_PLAYBACK"));
    }

    void reset() {
        windowStartNs = 0;
        previousPulseNs = 0;
        lastShownTimestampUs = -1;
        lastShownNumber = -1;
        clearWindow();
    }

    long lastShownTimestampUs() {
        return lastShownTimestampUs;
    }

    void pulse(long now) {
        if (windowStartNs == 0) {
            windowStartNs = now;
        }
        if (previousPulseNs != 0) {
            long gapNs = now - previousPulseNs;
            if (gapNs > SLOW_PULSE_NS) {
                slowPulses++;
            }
            maxPulseGapNs = Math.max(maxPulseGapNs, gapNs);
        }
        previousPulseNs = now;
        pulses++;
    }

    // A pulse observed an empty view queue at least half a frame beyond the estimated next frame's due time.
    // Counts pulses, not missing frames; the next PTS is estimated from the nominal frame rate.
    void starved(FrameDecoder decoder) {
        emptyAndOverduePulses++;
        if (decoder == null) {
            emptyDecoderPulses++;
            return;
        }
        FrameDecoder.QueueProbe probe = decoder.queueProbe();
        if (probe.queuedItems() == 0) {
            emptyDecoderPulses++;
        } else if (probe.headPending()) {
            headPendingPulses++;
        } else {
            headCompletePulses++;
        }
    }

    void shown(int frameNumber, long timestampUs, long preparedAtNs, long targetNs,
            long handedToViewNs, long convertNs, long notifyNs, int nominalDurationUs) {
        displayed++;
        if (lastShownNumber >= 0 && frameNumber > lastShownNumber + 1) {
            frameNumberGaps++;
            missingNumbers += frameNumber - lastShownNumber - 1;
        }
        lastShownNumber = frameNumber;
        lastShownTimestampUs = timestampUs;
        long latenessNs = Math.max(0, handedToViewNs - targetNs);
        maxLatenessNs = Math.max(maxLatenessNs, latenessNs);
        if (nominalDurationUs > 0 && latenessNs > nominalDurationUs * 1_000L) {
            lateAtView++;
        }
        if (preparedAtNs > targetNs) {
            lateAtPreparation++;
        }
        maxConvertNs = Math.max(maxConvertNs, convertNs);
        maxNotifyNs = Math.max(maxNotifyNs, notifyNs);
        totalConvertNs += convertNs;
        totalNotifyNs += notifyNs;
    }

    void reportIfDue(long now, int readyFrames, FrameDecoder decoder) {
        if (windowStartNs != 0 && now - windowStartNs >= REPORT_NS) {
            report(readyFrames, decoder);
            clearWindow();
            windowStartNs = now;
        }
    }

    void reportFinal(int readyFrames, FrameDecoder decoder) {
        if (pulses > 0) {
            report(readyFrames, decoder);
            reset();
        }
    }

    private void report(int readyFrames, FrameDecoder decoder) {
        FrameDecoder.QueueProbe queue = decoder == null ? null : decoder.queueProbe();
        long avgConvertUs = displayed == 0 ? 0 : totalConvertNs / displayed / 1_000;
        long avgNotifyUs = displayed == 0 ? 0 : totalNotifyNs / displayed / 1_000;
        System.err.printf(
                "PLAYBACK TRACE: pulses=%d slowPulses(>50ms)=%d maxPulseGapMs=%.1f "
                + "shownToView=%d displayedNumberGaps=%d numbersSkipped=%d "
                + "lateToView(>1frame)=%d preparedAfterDeadline=%d maxViewLatenessMs=%.1f%n",
                pulses, slowPulses, maxPulseGapNs / (double) NANOS_PER_MS,
                displayed, frameNumberGaps, missingNumbers,
                lateAtView, lateAtPreparation, maxLatenessNs / (double) NANOS_PER_MS);
        System.err.printf(
                "PLAYBACK TRACE: overdueEmptyPulses=%d (decoderQueueEmpty=%d headUnfinished=%d headFinished=%d) "
                + "readyFrames=%d decoderQueued=%d decoderHeadUnfinished=%s "
                + "convertAvgUs=%d convertMaxMs=%.1f notifyAvgUs=%d notifyMaxMs=%.1f%n",
                emptyAndOverduePulses, emptyDecoderPulses, headPendingPulses, headCompletePulses,
                readyFrames, queue == null ? -1 : queue.queuedItems(),
                queue != null && queue.headPending(), avgConvertUs,
                maxConvertNs / (double) NANOS_PER_MS, avgNotifyUs,
                maxNotifyNs / (double) NANOS_PER_MS);
    }

    private void clearWindow() {
        pulses = 0;
        slowPulses = 0;
        maxPulseGapNs = 0;
        displayed = 0;
        frameNumberGaps = 0;
        missingNumbers = 0;
        lateAtView = 0;
        lateAtPreparation = 0;
        maxLatenessNs = 0;
        maxConvertNs = 0;
        maxNotifyNs = 0;
        totalConvertNs = 0;
        totalNotifyNs = 0;
        emptyAndOverduePulses = 0;
        emptyDecoderPulses = 0;
        headPendingPulses = 0;
        headCompletePulses = 0;
    }
}
