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

import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.bytedeco.javacv.Frame;

/**
 * Decodes media on a thread of its own, and applies the effects to the decoded frames on a pool of worker threads, so
 * so heavy effect processing need not run on the JavaFX event thread.
 *
 * <p>
 * <b>Threading design.</b> There are three kinds of thread. The <i>decoder thread</i> is the only thread that ever
 * touches the {@link MediaSource}. It decodes frames one after another, which cannot be split up, because each frame
 * depends on those before it. Applying the effects to a frame, on the other hand, does not depend on any other frame,
 * so the decoder hands each decoded frame to a <i>worker thread</i> and carries straight on decoding. Several frames
 * are therefore in the effects at the same time, one per worker. The third kind is whoever consumes the results, which
 * is the JavaFX thread. It communicates with the decoder through two thread-safe queues and nothing else:
 * </p>
 * <ul>
 * <li>Results go out through a <i>bounded</i> queue of futures, in the order the media was decoded. A future is
 * handed over as soon as its work has been started, whether or not it has finished, and the consumer only ever takes
 * the one at the head of the queue once it is done. That keeps results in order however long each one takes.</li>
 * <li>The queue is <i>bounded</i>. When the consumer falls behind the queue fills up and the
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
 * therefore copied straight away, and the copy is handed to exactly one worker, which is the only thread to touch it
 * until the finished frame is passed on through the queue.
 * </p>
 *
 * <p>
 * <b>Order.</b> Workers finish in no particular order, but results are queued in the order the frames were decoded and
 * are only handed out from the front of the queue, so the consumer always sees them in order.
 * </p>
 *
 * <p>
 * <b>Stopping.</b> When the decoder thread ends, it first stops the workers and waits for all of them to finish, and
 * only then closes the source. Anyone who waits for the decoder thread to end can therefore be sure that nothing is
 * still using the effects.
 * </p>
 */
final class FrameDecoder {
    /** How often a waiting decoder wakes to check whether it has been asked to seek or stop. */
    private static final long DEFAULT_CHECK_INTERVAL_MS = 20;
    private static final long WORKER_STOP_TIMEOUT_SECONDS = 10;

    private record SeekRequest(int generation, long timestampUs) {
    }

    // A queued result, which may not have finished yet. The generation is kept beside the future so that stale results
    // can be recognised, and removed, without having to wait for them to finish.
    private record Queued(int generation, Future<DecodedItem> future) {
    }

    private final MediaSource source; // Used by the decoder thread only once started.
    private final FrameEffectsStage effects;
    private final Function<Frame, byte[]> audioSampler; // Null when there is no audio to extract.
    private final ExecutorService workers;
    private final BlockingQueue<Queued> output;
    private final BlockingQueue<SeekRequest> seekRequests = new LinkedBlockingQueue<>();
    private final AtomicInteger latestGeneration = new AtomicInteger();
    private final Thread thread = new Thread(this::run, "frame-decoder");
    private final long checkIntervalMs;
    private volatile boolean stopRequested;

    /**
     * A decoder with a single worker, which applies the effects to one frame at a time.
     *
     * @see #FrameDecoder(MediaSource, FrameEffectsStage, Function, int, int, long)
     */
    FrameDecoder(MediaSource source, FrameEffectsStage effects, Function<Frame, byte[]> audioSampler, int capacity) {
        this(source, effects, audioSampler, capacity, 1);
    }

    /** @see #FrameDecoder(MediaSource, FrameEffectsStage, Function, int, int, long) */
    FrameDecoder(
            MediaSource source,
            FrameEffectsStage effects,
            Function<Frame, byte[]> audioSampler,
            int capacity,
            int workerThreads) {
        this(source, effects, audioSampler, capacity, workerThreads, DEFAULT_CHECK_INTERVAL_MS);
    }

    /**
     * @param source          where frames come from; the decoder takes ownership and closes it when it stops
     * @param effects         applied to each video frame on a worker thread; must be safe to call from several threads
     * @param audioSampler    turns an audio frame into bytes for the sound card, or null if audio is not wanted
     * @param capacity        how many decoded items may wait in the output queue before the decoder blocks. This is also
     *                        the most frames that can be in the effects at once, so it should comfortably exceed the
     *                        number of workers, or some of them will have nothing to do.
     * @param workerThreads   how many frames may have their effects applied at the same time
     * @param checkIntervalMs how often a waiting decoder wakes of its own accord to look for a request. Normally a
     *                        decoder is woken at once when there is something for it to do, and this only matters as a
     *                        safety net. A test can set it very long to prove that the prompt wake-ups really happen.
     */
    FrameDecoder(
            MediaSource source,
            FrameEffectsStage effects,
            Function<Frame, byte[]> audioSampler,
            int capacity,
            int workerThreads,
            long checkIntervalMs) {
        this.checkIntervalMs = checkIntervalMs;
        this.source = source;
        this.effects = effects;
        this.audioSampler = audioSampler;
        this.output = new ArrayBlockingQueue<>(capacity);
        this.workers = Executors.newFixedThreadPool(workerThreads, new WorkerThreadFactory());
        thread.setDaemon(true); // A forgotten decoder must never keep the application alive.
    }

    /** Names effect workers and runs them at minimum priority so JavaFX rendering can be scheduled promptly. */
    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread worker = new Thread(task, "frame-worker-" + count.incrementAndGet());
            worker.setDaemon(true);
            // Effects are CPU-intensive; leave scheduling preference to JavaFX and the decoder.
            worker.setPriority(Thread.MIN_PRIORITY);
            return worker;
        }
    }

    /** Starts decoding from wherever the source is currently positioned, as generation 0. */
    void start() {
        thread.start();
    }

    /**
     * Asks the decoder to continue from the given position. Returns the new generation: from now on, only items
     * carrying this generation (or a later one) are worth looking at. Safe to call from any thread, at any time.
     *
     * The generation increment and request insertion must be atomic with respect to other callers. Otherwise one
     * caller could allocate generation 1, pause, and enqueue it after another caller's generation 2. The decoder
     * would then apply an older seek last, even though the consumer had already switched to generation 2.
     */
    synchronized int seek(long timestampUs) {
        int generation = latestGeneration.incrementAndGet();
        // Publish the request first, so that a decoder woken by the space freed below already sees it, and then remove
        // only the stale items, which are those from older generations. Items for the new position carry the new
        // generation, so they can never be removed by mistake, however the two threads happen to interleave.
        seekRequests.add(new SeekRequest(generation, timestampUs));
        discardStaleItems(generation);
        return generation;
    }

    // Removes every queued item that was decoded for a position older than the given generation, and cancels any of
    // them whose work has not finished, so that nobody wastes time on results that will never be used. This removes
    // items one at a time through the iterator, rather than with removeIf, because only the former wakes a decoder that
    // is waiting for room in a full queue. removeIf frees the room but leaves the decoder asleep until its wait runs out.
    private void discardStaleItems(int currentGeneration) {
        for (Iterator<Queued> items = output.iterator(); items.hasNext();) {
            Queued queued = items.next();
            if (queued.generation() < currentGeneration) {
                items.remove();
                queued.future().cancel(false);
            }
        }
    }

    /**
     * The next decoded item, or null if the next one is not ready yet. Never blocks. Items always come out in the
     * order they were decoded, so a finished item waits behind an unfinished one that was decoded before it.
     * Meant to be called by one thread at a time.
     */
    DecodedItem poll() {
        Queued head = output.peek();
        if (head == null || !head.future().isDone() || !output.remove(head)) {
            return null;
        }

        try {
            return head.future().get();
        } catch (CancellationException e) {
            return null;
        } catch (ExecutionException e) {
            return new DecodedItem.Failure(
                    latestGeneration.get(),
                    new IllegalStateException("A decoding task failed", e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Non-blocking diagnostic snapshot. No caller should make playback decisions from these approximate values;
     * the decoder and workers can advance immediately after the snapshot is taken.
     */
    record QueueProbe(int queuedItems, boolean headPending) {
    }

    QueueProbe queueProbe() {
        Queued head = output.peek();
        return new QueueProbe(output.size(), head != null && !head.future().isDone());
    }

    /**
     * Requests termination and wakes the decoder if it is waiting for a seek or output-queue space.
     * The interrupt is a wake-up, not permission for another thread to close the media source;
     * the decoder still closes its own source after stopping its workers.
     */
    void shutdown() {
        stopRequested = true;
        thread.interrupt();
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
                    SeekRequest seek = takeLatestSeek(idle ? checkIntervalMs : 0);
                    if (seek != null) {
                        // Anything stale that slipped into the queue after the caller cleared it is removed here.
                        discardStaleItems(seek.generation());
                        source.seek(seek.timestampUs());
                        generation = seek.generation();
                        idle = false;
                    }
                    if (!idle) {
                        idle = !decodeAndPublishNext(generation);
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception | Error e) {
                    // Errors such as running out of memory are reported too. If they were left to end the thread, the
                    // consumer would simply wait for frames that never come, with nothing to say what went wrong.
                    Exception cause = e instanceof Exception exception
                            ? exception
                            : new IllegalStateException("Decoding was stopped by " + e, e);
                    publish(new DecodedItem.Failure(generation, cause));
                    idle = true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Treat an interrupt as a request to stop.
        } finally {
            stopWorkers();
            source.close(); // The thread that used the source is the one that closes it.
        }
    }

    // Cancel pending effect work and wait for active workers before the decoder thread exits.
    // shutdown() deliberately interrupts the decoder to wake it from queue waits. That interrupt flag
    // must not cause awaitTermination() to return immediately while workers still own frames/effects.
    // Retain the interrupt status, wait up to the bound, then restore it for callers observing the thread.
    private void stopWorkers() {
        workers.shutdownNow();
        boolean interrupted = Thread.interrupted(); // Save and clear the decoder's wake-up interrupt.
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WORKER_STOP_TIMEOUT_SECONDS);
            while (!workers.isTerminated()) {
                long remainingNs = deadline - System.nanoTime();
                if (remainingNs <= 0) {
                    System.err.println("WARNING: frame workers did not stop within "
                            + WORKER_STOP_TIMEOUT_SECONDS + "s");
                    break;
                }
                try {
                    workers.awaitTermination(remainingNs, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    // Preserve the request, but finish the bounded cleanup wait rather than abandoning workers.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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

        // Hand the copy to a worker and carry on decoding. The future goes into the queue now, in decode order, and
        // becomes available to the consumer once the worker has finished with it.
        publish(generation, workers.submit(() -> applyEffects(generation, frameNumber, timestampUs, frame)));
        return true;
    }

    // Runs on a worker thread. A failure becomes an item rather than an exception, so that it is delivered to the
    // consumer at its proper place in the order, after all the frames before it.
    private DecodedItem applyEffects(int generation, int frameNumber, long timestampUs, Frame frame) {
        try {
            effects.apply(frame, new InfoFrame(frameNumber, timestampUs));
            return new DecodedItem.Video(generation, frameNumber, timestampUs, frame, System.nanoTime());
        } catch (Exception | Error e) {
            Exception cause = e instanceof Exception exception
                    ? exception
                    : new IllegalStateException("Applying the effects was stopped by " + e, e);
            return new DecodedItem.Failure(generation, cause);
        }
    }

    /** Queues an item whose work is already done. */
    private boolean publish(DecodedItem item) throws InterruptedException {
        return publish(item.generation(), CompletableFuture.completedFuture(item));
    }

    /**
     * Puts a result in the output queue, waiting for room if it is full. Gives up quietly, cancelling the result, if the
     * decoder has been asked to stop or a seek has made the result stale. Returns whether the result was queued.
     */
    private boolean publish(int generation, Future<DecodedItem> result) throws InterruptedException {
        Queued queued = new Queued(generation, result);
        while (!stopRequested) {
            if (!seekRequests.isEmpty()) {
                break;
            }
            if (output.offer(queued, checkIntervalMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        result.cancel(false);
        return false;
    }
}
