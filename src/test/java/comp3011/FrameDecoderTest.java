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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FrameDecoderTest {
    private static final long FRAME_US = 41_667;
    private static final long WAIT_MS = 5_000;

    private FrameDecoder decoder;

    @AfterEach
    void stopDecoder() throws InterruptedException {
        if (decoder != null) {
            decoder.shutdown();
            decoder.awaitTermination(WAIT_MS);
        }
    }

    /**
     * A source of tiny synthetic video. Like the real FFmpeg grabber it hands back the <i>same</i> Frame object every
     * time, overwriting its pixels, so a decoder that forgets to copy frames will visibly corrupt them. Every pixel
     * of frame n holds the value n.
     */
    private static class FakeMediaSource implements MediaSource {
        final int totalFrames;
        final AtomicInteger grabCount = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();
        final boolean withAudio;
        volatile int failAtPosition = -1;
        volatile boolean failWithError;
        private int position; // Index of the next frame to grab.
        private int lastFrameNumber;
        private long lastTimestampUs;
        private boolean nextIsAudio;
        private final Frame reusedVideoFrame = new Frame(8, 4, Frame.DEPTH_UBYTE, 3);
        private final Frame reusedAudioFrame = new Frame();

        FakeMediaSource(int totalFrames, boolean withAudio) {
            this.totalFrames = totalFrames;
            this.withAudio = withAudio;
            nextIsAudio = withAudio;
        }

        @Override
        public Frame grab() throws Exception {
            grabCount.incrementAndGet();
            if (position == failAtPosition) {
                if (failWithError) {
                    throw new OutOfMemoryError("simulated memory exhaustion");
                }
                throw new IllegalStateException("simulated decode failure");
            }
            if (position >= totalFrames) {
                return null;
            }
            lastFrameNumber = position;
            lastTimestampUs = position * FRAME_US;

            if (withAudio && nextIsAudio) {
                nextIsAudio = false;
                reusedAudioFrame.image = null; // Audio only: no image.
                reusedAudioFrame.samples = new java.nio.Buffer[] { ShortBuffer.wrap(new short[] { (short) position, 1 }) };
                return reusedAudioFrame;
            }
            nextIsAudio = withAudio;
            position++;

            ByteBuffer pixels = ((ByteBuffer) reusedVideoFrame.image[0]).duplicate();
            pixels.clear();
            while (pixels.hasRemaining()) {
                pixels.put((byte) lastFrameNumber);
            }
            return reusedVideoFrame;
        }

        @Override
        public long timestampUs() {
            return lastTimestampUs;
        }

        @Override
        public int frameNumber() {
            return lastFrameNumber;
        }

        @Override
        public void seek(long timestampUs) {
            position = (int) (timestampUs / FRAME_US);
            nextIsAudio = withAudio;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final FrameEffectsStage NO_EFFECTS = (frame, info) -> {
    };

    private FakeMediaSource startDecoder(int totalFrames, int capacity) {
        FakeMediaSource source = new FakeMediaSource(totalFrames, false);
        decoder = new FrameDecoder(source, NO_EFFECTS, null, capacity);
        decoder.start();
        return source;
    }

    /** Polls until an item of the wanted generation satisfying the test arrives, ignoring other generations. */
    private DecodedItem awaitItem(int generation, Predicate<DecodedItem> wanted) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            DecodedItem item = decoder.poll();
            if (item == null) {
                Thread.sleep(1);
            } else if (item.generation() == generation && wanted.test(item)) {
                return item;
            }
        }
        return fail("Timed out waiting for an item");
    }

    private DecodedItem.Video nextVideo(int generation) throws InterruptedException {
        return (DecodedItem.Video) awaitItem(generation, item -> item instanceof DecodedItem.Video);
    }

    /** Collects every item of the generation, in order, up to and including the first end-of-stream or failure. */
    private List<DecodedItem> collectUntilTerminal(int generation) throws InterruptedException {
        List<DecodedItem> items = new ArrayList<>();
        while (true) {
            DecodedItem item = awaitItem(generation, i -> true);
            items.add(item);
            if (item instanceof DecodedItem.EndOfStream || item instanceof DecodedItem.Failure) {
                return items;
            }
        }
    }

    @Test
    void deliversEveryFrameInOrderThenEndOfStream() throws Exception {
        startDecoder(20, 64);

        List<DecodedItem> items = collectUntilTerminal(0);

        assertEquals(21, items.size());
        for (int n = 0; n < 20; n++) {
            DecodedItem.Video video = assertInstanceOf(DecodedItem.Video.class, items.get(n));
            assertEquals(n, video.frameNumber());
            assertEquals(n * FRAME_US, video.timestampUs());
        }
        assertInstanceOf(DecodedItem.EndOfStream.class, items.get(20));
    }

    @Test
    void queuedFramesAreIndependentCopiesNotTheSourcesReusedFrame() throws Exception {
        startDecoder(20, 64);

        List<DecodedItem> items = collectUntilTerminal(0);

        for (int n = 0; n < 20; n++) {
            DecodedItem.Video video = (DecodedItem.Video) items.get(n);
            byte[] pixels = FrameTestSupport.pixelsOf(video.frame());
            assertEquals((byte) n, pixels[0], "frame " + n + " was overwritten after it was queued");
            assertEquals((byte) n, pixels[pixels.length - 1], "frame " + n + " was overwritten after it was queued");
        }
    }

    @Test
    void effectsRunOncePerFrameOnAWorkerThreadNotOnTheCallersThread() throws Exception {
        FakeMediaSource source = new FakeMediaSource(10, false);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger applied = new AtomicInteger();
        decoder = new FrameDecoder(source, (frame, info) -> {
            threads.add(Thread.currentThread().getName());
            applied.incrementAndGet();
        }, null, 64);
        decoder.start();

        collectUntilTerminal(0);

        assertEquals(10, applied.get());
        assertTrue(threads.stream().allMatch(name -> name.startsWith("frame-worker-")), "ran on " + threads);
        assertFalse(threads.contains(Thread.currentThread().getName()));
    }

    @Test
    void changesMadeByTheEffectsAreVisibleInTheQueuedFrame() throws Exception {
        FakeMediaSource source = new FakeMediaSource(3, false);
        decoder = new FrameDecoder(source, (frame, info) -> {
            ((ByteBuffer) frame.image[0]).put(0, (byte) 99);
        }, null, 8);
        decoder.start();

        DecodedItem.Video video = nextVideo(0);

        assertEquals((byte) 99, FrameTestSupport.pixelsOf(video.frame())[0]);
    }

    @Test
    void aFullQueueMakesTheDecoderWaitInsteadOfRunningAhead() throws Exception {
        int capacity = 4;
        FakeMediaSource source = startDecoder(1_000, capacity);

        Thread.sleep(300); // Nobody is consuming, so the decoder should have stalled.
        int grabsWhileStalled = source.grabCount.get();
        assertTrue(grabsWhileStalled <= capacity + 2, "decoded " + grabsWhileStalled + " frames into a queue of " + capacity);

        for (int i = 0; i < 20; i++) {
            nextVideo(0); // Consuming lets it carry on.
        }
        assertTrue(source.grabCount.get() > grabsWhileStalled);
    }

    @Test
    void seekDiscardsStaleFramesAndRestartsFromTheNewPosition() throws Exception {
        startDecoder(1_000, 4);
        nextVideo(0);
        nextVideo(0);

        int generation = decoder.seek(500 * FRAME_US);

        assertEquals(1, generation);
        DecodedItem.Video first = nextVideo(generation);
        assertEquals(500, first.frameNumber());
        assertEquals(501, nextVideo(generation).frameNumber());
        assertEquals(502, nextVideo(generation).frameNumber());
    }

    @Test
    void onlyTheLatestOfSeveralQuickSeeksIsApplied() throws Exception {
        startDecoder(1_000, 4);

        decoder.seek(100 * FRAME_US);
        decoder.seek(200 * FRAME_US);
        int latest = decoder.seek(300 * FRAME_US);

        assertEquals(3, latest);
        assertEquals(300, nextVideo(latest).frameNumber());
    }

    @Test
    void theFirstFrameAfterASeekIsNeverLostWhateverTheTimingOfTheDecoder() throws Exception {
        // Regression test. Discarding the queue after sending the request let the decoder queue a frame for the new
        // position in between, which the discard then threw away. The window is tiny (it showed up about once in every
        // few hundred seeks), so this tries a great many. A failure is always real, but a pass is not a proof.
        startDecoder(100_000, 4);

        for (int i = 1; i <= 1_500; i++) {
            long target = 50L * i; // Always inside the 100,000 frames available.
            int generation = decoder.seek(target * FRAME_US);
            assertEquals(target, nextVideo(generation).frameNumber(), "seek " + i + " lost its first frame");
        }
    }

    @Test
    void aSeekWakesADecoderThatIsWaitingForRoomInAFullQueueAtOnce() throws Exception {
        // The decoder is told to look for requests only every five seconds, so if it is not woken by the seek itself,
        // the first frame of the new position takes about five seconds to arrive instead of a few milliseconds.
        FakeMediaSource source = new FakeMediaSource(100_000, false);
        decoder = new FrameDecoder(source, NO_EFFECTS, null, 2, 1, 5_000);
        decoder.start();
        Thread.sleep(300); // The decoder is now blocked, waiting for room.

        long began = System.nanoTime();
        int generation = decoder.seek(5_000 * FRAME_US);
        assertEquals(5_000, nextVideo(generation).frameNumber());
        long elapsedMs = (System.nanoTime() - began) / 1_000_000L;

        assertTrue(elapsedMs < 1_000, "the seek took " + elapsedMs + "ms to reach a blocked decoder");
    }

    @Test
    void aSeekIsNoticedEvenWhileTheDecoderIsBlockedOnAFullQueue() throws Exception {
        FakeMediaSource source = startDecoder(1_000, 2);
        Thread.sleep(200); // Decoder is now stalled with a full queue.
        int grabsBefore = source.grabCount.get();

        int generation = decoder.seek(700 * FRAME_US);

        assertEquals(700, nextVideo(generation).frameNumber());
        assertTrue(source.grabCount.get() >= grabsBefore);
    }

    @Test
    void endOfStreamIsReportedOnceThenTheDecoderWaitsForASeek() throws Exception {
        FakeMediaSource source = startDecoder(5, 64);
        collectUntilTerminal(0);
        Thread.sleep(150);
        assertNull(decoder.poll(), "nothing further should be produced after the end of the media");
        int grabsAtEnd = source.grabCount.get();
        Thread.sleep(100);
        assertEquals(grabsAtEnd, source.grabCount.get(), "the decoder kept grabbing after the end");

        int generation = decoder.seek(2 * FRAME_US);
        List<DecodedItem> items = collectUntilTerminal(generation);

        assertEquals(4, items.size()); // Frames 2, 3, 4 and the end of the stream.
        assertEquals(2, ((DecodedItem.Video) items.get(0)).frameNumber());
        assertInstanceOf(DecodedItem.EndOfStream.class, items.get(3));
    }

    @Test
    void aDecodeFailureIsReportedAndTheDecoderStaysIdle() throws Exception {
        FakeMediaSource source = new FakeMediaSource(100, false);
        source.failAtPosition = 3;
        decoder = new FrameDecoder(source, NO_EFFECTS, null, 64);
        decoder.start();

        List<DecodedItem> items = collectUntilTerminal(0);

        assertEquals(4, items.size()); // Frames 0, 1, 2 and then the failure.
        DecodedItem.Failure failure = assertInstanceOf(DecodedItem.Failure.class, items.get(3));
        assertEquals("simulated decode failure", failure.cause().getMessage());
        Thread.sleep(100);
        assertNull(decoder.poll());
    }

    @Test
    void anErrorThatWouldEndTheDecoderThreadIsReportedAsAFailureInstead() throws Exception {
        FakeMediaSource source = new FakeMediaSource(100, false);
        source.failAtPosition = 3;
        source.failWithError = true;
        decoder = new FrameDecoder(source, NO_EFFECTS, null, 64);
        decoder.start();

        List<DecodedItem> items = collectUntilTerminal(0);

        assertEquals(4, items.size()); // Frames 0, 1, 2 and then the failure.
        DecodedItem.Failure failure = assertInstanceOf(DecodedItem.Failure.class, items.get(3));
        assertTrue(failure.cause().getMessage().contains("simulated memory exhaustion"), failure.cause().getMessage());
        Thread.sleep(100);
        assertNull(decoder.poll());
    }

    @Test
    void aFailureInTheEffectsIsReportedAsAFailure() throws Exception {
        FakeMediaSource source = new FakeMediaSource(10, false);
        decoder = new FrameDecoder(source, (frame, info) -> {
            if (info.frameNumber() == 2) {
                throw new IllegalArgumentException("effect blew up");
            }
        }, null, 64);
        decoder.start();

        List<DecodedItem> items = collectUntilTerminal(0);

        assertEquals(3, items.size());
        assertEquals("effect blew up", assertInstanceOf(DecodedItem.Failure.class, items.get(2)).cause().getMessage());
    }

    @Test
    void audioChunksAreDeliveredWhenASamplerIsGiven() throws Exception {
        FakeMediaSource source = new FakeMediaSource(3, true);
        decoder = new FrameDecoder(source, NO_EFFECTS, frame -> new byte[] { 7, 7 }, 64);
        decoder.start();

        List<DecodedItem> items = collectUntilTerminal(0);

        long audioChunks = items.stream().filter(i -> i instanceof DecodedItem.Audio).count();
        long videoFrames = items.stream().filter(i -> i instanceof DecodedItem.Video).count();
        assertEquals(3, videoFrames);
        assertEquals(3, audioChunks);
        DecodedItem.Audio audio = (DecodedItem.Audio) items.get(0);
        assertEquals(0, audio.timestampUs());
        assertEquals(2, audio.samples().length);
    }

    @Test
    void noAudioIsDeliveredWithoutASampler() throws Exception {
        FakeMediaSource source = new FakeMediaSource(3, true);
        decoder = new FrameDecoder(source, NO_EFFECTS, null, 64);
        decoder.start();

        List<DecodedItem> items = collectUntilTerminal(0);

        assertTrue(items.stream().noneMatch(i -> i instanceof DecodedItem.Audio));
        assertEquals(4, items.size());
    }

    @Test
    void shutdownStopsTheThreadAndClosesTheSourceExactlyOnce() throws Exception {
        FakeMediaSource source = startDecoder(1_000, 4);
        nextVideo(0);

        decoder.shutdown();

        assertTrue(decoder.awaitTermination(WAIT_MS), "decoder thread did not stop");
        assertEquals(1, source.closeCount.get());
    }

    @Test
    void shutdownWorksWhileTheDecoderIsBlockedOnAFullQueue() throws Exception {
        FakeMediaSource source = startDecoder(1_000, 1);
        Thread.sleep(200);

        decoder.shutdown();

        assertTrue(decoder.awaitTermination(WAIT_MS));
        assertEquals(1, source.closeCount.get());
    }

    @Test
    void shutdownWorksWhileTheDecoderIsIdleAtTheEndOfTheMedia() throws Exception {
        FakeMediaSource source = startDecoder(2, 64);
        collectUntilTerminal(0);

        decoder.shutdown();

        assertTrue(decoder.awaitTermination(WAIT_MS));
        assertEquals(1, source.closeCount.get());
    }

    @Test
    void nothingIsDeliveredAfterShutdownIsRequestedAndDrained() throws Exception {
        startDecoder(1_000, 4);
        decoder.shutdown();
        assertTrue(decoder.awaitTermination(WAIT_MS));
        while (decoder.poll() != null) {
            // Drain whatever was already queued.
        }
        assertFalse(decoder.awaitTermination(0) && decoder.poll() != null);
    }
}
