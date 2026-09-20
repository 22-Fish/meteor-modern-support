package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.CustomTextRenderer;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * 自定义字体 + 原版字体回退。
 *
 * <p>Meteor 的自定义字体只打包了 拉丁字母 / 希腊 / 西里尔 等码点，中文等字形没有，
 * 遇到缺字会画出空白。这里在缺字时把整段文字交给原版字体渲染，
 * 于是「自定义字体」和「中文能显示」可以同时成立。
 */
public class FallbackTextRenderer implements TextRenderer {
    public static final FallbackTextRenderer INSTANCE = new FallbackTextRenderer();

    private boolean building;
    private double scale = 1;

    private FallbackTextRenderer() {
    }

    private static CustomTextRenderer custom() {
        // 由 Meteor 的字体设置加载; 还没加载(或加载失败)时为 null
        return Fonts.RENDERER;
    }

    /** 自定义字体打包的码点范围: Basic Latin / Latin-1 / Latin Extended-A / Greek / Cyrillic / ∞ */
    private static boolean customCanRender(String text) {
        for (int i = 0; i < text.length(); i++) {
            int cp = text.charAt(i);
            boolean supported = (cp >= 32 && cp <= 126)
                || (cp >= 160 && cp <= 383)
                || (cp >= 880 && cp <= 1023)
                || (cp >= 1024 && cp <= 1279)
                || cp == 8734;

            if (!supported) return false;
        }

        return true;
    }

    private static boolean useCustom(String text) {
        CustomTextRenderer renderer = custom();
        return renderer != null && customCanRender(text);
    }

    @Override
    public void setAlpha(double a) {
        CustomTextRenderer renderer = custom();
        if (renderer != null) renderer.setAlpha(a);

        VanillaTextRenderer.INSTANCE.setAlpha(a);
    }

    @Override
    public void begin(double scale, boolean scaleOnly, boolean big) {
        if (building) throw new RuntimeException("FallbackTextRenderer.begin() called twice");

        building = true;
        this.scale = scale;

        CustomTextRenderer renderer = custom();
        if (renderer != null) renderer.begin(scale, scaleOnly, big);
    }

    @Override
    public double getWidth(String text, int length, boolean shadow) {
        if (text.isEmpty()) return 0;

        if (!useCustom(text)) return VanillaTextRenderer.INSTANCE.getWidth(text, length, shadow);
        return custom().getWidth(text, length, shadow);
    }

    @Override
    public double getHeight(boolean shadow) {
        CustomTextRenderer renderer = custom();
        if (renderer == null) return VanillaTextRenderer.INSTANCE.getHeight(shadow);

        return renderer.getHeight(shadow);
    }

    @Override
    public double render(String text, double x, double y, Color color, boolean shadow) {
        boolean wasBuilding = building;
        if (!wasBuilding) begin(1, false, false);

        double result;
        if (useCustom(text)) {
            result = custom().render(text, x, y, color, shadow);
        } else {
            VanillaTextRenderer vanilla = VanillaTextRenderer.INSTANCE;

            boolean vanillaBuilding = vanilla.isBuilding();
            if (!vanillaBuilding) vanilla.begin(scale, false, false);

            result = vanilla.render(text, x, y, color, shadow);

            if (!vanillaBuilding) vanilla.end();
        }

        if (!wasBuilding) end();
        return result;
    }

    @Override
    public boolean isBuilding() {
        return building;
    }

    @Override
    public void end() {
        if (!building) throw new RuntimeException("FallbackTextRenderer.end() called without calling begin()");

        building = false;
        scale = 1;

        CustomTextRenderer renderer = custom();
        if (renderer != null) renderer.end();
    }
}
