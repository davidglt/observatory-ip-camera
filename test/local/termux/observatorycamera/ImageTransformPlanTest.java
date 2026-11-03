package local.termux.observatorycamera;

/** Dependency-free EXIF, orientation, overlay, and mirror transformation matrix test. */
public final class ImageTransformPlanTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

    public static void main(String[] args) {
        check(ExifOrientation.rotationDegrees(5) == 90 && ExifOrientation.flipHorizontal(5),
            "EXIF transpose rotates clockwise then mirrors horizontally");
        check(ExifOrientation.rotationDegrees(7) == 270 && ExifOrientation.flipHorizontal(7),
            "EXIF transverse rotates counterclockwise then mirrors horizontally");
        for (int tag = 1; tag <= 8; tag++) {
            for (boolean overlay : new boolean[]{false, true}) {
                for (boolean mirror : new boolean[]{false, true}) {
                    CaptureConfig config = CaptureConfig.parse("overlay=" + (overlay ? "on" : "off")
                        + " mirror=" + (mirror ? "on" : "off"), CaptureConfig.defaults()).config;
                    ImageTransformPlan plan = ImageTransformPlan.forFrame(config, tag);
                    boolean transformed = overlay || mirror;
                    check(plan.passthrough == !transformed, "passthrough " + tag + " " + overlay + " " + mirror);
                    check(plan.normalizeExif == (transformed && !ExifOrientation.isNormal(tag)),
                        "EXIF normalized once " + tag + " " + overlay + " " + mirror);
                    check(plan.mirrorPixels == (transformed && mirror), "scene mirror " + tag + " " + overlay + " " + mirror);
                    check(plan.drawOverlay == (transformed && overlay), "upright overlay " + tag + " " + overlay + " " + mirror);
                    check(plan.outputExifOrientation == (transformed ? 1 : tag),
                        "output EXIF matches transformed pixels " + tag + " " + overlay + " " + mirror);
                }
            }
        }
        CaptureConfig manual = CaptureConfig.parse("orientation_mode=manual rotation_degrees=270", CaptureConfig.defaults()).config;
        check(JpegOrientation.calculate(manual, 90, 0, false) == 270,
            "manual rotation is independent of sensor orientation");
        CaptureConfig quality = CaptureConfig.parse("jpeg_quality=37", CaptureConfig.defaults()).config;
        check(ImageTransformPlan.forFrame(quality, 1).jpegQuality == 37,
            "overlay re-encoding uses the configured JPEG quality");
        CaptureConfig automatic = CaptureConfig.defaults();
        check(JpegOrientation.calculate(automatic, 90, 270, false) == 0,
            "automatic orientation uses sensor and physical device angles");
        System.out.println("ImageTransformPlanTest passed");
    }
}
