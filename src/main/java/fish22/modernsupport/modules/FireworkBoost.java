package fish22.modernsupport.modules;

import fish22.modernsupport.utils.ElytraFlySupport;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 烟花加速（独立模块，移动分类）
 *
 * <p>使用自己的烟花时，把玩家速度设为「本 tick 移动运算实际使用的朝向 × 加速值」
 * （史莱姆 mod rocketBoost 的原逻辑，运算实现在 {@link ElytraFlySupport}）。
 *
 * <p><b>方向取实际运算朝向</b>：本模块在「移动运算之前」应用
 * （{@link fish22.modernsupport.mixin.MixinPlayerTravel} 的 travel 入口、
 * 甲飞的滑翔移动入口），那时合法平飞/甲飞伪造的目标朝向已经生效——
 * 既不是相机（视觉）朝向，也不依赖任何服务端记账朝向，
 * 只发包转头的模块也不会受影响。
 *
 * <p>生效条件：自己身上有活跃烟花 + 正在滑翔（甲飞看「服务器认滑翔」的窗口）。
 *
 * <p><b>重缩放算法 V3</b>（史莱姆的 {@code applyAxisLimit3}）：盒子照抄 Grim 的烟花盒
 * （当前朝向 + 上一 tick 朝向两个 look 相加，逐轴 ±范围封顶），然后把速度逐轴顶到盒子边界 ——
 * 垂直与水平能同时吃满，所以爬升极快。抬头比最佳爬升角更陡时还会把「服务器朝向」的俯仰
 * 压回最佳角（视角不动），运算都在 {@link ElytraFlySupport} 里。
 */
public class FireworkBoost extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 烟花加速时每 tick 的速度（史莱姆 mod 默认 1.7） */
    private final Setting<Double> boostSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("加速值")
        .description("烟花加速时每 tick 的速度。")
        .defaultValue(1.7)
        .range(0.0, 10000.0)
        .noSlider()
        .build()
    );

    /** 史莱姆的 auto-rescale-firework-box */
    private final Setting<Boolean> fireworkRescale = sgGeneral.add(new BoolSetting.Builder()
        .name("自动重缩放")
        .description("按 Grim 三轴运动量限制压缩烟花加速")
        .defaultValue(false)
        .build()
    );

    /** 史莱姆的 auto-rescale-firework-amount */
    private final Setting<Double> fireworkRescaleAmount = sgGeneral.add(new DoubleSetting.Builder()
        .name("重缩放范围")
        .description("三轴允许运动的基准范围。")
        .defaultValue(1.65)
        .range(0.0, 10000.0)
        .noSlider()
        .visible(fireworkRescale::get)
        .build()
    );

    /** 史莱姆的 auto-rescale-firework-al */
    private final Setting<ElytraFlySupport.FireworkRescaleAlgorithm> fireworkRescaleAlgorithm =
        sgGeneral.add(new EnumSetting.Builder<ElytraFlySupport.FireworkRescaleAlgorithm>()
            .name("重缩放算法")
            .description("V1：按当前/上一视角和重力限制。V2：再叠加滑翔模拟与前一 tick 真实移动速度。"
                + "V3：逐轴顶满 Grim 的烟花盒子（爬升最快）")
            .defaultValue(ElytraFlySupport.FireworkRescaleAlgorithm.V1)
            .visible(fireworkRescale::get)
            .build()
        );

    /** 史莱姆的 auto-rescale-firework-anti-lag-threshold（V3 专用） */
    private final Setting<Double> fireworkRescaleV3Margin = sgGeneral.add(new DoubleSetting.Builder()
        .name("V3盒子余量")
        .description("V3 专用：盒子两侧各收缩的余量（史莱姆的防延迟阈值），越大越保守")
        .defaultValue(0.002)
        .range(0.0, 1.0)
        .noSlider()
        .visible(() -> fireworkRescale.get()
            && fireworkRescaleAlgorithm.get() == ElytraFlySupport.FireworkRescaleAlgorithm.V3)
        .build()
    );

    /** 史莱姆的 auto-rescale-axis-zero-point-three（V3 专用） */
    private final Setting<Double> fireworkRescaleV3ExtraY = sgGeneral.add(new DoubleSetting.Builder()
        .name("V3垂直额外补偿")
        .description("V3 专用：近乎垂直抬升时给 Y 轴额外放宽的量（史莱姆的 0.03）")
        .defaultValue(0.03)
        .range(0.0, 1.0)
        .noSlider()
        .visible(() -> fireworkRescale.get()
            && fireworkRescaleAlgorithm.get() == ElytraFlySupport.FireworkRescaleAlgorithm.V3)
        .build()
    );

    /** 史莱姆的 auto-rescale-best-climbing-speed（V3 专用） */
    private final Setting<Boolean> fireworkRescaleV3BestAngle = sgGeneral.add(new BoolSetting.Builder()
        .name("V3爬升角校正")
        .description("V3 专用：抬头比最佳爬升角还陡时把服务器朝向的俯仰压回最佳角（视角不动），"
            + "垂直照样吃满、水平也能吃满")
        .defaultValue(true)
        .visible(() -> fireworkRescale.get()
            && fireworkRescaleAlgorithm.get() == ElytraFlySupport.FireworkRescaleAlgorithm.V3)
        .build()
    );

    /** 史莱姆的 auto-rescale-axis-zero-point-three（Y 轴分支） */
    private final Setting<Double> fireworkRescaleExtraY = sgGeneral.add(new DoubleSetting.Builder()
        .name("Y轴额外补偿")
        .description("V2 在垂直方向补入的额外滑翔模拟余量")
        .defaultValue(0.00)
        .range(0.0, 10000.0)
        .noSlider()
        .visible(() -> fireworkRescale.get()
            && fireworkRescaleAlgorithm.get() == ElytraFlySupport.FireworkRescaleAlgorithm.V2)
        .build()
    );

    /** 史莱姆的 auto-rescale-axis-zero-point-three（XZ 轴分支） */
    private final Setting<Double> fireworkRescaleExtraXZ = sgGeneral.add(new DoubleSetting.Builder()
        .name("XZ轴额外补偿")
        .description("V2 在水平方向补入的额外滑翔模拟余量")
        .defaultValue(0.00)
        .range(0.0, 10000.0)
        .noSlider()
        .visible(() -> fireworkRescale.get()
            && fireworkRescaleAlgorithm.get() == ElytraFlySupport.FireworkRescaleAlgorithm.V2)
        .build()
    );

    /** 史莱姆的 firework-boost-use-rescale */
    private final Setting<Boolean> fireworkBoostUseRescale = sgGeneral.add(new BoolSetting.Builder()
        .name("烟花加速使用重缩放")
        .description("烟花加速后套用自动重缩放")
        .defaultValue(false)
        .visible(fireworkRescale::get)
        .build()
    );

    /** 渲染组：玩家底下画角度指示（X = 最佳水平飞行角度，半根 = 当前偏航） */
    private final SettingGroup sgRender = settings.createGroup("渲染");

    /** 渲染总开关 */
    private final Setting<Boolean> renderEnabled = sgRender.add(new BoolSetting.Builder()
        .name("渲染")
        .description("在玩家下方渲染角度指示。")
        .defaultValue(true)
        .build()
    );

    /** 当前偏航那条线的开关 */
    private final Setting<Boolean> renderCurrentYaw = sgRender.add(new BoolSetting.Builder()
        .name("玩家偏航渲染")
        .description("渲染当前偏航那条线（从玩家位置朝正前方画半根，后段不画）。")
        .defaultValue(true)
        .visible(renderEnabled::get)
        .build()
    );

    /** 最佳水平飞行角度那两根线的颜色 */
    private final Setting<SettingColor> bestAngleColor = sgRender.add(new ColorSetting.Builder()
        .name("最佳加速角度颜色")
        .description("指向最佳水平飞行角度（当前偏航所在象限的两根 45° 斜向，V3 逐轴盒子能吃到最大水平速度）的那两根线。")
        .defaultValue(new SettingColor(255, 0, 0, 120))
        .visible(renderEnabled::get)
        .build()
    );

    /** 当前偏航那条线的颜色 */
    private final Setting<SettingColor> currentYawColor = sgRender.add(new ColorSetting.Builder()
        .name("当前偏航角度颜色")
        .description("指向当前偏航（烟花加速实际用的那份朝向）的那条线。")
        .defaultValue(new SettingColor(0, 255, 255, 120))
        .visible(renderEnabled::get)
        .build()
    );

    /** 每条线的长度（格） */
    private final Setting<Double> renderLength = sgRender.add(new DoubleSetting.Builder()
        .name("渲染长度")
        .description("每条线的长度（格）。")
        .defaultValue(10.0)
        .range(1.0, 64.0)
        .sliderRange(1.0, 32.0)
        .visible(renderEnabled::get)
        .build()
    );

    /** 每条线的宽度（格） */
    private final Setting<Double> renderWidth = sgRender.add(new DoubleSetting.Builder()
        .name("渲染宽度")
        .description("每条线的宽度（格）。")
        .defaultValue(0.2)
        .range(0.02, 2.0)
        .sliderRange(0.02, 1.0)
        .visible(renderEnabled::get)
        .build()
    );

    /** 渲染高度相对玩家的偏移 */
    private final Setting<Double> renderYOffset = sgRender.add(new DoubleSetting.Builder()
        .name("Y偏移")
        .description("渲染高度相对玩家的偏移（-5 = 玩家下方 5 格）。")
        .defaultValue(-5.0)
        .range(-16.0, 16.0)
        .sliderRange(-12.0, 4.0)
        .visible(renderEnabled::get)
        .build()
    );

    /** 同一 tick 只应用一次（甲飞模式下有两个移动入口会调用） */
    private int lastAppliedTick = -1;

    public FireworkBoost() {
        super(Categories.Movement, "烟花加速",
            "使用自己的烟花时，把速度设为实际移动朝向 × 加速值");

        // 把设置引用交给运算实现（史莱姆原逻辑在 ElytraFlySupport 中，数值不做任何改动）
        ElytraFlySupport.fireworkBoostSpeed = boostSpeed;
        ElytraFlySupport.fireworkRescale = fireworkRescale;
        ElytraFlySupport.fireworkRescaleAmount = fireworkRescaleAmount;
        ElytraFlySupport.fireworkRescaleAlgorithm = fireworkRescaleAlgorithm;
        ElytraFlySupport.fireworkRescaleExtraY = fireworkRescaleExtraY;
        ElytraFlySupport.fireworkRescaleExtraXZ = fireworkRescaleExtraXZ;
        ElytraFlySupport.fireworkBoostUseRescale = fireworkBoostUseRescale;
        ElytraFlySupport.fireworkRescaleV3Margin = fireworkRescaleV3Margin;
        ElytraFlySupport.fireworkRescaleV3ExtraY = fireworkRescaleV3ExtraY;
        ElytraFlySupport.fireworkRescaleV3BestAngle = fireworkRescaleV3BestAngle;
    }

    @Override
    public void onActivate() {
        lastAppliedTick = -1;
        ElytraFlySupport.resetFireworkRescaleState();
    }

    @Override
    public void onDeactivate() {
        lastAppliedTick = -1;
        ElytraFlySupport.resetFireworkRescaleState();
    }

    /** 重缩放要用「真正发出去的包」记账（取消的包不记） */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        ElytraFlySupport.captureFireworkRescalePacket(event);
    }

    /** 服务器位置纠正后清零上一 tick 真实移动（史莱姆的 SET_BACK 分支） */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        ElytraFlySupport.captureFireworkRescaleReceive(event);
    }

    /**
     * 玩家底下画角度指示：红线画成 X = 最佳水平飞行角度，青线半根 = 当前偏航。
     *
     * <p>青线 = 当前偏航（和烟花加速实际运算用的那份朝向一致：合法转头/甲飞时不是相机）。
     * 从玩家位置朝正前方画半根，后段不画。
     *
     * <p>红线 = 最佳水平飞行角度，取当前偏航所在象限的两根 45° 斜向（画成 X）：
     * V3 的盒子逐轴独立，斜 45° 飞时 X 与 Z 两根轴能各自顶到 1.65，水平速度是沿轴飞时的
     * √2 倍（沿轴飞时另一根水平轴只有反 tick 跳过那点余量）。两根斜线都是最优，
     * 往离当前偏航近的那根转就行。
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderEnabled.get()) return;
        if (mc.player == null) return;
        // 甲飞模式下鞘翅飞行模块关掉就不画（本地滑翔标志 / 滑翔窗口会是甲飞留下的残留）
        if (!ElytraFlySupport.shouldRenderFireworkGuide()) return;

        // 用 tickDelta 插值，和画面里的玩家位置同步
        double cx = Mth.lerp(event.tickDelta, mc.player.xOld, mc.player.getX());
        double cy = Mth.lerp(event.tickDelta, mc.player.yOld, mc.player.getY()) + renderYOffset.get();
        double cz = Mth.lerp(event.tickDelta, mc.player.zOld, mc.player.getZ());

        double len = renderLength.get();
        double halfWidth = renderWidth.get() / 2.0;

        // 青线：当前偏航，从玩家位置朝正前方半根
        float yaw = ElytraFlySupport.rotationToYaw(LegalRotation.getServerLook(mc.player));
        if (renderCurrentYaw.get()) {
            renderLine(event, cx, cy, cz, yaw, len, halfWidth, currentYawColor.get(), true);
        }

        // 红线：当前象限的两根 45° 斜向，以玩家为中心画成 X
        float axisYaw = Math.round(yaw / 90.0F) * 90.0F;
        renderLine(event, cx, cy, cz, axisYaw + 45.0F, len, halfWidth, bestAngleColor.get(), false);
        renderLine(event, cx, cy, cz, axisYaw - 45.0F, len, halfWidth, bestAngleColor.get(), false);
    }

    /**
     * 在 y 高度画一条水平带。
     *
     * @param frontHalf true = 从 (cx,cz) 朝朝向正前方画一根（后段不画）；
     *                  false = 以 (cx,cz) 为中心两头各画一半
     */
    private static void renderLine(Render3DEvent event, double cx, double cy, double cz,
                                   float yaw, double len, double halfWidth, SettingColor color,
                                   boolean frontHalf) {
        double rad = Math.toRadians(yaw);
        // 原版偏航 → 水平方向（yaw 0 = +Z，yaw 90 = -X）
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        // 水平面内的垂直方向（绕 Y 转 90°）
        double px = -dz;
        double pz = dx;

        double halfLen = len / 2.0;
        double ax = cx + dx * halfLen;
        double az = cz + dz * halfLen;
        double bx = frontHalf ? cx : cx - dx * halfLen;
        double bz = frontHalf ? cz : cz - dz * halfLen;

        event.renderer.quad(
            ax + px * halfWidth, cy, az + pz * halfWidth,
            ax - px * halfWidth, cy, az - pz * halfWidth,
            bx - px * halfWidth, cy, bz - pz * halfWidth,
            bx + px * halfWidth, cy, bz + pz * halfWidth,
            color
        );
    }

    /**
     * 移动运算之前应用烟花加速。
     *
     * <p>调用点：{@link fish22.modernsupport.mixin.MixinPlayerTravel}（原版 travel 入口，紧跟在
     * 合法转头强制朝向之后）与 {@link ElytraFlySupport#travelAsElytra(Player)}（甲飞的滑翔移动入口）。
     * 此时实体朝向就是本 tick 移动运算真正会用的朝向，所以加速方向不会和移动方向分叉。
     */
    public static void beforeMove(Player player) {
        FireworkBoost module = Modules.get().get(FireworkBoost.class);
        if (module == null || !module.isActive()) return;
        module.apply(player);
    }

    private void apply(Player player) {
        if (player == null || player != mc.player) return;
        if (player.tickCount == lastAppliedTick) return;
        lastAppliedTick = player.tickCount;

        ElytraFlySupport.updateFireworkRescaleState();
        ElytraFlySupport.applyFireworkBoost();
    }
}
