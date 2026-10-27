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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests of what the pool of effect workers inside {@link FrameDecoder} must guarantee. */
class FrameDecoderWorkersTest {
    private static final long FRAME_US = 41_667;
    private static final long WAIT_MS = 10_000;

    private FrameDecoder decoder;
    private final List<CountDownLatch> gatesToOpenAtTheEnd = new ArrayList<>();

    @AfterEach
    void stopDecoder() throws InterruptedException {
        for (CountDownLatch gate : gatesToOpenAtTheEnd) {
            gate.countDown();
        }
        if (decoder != null) {
            decoder.shutdown();
            decoder.awaitTermination(WAIT_MS);
        }
    }

    /**
     * Numbered video frames, handed out in one reused Frame like the real grabber does. Either every pixel of frame n
     * holds n, or, if noisy, the pixels are the same pseudo-random noise as FrameTestSupport.syntheticFrame(n).
     */
    private static class NumberedSource implements MediaSource {
        final int totalFrames;
        final boolean noisy;
        final AtomicInteger grabCount = new AtomicInteger();
        final AtomicInteger seekCount = new AtomicInteger();
        private final Frame reused;
        private int position;
        private int last;

        NumberedSource(int totalFrames, boolean noisy) {
            this.totalFrames = totalFrames;
            this.noisy = noisy;
            this.reused = noisy
                    ? new Frame(FrameTestSupport.WIDTH, FrameTestSupport.HEIGHT, Frame.DEPTH_UBYTE, FrameTestSupport.CHANNELS)
                    : new Frame(8, 4, Frame.DEPTH_UBYTE, 3);
        }

        @Override
        public Frame grab() {
            grabCount.incrementAndGet();
            if (position >= totalFrames) {
                return null;
            }
            last = position++;
            ByteBuffer pixels = ((ByteBuffer) reused.image[0]).duplicate();
            pixels.clear();
            if (noisy) {
                byte[] bytes = new byte[pixels.remaining()];
                new Random(1000L + last).nextBytes(bytes);
                pixels.put(bytes);
            } else {
                while (pixels.hasRemaining()) {
                    pixels.put((byte) last);
                }
            }
            return reused;
        }

        @Override
        public long timestampUs() {
            return last * FRAME_US;
        }

        @Override
        public int frameNumber() {
            return last;
        }

        @Override
        public void seek(long timestampUs) {
            position = (int) (timestampUs / FRAME_US);
            seekCount.incrementAndGet();
        }

        @Override
        public void close() {
        }
    }

    private CountDownLatch newGate() {
        CountDownLatch gate = new CountDownLatch(1);
        gatesToOpenAtTheEnd.add(gate);
        return gate;
    }

    private DecodedItem awaitItem(int generation) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            DecodedItem item = decoder.poll();
            if (item == null) {
                Thread.sleep(1);
            } else if (item.generation() == generation) {
                return item;
            }
        }
        return fail("Timed out waiting for an item");
    }

    private List<DecodedItem> collectUntilTerminal(int generation) throws InterruptedException {
        List<DecodedItem> items = new ArrayList<>();
        while (true) {
            DecodedItem item = awaitItem(generation);
            items.add(item);
            if (item instanceof DecodedItem.EndOfStream || item instanceof DecodedItem.Failure) {
                return items;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void start(NumberedSource source, FrameEffectsStage effects, int capacity, int workers) {
        decoder = new FrameDecoder(source, effects, null, capacity, workers);
        decoder.start();
    }

    @Test
    void effectWorkersHaveMinimumPriority() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger wrongPriority = new AtomicInteger();
        start(new NumberedSource(24, false), (frame, info) -> {
            processed.incrementAndGet();
            if (Thread.currentThread().getPriority() != Thread.MIN_PRIORITY) {
                wrongPriority.incrementAndGet();
            }
        }, 12, 3);

        collectUntilTerminal(0);
        assertEquals(24, processed.get());
        assertEquals(0, wrongPriority.get(), "Effect work should run at minimum thread priority");
    }

    @Test
    void concurrentSeeksCannotApplyAnOlderGenerationAfterANewerOne() throws Exception {
        // The GUI normally makes seek requests on one thread, but FrameDecoder.seek() also promises callers that
        // seek requests from multiple threads are safe. Generation allocation and queue insertion must be ordered
        // together, otherwise the decoder can be directed backwards by a delayed caller.
        List<Long> appliedSeeks = Collections.synchronizedList(new ArrayList<>());
        NumberedSource source = new NumberedSource(200_000, false) {
            @Override
            public void seek(long timestampUs) {
                super.seek(timestampUs);
                appliedSeeks.add(timestampUs); // Executed by the decoder thread, not the seeking callers.
            }
        };
        start(source, (frame, info) -> { }, 8, 3);

        final int callers = 6;
        final int requestsPerCaller = 24;
        CountDownLatch startTogether = new CountDownLatch(1);
        Map<Long, Integer> requestedGenerations = new ConcurrentHashMap<>();
        ExecutorService seekingThreads = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                final int callerId = caller;
                tasks.add(seekingThreads.submit(() -> {
                    startTogether.await();
                    for (int request = 0; request < requestsPerCaller; request++) {
                        long targetUs = (2_000L + callerId * requestsPerCaller + request) * FRAME_US;
                        int generation = decoder.seek(targetUs);
                        requestedGenerations.put(targetUs, generation);
                    }
                    return null;
                }));
            }
            startTogether.countDown();
            for (Future<?> task : tasks) {
                task.get(WAIT_MS, TimeUnit.MILLISECONDS);
            }

            // Force an observable final seek, then check every seek that the decoder actually performed.
            long finalTargetUs = 190_000L * FRAME_US;
            int finalGeneration = decoder.seek(finalTargetUs);
            requestedGenerations.put(finalTargetUs, finalGeneration);
            assertEquals(190_000, assertInstanceOf(DecodedItem.Video.class,
                    awaitItem(finalGeneration)).frameNumber());

            int previousGeneration = -1;
            synchronized (appliedSeeks) {
                for (long timestampUs : appliedSeeks) {
                    Integer generation = requestedGenerations.get(timestampUs);
                    assertTrue(generation != null, "decoder applied an unknown seek " + timestampUs);
                    assertTrue(generation > previousGeneration,
                            "decoder applied generation " + generation + " after " + previousGeneration);
                    previousGeneration = generation;
                }
            }
            assertEquals(finalGeneration, previousGeneration, "latest seek was not applied last");
        } finally {
            seekingThreads.shutdownNow();
            assertTrue(seekingThreads.awaitTermination(WAIT_MS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void framesComeOutInDecodeOrderEvenWhenWorkersFinishOutOfOrder() throws Exception {
        // Every fourth frame is slow, so frames after it are finished first and have to wait their turn.
        start(new NumberedSource(60, false), (frame, info) -> sleep(info.frameNumber() % 4 == 0 ? 40 : 1), 16, 4);

        List<DecodedItem> items = collectUntilTerminal(0);

        assertEquals(61, items.size());
        for (int n = 0; n < 60; n++) {
            assertEquals(n, assertInstanceOf(DecodedItem.Video.class, items.get(n)).frameNumber());
        }
        assertInstanceOf(DecodedItem.EndOfStream.class, items.get(60));
    }

    @Test
    void workersApplyEffectsToDifferentFramesAtTheSameTime() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        int frames = 48;
        start(new NumberedSource(frames, false), (frame, info) -> {
            int now = running.incrementAndGet();
            mostAtOnce.accumulateAndGet(now, Math::max);
            sleep(20); // Sleeping needs no CPU, so this holds however many cores the test machine has.
            running.decrementAndGet();
        }, 32, 8);

        collectUntilTerminal(0);

        // How many frames were in the effects at once does not depend on how fast or how busy the machine is, unlike how
        // long the whole thing took, so that is what this checks.
        assertTrue(mostAtOnce.get() >= 4, "only " + mostAtOnce.get() + " frames were ever in the effects at once");
    }

    @Test
    void aSeekCancelsFramesQueuedBehindARunningOne() throws Exception {
        CountDownLatch gate = newGate();
        CountDownLatch firstFrameStarted = new CountDownLatch(1);
        List<Integer> applied = Collections.synchronizedList(new ArrayList<>());
        NumberedSource source = new NumberedSource(100_000, false);
        // One worker, held up on frame 0, so the frames the decoder keeps handing over queue up behind it.
        start(source, (frame, info) -> {
            if (info.frameNumber() == 0) {
                firstFrameStarted.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            applied.add(info.frameNumber());
        }, 8, 1);
        assertTrue(firstFrameStarted.await(WAIT_MS, TimeUnit.MILLISECONDS));
        // Wait for the decoder to get ahead and fill the queue, however long that takes on this machine.
        long ready = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (source.grabCount.get() < 9 && System.nanoTime() < ready) {
            Thread.sleep(5);
        }
        assertTrue(source.grabCount.get() >= 9, "the decoder never got ahead, so this test shows nothing");
        Thread.sleep(50); // Let it settle on the full queue.

        int generation = decoder.seek(50_000 * FRAME_US);

        // Keep the worker held up until the decoder has acted on the seek. It cancels any stale work still queued
        // before it moves the source, so once the source has moved, none of that work can run any more.
        long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (source.seekCount.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, source.seekCount.get(), "the decoder never acted on the seek");
        gate.countDown();

        assertEquals(50_000, ((DecodedItem.Video) awaitItem(generation)).frameNumber());
        long staleFramesProcessed = applied.stream().filter(n -> n > 0 && n < 50_000).count();
        // Without cancelling, around eight frames queued behind the held-up one would have had their effects applied.
        assertEquals(0, staleFramesProcessed, "stale frames still had the effects applied");
    }

    @Test
    void aFullQueueLimitsHowManyFramesAreInTheEffects() throws Exception {
        CountDownLatch gate = newGate();
        int capacity = 6;
        NumberedSource source = new NumberedSource(10_000, false);
        start(source, (frame, info) -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, capacity, 2);

        Thread.sleep(300);
        int grabsWhileHeldUp = source.grabCount.get();
        assertTrue(grabsWhileHeldUp <= capacity + 2, "decoded " + grabsWhileHeldUp + " frames into a queue of " + capacity);

        gate.countDown();
        for (int i = 0; i < 20; i++) {
            assertInstanceOf(DecodedItem.Video.class, awaitItem(0));
        }
        assertTrue(source.grabCount.get() > grabsWhileHeldUp);
    }

    @Test
    void aFailureOnAWorkerIsDeliveredInOrderAfterTheFramesBeforeIt() throws Exception {
        // Frame 5 fails at once, long before frames 0 to 4 have finished, and frames after it finish too.
        start(new NumberedSource(40, false), (frame, info) -> {
            if (info.frameNumber() == 5) {
                throw new IllegalStateException("effect failed on frame 5");
            }
            sleep(20);
        }, 16, 4);

        List<DecodedItem> items = collectUntilTerminal(0);

        assertEquals(6, items.size());
        for (int n = 0; n < 5; n++) {
            assertEquals(n, assertInstanceOf(DecodedItem.Video.class, items.get(n)).frameNumber());
        }
        assertEquals("effect failed on frame 5", assertInstanceOf(DecodedItem.Failure.class, items.get(5)).cause().getMessage());
    }

    @Test
    void whenTheDecoderThreadHasEndedNoWorkerIsStillRunningEffects() throws Exception {
        AtomicInteger running = new AtomicInteger();
        start(new NumberedSource(1_000, false), (frame, info) -> {
            running.incrementAndGet();
            sleep(150);
            running.decrementAndGet();
        }, 16, 4);
        long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (running.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(running.get() >= 2, "the workers never got going");

        decoder.shutdown();

        assertTrue(decoder.awaitTermination(WAIT_MS), "decoder thread did not stop");
        // This is what lets the player safely reuse the effect processors after waiting for the decoder.
        assertEquals(0, running.get(), "a worker was still applying effects after the decoder thread ended");
    }

    @Test
    void realEffectsOnManyWorkersGiveExactlyTheSameFramesAsOneAtATime() throws Exception {
        List<FrameProcessor> processors = new ArrayList<>();
        for (FrameProcessorOption option : FrameProcessorCatalogue.options()) {
            processors.add(option.create());
        }
        EffectChain.MediaProperties media =
                new EffectChain.MediaProperties("test-movie.mp4", FrameTestSupport.FRAME_COUNT, 24.0, 24, 41_667, 0);

        // The reference answer: the same processors, one frame at a time on this thread.
        EffectChain referenceChain = new EffectChain(processors, media);
        List<byte[]> expected = new ArrayList<>();
        for (int n = 0; n < FrameTestSupport.FRAME_COUNT; n++) {
            Frame frame = FrameTestSupport.syntheticFrame(n);
            referenceChain.apply(frame, new InfoFrame(n, n * FRAME_US));
            expected.add(FrameTestSupport.pixelsOf(frame));
        }

        start(new NumberedSource(FrameTestSupport.FRAME_COUNT, true), new EffectChain(processors, media), 24, 6);
        List<DecodedItem> items = collectUntilTerminal(0);

        assertEquals(FrameTestSupport.FRAME_COUNT + 1, items.size());
        for (int n = 0; n < FrameTestSupport.FRAME_COUNT; n++) {
            DecodedItem.Video video = assertInstanceOf(DecodedItem.Video.class, items.get(n));
            assertEquals(n, video.frameNumber());
            assertTrue(java.util.Arrays.equals(expected.get(n), FrameTestSupport.pixelsOf(video.frame())),
                    "frame " + n + " differs from the one-at-a-time result");
        }
    }
}
