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

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * A {@link MediaSource} backed by a started FFmpeg grabber. It is the only place the decoder's world touches the FFmpeg
 * library.
 *
 * <p>
 * Whoever hands the grabber over must stop using it: from then on it belongs to the thread that calls this source.
 * </p>
 */
final class FFmpegMediaSource implements MediaSource {
    private final FFmpegFrameGrabber grabber;
    private final boolean audioAvailable;

    /**
     * @param grabber        a grabber that has already been started
     * @param audioAvailable whether the media has audio. When it does not, only images are decoded, which is cheaper.
     */
    FFmpegMediaSource(FFmpegFrameGrabber grabber, boolean audioAvailable) {
        this.grabber = grabber;
        this.audioAvailable = audioAvailable;
    }

    @Override
    public Frame grab() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    @Override
    public long timestampUs() {
        return grabber.getTimestamp();
    }

    @Override
    public int frameNumber() {
        return grabber.getFrameNumber();
    }

    @Override
    public void seek(long timestampUs) throws Exception {
        grabber.setTimestamp(timestampUs);
    }

    @Override
    public void close() {
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
    }
}
