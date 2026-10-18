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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class EffectChainTest {
    private static final EffectChain.MediaProperties MEDIA =
            new EffectChain.MediaProperties("movie", 240, 24.0, 24, 41_667, 3);

    /** A processor that does nothing but write down what it is asked to do. */
    private static class RecordingProcessor extends FrameProcessor {
        private final String name;
        private final List<String> log;
        boolean failInitialise;

        RecordingProcessor(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void initialise(InfoVideo videoInfo) throws Exception {
            if (failInitialise) {
                throw new IllegalStateException(name + " cannot initialise");
            }
            super.initialise(videoInfo);
            log.add(name + " initialise");
        }

        @Override
        public void process(Frame frame, InfoFrame info) {
            log.add(name + " frame " + info.frameNumber());
        }

        InfoVideo seenVideoInfo() {
            return videoInfo;
        }
    }

    private static Frame frame() {
        return new Frame(8, 4, Frame.DEPTH_UBYTE, 3);
    }

    @Test
    void initialisesEveryProcessorOnceBeforeTheFirstFrameThenAppliesThemInOrder() throws Exception {
        List<String> log = new ArrayList<>();
        EffectChain chain = new EffectChain(
                List.of(new RecordingProcessor("A", log), new RecordingProcessor("B", log)), MEDIA);

        chain.apply(frame(), new InfoFrame(0, 0));
        chain.apply(frame(), new InfoFrame(1, 41_667));
        chain.apply(frame(), new InfoFrame(2, 83_334));

        assertEquals(
                List.of(
                        "A initialise", "B initialise",
                        "A frame 0", "B frame 0",
                        "A frame 1", "B frame 1",
                        "A frame 2", "B frame 2"),
                log);
    }

    @Test
    void theSameProcessorMayAppearMoreThanOnceInTheChain() throws Exception {
        List<String> log = new ArrayList<>();
        RecordingProcessor a = new RecordingProcessor("A", log);
        RecordingProcessor b = new RecordingProcessor("B", log);
        EffectChain chain = new EffectChain(List.of(a, b, a), MEDIA);

        chain.apply(frame(), new InfoFrame(0, 0));

        assertEquals(List.of("A initialise", "B initialise", "A initialise", "A frame 0", "B frame 0", "A frame 0"), log);
    }

    @Test
    void videoInfoCombinesTheFirstFramesGeometryWithTheMediaProperties() throws Exception {
        RecordingProcessor processor = new RecordingProcessor("A", new ArrayList<>());
        EffectChain chain = new EffectChain(List.of(processor), MEDIA);
        Frame first = new Frame(16, 8, Frame.DEPTH_UBYTE, 3);

        chain.apply(first, new InfoFrame(0, 0));

        InfoVideo info = processor.seenVideoInfo();
        assertEquals("movie", info.mediaName());
        assertEquals(240, info.totalVideoFrames());
        assertEquals(16, info.imageWidth());
        assertEquals(8, info.imageHeight());
        assertEquals(first.imageDepth, info.imageDepth());
        assertEquals(3, info.imageChannels());
        assertEquals(first.imageStride, info.imageStride());
        assertEquals(24, info.intFrameRate());
        assertEquals(41_667, info.frameDurationUs());
        assertEquals(3, info.pixelFormat());
    }

    @Test
    void anEmptyChainLeavesFramesAlone() throws Exception {
        EffectChain chain = new EffectChain(List.of(), MEDIA);
        chain.apply(frame(), new InfoFrame(0, 0)); // Simply must not fail.
    }

    @Test
    void aNewChainInitialisesTheProcessorsAgain() throws Exception {
        List<String> log = new ArrayList<>();
        RecordingProcessor processor = new RecordingProcessor("A", log);

        new EffectChain(List.of(processor), MEDIA).apply(frame(), new InfoFrame(0, 0));
        new EffectChain(List.of(processor), MEDIA).apply(frame(), new InfoFrame(0, 0));

        assertEquals(List.of("A initialise", "A frame 0", "A initialise", "A frame 0"), log);
    }

    @Test
    void failedInitialisationIsReportedAndTriedAgainOnTheNextFrame() throws Exception {
        List<String> log = new ArrayList<>();
        RecordingProcessor processor = new RecordingProcessor("A", log);
        processor.failInitialise = true;
        EffectChain chain = new EffectChain(List.of(processor), MEDIA);

        assertThrows(IllegalStateException.class, () -> chain.apply(frame(), new InfoFrame(0, 0)));
        assertEquals(List.of(), log, "no frame may be processed by an uninitialised processor");

        processor.failInitialise = false;
        chain.apply(frame(), new InfoFrame(1, 41_667));
        assertEquals(List.of("A initialise", "A frame 1"), log);
    }

    /**
     * A processor that is slow to initialise and that notices if it is ever asked to process a frame before it has
     * finished initialising, or if it is initialised more than once.
     */
    private static class SlowInitialisingProcessor extends FrameProcessor {
        final AtomicInteger initialiseCalls = new AtomicInteger();
        final AtomicInteger framesBeforeInitialised = new AtomicInteger();
        final AtomicInteger framesProcessed = new AtomicInteger();
        private volatile boolean ready;

        @Override
        public void initialise(InfoVideo videoInfo) throws Exception {
            initialiseCalls.incrementAndGet();
            Thread.sleep(100); // Long enough that the other threads arrive while this one is still working.
            super.initialise(videoInfo);
            ready = true;
        }

        @Override
        public void process(Frame frame, InfoFrame info) {
            if (!ready) {
                framesBeforeInitialised.incrementAndGet();
            }
            framesProcessed.incrementAndGet();
        }
    }

    @Test
    void manyThreadsArrivingTogetherInitialiseOnceAndNoneProcessesBeforeItIsDone() throws Exception {
        int threads = 8;
        SlowInitialisingProcessor processor = new SlowInitialisingProcessor();
        EffectChain chain = new EffectChain(List.of(processor), MEDIA);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch allReady = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int frameNumber = t;
                futures.add(pool.submit(() -> {
                    allReady.countDown();
                    go.await(); // Release every thread at the same moment.
                    chain.apply(frame(), new InfoFrame(frameNumber, frameNumber * 41_667L));
                    return null;
                }));
            }
            allReady.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, processor.initialiseCalls.get(), "initialised more than once");
        assertEquals(0, processor.framesBeforeInitialised.get(), "a frame was processed before initialisation finished");
        assertEquals(threads, processor.framesProcessed.get());
    }

    @Test
    void framesProcessedConcurrentlyAreAllSeenByEveryProcessor() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        EffectChain chain = new EffectChain(
                List.of(new RecordingProcessor("A", log), new RecordingProcessor("B", log)), MEDIA);
        chain.apply(frame(), new InfoFrame(0, 0)); // Initialise first, so only the processing is concurrent.
        log.clear();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int n = 1; n <= 100; n++) {
                int frameNumber = n;
                futures.add(pool.submit(() -> {
                    chain.apply(frame(), new InfoFrame(frameNumber, frameNumber * 41_667L));
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(200, log.size());
        for (int n = 1; n <= 100; n++) {
            assertEquals(true, log.contains("A frame " + n) && log.contains("B frame " + n), "frame " + n);
        }
    }
}
