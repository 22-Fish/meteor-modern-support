package fish22.modernsupport.font;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.LightCoordsUtil;

/**
 * 标准字体渲染器: 结构照抄 {@link VanillaTextRenderer}, 只把字体换成「系统字体建的 Font」
 * 所以位置/缩放/阴影/行高和原版渲染器完全同一套算法, Meteor 的排版不用改
 * 字体没准备好 (未开启/加载失败) 时整段交回原版渲染器
 */
public class StandardTextRenderer implements TextRenderer {
    public static final StandardTextRenderer INSTANCE = new StandardTextRenderer();

    private final ByteBufferBuilder buffer = new ByteBufferBuilder(2048);
    private final MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(buffer);
    private final PoseStack matrices = new PoseStack();

    private boolean building;
    private double scale = 2;
    private double alpha = 1;
    /** 本段文字用的字体 (begin 时定下来, 中途不换) */
    private Font font;

    private StandardTextRenderer() {
    }

    @Override
    public void setAlpha(double a) {
        alpha = a;
        VanillaTextRenderer.INSTANCE.setAlpha(a);
    }

    @Override
    public void begin(double scale, boolean scaleOnly, boolean big) {
        if (building) throw new RuntimeException("StandardTextRenderer.begin() called twice");

        building = true;
        this.scale = scale * 2;
        this.font = StandardFont.current(scale);

        if (font == null) VanillaTextRenderer.INSTANCE.begin(scale, scaleOnly, big);
    }

    @Override
    public double getWidth(String text, int length, boolean shadow) {
        if (text.isEmpty()) return 0;

        Font font = building ? this.font : StandardFont.current(1);
        if (font == null) return VanillaTextRenderer.INSTANCE.getWidth(text, length, shadow);

        if (length != text.length()) text = text.substring(0, length);
        return (font.width(text) + (shadow ? 1 : 0)) * scale;
    }

    @Override
    public double getHeight(boolean shadow) {
        Font font = building ? this.font : StandardFont.current(1);
        if (font == null) return VanillaTextRenderer.INSTANCE.getHeight(shadow);

        return (font.lineHeight + (shadow ? 1 : 0)) * scale;
    }

    @Override
    public double render(String text, double x, double y, Color color, boolean shadow) {
        boolean wasBuilding = building;
        if (!wasBuilding) begin(1, false, false);

        if (font == null) {
            double result = VanillaTextRenderer.INSTANCE.render(text, x, y, color, shadow);
            if (!wasBuilding) end();
            return result;
        }

        x += 0.5 * scale;
        y += 0.5 * scale;

        int preA = color.a;
        color.a = (int) (((double) color.a / 255 * alpha) * 255);

        matrices.pushPose();
        matrices.scale((float) scale, (float) scale, 1);
        font.drawInBatch(text, (float) (x / scale), (float) (y / scale), color.getPacked(), shadow, matrices.last().pose(), immediate, Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
        double x2 = (x / scale) + font.width(text);
        matrices.popPose();

        color.a = preA;

        if (!wasBuilding) end();
        return (x2 - 1) * scale;
    }

    @Override
    public boolean isBuilding() {
        return building;
    }

    @Override
    public void end() {
        if (!building) throw new RuntimeException("StandardTextRenderer.end() called without calling begin()");

        boolean vanilla = font == null;
        building = false;
        scale = 2;
        font = null;

        if (vanilla) VanillaTextRenderer.INSTANCE.end();
        else immediate.endBatch();
    }
}
