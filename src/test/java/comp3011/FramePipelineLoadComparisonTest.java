/*
 * COMP3011 Assignment 2 — synthetic pipeline load comparison.
 * Original player architecture supplied by Simon Ratcliffe.
 * Test and comparison harness added for this project by Kali Atkinson.
 *
 * This test deliberately makes no absolute FPS or relative speed assertion:
 * CPU scheduling and CI machine load vary, but byte-identical output must not.
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A repeatable cooperative-versus-parallel effects throughput comparison, run by {@code mvn verify}.
 *
 * <p>The cooperative case grabs and processes every frame on the calling thread,
 * using the same input and effect chain as the concurrent decoder. The other two
 * cases run a {@link FrameDecoder} with one worker and with four workers.
 * None of these cases launch the supplied original single-threaded JavaFX player;
 * the full original-player GUI comparison requires the starter project and a graphical benchmark.</p>
 *
 * <p>The frames must be bit-for-bit identical and in decode order. Timings are
 * printed for manual comparison, without flaky CI performance thresholds.</p>
 */
class FramePipelineLoadComparisonTest {
    private static final int FRAMES = 96;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final long FRAME_DURATION_US = 41_667L;
    private static final long TIMEOUT_MS = 45_000L;

    private record Measurement(long elapsedNs, List<byte[]> frames) {
    }

    /** Emulates a decoder that reuses its image buffer between consecutive grabs. */
    private static final class SyntheticVideoSource implements MediaSource {
        private final Frame reused = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
        private int position;
        private int lastFrame;

        @Override
        public Frame grab() {
            if (position == FRAMES) {
                return null;
            }
            lastFrame = position++;
            ByteBuffer target = ((ByteBuffer) reused.image[0]).duplicate();
            target.clear();
            byte[] pixels = new byte[target.remaining()];
            // Identical deterministic input for both runs; varied brightness over time.
            Arrays.fill(pixels, (byte) (lastFrame * 37 + 17));
            target.put(pixels);
            return reused;
        }

        @Override
        public long timestampUs() {
            return lastFrame * FRAME_DURATION_US;
        }

        @Override
        public int frameNumber() {
            return lastFrame;
        }

        @Override
        public void seek(long timestampUs) {
            position = (int) Math.max(0, Math.min(FRAMES, timestampUs / FRAME_DURATION_US));
        }

        @Override
        public void close() {
            // Synthetic in-memory source; no external resources to close.
        }
    }

    /** Two identical W/Y/V passes to exercise the actual chained/fused effect path. */
    private static EffectChain effects() {
        return new EffectChain(
                List.of(new FrameBlackAndWhiter(), new FrameYellower(), new FrameVignetter(),
                        new FrameBlackAndWhiter(), new FrameYellower(), new FrameVignetter()),
                new EffectChain.MediaProperties("synthetic-load.mp4", FRAMES, 24.0, 24,
                        (int) FRAME_DURATION_US, 0));
    }

    private static byte[] snapshot(Frame frame) {
        ByteBuffer source = ((ByteBuffer) frame.image[0]).duplicate();
        source.clear();
        byte[] pixels = new byte[source.remaining()];
        source.get(pixels);
        Reference.reachabilityFence(frame);
        return pixels;
    }

    /** Sequential cooperative baseline: grab and apply all effects on one thread, as in the starter's model. */
    private static Measurement runCooperatively() throws Exception {
        SyntheticVideoSource source = new SyntheticVideoSource();
        EffectChain chain = effects();
        List<byte[]> collected = new ArrayList<>();
        long start = System.nanoTime();
        try {
            Frame frame;
            while ((frame = source.grab()) != null) {
                int number = source.frameNumber();
                long timestampUs = source.timestampUs();
                assertEquals(collected.size(), number, "cooperative baseline must retain decode order");
                assertEquals(number * FRAME_DURATION_US, timestampUs);
                // No worker or frame clone is necessary; this thread finishes before grabbing again.
                chain.apply(frame, new InfoFrame(number, timestampUs));
                collected.add(snapshot(frame));
            }
            assertEquals(FRAMES, collected.size());
            return new Measurement(System.nanoTime() - start, collected);
        } finally {
            source.close();
        }
    }

    private static Measurement run(int workerCount) throws Exception {
        FrameDecoder decoder = new FrameDecoder(new SyntheticVideoSource(), effects(), null, 24, workerCount);
        List<byte[]> collected = new ArrayList<>();
        long start = System.nanoTime();
        decoder.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
            boolean finished = false;
            while (System.nanoTime() < deadline) {
                DecodedItem item = decoder.poll();
                if (item == null) {
                    Thread.sleep(1);
                    continue;
                }
                if (item instanceof DecodedItem.Video video) {
                    assertEquals(collected.size(), video.frameNumber(), "frames must remain in decode order");
                    assertEquals(collected.size() * FRAME_DURATION_US, video.timestampUs());
                    collected.add(snapshot(video.frame()));
                } else if (item instanceof DecodedItem.Failure failure) {
                    fail("Effect processing failed", failure.cause());
                } else if (item instanceof DecodedItem.EndOfStream end) {
                    assertEquals(0, end.generation());
                    finished = true;
                    break;
                }
            }
            assertTrue(finished, "timed out before receiving end-of-stream");
            assertEquals(FRAMES, collected.size());
            return new Measurement(System.nanoTime() - start, collected);
        } finally {
            decoder.shutdown();
            assertTrue(decoder.awaitTermination(10_000), "decoder did not shut down after load test");
        }
    }

    @Test
    @Timeout(120)
    void cooperativeSingleWorkerAndFourWorkersMatchAndReportThroughput() throws Exception {
        Measurement cooperative = runCooperatively();
        Measurement single = run(1);
        Measurement parallel = run(4);

        assertEquals(cooperative.frames().size(), single.frames().size());
        assertEquals(single.frames().size(), parallel.frames().size());
        for (int i = 0; i < FRAMES; i++) {
            assertArrayEquals(cooperative.frames().get(i), single.frames().get(i),
                    "one-worker decoder changed the pixels in frame " + i);
            assertArrayEquals(cooperative.frames().get(i), parallel.frames().get(i),
                    "parallel decoder changed the pixels in frame " + i);
        }

        double cooperativeSeconds = cooperative.elapsedNs() / 1_000_000_000.0;
        double singleSeconds = single.elapsedNs() / 1_000_000_000.0;
        double parallelSeconds = parallel.elapsedNs() / 1_000_000_000.0;
        System.out.printf("LOAD COMPARISON (%d synthetic %dx%d frames, six ordered colour effects): "
                        + "cooperative = %.3fs, 1 worker = %.3fs, 4 workers = %.3fs, "
                        + "cooperative/4-worker ratio = %.2fx%n",
                FRAMES, WIDTH, HEIGHT, cooperativeSeconds, singleSeconds, parallelSeconds,
                cooperativeSeconds / parallelSeconds);
        // Correctness is asserted; speed is observational because the CI environment is shared.
    }
}
