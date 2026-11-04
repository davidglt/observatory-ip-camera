/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.TimeZone;

/**
 * Burns the overlay into JPEG pixels. With the overlay enabled the camera JPEG is decoded, the EXIF
 * orientation is applied exactly once, text is drawn and the image is re-encoded; Bitmap.compress
 * writes no EXIF block, so the output is implicitly upright and cannot be rotated twice by viewers.
 * With the overlay disabled the camera JPEG bytes are returned unchanged (no decode, no re-encode).
 * Runs on a worker thread only.
 */
final class FrameOverlay {
    static final class Result {
        final byte[] jpeg;
        final boolean rendered;
        final boolean transformed;
        final int width;
        final int height;
        final int exifOrientation;
        final long processingNanos;
        Result(byte[] jpeg, boolean rendered, boolean transformed, int width, int height, int exifOrientation,
               long processingNanos) {
            this.jpeg = jpeg;
            this.rendered = rendered;
            this.transformed = transformed;
            this.width = width;
            this.height = height;
            this.exifOrientation = exifOrientation;
            this.processingNanos = processingNanos;
        }
    }

    private FrameOverlay() {}

    static Result process(byte[] jpeg, CaptureConfig config, double actualFps, long wallMillis, TimeZone zone) throws IOException {
        return process(jpeg, config, actualFps, wallMillis, zone, null);
    }

    static Result process(byte[] jpeg, CaptureConfig config, double actualFps, long wallMillis, TimeZone zone,
                          OverlayTelemetry telemetry) throws IOException {
        long start = System.nanoTime();
        if (!config.overlayEnabled && !config.mirror)
            return new Result(jpeg, false, false, 0, 0, 1, System.nanoTime() - start);
        int sourceOrientation = readOrientation(jpeg);
        ImageTransformPlan plan = ImageTransformPlan.forFrame(config, sourceOrientation);
        if (plan.passthrough)
            return new Result(jpeg, false, false, 0, 0, 1, System.nanoTime() - start);
        Bitmap decoded = null;
        Bitmap upright = null;
        try {
            int tag = plan.normalizeExif ? sourceOrientation : ExifInterface.ORIENTATION_NORMAL;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = true;
            decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (decoded == null) throw new IOException("JPEG decode failed.");
            upright = normalize(decoded, tag);
            if (upright != decoded) { decoded.recycle(); decoded = null; }
            if (plan.mirrorPixels) {
                Bitmap source = upright;
                Bitmap mirrored = mirror(source);
                if (mirrored != source) {
                    if (decoded == source) decoded = null;
                    source.recycle();
                }
                upright = mirrored;
            }
            if (plan.drawOverlay)
                draw(upright, OverlayFormat.lines(config, actualFps, wallMillis, zone, telemetry), config);
            ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 4096);
            if (!upright.compress(Bitmap.CompressFormat.JPEG, plan.jpegQuality, out)) throw new IOException("JPEG encode failed.");
            return new Result(out.toByteArray(), config.overlayEnabled, true, upright.getWidth(), upright.getHeight(), 1,
                System.nanoTime() - start);
        } catch (OutOfMemoryError e) {
            throw new IOException("Out of memory while rendering the overlay.");
        } finally {
            if (decoded != null) decoded.recycle();
            if (upright != null) upright.recycle();
        }
    }

    private static int readOrientation(byte[] jpeg) {
        try {
            return new ExifInterface(new ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** Returns a mutable, upright bitmap; the source is returned itself when no transform is needed and it is mutable. */
    private static Bitmap normalize(Bitmap source, int tag) {
        Bitmap result = source;
        if (!ExifOrientation.isNormal(tag)) {
            Matrix m = new Matrix();
            m.postRotate(ExifOrientation.rotationDegrees(tag));
            if (ExifOrientation.flipHorizontal(tag)) m.postScale(-1, 1);
            if (ExifOrientation.flipVertical(tag)) m.postScale(1, -1);
            result = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
        }
        if (!result.isMutable()) {
            Bitmap copy = result.copy(Bitmap.Config.ARGB_8888, true);
            if (result != source) result.recycle();
            if (copy == null) throw new OutOfMemoryError();
            result = copy;
        }
        return result;
    }

    private static Bitmap mirror(Bitmap source) {
        Matrix matrix = new Matrix();
        matrix.setScale(-1f, 1f);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static void draw(Bitmap bitmap, List<String> lines, CaptureConfig config) {
        if (lines.isEmpty()) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int margin = OverlayLayout.margin(w, h);
        float size = OverlayLayout.textSizePx(config.textSize, w, h);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        float widest;
        for (int i = 0; ; i++) {
            paint.setTextSize(size);
            widest = 0;
            for (String line : lines) widest = Math.max(widest, paint.measureText(line));
            if (widest <= OverlayLayout.maxTextWidth(w, margin) || size <= 6f || i >= 30) break;
            size *= 0.9f;
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = fm.descent - fm.ascent;
        float blockH = lineHeight * lines.size();
        float[] origin = OverlayLayout.blockOrigin(config.position, w, h, widest, blockH, margin);
        Canvas canvas = new Canvas(bitmap);
        for (int i = 0; i < lines.size(); i++) {
            float x = origin[0];
            float y = origin[1] + i * lineHeight - fm.ascent;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(2f, size * 0.18f));
            paint.setColor(Color.BLACK);
            paint.setShadowLayer(size * 0.08f, size * 0.05f, size * 0.05f, Color.BLACK);
            canvas.drawText(lines.get(i), x, y, paint);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor(OverlayFormat.textColor(config)));
            canvas.drawText(lines.get(i), x, y, paint);
        }
    }
}
