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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.bytedeco.javacv.Frame;

/**
 * Applies the list of frame processors, in order, to each video frame of one playback session.
 *
 * <p>
 * The processors must be told about the video before they see their first frame, and the details they need (such as the
 * frame size) are only known once a real frame has been decoded. So the chain initialises every processor, once, when
 * it is given its first frame.
 * </p>
 *
 * <p>
 * <b>Thread safety.</b> A chain belongs to one playback session, and {@link #apply} may be called from several threads
 * at once, each with a different frame. Two things make that safe:
 * </p>
 * <ul>
 * <li>Initialisation is a critical region. It must finish, exactly once, before any thread processes a frame, so the
 * first threads to arrive take a lock, and the rest wait for the initialisation to finish rather than carrying on.</li>
 * <li>Once initialised, each processor only ever reads its own state while processing a frame, so any number of frames
 * can be processed at the same time. Processors are required to behave that way. Keeping a scratch buffer in a field
 * is not allowed, for example, and the processors that used to do it now keep their scratch data local to each call.</li>
 * </ul>
 *
 * <p>
 * Nothing else may touch the processors while a session is running: they are stateful, and initialising them again
 * would disturb frames that are still being processed.
 * </p>
 */
final class EffectChain implements FrameEffectsStage {
    /** Facts about the media that come from the decoder rather than from any one frame. */
    record MediaProperties(
            String mediaName,
            int totalVideoFrames,
            double frameRate,
            int intFrameRate,
            int frameDurationUs,
            int pixelFormat) {
    }

    private final List<FrameProcessor> processors;
    private final MediaProperties media;
    private final Object initialiseLock = new Object();
    private volatile boolean initialised; // Written only under the lock. volatile so that other threads see it, and everything the initialisation did, once it is true.

    // Optional diagnostics. Disabled by default, including on TITAN: enable locally with the
    // COMP3011_PROFILE_EFFECTS=1 environment variable. Each EffectChain owns its own counters.
    // LongAdders collect timings without forcing worker threads through one shared lock.
    private static final int PROFILE_REPORT_EVERY_FRAMES = 120;
    private final LongAdder[] effectTimeNs;
    private final LongAdder[] effectCalls;
    private final AtomicInteger profiledFrames;
    private final Object profileReportLock = new Object();
    private int lastReportedFrames;

    EffectChain(List<FrameProcessor> processors, MediaProperties media) {
        this.processors = List.copyOf(processors);
        this.media = media;
        boolean profiling = "1".equals(System.getenv("COMP3011_PROFILE_EFFECTS"));
        if (profiling) {
            effectTimeNs = new LongAdder[processors.size()];
            effectCalls = new LongAdder[processors.size()];
            for (int i = 0; i < processors.size(); i++) {
                effectTimeNs[i] = new LongAdder();
                effectCalls[i] = new LongAdder();
            }
            profiledFrames = new AtomicInteger();
        } else {
            effectTimeNs = null;
            effectCalls = null;
            profiledFrames = null;
        }
    }

    @Override
    public void apply(Frame frame, InfoFrame info) throws Exception {
        if (!initialised) { // The common case, once running, costs one volatile read and no locking.
            synchronized (initialiseLock) {
                if (!initialised) { // Another thread may have finished the job while this one waited for the lock.
                    initialiseProcessors(frame);
                    initialised = true;
                }
            }
        }

        // Keep the ordinary playback hot path free of clocks and atomic updates.
        if (effectTimeNs == null) {
            for (FrameProcessor processor : processors) {
                processor.process(frame, info);
            }
            return;
        }

        for (int i = 0; i < processors.size(); i++) {
            long startNs = System.nanoTime();
            try {
                processors.get(i).process(frame, info);
            } finally {
                effectTimeNs[i].add(System.nanoTime() - startNs);
                effectCalls[i].increment();
            }
        }
        int completed = profiledFrames.incrementAndGet();
        if (completed % PROFILE_REPORT_EVERY_FRAMES == 0) {
            reportEffectTimings(completed);
        }
    }

    // Totals are accumulated durations over all worker threads, not single-thread CPU usage.
    // Grouping repeated instances by effect type makes the most expensive effects easy to spot.
    private void reportEffectTimings(int completedFrames) {
        // Only profiling workers enter here, and only once per reporting interval. The
        // lock prevents two snapshots being printed out of order or interleaved.
        synchronized (profileReportLock) {
            if (completedFrames <= lastReportedFrames) {
                return;
            }
            lastReportedFrames = completedFrames;
            writeEffectTimings(completedFrames);
        }
    }

    private void writeEffectTimings(int completedFrames) {
        record Summary(String name, long timeNs, long calls) {
        }
        Map<String, long[]> totals = new HashMap<>();
        for (int i = 0; i < processors.size(); i++) {
            Class<?> type = processors.get(i).getClass();
            String name = type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName();
            long[] total = totals.computeIfAbsent(name, ignored -> new long[2]);
            total[0] += effectTimeNs[i].sum();
            total[1] += effectCalls[i].sum();
        }
        List<Summary> ranking = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            ranking.add(new Summary(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        ranking.sort(Comparator.comparingLong(Summary::timeNs).reversed());
        long allNs = ranking.stream().mapToLong(Summary::timeNs).sum();
        System.err.printf("EFFECT PROFILE after %d frames (cumulative; elapsed processing time across workers):%n",
                completedFrames);
        for (Summary summary : ranking) {
            double share = allNs == 0 ? 0 : 100.0 * summary.timeNs() / allNs;
            double averageMs = summary.calls() == 0 ? 0 : summary.timeNs() / 1_000_000.0 / summary.calls();
            System.err.printf("  %-26s %5.1f%%   %7.3f ms/call   %d calls%n",
                    summary.name(), share, averageMs, summary.calls());
        }
    }

    private void initialiseProcessors(Frame firstFrame) throws Exception {
        InfoVideo videoInfo = new InfoVideo(
                media.mediaName(),
                media.totalVideoFrames(),
                firstFrame.imageWidth,
                firstFrame.imageHeight,
                firstFrame.imageDepth,
                firstFrame.imageChannels,
                firstFrame.imageStride,
                media.frameRate(),
                media.intFrameRate(),
                media.frameDurationUs(),
                media.pixelFormat());

        for (FrameProcessor processor : processors) {
            processor.initialise(videoInfo);
        }
    }
}
