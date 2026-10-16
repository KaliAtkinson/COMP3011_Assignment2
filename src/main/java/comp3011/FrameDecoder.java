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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.bytedeco.javacv.Frame;

/**
 * Decodes media on a thread of its own, so that decoding and applying effects no longer compete with the JavaFX thread
 * for time.
 *
 * <p>
 * <b>Threading design.</b> The decoder thread is the only thread that ever touches the {@link MediaSource}. Every other
 * thread communicates with it through two thread-safe queues and nothing else:
 * </p>
 * <ul>
 * <li>Results go out through a <i>bounded</i> queue. When the consumer falls behind the queue fills up and the
 * decoder blocks, so decoding can never run away from the display and use unlimited memory (back-pressure).</li>
 * <li>Seek requests come in through a second queue. The decoder looks at it between frames and also while it is
 * waiting for room in the output queue, so a seek is noticed within a few milliseconds.</li>
 * </ul>
 *
 * <p>
 * <b>Generations.</b> Each seek starts a new generation and every {@link DecodedItem} is stamped with the generation
 * it was decoded in. Frames decoded before a seek may still be sitting in the queue, or in flight, when the seek
 * happens. The consumer simply ignores items from any generation other than the one {@link #seek(long)} last returned,
 * so there is no need to stop the decoder, wait for it, or lock anything to get a clean cut.
 * </p>
 *
 * <p>
 * <b>Frame ownership.</b> The source is allowed to reuse one {@link Frame} object for every grab. Each video frame is
 * therefore copied straight away, and it is the copy that has the effects applied and is queued.
 * </p>
 */
final class FrameDecoder {
    /** How often a waiting decoder wakes to check whether it has been asked to seek or stop. */
    private static final long CHECK_INTERVAL_MS = 20;

    private record SeekRequest(int generation, long timestampUs) {
    }

    private final MediaSource source; // Used by the decoder thread only once started.
    private final FrameEffectsStage effects;
    private final Function<Frame, byte[]> audioSampler; // Null when there is no audio to extract.
    private final BlockingQueue<DecodedItem> output;
    private final BlockingQueue<SeekRequest> seekRequests = new LinkedBlockingQueue<>();
    private final AtomicInteger latestGeneration = new AtomicInteger();
    private final Thread thread = new Thread(this::run, "frame-decoder");
    private volatile boolean stopRequested;

    /**
     * @param source       where frames come from; the decoder takes ownership and closes it when it stops
     * @param effects      applied to each video frame on the decoder thread
     * @param audioSampler turns an audio frame into bytes for the sound card, or null if audio is not wanted
     * @param capacity     how many decoded items may wait in the output queue before the decoder blocks
     */
    FrameDecoder(MediaSource source, FrameEffectsStage effects, Function<Frame, byte[]> audioSampler, int capacity) {
        this.source = source;
        this.effects = effects;
        this.audioSampler = audioSampler;
        this.output = new ArrayBlockingQueue<>(capacity);
        thread.setDaemon(true); // A forgotten decoder must never keep the application alive.
    }

    /** Starts decoding from wherever the source is currently positioned, as generation 0. */
    void start() {
        thread.start();
    }

    /**
     * Asks the decoder to continue from the given position. Returns the new generation: from now on, only items
     * carrying this generation (or a later one) are worth looking at. Safe to call from any thread, at any time.
     */
    int seek(long timestampUs) {
        int generation = latestGeneration.incrementAndGet();
        // Empty the queue BEFORE the request is visible. The decoder cannot start on the new position until it sees the
        // request, so nothing for the new position can be in the queue yet to be thrown away by mistake. Stale items
        // that slip in after this are harmless, because the consumer filters them out by generation.
        output.clear();
        seekRequests.add(new SeekRequest(generation, timestampUs));
        return generation;
    }

    /** The next decoded item, or null if there is none yet. Never blocks. */
    DecodedItem poll() {
        return output.poll();
    }

    /** Asks the decoder to stop and close its source. Returns immediately; see {@link #awaitTermination(long)}. */
    void shutdown() {
        stopRequested = true;
    }

    /** Waits up to the given time for the decoder thread to finish. Returns true if it has. */
    boolean awaitTermination(long timeoutMs) throws InterruptedException {
        thread.join(timeoutMs);
        return !thread.isAlive();
    }

    // ---- Everything below runs on the decoder thread ----

    private void run() {
        int generation = 0;
        boolean idle = false; // True after the end of the media or a failure: nothing to do until told to seek.
        try {
            while (!stopRequested) {
                try {
                    SeekRequest seek = takeLatestSeek(idle ? CHECK_INTERVAL_MS : 0);
                    if (seek != null) {
                        source.seek(seek.timestampUs());
                        generation = seek.generation();
                        idle = false;
                    }
                    if (!idle) {
                        idle = !decodeAndPublishNext(generation);
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    publish(new DecodedItem.Failure(generation, e));
                    idle = true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Treat an interrupt as a request to stop.
        } finally {
            source.close(); // The thread that used the source is the one that closes it.
        }
    }

    /** Returns the most recent seek request, ignoring any earlier ones, or null if there is none. */
    private SeekRequest takeLatestSeek(long waitMs) throws InterruptedException {
        SeekRequest latest = waitMs > 0
                ? seekRequests.poll(waitMs, TimeUnit.MILLISECONDS)
                : seekRequests.poll();
        if (latest == null) {
            return null;
        }
        SeekRequest next;
        while ((next = seekRequests.poll()) != null) {
            latest = next;
        }
        return latest;
    }

    /** Decodes one frame and publishes what came out of it. Returns false once nothing more can be decoded. */
    private boolean decodeAndPublishNext(int generation) throws Exception {
        Frame grabbed = source.grab();
        if (grabbed == null) {
            publish(new DecodedItem.EndOfStream(generation));
            return false;
        }

        long timestampUs = source.timestampUs();
        if (audioSampler != null && grabbed.samples != null) {
            byte[] samples = audioSampler.apply(grabbed);
            if (samples.length > 0) {
                publish(new DecodedItem.Audio(generation, timestampUs, samples));
            }
        }

        if (grabbed.image == null) {
            return true;
        }

        int frameNumber = source.frameNumber();
        Frame frame = grabbed.clone(); // The source may overwrite 'grabbed' on its next grab, so work on a copy.
        effects.apply(frame, new InfoFrame(frameNumber, timestampUs));
        publish(new DecodedItem.Video(generation, frameNumber, timestampUs, frame, System.nanoTime()));
        return true;
    }

    /**
     * Puts an item in the output queue, waiting for room if it is full. Gives up quietly, dropping the item, if the
     * decoder has been asked to stop or a seek has made the item stale. Returns whether the item was queued.
     */
    private boolean publish(DecodedItem item) throws InterruptedException {
        while (!stopRequested) {
            if (!seekRequests.isEmpty()) {
                return false;
            }
            if (output.offer(item, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }
}
