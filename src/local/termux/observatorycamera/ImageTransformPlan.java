package local.termux.observatorycamera;

/** Describes pixel/EXIF work needed for one JPEG without using Android bitmap classes. */
final class ImageTransformPlan {
    final boolean passthrough;
    final boolean normalizeExif;
    final boolean mirrorPixels;
    final boolean drawOverlay;
    final int outputExifOrientation;
    final int jpegQuality;

    private ImageTransformPlan(boolean passthrough, boolean normalizeExif, boolean mirrorPixels,
                               boolean drawOverlay, int outputExifOrientation, int jpegQuality) {
        this.passthrough = passthrough;
        this.normalizeExif = normalizeExif;
        this.mirrorPixels = mirrorPixels;
        this.drawOverlay = drawOverlay;
        this.outputExifOrientation = outputExifOrientation;
        this.jpegQuality = jpegQuality;
    }

    static ImageTransformPlan forFrame(CaptureConfig config, int exifOrientation) {
        boolean passthrough = !config.overlayEnabled && !config.mirror;
        boolean transform = !passthrough;
        return new ImageTransformPlan(passthrough, transform && !ExifOrientation.isNormal(exifOrientation),
            transform && config.mirror, transform && config.overlayEnabled, transform ? 1 : exifOrientation,
            config.jpegQuality);
    }
}
