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

import java.io.File;
import java.lang.ref.Reference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the playback clock, seeking logic and audio scheduling, and publishes
 * decoded frames and playback state through callbacks supplied by its owning
 * {@link VideoPlayerController}, keeping it decoupled from the JavaFX view.
 * </p>
 *
 * <p>
 * <b>Threads.</b> Decoding the media happens on a {@link FrameDecoder} thread,
 * and applying the frame processors happens on that decoder's pool of worker
 * threads, one frame per worker at a time, so that the effects can use every core.
 * Everything else in this class - the clock,
 * audio writing, showing frames, and every public method - runs on the JavaFX
 * thread, so none of the fields below need locking. The only crossings between
 * the two threads are the decoder's two queues, plus the
 * {@link #audioOutputEnabled} flag, which is volatile for that reason. Once a
 * decoder has been started the FFmpeg grabber belongs to it and is never
 * touched from here again, and nor are the frame processors, until it has stopped.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    // One worker for each core, since applying the effects is what takes the time and it spreads across all of them.
    private static final int DECODER_WORKER_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());
    // Decoded items (video and audio) the decoder may run ahead. That is also the most frames that can be in the effects
    // at once, so it must comfortably exceed the number of workers or some would have nothing to do. Every frame in here
    // is a full copy of a decoded picture, so it is kept no larger than that needs.
    private static final int DECODER_QUEUE_CAPACITY = 2 * DECODER_WORKER_THREADS + 8;
    private static final int MAX_READY_FRAMES = 4; // Decoded video frames held here waiting for their moment.
    private static final long DECODER_STOP_TIMEOUT_MS = 2_000;
    private static final int FRAMES_KEPT_AFTER_SHOWING = 3;
    private static final byte[] NO_AUDIO = new byte[0];

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // The 3rd party decoder. Ours only until it is handed to the decoder thread.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects. Used on the JavaFX thread only.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private FrameDecoder decoder; // Decodes and applies the effects on its own thread. Null when nothing is open.
    private int activeGeneration; // Only decoder items stamped with this generation are current. Others are stale.
    private final Deque<DecodedItem.Video> readyFrames = new ArrayDeque<>(); // Decoded and waiting to be shown.
    private final Deque<Frame> recentlyShown = new ArrayDeque<>(); // Frames just shown, kept a little before they are given back.
    private boolean endOfStreamReached; // The decoder has said there is nothing after the frames in readyFrames.
    private Exception pendingFailure; // A decode error, to be reported once the frames before it have been shown.
    private int framesAllowedWhilePaused; // How many more frames may appear while paused. See togglePause.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled; // Also read by the decoder thread, so volatile.
    private boolean audioAvailable;
    private EffectChain effectChain; // Applies the effects for the current playback session.
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private final PlaybackClock clock = new PlaybackClock(); // Maps media timestamps to System.nanoTime() moments.
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = List.copyOf(frameProcessors);
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            // Pausing does not stop a frame that was already waiting from appearing when its time comes, and nor does it
            // stop the first frame after a start or seek, which is still on its way if the clock has not started.
            // Nothing after that is shown until playback resumes. This is how the single-threaded player behaved.
            framesAllowedWhilePaused = !clock.isAnchored() || !readyFrames.isEmpty() ? 1 : 0;
            clock.pause(System.nanoTime());
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        // The decoder does the actual seeking. Frames it decoded for the old position may still be on their way, but
        // they carry the old generation and so will be ignored.
        activeGeneration = decoder.seek(grabTimestampUs);
        resetPlaybackClock(logicalTimestampUs);
        flushAudioOutput();
        notifyPlaybackStateChanged();
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startDecoder();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        effectChain = new EffectChain(frameProcessors, new EffectChain.MediaProperties(
                mediaName(videoFile),
                totalVideoFrames,
                frameRate,
                intFrameRate,
                videoFrameDurationUs,
                grabber.getPixelFormat()));

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        readyFrames.clear();
        endOfStreamReached = false;
        pendingFailure = null;
        framesAllowedWhilePaused = 1; // The first frame after a start or seek is shown even if paused.
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        clock.reset(logicalTimestampUs, pauseRequested, System.nanoTime());
    }

    private void resumePlaybackClock(long now) {
        clock.resume(now);
    }

    // Hands the grabber over to a new decoder thread and starts it. From here on the grabber must not be touched on
    // this thread: the decoder owns it, and closes it when it stops.
    private void startDecoder() {
        MediaSource source = new FFmpegMediaSource(grabber, audioAvailable);
        grabber = null;

        // Called on the decoder thread. The audio player's copySamples only uses a scratch buffer of its own, so it is safe
        // to call there while this thread writes to the sound card.
        AudioPlayer player = audioPlayer;
        Function<Frame, byte[]> audioSampler = audioAvailable
                ? frame -> audioOutputEnabled ? player.copySamples(frame) : NO_AUDIO
                : null;

        decoder = new FrameDecoder(source, effectChain, audioSampler, DECODER_QUEUE_CAPACITY, DECODER_WORKER_THREADS);
        activeGeneration = 0;
        decoder.start();
    }

    // Asks the decoder to stop and waits for it to finish. We wait because it shares the effect processors with whatever
    // decoder is started next, and they must never be used by two threads at once. It also closes the grabber, and the
    // audio player must not be closed while it may still be copying audio.
    private void stopDecoder() {
        if (decoder == null) {
            return;
        }

        FrameDecoder stopping = decoder;
        decoder = null;
        stopping.shutdown();
        try {
            if (!stopping.awaitTermination(DECODER_STOP_TIMEOUT_MS)) {
                System.err.println("WARNING: the decoder thread did not stop within " + DECODER_STOP_TIMEOUT_MS + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. The heavy work of decoding and applying effects now happens
    // on the decoder thread, so all that is left for us is: (1) collect whatever the decoder has finished, (2) keep audio
    // flowing if sound is on, (3) display a frame if one is due, and (4) notice when playback has reached the end.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        drainDecoder();
        writeDueAudio(now);
        displayDueFrame(now);
        reportEndOfPlayback();
    }

    // Moves whatever the decoder has finished into our own, single-threaded, collections. We stop early when enough
    // frames are waiting, which leaves the rest in the decoder's queue and so makes the decoder wait (back-pressure).
    private void drainDecoder() {
        while (readyFrames.size() < MAX_READY_FRAMES && !endOfStreamReached && pendingFailure == null) {
            DecodedItem item = decoder.poll();
            if (item == null) {
                return;
            }
            if (item.generation() != activeGeneration) {
                continue; // Decoded for a position we have since left.
            }

            switch (item) {
                case DecodedItem.Video video -> readyFrames.add(video);
                case DecodedItem.Audio audio -> queueAudio(audio);
                case DecodedItem.EndOfStream _ -> endOfStreamReached = true;
                case DecodedItem.Failure failure -> pendingFailure = failure.cause();
            }
        }
    }

    private void displayDueFrame(long now) {
        DecodedItem.Video next = readyFrames.peek();
        if (next == null) {
            return;
        }
        if (pauseRequested && framesAllowedWhilePaused == 0) {
            return;
        }
        // The first frame after the clock has been reset is due the moment we are ready to show it, because it is the
        // frame that starts the clock. Every later frame is due according to the clock.
        if (clock.isAnchored() && clock.targetTimeNs(next.timestampUs()) > now) {
            return;
        }

        readyFrames.remove();
        if (pauseRequested) {
            framesAllowedWhilePaused--;
        }
        displayFrame(next, now);
    }

    private void displayFrame(DecodedItem.Video video, long now) {
        // The clock starts ticking when the first frame is shown, not when it was decoded, so the time it took to decode
        // and process that frame cannot make the frame (and every one after it) late before it has even appeared.
        if (!clock.isAnchored()) {
            clock.anchor(video.timestampUs(), pauseRequested, now);
        }
        long targetTimeNs = clock.targetTimeNs(video.timestampUs());

        currentTimestampUs = clock.logicalTimestampUs(video.timestampUs());
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored. The headroom
        // is how long before its due time the frame was ready. Negative means the frame was late.
        if (intFrameRate > 0 && video.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - video.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        // The conversion to a JavaFX Image stays here on the JavaFX thread. That keeps the converter on a single thread
        // and means each Image is made only when it is about to be shown.
        Image image;
        try {
            image = converter.convert(video.frame());
        } catch (Exception e) {
            handlePlaybackError(e);
            return;
        } finally {
            // The pixels of a frame are in native memory that is freed once the Frame can no longer be reached, and the
            // JVM may treat the frame as unreachable as soon as it is no longer used, which is while the converter is
            // still copying from it. The decoder thread allocates a new frame for every frame it decodes, and that is when
            // freed memory gets released and reused, so without this the copy can read pixels from the next frame
            // (a torn picture) or from freed memory. The fence keeps the frame alive until the conversion has finished.
            Reference.reachabilityFence(video);
        }
        notifyFrameReady(image);
        giveBackFramesFinishedWith(video.frame());
    }

    // A frame is finished with once its picture has been copied into a JavaFX Image. It is not given back at once but a
    // few frames later, so that if anything on the display side were still to be looking at its pixels, it would have
    // long finished before the memory is reused for a new picture.
    private void giveBackFramesFinishedWith(Frame shown) {
        recentlyShown.addLast(shown);
        while (recentlyShown.size() > FRAMES_KEPT_AFTER_SHOWING) {
            // No frame pool exists yet; releasing the deque reference permits normal GC.
        }
    }

    // Playback has ended, or failed, once every frame before that point has been shown. As before, nothing ends while paused.
    private void reportEndOfPlayback() {
        if (!playbackOpen || pauseRequested || !readyFrames.isEmpty()) {
            return;
        }

        if (pendingFailure != null) {
            handlePlaybackError(pendingFailure);
        } else if (endOfStreamReached) {
            finishPlayback();
        }
    }

    private void queueAudio(DecodedItem.Audio audio) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        pendingAudio.add(new PendingAudio(audio.timestampUs(), audio.samples()));
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || !clock.isRunning()) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = clock.dueMediaTimestampUs(now, AUDIO_LEAD_NS);
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        stopDecoder();
        readyFrames.clear();
        recentlyShown.clear();
        endOfStreamReached = false;
        pendingFailure = null;
        pendingAudio.clear();
        clock.stop();
        audioAvailable = false;
        effectChain = null;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) { // Only if it was opened but never handed to a decoder.
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}