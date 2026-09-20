package fish22.modernsupport.font;

/**
 * 字形灰阶调整: 把小字号下"发虚"的抗锯齿灰边压实一点
 *
 * <p>低分辨率屏上小字号的笔画只有 1~2 像素, 抗锯齿的灰边会把细笔画洗白, 看着就糊。
 * 这里在字形进图集之前对灰阶做一次单调曲线调整 (只改灰度, 不动形状),
 * 曲线是单调递增的, 所以线条只会更实, 不会断。
 *
 * <p>注意: toString() 会作为值写进配置, 改名会让老配置失效
 */
public enum FontSharpness {
    Off("关闭", 1.0),
    Light("轻微", 0.85),
    Strong("明显", 0.70);

    private final String label;
    private final int[] lut;

    FontSharpness(String label, double gamma) {
        this.label = label;
        this.lut = gamma == 1.0 ? null : build(gamma);
    }

    /** 256 项灰阶查表, 不需要调整时返回 null */
    public int[] lut() {
        return lut;
    }

    @Override
    public String toString() {
        return label;
    }

    private static int[] build(double gamma) {
        int[] table = new int[256];

        for (int i = 0; i < table.length; i++) {
            table[i] = (int) Math.round(255 * Math.pow(i / 255.0, gamma));
        }

        return table;
    }
}
