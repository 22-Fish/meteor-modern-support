package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * GUI 显示微调：按设置里配置的圆角半径画矩形，圆角为 0 时直接走原版 quad。
 */
public final class GuiTweaks {
    private GuiTweaks() {
    }

    /** 当前主题的圆角半径（已乘主题比例），0 = 直角 */
    public static double cornerRadius(GuiTheme theme) {
        ThemeAccessor accessor = ThemeAccessor.of(theme);
        if (accessor == null) return 0;

        double radius = accessor.getCornerRadius().get();
        return radius <= 0 ? 0 : theme.scale(radius);
    }

    /**
     * 画一个矩形，四个角可单独开关圆角。
     * 窗口正文只圆下面两个角（上面接标题栏），标题栏只圆上面两个角。
     * 不圆角的那两个角要补成直角，否则会露出后面的画面。
     */
    public static void quad(GuiRenderer renderer, GuiTheme theme, double x, double y, double width, double height,
                            boolean topLeft, boolean topRight, boolean bottomLeft, boolean bottomRight, Color color) {
        double radius = cornerRadius(theme);
        radius = Math.min(radius, Math.min(width, height) / 2);

        if (radius < 1) {
            renderer.quad(x, y, width, height, color);
            return;
        }

        // 中间十字：除四个角以外的部分都是普通矩形
        renderer.quad(x + radius, y, width - radius * 2, height, color);
        renderer.quad(x, y + radius, radius, height - radius * 2, color);
        renderer.quad(x + width - radius, y + radius, radius, height - radius * 2, color);

        if (topLeft) arc(renderer, x + radius, y + radius, radius, Math.PI, Math.PI * 1.5, color);
        else renderer.quad(x, y, radius, radius, color);

        if (topRight) arc(renderer, x + width - radius, y + radius, radius, Math.PI * 1.5, Math.PI * 2, color);
        else renderer.quad(x + width - radius, y, radius, radius, color);

        if (bottomRight) arc(renderer, x + width - radius, y + height - radius, radius, 0, Math.PI * 0.5, color);
        else renderer.quad(x + width - radius, y + height - radius, radius, radius, color);

        if (bottomLeft) arc(renderer, x + radius, y + height - radius, radius, Math.PI * 0.5, Math.PI, color);
        else renderer.quad(x, y + height - radius, radius, radius, color);
    }

    /**
     * 用三角扇形近似一个圆角。
     * 顶点顺序要和 meteor 自己的 quad 一致：UI 管线开了背面剔除（withCull(true)），
     * 顺序反了的三角形会被丢掉，看起来就是「没有圆角」。
     */
    private static void arc(GuiRenderer renderer, double centerX, double centerY, double radius,
                            double startAngle, double endAngle, Color color) {
        int segments = 8;
        double step = (endAngle - startAngle) / segments;

        for (int i = 0; i < segments; i++) {
            double a1 = startAngle + step * i;
            double a2 = a1 + step;

            renderer.triangle(
                centerX, centerY,
                centerX + Math.cos(a2) * radius, centerY + Math.sin(a2) * radius,
                centerX + Math.cos(a1) * radius, centerY + Math.sin(a1) * radius,
                color
            );
        }
    }
}
