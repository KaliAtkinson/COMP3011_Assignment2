/*
 * COMP3011 Assignment 2 video player.
 * Authors: Simon Ratcliffe (starter code); Kali Atkinson (project modifications).
 *
 * Combines the specific W-Y-V and V-Y-W colour-effect sequences into one pixel pass.
 */
package comp3011;

import java.nio.ByteBuffer;
import java.util.List;
import org.bytedeco.javacv.Frame;

/** A bit-exact fast path for adjacent black-and-white / warm / vignette effects. */
final class FusedColourRun implements FrameEffectsStage {
    private final List<FrameProcessor> originals;
    private final boolean grayscaleFirst;
    private final FrameYellower yellow;
    private final FrameVignetter vignette;
    private final ThreadLocal<byte[]> rows = ThreadLocal.withInitial(() -> new byte[0]);

    FusedColourRun(List<FrameProcessor> processors) {
        originals = List.copyOf(processors);
        grayscaleFirst = originals.get(0).getClass() == FrameBlackAndWhiter.class;
        yellow = (FrameYellower) originals.get(1);
        vignette = (FrameVignetter) (grayscaleFirst ? originals.get(2) : originals.get(0));
    }

    static boolean supports(FrameProcessor a, FrameProcessor b, FrameProcessor c) {
        return (a.getClass() == FrameBlackAndWhiter.class && b.getClass() == FrameYellower.class
                    && c.getClass() == FrameVignetter.class)
                || (a.getClass() == FrameVignetter.class && b.getClass() == FrameYellower.class
                    && c.getClass() == FrameBlackAndWhiter.class);
    }

    @Override
    public void apply(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) {
            applyOriginal(frame, info);
            return;
        }
        InfoVideo video = originals.get(0).videoInfo;
        int channels = video.imageChannels();
        if (channels < 3 || (video.imageDepth() != Frame.DEPTH_UBYTE && video.imageDepth() != Frame.DEPTH_BYTE)) {
            applyOriginal(frame, info);
            return;
        }
        int width = video.imageWidth();
        int height = video.imageHeight();
        int stride = video.imageStride();
        int rowBytes = width * channels;
        byte[] row = rows.get();
        if (row.length < rowBytes) {
            row = new byte[rowBytes];
            rows.set(row);
        }
        if (grayscaleFirst) {
            for (int y = 0; y < height; y++) {
                int rowStart = y * stride;
                pixels.get(rowStart, row, 0, rowBytes);
                for (int x = 0; x < width; x++) {
                    int base = x * channels;
                    int b = row[base] & 255;
                    int g = row[base + 1] & 255;
                    int r = row[base + 2] & 255;
                    int grey = (54 * r + 183 * g + 19 * b) / 256;
                    row[base] = vignette.vignetteValue(x, y, yellow.blueValue(grey) & 255);
                    row[base + 1] = vignette.vignetteValue(x, y, yellow.greenValue(grey) & 255);
                    row[base + 2] = vignette.vignetteValue(x, y, yellow.redValue(grey) & 255);
                    for (int c = 3; c < channels; c++) {
                        row[base + c] = vignette.vignetteValue(x, y, grey);
                    }
                }
                pixels.put(rowStart, row, 0, rowBytes);
            }
        } else {
            for (int y = 0; y < height; y++) {
                int rowStart = y * stride;
                pixels.get(rowStart, row, 0, rowBytes);
                for (int x = 0; x < width; x++) {
                    int base = x * channels;
                    int b = yellow.blueValue(vignette.vignetteValue(x, y, row[base] & 255) & 255) & 255;
                    int g = yellow.greenValue(vignette.vignetteValue(x, y, row[base + 1] & 255) & 255) & 255;
                    int r = yellow.redValue(vignette.vignetteValue(x, y, row[base + 2] & 255) & 255) & 255;
                    byte grey = (byte) ((54 * r + 183 * g + 19 * b) / 256);
                    for (int c = 0; c < channels; c++) {
                        row[base + c] = grey;
                    }
                }
                pixels.put(rowStart, row, 0, rowBytes);
            }
        }
    }

    private void applyOriginal(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : originals) {
            processor.process(frame, info);
        }
    }
}
