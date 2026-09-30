package fish22.modernsupport.modules;

import fish22.modernsupport.utils.ElytraFlySupport;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
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
