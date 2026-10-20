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

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;

/**
 * Helpers for exercising frame processors on small synthetic frames, without needing a real video file. Kept free of
 * any test-framework code so that the same logic can be reused by different tests.
 */
final class FrameTestSupport {
    static final int WIDTH = 160;
    static final int HEIGHT = 96;
    static final int CHANNELS = 3;
    // Long enough to cross two of FrameMottler's 72-frame damage-mask segment boundaries.
    static final int FRAME_COUNT = 160;
    static final int WORKER_THREADS = 8;

    private FrameTestSupport() {
    }

    /** A frame whose pixels are a pure function of frameNumber, so any run can recreate the identical input. */
    static Frame syntheticFrame(int frameNumber) {
        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, CHANNELS);
        byte[] bytes = new byte[frame.imageStride * frame.imageHeight];
        new Random(1000L + frameNumber).nextBytes(bytes);
        ByteBuffer buffer = ((ByteBuffer) frame.image[0]).duplicate();
        buffer.clear();
        buffer.put(bytes);
        return frame;
    }

    /** Describes the synthetic video the same way VideoPlayerModel describes a real one: from the frame itself. */
    static InfoVideo infoVideo() {
        Frame sample = syntheticFrame(0);
        return new InfoVideo(
                "test-movie.mp4",
                FRAME_COUNT,
                sample.imageWidth,
                sample.imageHeight,
                sample.imageDepth,
                sample.imageChannels,
                sample.imageStride,
                24.0,
                24,
                41_667,
                0);
    }

    static byte[] pixelsOf(Frame frame) {
        ByteBuffer buffer = ((ByteBuffer) frame.image[0]).duplicate();
        buffer.clear();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        // A frame's pixels live in native memory that is freed once the Frame can no longer be reached, and the JVM may
        // consider the frame unreachable as soon as it is no longer used, which is before the copy above has finished.
        // Other threads allocating frames can then free the memory and reuse it while it is still being read. The fence
        // keeps the frame alive until the copy is done.
        Reference.reachabilityFence(frame);
        return bytes;
    }

    /** Builds the input for frameNumber, runs the processor on it and returns the resulting pixels. */
    static byte[] processFrame(FrameProcessor processor, int frameNumber) throws Exception {
        Frame frame = syntheticFrame(frameNumber);
        processor.process(frame, new InfoFrame(frameNumber, frameNumber * 41_667L));
        return pixelsOf(frame);
    }

    /** The reference answer: every frame processed in order on this one thread. */
    static List<byte[]> processSequentially(FrameProcessor processor) throws Exception {
        List<byte[]> results = new ArrayList<>();
        for (int n = 0; n < FRAME_COUNT; n++) {
            results.add(processFrame(processor, n));
        }
        return results;
    }

    /** Every frame processed at the same time on a pool of threads, results returned in frame order. */
    static List<byte[]> processInParallel(FrameProcessor processor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int n = 0; n < FRAME_COUNT; n++) {
                final int frameNumber = n;
                futures.add(pool.submit(() -> processFrame(processor, frameNumber)));
            }
            List<byte[]> results = new ArrayList<>();
            for (Future<byte[]> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** How many frames differ between two runs. */
    static int countDifferentFrames(List<byte[]> expected, List<byte[]> actual) {
        int different = 0;
        for (int i = 0; i < expected.size(); i++) {
            if (!java.util.Arrays.equals(expected.get(i), actual.get(i))) {
                different++;
            }
        }
        return different;
    }

    /** How many frames the processor actually changed compared with the untouched input. */
    static int countModifiedFrames(List<byte[]> processed) {
        int modified = 0;
        for (int n = 0; n < processed.size(); n++) {
            if (!java.util.Arrays.equals(pixelsOf(syntheticFrame(n)), processed.get(n))) {
                modified++;
            }
        }
        return modified;
    }
}
