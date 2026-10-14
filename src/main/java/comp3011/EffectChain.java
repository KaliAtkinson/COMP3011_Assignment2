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
 * A chain belongs to one playback session and is <b>not thread-safe</b>: it must be used by one thread at a time. That
 * is also what makes it safe, because the processors it holds are stateful (they are initialised, and a few keep
 * caches), and nothing else may touch them while a session is running.
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
    private boolean initialised;

    EffectChain(List<FrameProcessor> processors, MediaProperties media) {
        this.processors = List.copyOf(processors);
        this.media = media;
    }

    @Override
    public void apply(Frame frame, InfoFrame info) throws Exception {
        if (!initialised) {
            initialiseProcessors(frame);
            initialised = true;
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
