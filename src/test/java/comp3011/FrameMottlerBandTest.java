/*
 * COMP3011 Assignment 2 video player.
 * Regression tests for the four-row FrameMottler pixel-processing fast path.
 * Reference SHA-256 values were generated from the original per-pixel ByteBuffer implementation.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameMottlerBandTest {
    private static final int REFERENCE_SEED = 0x42E3103;
    private static final int[] NUMBERS = {0, 1, 71, 72, 143, 144};

    // Original FrameMottler results, including row padding and untouched alpha bytes.
    private static final String[] RGB_RESULTS = {
        "2a20b66cda1932fc7b0fcd5b32f535ffcb7b151efbda0c8fb129cd15ca3a58da",
        "3c6fa6684a89845462ad090c6c942f16f8228dd4e38a357f85d41d77b3bf4489",
        "fedc6a46a307aeb1e814249bd6dcc8c5d68aaf738448fb37fef219a356bd6881",
        "f7ba7ab9ac8501b0e9056a7c83ccd88ac2ac657086707e617c8803b0cb5d2931",
        "46f0aa38e6b686e5ac19532dce58ea57f26dd088379c2469d87090bd4c4d38d0",
        "19393acedd57f6d6b1409fdda01a3360389bd307683b0a79a8a1d9c04acd0773"
    };
    private static final String[] PADDED_BGRA_RESULTS = {
        "6177dd0f54b9ff4341f600d9c2d353ed1afb7d6309400c50e121de234b6c3ef8",
        "153a96d7d041dc69397388c9cf39599926c7c6eaad76867ab3542e5b55f646ad",
        "186baaae9810180f1456e27667d63fce5a55470db301de625ae724def704bf81",
        "6118b97c968ba2fe8c7fc3f9befab1f2722d71219838f31e7d0d0fa401be799b",
        "84f2eb0d5e92770123cd6fda50ef25633bc7604d017e190ea490369b171ccc03",
        "603ac95cdce97c1862a1fb84b58ad9d267a5b7bab4394a45ebeb6fedd2c21d81"
    };
    private static final String[] FULL_HD_RESULTS = {
        "6a7517ded9dd25e9bafa61a5d5b9a0f523b9462af3296266b55345524dcd1039",
        "0c5e75f7a249575424136879af518cd7c7a7b6bc73ece06f412d776ee4fc5f87"
    };

    private static Frame frame(int width, int height, int channels, int stride, int number, boolean direct) {
        Frame result = new Frame(width, height, Frame.DEPTH_UBYTE, channels);
        result.imageStride = stride;
        byte[] bytes = new byte[stride * height];
        new Random(1000L + number).nextBytes(bytes);
        ByteBuffer pixels = direct ? ByteBuffer.allocateDirect(bytes.length) : ByteBuffer.allocate(bytes.length);
        pixels.put(bytes);
        pixels.clear();
        result.image[0] = pixels;
        return result;
    }

    private static FrameMottler processor(Frame sample) throws Exception {
        FrameMottler result = new FrameMottler();
        result.initialise(new InfoVideo("movie.mp4", 400,
                sample.imageWidth, sample.imageHeight, sample.imageDepth,
                sample.imageChannels, sample.imageStride, 24.0, 24, 41_667, 0));
        // FrameMottler's instance numbering is deliberately global; pin only the test's
        // media seed so reference results do not depend on JUnit test execution order.
        Field seed = FrameMottler.class.getDeclaredField("mediaSeed");
        seed.setAccessible(true);
        seed.setInt(result, REFERENCE_SEED);
        return result;
    }

    private static void check(FrameMottler effect, int width, int height, int channels,
            int stride, int number, boolean direct, String expected) throws Exception {
        Frame input = frame(width, height, channels, stride, number, direct);
        effect.process(input, new InfoFrame(number, number * 41_667L));
        ByteBuffer pixels = ((ByteBuffer) input.image[0]).duplicate();
        pixels.clear();
        byte[] bytes = new byte[pixels.remaining()];
        pixels.get(bytes);
        Reference.reachabilityFence(input);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(expected, actual, "frame " + number + " at " + width + "x" + height
                + " / " + channels + " channels / " + (direct ? "direct" : "heap"));
    }

    @Test
    void preservesOriginalPixelsAcrossDamageTransitionsAndPaddedRows() throws Exception {
        for (boolean direct : new boolean[]{true, false}) {
            FrameMottler rgb = processor(frame(160, 96, 3, 480, 0, direct));
            FrameMottler padded = processor(frame(31, 17, 4, 128, 0, direct));
            for (int i = 0; i < NUMBERS.length; i++) {
                check(rgb, 160, 96, 3, 480, NUMBERS[i], direct, RGB_RESULTS[i]);
                check(padded, 31, 17, 4, 128, NUMBERS[i], direct, PADDED_BGRA_RESULTS[i]);
            }
        }
    }

    @Test
    void matchesOriginalFullResolutionOutput() throws Exception {
        FrameMottler effect = processor(frame(1920, 800, 3, 5760, 0, true));
        check(effect, 1920, 800, 3, 5760, 0, true, FULL_HD_RESULTS[0]);
        check(effect, 1920, 800, 3, 5760, 72, true, FULL_HD_RESULTS[1]);
    }

    @Test
    void concurrentFramesDoNotOverwriteEachOthersPixels() throws Exception {
        FrameMottler effect = processor(frame(160, 96, 3, 480, 0, true));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int repeat = 0; repeat < 3; repeat++) {
                for (int i = 0; i < NUMBERS.length; i++) {
                    final int index = i;
                    jobs.add(executor.submit(() -> {
                        check(effect, 160, 96, 3, 480, NUMBERS[index], true, RGB_RESULTS[index]);
                        return null;
                    }));
                }
            }
            for (Future<?> job : jobs) {
                job.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
