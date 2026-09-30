package fish22.modernsupport.modules;

import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.ElytraFlySupport;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.player.Player;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅自动拉升（独立模块，移动分类）
 *
 * <p>滑翔中一直把「服务器朝向」的俯仰角定成最佳爬升角，并按「烟花加速」当前的模式
 * 自动补烟花，一路顶速爬升（偏航角不动，别人定的方向 / 相机朝向照样）。
 *
 * <p><b>俯仰角</b>（{@link ElytraFlySupport#pullupPitch(float)}）：默认跟随「烟花加速」的
 * 重缩放算法 —— V1 36°、V2 54.5°、V3 用最佳爬升角（45°~60°，随朝向变）；
 * 「重缩放」没开时按 V3 那套算。也可以切成固定角度。
 *
 * <p><b>定角方式</b>：走合法转头 API（只改发出去的朝向，视角不动），
 * 调用点和烟花加速同一处（移动运算之前），所以加速方向、移动方向、发包朝向三者一致。
 * 转角的优先级跟「合法转头API配置」的默认优先级一样，被更高优先级的模块顶掉时这一 tick 不拉升。
 *
 * <p><b>自动烟花</b>：和「鞘翅飞行」的自动烟花同一套（释放时机、甲飞换装窗口延后、
 * 背包烟花都一样），只是不带「烟花优先级」选项 —— 等级按快捷栏第一个烟花的等级，
 * 其次最低可用等级。两边的自动烟花共用冷却与队列，同时开也不会同 tick 放两发。
 *
 * <p>单独按潜行键（没按跳跃）时不拉升：那是手动俯冲，让给「鞘翅飞行」/相机。
 */
public class ElytraAutoPullup extends Module {

    /** 俯仰角来源 */
    public enum PitchSource {
        FOLLOW("跟随烟花加速"),
        FIXED("固定角度");

        private final String displayName;

        PitchSource(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFirework = settings.createGroup("自动烟花");

    /** 俯仰角来源：跟烟花加速的重缩放算法，还是固定角度 */
    private final Setting<PitchSource> pitchSource = sgGeneral.add(new EnumSetting.Builder<PitchSource>()
        .name("俯仰角来源")
        .description("跟随烟花加速：按重缩放算法取最佳爬升角。固定角度：一直用下面那个角度")
        .defaultValue(PitchSource.FOLLOW)
        .build()
    );

    /** 固定俯仰角（抬头多少度） */
    private final Setting<Double> fixedPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("固定俯仰角")
        .description("抬头多少度（越大越接近垂直）")
        .defaultValue(54.5)
        .range(1.0, 90.0)
        .sliderRange(1.0, 90.0)
        .visible(() -> pitchSource.get() == PitchSource.FIXED)
        .build()
    );

    /** 自动烟花（和鞘翅飞行的自动烟花同一套，不带优先级选项） */
    private final Setting<Boolean> autoFirework = sgFirework.add(new BoolSetting.Builder()
        .name("自动烟花")
        .description("拉升时自动补烟花，掉了就放")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> backpackFirework = sgFirework.add(new BoolSetting.Builder()
        .name("背包烟花")
        .description("自动烟花允许使用背包中的烟花")
        .defaultValue(false)
        .visible(autoFirework::get)
        .build()
    );

    private final Setting<BackpackUse.Mode> backpackMode = sgFirework.add(new EnumSetting.Builder<BackpackUse.Mode>()
        .name("背包使用发包")
        .description("背包烟花的交换发包方式。SWAP：2 次 SWAP 点击（背包槽与目标格互换）；PICKUP：4 次 PICKUP 点击（走光标，背包满也能换）")
        .defaultValue(BackpackUse.Mode.SWAP)
        .visible(() -> autoFirework.get() && backpackFirework.get())
        .build()
    );

    private final Setting<BackpackUse.TargetSlot> backpackTarget = sgFirework.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
        .name("目标槽位")
        .description("背包烟花换到哪一格使用。副手：换到副手使用（不碰手上那一格）；主手：换到手持那一格；快捷栏：换到除手持那一格以外的一个快捷栏格")
        .defaultValue(BackpackUse.TargetSlot.OFFHAND)
        .visible(() -> autoFirework.get() && backpackFirework.get())
        .build()
    );

    private final Setting<Integer> fwIntervalLv1 = sgFirework.add(new IntSetting.Builder()
        .name("1级烟花间隔")
        .description("1 级烟花的释放间隔（tick）")
        .defaultValue(30)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(autoFirework::get)
        .build()
    );

    private final Setting<Integer> fwIntervalLv2 = sgFirework.add(new IntSetting.Builder()
        .name("2级烟花间隔")
        .description("2 级烟花的释放间隔（tick）")
        .defaultValue(40)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(autoFirework::get)
        .build()
    );

    private final Setting<Integer> fwIntervalLv3 = sgFirework.add(new IntSetting.Builder()
        .name("3级烟花间隔")
        .description("3 级烟花的释放间隔（tick）")
        .defaultValue(50)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(autoFirework::get)
        .build()
    );

    public ElytraAutoPullup() {
        super(Categories.Movement, "鞘翅自动拉升",
            "有烟花加速时自动定在最佳爬升角，并自动补烟花一路爬升");

        // 自动烟花的背包设置交给 ElytraFlySupport（延后释放时要用）
        ElytraFlySupport.pullupBackpackFirework = backpackFirework;
        ElytraFlySupport.pullupBackpackMode = backpackMode;
        ElytraFlySupport.pullupBackpackTarget = backpackTarget;
        ElytraFlySupport.pullupFwIntervalLv1 = fwIntervalLv1;
        ElytraFlySupport.pullupFwIntervalLv2 = fwIntervalLv2;
        ElytraFlySupport.pullupFwIntervalLv3 = fwIntervalLv3;
    }

    @Override
    public void onDeactivate() {
        // 关模块清掉还排着队的那一发（和鞘翅飞行的自动烟花共用队列，见 ElytraFlySupport）
        ElytraFlySupport.cancelPendingAutoFirework();
    }

    /** 自动烟花：滑翔中按间隔补烟花，释放时机和鞘翅飞行的自动烟花一样 */
    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (Freeze.isFrozen() || !ElytraFlySupport.isGlidingNow()) return;
        if (autoFirework.get()) ElytraFlySupport.tickPullupFirework();
    }

    /**
     * 移动运算之前定角（挂点：{@link fish22.modernsupport.mixin.MixinPlayerTravel} 的 travel 入口、
     * {@link ElytraFlySupport#travelAsElytra(Player)} 的滑翔移动入口）。
     */
    public static void beforeMove(Player player) {
        ElytraAutoPullup module = Modules.get().get(ElytraAutoPullup.class);
        if (module == null || !module.isActive()) return;
        module.applyPullup(player);
    }

    private void applyPullup(Player player) {
        if (player == null || player != mc.player) return;
        if (Freeze.isFrozen() || !ElytraFlySupport.isGlidingNow()) return;
        // 潜行键单独按下 = 手动俯冲（甲飞里潜行本来就是往下飞），这一 tick 不抢
        if (mc.options.keyShift.isDown() && !mc.options.keyJump.isDown()) return;

        // 偏航角保持别人定的（甲飞方向 / 相机），只压俯仰；
        // 滑翔运算与烟花加速都用这份「服务器朝向」，所以方向、移动、发包三者一致
        float yaw = ElytraFlySupport.rotationToYaw(LegalRotation.getServerLook(mc.player));
        float pitch = pitchSource.get() == PitchSource.FIXED
            ? (float) -Math.abs(fixedPitch.get())
            : ElytraFlySupport.pullupPitch(yaw);
        LegalRotation.rotate(yaw, pitch, LegalRotation.Mode.QUIET, LegalRotation.defaultPriority());
    }
}
