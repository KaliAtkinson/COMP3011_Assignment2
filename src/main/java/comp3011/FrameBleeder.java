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

import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Simulates bright light bleeding into nearby pixels, with a warm colour bias.
 * This one is expensive in CPU time!
 *
 * <p>
 * For each frame, {@link #buildBrightMask(ByteBuffer)} identifies bright
 * regions, {@link #blurBrightMask()} spreads their influence, and
 * {@link #applyBleed(ByteBuffer)} adds the resulting coloured glow.
 * </p>
 */
public class FrameBleeder extends FrameProcessor {
    private static final int MASK_BLOCK_SIZE = 4;
    private static final int BLUR_RADIUS = 3;
    private static final int BLEED_LUMINANCE_THRESHOLD = 150;
    private static final double BLEED_GAMMA = 1.25;
    private static final double BLEED_MASK_GAIN = 2.4;
    private static final double RED_BLEED_STRENGTH = 105.0;
    private static final double GREEN_BLEED_STRENGTH = 62.0;
    private static final double BLUE_BLEED_STRENGTH = 18.0;

    // Per-worker scratch storage avoids repeated HD-frame allocations without sharing
    // mutable arrays between frames being processed concurrently.
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        double[] brightMask = new double[0];
        double[] horizontalBlurMask = new double[0];
        double[] blurredMask = new double[0];
        byte[] blockRows = new byte[0];
        byte[] pixelRow = new byte[0];
        boolean[] cellCanGlow = new boolean[0];

        void ensureCapacity(int maskPixels, int blockBytes, int rowBytes, int maskColumns) {
            if (brightMask.length < maskPixels) {
                brightMask = new double[maskPixels];
                horizontalBlurMask = new double[maskPixels];
                blurredMask = new double[maskPixels];
            }
            if (blockRows.length < blockBytes) blockRows = new byte[blockBytes];
            if (pixelRow.length < rowBytes) pixelRow = new byte[rowBytes];
            if (cellCanGlow.length < maskColumns) cellCanGlow = new boolean[maskColumns];
        }
    }

    private int maskWidth;
    private int maskHeight;
    private int[] xMaskLeft;
    private double[] xMaskBlend;
    private int[] yMaskTop;
    private double[] yMaskBlend;

    public FrameBleeder() {
        super();
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);

        maskWidth = (videoInfo.imageWidth() + MASK_BLOCK_SIZE - 1) / MASK_BLOCK_SIZE;
        maskHeight = (videoInfo.imageHeight() + MASK_BLOCK_SIZE - 1) / MASK_BLOCK_SIZE;
        buildSamplingLookups();
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameBleeder requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameBleeder requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameBleeder requires at least three image channels");
        }

        Scratch work = scratch.get();
        work.ensureCapacity(maskWidth * maskHeight, MASK_BLOCK_SIZE * videoInfo.imageStride(),
                videoInfo.imageWidth() * videoInfo.imageChannels(), maskWidth);

        buildBrightMask(pixels, work.brightMask, work.blockRows);
        blurHorizontally(work.brightMask, work.horizontalBlurMask);
        blurVertically(work.horizontalBlurMask, work.blurredMask);
        applyBleed(pixels, work.blurredMask, work.pixelRow, work.cellCanGlow);
    }

    private void buildSamplingLookups() {
        xMaskLeft = new int[videoInfo.imageWidth()];
        xMaskBlend = new double[videoInfo.imageWidth()];
        yMaskTop = new int[videoInfo.imageHeight()];
        yMaskBlend = new double[videoInfo.imageHeight()];

        buildAxisSamplingLookups(videoInfo.imageWidth(), maskWidth, xMaskLeft, xMaskBlend);
        buildAxisSamplingLookups(videoInfo.imageHeight(), maskHeight, yMaskTop, yMaskBlend);
    }

    private void buildAxisSamplingLookups(int imageSize, int maskSize, int[] maskStarts, double[] maskBlends) {
        if (imageSize <= 1 || maskSize <= 1) {
            for (int position = 0; position < imageSize; position++) {
                maskStarts[position] = 0;
                maskBlends[position] = 0.0;
            }
            return;
        }

        for (int position = 0; position < imageSize; position++) {
            double maskPosition = (position + 0.5) / MASK_BLOCK_SIZE - 0.5;
            maskPosition = clamp(maskPosition, 0.0, maskSize - 1.0);

            int maskStart = (int) Math.floor(maskPosition);
            if (maskStart >= maskSize - 1) {
                maskStart = maskSize - 2;
                maskPosition = maskSize - 1;
            }

            maskStarts[position] = maskStart;
            maskBlends[position] = maskPosition - maskStart;
        }
    }

    // The brightness a block contributes depends only on the block's brightest luminance, which is a whole number from 0
    // to 255, so each of the 256 possible answers is worked out once rather than for every block of every frame.
    private static final double[] BRIGHTNESS_BY_LUMINANCE = buildBrightnessTable();

    private static double[] buildBrightnessTable() {
        double[] table = new double[256];
        for (int luminance = 0; luminance < table.length; luminance++) {
            table[luminance] = thresholdedBrightness(luminance);
        }
        return table;
    }

    private static double thresholdedBrightness(int luminance) {
        if (luminance <= BLEED_LUMINANCE_THRESHOLD) {
            return 0.0;
        }

        double normalised = (luminance - BLEED_LUMINANCE_THRESHOLD) / (double) (255 - BLEED_LUMINANCE_THRESHOLD);
        return Math.pow(normalised, BLEED_GAMMA);
    }

    // Finds the brightest pixel in each block. The frame is read a whole row of blocks at a time into an array, which is
    // far faster than reading it a byte at a time from the buffer, and gives exactly the same answers.
    private void buildBrightMask(ByteBuffer pixels, double[] brightMask, byte[] rows) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        for (int maskY = 0; maskY < maskHeight; maskY++) {
            int blockY = maskY * MASK_BLOCK_SIZE;
            int rowCount = Math.min(blockY + MASK_BLOCK_SIZE, height) - blockY;
            pixels.get(blockY * stride, rows, 0, rowCount * stride);

            for (int maskX = 0; maskX < maskWidth; maskX++) {
                int blockX = maskX * MASK_BLOCK_SIZE;
                int blockXEnd = Math.min(blockX + MASK_BLOCK_SIZE, width);
                int maximumLuminance = 0;

                for (int row = 0; row < rowCount; row++) {
                    int rowStart = row * stride;
                    for (int x = blockX; x < blockXEnd; x++) {
                        int base = rowStart + x * channels;
                        // JavaCV supplies these decoded frames in BGR byte order.
                        int b = rows[base] & 0xFF;
                        int g = rows[base + 1] & 0xFF;
                        int r = rows[base + 2] & 0xFF;
                        int luminance = (54 * r + 183 * g + 19 * b) / 256;
                        if (luminance > maximumLuminance) {
                            maximumLuminance = luminance;
                        }
                    }
                }

                brightMask[maskY * maskWidth + maskX] = BRIGHTNESS_BY_LUMINANCE[maximumLuminance];
            }
        }
    }

    private static final int BLUR_SAMPLES = 2 * BLUR_RADIUS + 1;

    // Away from the edges every sample is inside the mask, so the clamping can be skipped. The samples are still added up
    // in the same order, which matters because floating point addition is not associative.
    private void blurHorizontally(double[] source, double[] destination) {
        for (int y = 0; y < maskHeight; y++) {
            int rowStart = y * maskWidth;
            for (int x = 0; x < maskWidth; x++) {
                double total = 0.0;

                if (x >= BLUR_RADIUS && x < maskWidth - BLUR_RADIUS) {
                    for (int dx = -BLUR_RADIUS; dx <= BLUR_RADIUS; dx++) {
                        total += source[rowStart + x + dx];
                    }
                } else {
                    for (int dx = -BLUR_RADIUS; dx <= BLUR_RADIUS; dx++) {
                        total += source[rowStart + clamp(x + dx, 0, maskWidth - 1)];
                    }
                }

                destination[rowStart + x] = total / BLUR_SAMPLES;
            }
        }
    }

    private void blurVertically(double[] source, double[] destination) {
        for (int y = 0; y < maskHeight; y++) {
            boolean interior = y >= BLUR_RADIUS && y < maskHeight - BLUR_RADIUS;
            for (int x = 0; x < maskWidth; x++) {
                double total = 0.0;

                if (interior) {
                    for (int dy = -BLUR_RADIUS; dy <= BLUR_RADIUS; dy++) {
                        total += source[(y + dy) * maskWidth + x];
                    }
                } else {
                    for (int dy = -BLUR_RADIUS; dy <= BLUR_RADIUS; dy++) {
                        total += source[clamp(y + dy, 0, maskHeight - 1) * maskWidth + x];
                    }
                }

                destination[y * maskWidth + x] = total / BLUR_SAMPLES;
            }
        }
    }

    // Adds the glow. Most of a typical picture is too dark to glow at all, and a pixel only glows if at least one of the
    // four mask values it is blended from is above zero, so whole cells and rows that cannot glow are skipped without
    // working anything out. Where there is a glow, the arithmetic is exactly as before. Each row is read and written back
    // in one go instead of a byte at a time.
    private void applyBleed(ByteBuffer pixels, double[] blurredMask, byte[] row, boolean[] cellCanGlow) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();
        int rowBytes = width * channels;
        boolean rowCanGlow = false;
        int cellsWorkedOutFor = -1;

        for (int y = 0; y < height; y++) {
            int y0 = yMaskTop[y];
            int y1 = Math.min(y0 + 1, maskHeight - 1);
            double yBlend = yMaskBlend[y];

            if (y0 != cellsWorkedOutFor) {
                cellsWorkedOutFor = y0;
                rowCanGlow = false;
                for (int cell = 0; cell < maskWidth; cell++) {
                    int next = Math.min(cell + 1, maskWidth - 1);
                    boolean canGlow = blurredMask[y0 * maskWidth + cell] != 0.0
                            || blurredMask[y0 * maskWidth + next] != 0.0
                            || blurredMask[y1 * maskWidth + cell] != 0.0
                            || blurredMask[y1 * maskWidth + next] != 0.0;
                    cellCanGlow[cell] = canGlow;
                    rowCanGlow |= canGlow;
                }
            }
            if (!rowCanGlow) {
                continue;
            }

            int rowStart = y * stride;
            pixels.get(rowStart, row, 0, rowBytes);
            boolean changed = false;

            // The four mask values a pixel is blended from only change every few pixels, so they are looked up once per
            // cell. The differences between them are also worked out once per cell. Each pixel then does exactly the same
            // arithmetic as lerp(a, b, blend) = a + (b - a) * blend, in the same order, so the answers are identical.
            int topRow = y0 * maskWidth;
            int bottomRow = y1 * maskWidth;
            int cellOf = -1;
            boolean canGlow = false;
            double topLeft = 0.0;
            double bottomLeft = 0.0;
            double topStep = 0.0;
            double bottomStep = 0.0;

            for (int x = 0; x < width; x++) {
                int x0 = xMaskLeft[x];
                if (x0 != cellOf) {
                    cellOf = x0;
                    canGlow = cellCanGlow[x0];
                    if (canGlow) {
                        int x1 = Math.min(x0 + 1, maskWidth - 1);
                        topLeft = blurredMask[topRow + x0];
                        bottomLeft = blurredMask[bottomRow + x0];
                        topStep = blurredMask[topRow + x1] - topLeft;
                        bottomStep = blurredMask[bottomRow + x1] - bottomLeft;
                    }
                }
                if (!canGlow) {
                    continue;
                }

                double xBlend = xMaskBlend[x];
                double top = topLeft + topStep * xBlend;
                double bottom = bottomLeft + bottomStep * xBlend;
                double bleedStrength = Math.min(1.0, (top + (bottom - top) * yBlend) * BLEED_MASK_GAIN);
                if (bleedStrength <= 0.0) {
                    continue;
                }

                int base = x * channels;
                row[base] = boostedByte(row[base], roundedBoost(bleedStrength * BLUE_BLEED_STRENGTH));
                row[base + 1] = boostedByte(row[base + 1], roundedBoost(bleedStrength * GREEN_BLEED_STRENGTH));
                row[base + 2] = boostedByte(row[base + 2], roundedBoost(bleedStrength * RED_BLEED_STRENGTH));
                changed = true;
            }

            if (changed) {
                pixels.put(rowStart, row, 0, rowBytes);
            }
        }
    }

    // The same as (int) Math.round(value) for a value that is not negative and is far below 2^31, but quicker. Rounding
    // half up is: the whole part, plus one if the fractional part is at least a half. Taking the whole part away from
    // the value is exact, so no rounding error can creep in.
    static int roundedBoost(double value) {
        long whole = (long) value;
        return (int) (value - whole >= 0.5 ? whole + 1 : whole);
    }

    private byte boostedByte(byte value, int boost) {
        return (byte) Math.min(255, Byte.toUnsignedInt(value) + boost);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
