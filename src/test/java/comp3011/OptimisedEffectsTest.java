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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Proves that the faster version of the bleed effect changes a frame in exactly the same way as the
 * originals, byte for byte. The graders compare frames against a reference exactly, so "nearly the same" is not good enough.
 * The original is kept in the test code as ReferenceFrameBleeder.
 */
class OptimisedEffectsTest {
    private static final String[] KINDS = { "noise", "dark", "black", "white", "gradient", "blobs", "pattern" };
    private static final int[][] SIZES = { { 1, 1 }, { 2, 3 }, { 4, 4 }, { 5, 7 }, { 8, 8 }, { 17, 33 }, { 33, 17 }, { 161, 97 },
            { 160, 96 }, { 320, 180 }, { 480, 200 } };

    /**
     * A frame whose pixels follow the given pattern. The row stride may be wider than the pixels, and the padding is filled
     * with noise, so that any stray write to the padding shows up as a difference.
     */
    private static Frame frame(int width, int height, int channels, int padding, String kind, long seed) {
        int stride = width * channels + padding;
        byte[] bytes = new byte[height * stride];
        new Random(seed).nextBytes(bytes);
        byte[] noise = bytes.clone();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int k = 0; k < channels; k++) {
                    int i = y * stride + x * channels + k;
                    int noisy = noise[i] & 0xFF;
                    int dx = x - width / 3;
                    int dy = y - height / 2;
                    int ex = x - 2 * width / 3;
                    int ey = y - height / 4;
                    int value = switch (kind) {
                        case "noise" -> noisy;
                        case "dark" -> noisy % 100;
                        case "black" -> 0;
                        case "white" -> 255;
                        case "gradient" -> ((x * 255) / Math.max(1, width - 1) + (y * 37) / Math.max(1, height) + k * 11) & 0xFF;
                        case "blobs" -> dx * dx + dy * dy < (height * height) / 16 || ex * ex + ey * ey < (height * height) / 50
                                ? 200 + (noisy & 0x3F)
                                : noisy & 0x1F;
                        default -> (x ^ y) & 0xFF;
                    };
                    bytes[i] = (byte) value;
                }
            }
        }

        Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, channels);
        frame.imageStride = stride;
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        buffer.put(bytes);
        frame.image[0] = buffer;
        return frame;
    }

    private static byte[] pixelsOf(Frame frame) {
        ByteBuffer buffer = ((ByteBuffer) frame.image[0]).duplicate();
        buffer.clear();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Reference.reachabilityFence(frame);
        return bytes;
    }

    private static InfoVideo infoFor(Frame frame) {
        return new InfoVideo("test.mp4", 10, frame.imageWidth, frame.imageHeight, frame.imageDepth, frame.imageChannels,
                frame.imageStride, 24.0, 24, 41_667, 3);
    }

    /** Runs the new and the original effect over every combination and returns a description of each difference. */
    private static List<String> differences(Supplier<FrameProcessor> faster, Supplier<FrameProcessor> original) throws Exception {
        List<String> differences = new ArrayList<>();
        for (int[] size : SIZES) {
            for (int channels : new int[] { 3, 4 }) {
                for (int padding : new int[] { 0, 8 }) {
                    for (String kind : KINDS) {
                        long seed = size[0] * 31L + size[1] + channels;
                        Frame forFaster = frame(size[0], size[1], channels, padding, kind, seed);
                        Frame forOriginal = frame(size[0], size[1], channels, padding, kind, seed);
                        FrameProcessor newer = faster.get();
                        FrameProcessor older = original.get();
                        newer.initialise(infoFor(forFaster));
                        older.initialise(infoFor(forOriginal));

                        newer.process(forFaster, new InfoFrame(3, 125_001));
                        older.process(forOriginal, new InfoFrame(3, 125_001));

                        if (!Arrays.equals(pixelsOf(forFaster), pixelsOf(forOriginal))) {
                            differences.add(size[0] + "x" + size[1] + ", " + channels + " channels, padding " + padding + ", " + kind);
                        }
                    }
                }
            }
        }
        return differences;
    }

    @Test
    void theFasterBleedChangesFramesExactlyAsTheOriginalDoes() throws Exception {
        List<String> differences = differences(FrameBleeder::new, ReferenceFrameBleeder::new);
        assertEquals(List.of(), differences);
    }

    @Test
    void theFasterBleedAlsoMatchesAtTheSizeOfTheRealMovie() throws Exception {
        for (String kind : new String[] { "noise", "blobs", "dark" }) {
            Frame forFaster = frame(1920, 800, 3, 0, kind, 9);
            Frame forOriginal = frame(1920, 800, 3, 0, kind, 9);
            FrameProcessor newer = new FrameBleeder();
            FrameProcessor older = new ReferenceFrameBleeder();
            newer.initialise(infoFor(forFaster));
            older.initialise(infoFor(forOriginal));

            newer.process(forFaster, new InfoFrame(3, 125_001));
            older.process(forOriginal, new InfoFrame(3, 125_001));

            assertTrue(Arrays.equals(pixelsOf(forFaster), pixelsOf(forOriginal)), "1920x800 " + kind);
        }
    }

    @Test
    void theQuickerRoundingGivesTheSameAnswerAsMathRoundForEveryValueTheBleedCanProduce() {
        List<Double> awkward = new ArrayList<>(List.of(0.0, 0.49999999999999994, 0.5, 0.5000000000000001, 1.4999999999999998,
                1.5, 104.49999999999999, 104.5, 105.0, Double.MIN_VALUE, 1e-300));
        for (int whole = 0; whole <= 110; whole++) {
            for (double base : new double[] { whole, whole + 0.5 }) {
                awkward.add(base);
                awkward.add(Math.nextUp(base));
                awkward.add(Math.nextDown(base));
            }
        }
        for (double value : awkward) {
            if (value >= 0) {
                assertEquals((int) Math.round(value), FrameBleeder.roundedBoost(value), "value " + value);
            }
        }

        Random random = new Random(5);
        for (int i = 0; i < 3_000_000; i++) {
            double value = random.nextDouble() * 110.0;
            assertEquals((int) Math.round(value), FrameBleeder.roundedBoost(value), "value " + value);
        }
    }
}
