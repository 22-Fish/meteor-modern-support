/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 22_Fish
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fish22.modernsupport.utils;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * meteor logo 的「描边写出来 → 一起点亮」动画绘制。
 *
 * <p>按桌面那版 HTML 复刻：6 条 SVG 路径、pathLength=1 的 dash 逐段画出、
 * 1.7s 后一起点填充 + drop-shadow 发光。发光用低分辨率遮罩 + 三次盒式模糊模拟高斯，
 * 效果和 CSS 的 drop-shadow 基本一致。
 *
 * <p>只用 AWT，不碰游戏，方便单独跑测试出图。
 */
public final class MeteorLogoPainter {
    /** HTML 里 svg 的 viewBox */
    public static final double VIEW_WIDTH = 532.0;
    public static final double VIEW_HEIGHT = 125.0;
    /** 描边宽 5 会画出 viewBox 外面（HTML 里 overflow visible），画布四周留边 */
    public static final double PAD = 10.0;

    /** 时间轴（毫秒），和 HTML 的 animation-duration / animation-delay 一一对应 */
    public static final double DRAW_DURATION = 900.0;
    public static final double DRAW_STAGGER = 150.0;
    public static final double DRAW_TOTAL = DRAW_DURATION + DRAW_STAGGER * 5.0;
    /** 全部描完之后才点亮（HTML 里是 animation-delay: 1.7s） */
    public static final double LIGHT_DELAY = 1700.0;
    public static final double LIGHT_DURATION = 800.0;

    /**
     * drop-shadow 的模糊半径。
     *
     * <p>SVG 元素的 filter 跑在用户坐标系里，浏览器实测：HTML 里的 18px / 6px
     * 就是这个坐标系下的 σ，所以发光尺寸跟着 logo 一起缩放
     */
    private static final double SHADOW_MAX = 18.0;
    private static final double SHADOW_MIN = 6.0;

    private static final float STROKE_WIDTH = 5.0f;
    private static final int ARGB_STROKE = 0xFFEAEAEA;
    private static final int ARGB_ACCENT = 0xFFCC0000;

    private static final String[] PATH_DATA = {
        // M
        "M101.896 25.352V125H88.792V50.696L55.672 125H46.456L13.192 50.552V125H0.0880127V25.352H14.2"
            + "L51.064 107.72L87.928 25.352H101.896Z",
        // E
        "M137.223 35.288V68.84H173.799V79.64H137.223V114.2H178.119V125H124.119V24.488H178.119V35.288H137.223Z",
        // T
        "M259.723 24.632V35.288H232.363V125H219.259V35.288H191.755V24.632H259.723Z",
        // E
        "M288.958 35.288V68.84H325.534V79.64H288.958V114.2H329.854V125H275.854V24.488H329.854V35.288H288.958Z",
        // R（两个子路径，靠 evenodd 挖空）
        "M515.733 125L491.829 83.96H475.989V125H462.885V24.632H495.285C502.869 24.632 509.253 25.928 514.437 28.52"
            + "C519.717 31.112 523.653 34.616 526.245 39.032C528.837 43.448 530.133 48.488 530.133 54.152"
            + "C530.133 61.064 528.117 67.16 524.085 72.44C520.149 77.72 514.197 81.224 506.229 82.952L531.429 125H515.733Z"
            + "M475.989 73.448H495.285C502.389 73.448 507.717 71.72 511.269 68.264C514.821 64.712 516.597 60.008 516.597 54.152"
            + "C516.597 48.2 514.821 43.592 511.269 40.328C507.813 37.064 502.485 35.432 495.285 35.432H475.989V73.448Z",
        // 红色流星那一笔
        "M388.661 120.503C393.958 123.501 399.817 125 406.24 125C412.728 125 418.621 123.501 423.918 120.503"
            + "C429.215 117.439 433.386 113.208 436.432 107.812C439.477 102.416 441 96.2872 441 89.4254"
            + "C441 82.5637 439.477 76.4347 436.432 71.0386C433.386 65.6424 421.215 57.4454 415.918 54.4476L336.5 0"
            + "L375.048 101.812C378 112 383.364 117.439 388.661 120.503Z"
    };
    /** 与 PATH_DATA 一一对应，true = 红色流星那一笔 */
    private static final boolean[] ACCENT = {false, false, false, false, false, true};

    private static final Path2D[] SHAPES = new Path2D[PATH_DATA.length];
    private static final double[] LENGTHS = new double[PATH_DATA.length];

    static {
        for (int i = 0; i < PATH_DATA.length; i++) {
            SHAPES[i] = parsePath(PATH_DATA[i]);
            LENGTHS[i] = pathLength(SHAPES[i]);
        }
    }

    // 画布（复用，别每帧新建）
    private BufferedImage canvas;
    private int[] canvasPixels;
    // 发光遮罩（低分辨率）
    private BufferedImage glowMask;
    private int[] glowPixels;
    private int[] glowAlpha;
    private int[] glowScratch;
    private int maskWidth;
    private int maskHeight;

    /**
     * 画一帧。
     *
     * @param width   画布宽（像素）
     * @param height  画布高（像素）
     * @param drawMs  描边动画已经过了多少毫秒
     * @param lightMs 点亮动画已经过了多少毫秒，小于 0 = 还没点亮
     * @return 画布，像素格式 TYPE_INT_ARGB
     */
    public BufferedImage paint(int width, int height, double drawMs, double lightMs) {
        ensureCanvas(width, height);

        Graphics2D g = canvas.createGraphics();
        try {
            // 清空
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, width, height);
            g.setComposite(AlphaComposite.SrcOver);

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // viewBox（含留边）→ 画布
            double scale = width / (VIEW_WIDTH + PAD * 2.0);
            g.scale(scale, scale);
            g.translate(PAD, PAD);

            double light = lightMs < 0.0 ? 0.0 : ease(lightMs / LIGHT_DURATION, 0.0, 0.0, 0.58, 1.0);
            boolean lit = lightMs >= 0.0 && light > 0.0;

            for (int i = 0; i < SHAPES.length; i++) {
                int argb = ACCENT[i] ? ARGB_ACCENT : ARGB_STROKE;

                // 顺序和 SVG 一致：光晕在元素下面，再填充，最后描边
                if (lit) {
                    double fillAlpha = Math.min(1.0, light / 0.45);
                    paintGlow(g, i, light, argb, fillAlpha, width, height);
                    g.setColor(withAlpha(argb, fillAlpha));
                    g.fill(SHAPES[i]);
                }

                double drawn = ease((drawMs - i * DRAW_STAGGER) / DRAW_DURATION, 0.65, 0.0, 0.35, 1.0);
                if (drawn <= 0.0) continue;

                g.setColor(new Color(argb, true));
                g.setStroke(strokeFor(i, drawn));
                g.draw(SHAPES[i]);
            }
        } finally {
            g.dispose();
        }

        return canvas;
    }

    /** 当前画布的像素（和 paint 返回的图共用同一块内存） */
    public int[] pixels() {
        return canvasPixels;
    }

    // ====== 绘制细节 ======

    /** 描边：dasharray 从 0 画到整条（等价于 HTML 里 pathLength=1、dashoffset 1→0） */
    private static BasicStroke strokeFor(int index, double drawn) {
        if (drawn >= 1.0) return new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4.0f);

        float len = (float) LENGTHS[index];
        return new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4.0f,
            new float[]{(float) (len * drawn), len * 2.0f}, 0.0f);
    }

    /**
     * drop-shadow：0~45% 段半径 0→18px、透明度 0→1，之后半径收到 6px
     *
     * <p>阴影是拿元素自己的 alpha 当遮罩的，所以填充还在淡入时阴影也跟着淡
     */
    private void paintGlow(Graphics2D g, int index, double light, int argb, double fillAlpha,
                           int width, int height) {
        double alpha = Math.min(1.0, light / 0.45);
        double radius = light <= 0.45
            ? SHADOW_MAX * (light / 0.45)
            : SHADOW_MAX + (SHADOW_MIN - SHADOW_MAX) * ((light - 0.45) / 0.55);
        if (alpha <= 0.004 || radius <= 0.05) return;

        int mw = Math.max(32, Math.round(width / 2.0f));
        int mh = Math.max(10, Math.round(height / 2.0f));
        ensureMask(mw, mh);

        // 把元素的形状（填充 + 描边）画进低分辨率遮罩
        Graphics2D mg = glowMask.createGraphics();
        try {
            mg.setComposite(AlphaComposite.Src);
            mg.setColor(new Color(0, 0, 0, 0));
            mg.fillRect(0, 0, mw, mh);
            mg.setComposite(AlphaComposite.SrcOver);
            mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            mg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            double maskScale = mw / (VIEW_WIDTH + PAD * 2.0);
            mg.scale(maskScale, maskScale);
            mg.translate(PAD, PAD);
            // 填充按当前透明度、描边始终不透明，和 CSS 里元素的 alpha 一致
            mg.setColor(new Color(255, 255, 255, (int) Math.round(fillAlpha * 255.0)));
            mg.fill(SHAPES[index]);
            mg.setColor(Color.WHITE);
            mg.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4.0f));
            mg.draw(SHAPES[index]);
        } finally {
            mg.dispose();
        }

        for (int i = 0; i < glowPixels.length; i++) glowAlpha[i] = glowPixels[i] >>> 24;

        // 半径换算成遮罩像素后交给盒式模糊
        boxBlur(glowAlpha, glowScratch, mw, mh, radius * mw / (VIEW_WIDTH + PAD * 2.0));

        int a = (int) Math.round(alpha * 255.0);
        int rgb = argb & 0xFFFFFF;
        for (int i = 0; i < glowPixels.length; i++) glowPixels[i] = ((glowAlpha[i] * a / 255) << 24) | rgb;

        g.drawImage(glowMask, (int) -PAD, (int) -PAD,
            (int) (VIEW_WIDTH + PAD * 2.0), (int) (VIEW_HEIGHT + PAD * 2.0), null);
    }

    private void ensureCanvas(int width, int height) {
        if (canvas != null && canvas.getWidth() == width && canvas.getHeight() == height) return;

        canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        canvasPixels = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
    }

    private void ensureMask(int width, int height) {
        if (glowMask != null && maskWidth == width && maskHeight == height) return;

        maskWidth = width;
        maskHeight = height;
        glowMask = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        glowPixels = ((DataBufferInt) glowMask.getRaster().getDataBuffer()).getData();
        glowAlpha = new int[width * height];
        glowScratch = new int[width * height];
    }

    private static Color withAlpha(int argb, double alpha) {
        int a = (int) Math.round(Math.max(0.0, Math.min(1.0, alpha)) * 255.0);
        return new Color((a << 24) | (argb & 0xFFFFFF), true);
    }

    // ====== 数学 ======

    /** CSS 的 cubic-bezier(x1, y1, x2, y2)，用二分求解（x1、x2 都在 0~1，保证单调） */
    private static double ease(double t, double x1, double y1, double x2, double y2) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;

        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) * 0.5;
            if (bezier(mid, x1, x2) < t) lo = mid;
            else hi = mid;
        }
        return bezier((lo + hi) * 0.5, y1, y2);
    }

    /** 三次贝塞尔（P0=0、P3=1） */
    private static double bezier(double t, double a1, double a2) {
        double mt = 1.0 - t;
        return 3.0 * mt * mt * t * a1 + 3.0 * mt * t * t * a2 + t * t * t;
    }

    /** 三次盒式模糊 ≈ 高斯模糊 */
    private static void boxBlur(int[] data, int[] scratch, int width, int height, double sigma) {
        if (sigma < 0.35) return;

        int radius = (int) Math.round((Math.sqrt(4.0 * sigma * sigma + 1.0) - 1.0) / 2.0);
        if (radius < 1) return;

        for (int pass = 0; pass < 3; pass++) {
            blurHorizontal(data, scratch, width, height, radius);
            blurVertical(scratch, data, width, height, radius);
        }
    }

    private static void blurHorizontal(int[] src, int[] dst, int width, int height, int radius) {
        int norm = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int sum = src[row] * (radius + 1);
            for (int x = 1; x <= radius; x++) sum += src[row + Math.min(x, width - 1)];

            for (int x = 0; x < width; x++) {
                dst[row + x] = sum / norm;
                sum += src[row + Math.min(x + radius + 1, width - 1)] - src[row + Math.max(x - radius, 0)];
            }
        }
    }

    private static void blurVertical(int[] src, int[] dst, int width, int height, int radius) {
        int norm = radius * 2 + 1;
        for (int x = 0; x < width; x++) {
            int sum = src[x] * (radius + 1);
            for (int y = 1; y <= radius; y++) sum += src[Math.min(y, height - 1) * width + x];

            for (int y = 0; y < height; y++) {
                dst[y * width + x] = sum / norm;
                sum += src[Math.min(y + radius + 1, height - 1) * width + x] - src[Math.max(y - radius, 0) * width + x];
            }
        }
    }

    // ====== SVG 路径 ======

    /** 解析 SVG 的 path d（支持 M/L/H/V/C/Z 及小写相对命令） */
    private static Path2D parsePath(String data) {
        Path2D.Float path = new Path2D.Float(Path2D.WIND_EVEN_ODD);

        String s = data.replace(',', ' ').trim();
        double[] args = new double[8];
        double x = 0.0;
        double y = 0.0;
        double startX = 0.0;
        double startY = 0.0;
        char command = 0;
        int pos = 0;

        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isLetter(c)) {
                command = c;
                pos++;
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
                continue;
            }

            int count = argCount(command);
            if (count == 0) {
                path.closePath();
                x = startX;
                y = startY;
                continue;
            }
            if (count < 0) throw new IllegalArgumentException("不支持的 SVG 命令: " + command);

            int[] cursor = {pos};
            for (int i = 0; i < count; i++) args[i] = readNumber(s, cursor);
            pos = cursor[0];

            boolean relative = Character.isLowerCase(command);
            switch (Character.toUpperCase(command)) {
                case 'M' -> {
                    x = relative ? x + args[0] : args[0];
                    y = relative ? y + args[1] : args[1];
                    startX = x;
                    startY = y;
                    path.moveTo(x, y);
                }
                case 'L' -> {
                    x = relative ? x + args[0] : args[0];
                    y = relative ? y + args[1] : args[1];
                    path.lineTo(x, y);
                }
                case 'H' -> {
                    x = relative ? x + args[0] : args[0];
                    path.lineTo(x, y);
                }
                case 'V' -> {
                    y = relative ? y + args[0] : args[0];
                    path.lineTo(x, y);
                }
                case 'C' -> {
                    double x1 = relative ? x + args[0] : args[0];
                    double y1 = relative ? y + args[1] : args[1];
                    double x2 = relative ? x + args[2] : args[2];
                    double y2 = relative ? y + args[3] : args[3];
                    x = relative ? x + args[4] : args[4];
                    y = relative ? y + args[5] : args[5];
                    path.curveTo(x1, y1, x2, y2, x, y);
                }
                case 'Z' -> {
                    path.closePath();
                    x = startX;
                    y = startY;
                }
                default -> throw new IllegalArgumentException("不支持的 SVG 命令: " + command);
            }

            // 一个命令后面跟多组数字 = 重复该命令（M 之后是 L）
            if (command == 'M') command = 'L';
            else if (command == 'm') command = 'l';
        }

        return path;
    }

    private static int argCount(char command) {
        return switch (Character.toUpperCase(command)) {
            case 'M', 'L' -> 2;
            case 'H', 'V' -> 1;
            case 'C' -> 6;
            case 'Z' -> 0;
            default -> -1;
        };
    }

    private static double readNumber(String s, int[] cursor) {
        int i = cursor[0];
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;

        int start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }

        if (start == i) throw new IllegalArgumentException("SVG 路径里有读不出来的数字: " + s.substring(start));

        cursor[0] = i;
        return Double.parseDouble(s.substring(start, i));
    }

    /** 路径总长（和 BasicStroke 一样按折线估算） */
    private static double pathLength(Path2D path) {
        double length = 0.0;
        double lastX = 0.0;
        double lastY = 0.0;
        double startX = 0.0;
        double startY = 0.0;
        double[] segment = new double[6];

        PathIterator iterator = new FlatteningPathIterator(path.getPathIterator(null), 0.25);
        while (!iterator.isDone()) {
            switch (iterator.currentSegment(segment)) {
                case PathIterator.SEG_MOVETO -> {
                    lastX = segment[0];
                    lastY = segment[1];
                    startX = lastX;
                    startY = lastY;
                }
                case PathIterator.SEG_LINETO -> {
                    length += Math.hypot(segment[0] - lastX, segment[1] - lastY);
                    lastX = segment[0];
                    lastY = segment[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    length += Math.hypot(startX - lastX, startY - lastY);
                    lastX = startX;
                    lastY = startY;
                }
                default -> {
                }
            }
            iterator.next();
        }

        return length;
    }
}
