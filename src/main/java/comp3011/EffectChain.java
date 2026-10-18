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

import java.util.List;

import org.bytedeco.javacv.Frame;

/**
 * Applies the list of frame processors, in order, to each video frame of one playback session.
 *
 * <p>
 * The processors must be told about the video before they see their first frame, and the details they need (such as the
 * frame size) are only known once a real frame has been decoded. So the chain initialises every processor, once, when
 * it is given its first frame.
 * </p>
 *
 * <p>
 * <b>Thread safety.</b> A chain belongs to one playback session, and {@link #apply} may be called from several threads
 * at once, each with a different frame. Two things make that safe:
 * </p>
 * <ul>
 * <li>Initialisation is a critical region. It must finish, exactly once, before any thread processes a frame, so the
 * first threads to arrive take a lock, and the rest wait for the initialisation to finish rather than carrying on.</li>
 * <li>Once initialised, each processor only ever reads its own state while processing a frame, so any number of frames
 * can be processed at the same time. Processors are required to behave that way. Keeping a scratch buffer in a field
 * is not allowed, for example, and the processors that used to do it now keep their scratch data local to each call.</li>
 * </ul>
 *
 * <p>
 * Nothing else may touch the processors while a session is running: they are stateful, and initialising them again
 * would disturb frames that are still being processed.
 * </p>
 */
final class EffectChain implements FrameEffectsStage {
    /** Facts about the media that come from the decoder rather than from any one frame. */
    record MediaProperties(
            String mediaName,
            int totalVideoFrames,
            double frameRate,
            int intFrameRate,
            int frameDurationUs,
            int pixelFormat) {
    }

    private final List<FrameProcessor> processors;
    private final MediaProperties media;
    private final Object initialiseLock = new Object();
    private volatile boolean initialised; // Written only under the lock. volatile so that other threads see it, and everything the initialisation did, once it is true.

    EffectChain(List<FrameProcessor> processors, MediaProperties media) {
        this.processors = List.copyOf(processors);
        this.media = media;
    }

    @Override
    public void apply(Frame frame, InfoFrame info) throws Exception {
        if (!initialised) { // The common case, once running, costs one volatile read and no locking.
            synchronized (initialiseLock) {
                if (!initialised) { // Another thread may have finished the job while this one waited for the lock.
                    initialiseProcessors(frame);
                    initialised = true;
                }
            }
        }

        for (FrameProcessor processor : processors) {
            processor.process(frame, info);
        }
    }

    private void initialiseProcessors(Frame firstFrame) throws Exception {
        InfoVideo videoInfo = new InfoVideo(
                media.mediaName(),
                media.totalVideoFrames(),
                firstFrame.imageWidth,
                firstFrame.imageHeight,
                firstFrame.imageDepth,
                firstFrame.imageChannels,
                firstFrame.imageStride,
                media.frameRate(),
                media.intFrameRate(),
                media.frameDurationUs(),
                media.pixelFormat());

        for (FrameProcessor processor : processors) {
            processor.initialise(videoInfo);
        }
    }
}
