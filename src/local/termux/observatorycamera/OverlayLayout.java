package local.termux.observatorycamera;

/** Overlay geometry: text size scaling, safe margins and block placement. Pure Java. */
final class OverlayLayout {
    /** Text sizes are expressed for a 720 px short image side and scaled to the actual image. */
    static final float REFERENCE_SHORT_SIDE = 720f;

    private OverlayLayout() {}

    static float textSizePx(int textSize, int width, int height) {
        return Math.max(8f, textSize * Math.min(width, height) / REFERENCE_SHORT_SIDE);
    }

    /** 3% of the short side, at least 8 px, so text stays inside overscan/rounded areas. */
    static int margin(int width, int height) {
        return Math.max(8, Math.round(Math.min(width, height) * 0.03f));
    }

    static float maxTextWidth(int width, int margin) { return Math.max(1f, width - 2f * margin); }

    /** Top-left corner {x, y} of a blockW x blockH block for the named position; never negative. */
    static float[] blockOrigin(String position, int width, int height, float blockW, float blockH, int margin) {
        boolean right = position.endsWith("right");
        boolean top = position.startsWith("top");
        float x = right ? width - margin - blockW : margin;
        float y = top ? margin : height - margin - blockH;
        return new float[]{Math.max(0f, x), Math.max(0f, y)};
    }
}
