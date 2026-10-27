/*
 * COMP3011 Assignment 2 video player.
 * Authors: Simon Ratcliffe (starter code); Kali Atkinson (project modifications).
 */
/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FusedColourRunTest {
    private static final EffectChain.MediaProperties MEDIA =
            new EffectChain.MediaProperties("movie.mp4", 100, 24, 24, 41667, 0);

    private static List<FrameProcessor> build(String sequence) {
        List<FrameProcessor> result = new ArrayList<>();
        for (char kind : sequence.toCharArray()) {
            result.add(switch (kind) {
                case 'w' -> new FrameBlackAndWhiter();
                case 'y' -> new FrameYellower();
                case 'v' -> new FrameVignetter();
                default -> throw new IllegalArgumentException("Unknown effect: " + kind);
            });
        }
        return result;
    }

    private static Frame frame(int width, int height, int channels, long seed) {
        Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, channels);
        byte[] image = new byte[frame.imageStride * height];
        new Random(seed).nextBytes(image);
        ByteBuffer buffer = ((ByteBuffer) frame.image[0]).duplicate();
        buffer.clear();
        buffer.put(image);
        return frame;
    }

    private static byte[] pixels(Frame frame) {
        ByteBuffer buffer = ((ByteBuffer) frame.image[0]).duplicate();
        buffer.clear();
        byte[] image = new byte[buffer.remaining()];
        buffer.get(image);
        java.lang.ref.Reference.reachabilityFence(frame);
        return image;
    }

    private static void compare(String sequence, int width, int height, int channels, int frameNumber)
            throws Exception {
        Frame original = frame(width, height, channels, frameNumber * 71L + 9L);
        Frame fused = frame(width, height, channels, frameNumber * 71L + 9L);
        List<FrameProcessor> reference = build(sequence);
        InfoVideo video = new InfoVideo("movie.mp4", 100, width, height, original.imageDepth,
                channels, original.imageStride, 24, 24, 41667, 0);
        for (FrameProcessor processor : reference) {
            processor.initialise(video);
        }
        InfoFrame info = new InfoFrame(frameNumber, frameNumber * 41667L);
        for (FrameProcessor processor : reference) {
            processor.process(original, info);
        }
        new EffectChain(build(sequence), MEDIA).apply(fused, info);
        assertArrayEquals(pixels(original), pixels(fused),
                sequence + " " + width + "x" + height + " with " + channels + " channels");
    }

    @Test
    void fusedRunsMatchOriginalProcessorsExactly() throws Exception {
        String[] sequences = { "wy", "yw", "wv", "vw", "yv", "vy", "wyv", "vyw", "vywv",
                "ww", "yy", "vv", "wyvvywwvyy", "ywvywyv" };
        int[][] layouts = { {1, 1, 3}, {2, 5, 4}, {17, 11, 3}, {53, 39, 4}, {160, 96, 3} };
        for (String sequence : sequences) {
            for (int[] layout : layouts) {
                compare(sequence, layout[0], layout[1], layout[2], 23);
            }
        }
    }

    @Test
    void concurrentFramesShareOneChainWithoutBufferCollisions() throws Exception {
        String sequence = "wyvvyw";
        EffectChain shared = new EffectChain(build(sequence), MEDIA);
        Frame first = frame(160, 96, 4, 9);
        shared.apply(first, new InfoFrame(0, 0)); // Initialise the shared processor chain.

        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> checks = new ArrayList<>();
            for (int n = 1; n <= 24; n++) {
                int frameNumber = n;
                checks.add(executor.submit(() -> {
                    Frame reference = frame(160, 96, 4, frameNumber * 71L + 9L);
                    Frame concurrent = frame(160, 96, 4, frameNumber * 71L + 9L);
                    List<FrameProcessor> operations = build(sequence);
                    InfoVideo video = new InfoVideo("movie.mp4", 100, 160, 96, reference.imageDepth,
                            4, reference.imageStride, 24, 24, 41667, 0);
                    InfoFrame info = new InfoFrame(frameNumber, frameNumber * 41667L);
                    for (FrameProcessor processor : operations) {
                        processor.initialise(video);
                        processor.process(reference, info);
                    }
                    shared.apply(concurrent, info);
                    assertArrayEquals(pixels(reference), pixels(concurrent));
                    return null;
                }));
            }
            for (Future<?> check : checks) {
                check.get();
            }
            assertEquals(24, checks.size());
        } finally {
            executor.shutdownNow();
        }
    }
}
