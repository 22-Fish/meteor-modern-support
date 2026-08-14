package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.ElytraFlySupport;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Meteor 官方「鞘翅飞行」（ElytraFly）模块增强 mixin
 *
 * <p>不新建模块、不新建模式选项：通过 {@link MixinElytraFlightModes} 把「甲飞/合法平飞」
 * 追加进官方模式列表（flightMode），本 mixin 只在官方模式为这两个值时
 * 接管官方事件处理器（跳过官方 Vanilla/Packet 等逻辑），
 * 并添加配套的小设置分组（甲飞/Grim/合法平飞/悬停），
 * 具体业务逻辑在 {@link ElytraFlySupport} 中。
 *
 * <p>官方 ElytraFly 已有的事件方法：onPlayerMove / onPreTick / onTick / onPacketSend / onPacketReceive，
 * 全部注入 HEAD 拦截；onActivate / onDeactivate 追加初始化/清理。
 */
@Mixin(value = ElytraFly.class, remap = false)
public abstract class MixinElytraFly {

    /** 官方模式设置（甲飞/合法平飞为追加值，按 name 判断） */
    @Shadow
    public Setting<ElytraFlightModes> flightMode;

    @Unique
    private Setting<ElytraFlySupport.ArmorMode> armorMode;

    @Unique
    private Setting<Boolean> muteSounds;

    @Unique
    private Setting<Boolean> moveToHotbar;

    @Unique
    private Setting<Boolean> firework;

    @Unique
    private Setting<Integer> fireworkDelay;

    @Unique
    private Setting<Integer> grimDelay;

    @Unique
    private Setting<Integer> correctionBackoff;

    @Unique
    private Setting<Boolean> autoFirework;

    @Unique
    private Setting<Boolean> autoSwapElytra;

    @Unique
    private Setting<Boolean> backpackFirework;

    @Unique
    private Setting<BackpackUse.Mode> backpackMode;

    @Unique
    private Setting<Integer> fwPriorityLv1;

    @Unique
    private Setting<Integer> fwPriorityLv2;

    @Unique
    private Setting<Integer> fwPriorityLv3;

    @Unique
    private Setting<Boolean> discardMomentum;

    @Unique
    private Setting<Boolean> freezeFirework;

    @Unique
    private Setting<Integer> fwIntervalLv1;

    @Unique
    private Setting<Integer> fwIntervalLv2;

    @Unique
    private Setting<Integer> fwIntervalLv3;

    @Unique
    private Setting<ElytraFlySupport.HoverMode> hoverMode;

    @Unique
    private Setting<Boolean> notGlidingUnfreeze;

    @Unique
    private Setting<Boolean> hoverFirework;

    @Unique
    private Setting<Integer> hoverFwIntervalLv1;

    @Unique
    private Setting<Integer> hoverFwIntervalLv2;

    @Unique
    private Setting<Integer> hoverFwIntervalLv3;

    // ====== 模式判断（追加的枚举值在编译期不可见，用 name 判断） ======

    @Unique
    private boolean isArmorMode() {
        return flightMode.get().name().equals("Armor");
    }

    @Unique
    private boolean isLegalMode() {
        return flightMode.get().name().equals("Legal");
    }

    @Unique
    private boolean isCustomMode() {
        return isArmorMode() || isLegalMode();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        ElytraFly self = (ElytraFly) (Object) this;

        // 官方其他模式的设置在甲飞/合法平飞下无意义，先隐藏（保留官方「模式」设置本身）
        hideOfficialSettings(self);

        // ====== 甲飞设置 ======
        SettingGroup sgArmor = self.settings.createGroup("甲飞");

        armorMode = sgArmor.add(new EnumSetting.Builder<ElytraFlySupport.ArmorMode>()
            .name("甲飞方式")
            .description("普通：每 tick 闪换 + 本地强制滑翔，适合原版服务器")
            .defaultValue(ElytraFlySupport.ArmorMode.Normal)
            .visible(this::isArmorMode)
            .build()
        );

        muteSounds = sgArmor.add(new BoolSetting.Builder()
            .name("静音")
            .description("屏蔽换装音效")
            .defaultValue(true)
            .visible(this::isArmorMode)
            .build()
        );

        moveToHotbar = sgArmor.add(new BoolSetting.Builder()
            .name("背包鞘翅自动挪热栏")
            .description("鞘翅不在热栏时，自动把背包里的鞘翅挪到热栏第 9 格（与原物品互换）。")
            .defaultValue(true)
            .visible(this::isArmorMode)
            .build()
        );

        // ====== Grim 设置 ======
        SettingGroup sgGrim = self.settings.createGroup("Grim");

        firework = sgGrim.add(new BoolSetting.Builder()
            .name("烟花")
            .description("Grim 模式：每次换装起飞后立即使用烟花加速")
            .defaultValue(true)
            .visible(() -> isArmorMode() && armorMode.get() == ElytraFlySupport.ArmorMode.Grim)
            .build()
        );

        fireworkDelay = sgGrim.add(new IntSetting.Builder()
            .name("烟花间隔")
            .description("Grim 模式：两次烟花之间的最小间隔（毫秒）。")
            .defaultValue(1000)
            .min(0)
            .max(20000)
            .visible(() -> isArmorMode() && armorMode.get() == ElytraFlySupport.ArmorMode.Grim && firework.get())
            .build()
        );

        grimDelay = sgGrim.add(new IntSetting.Builder()
            .name("换装间隔")
            .description("Grim 模式：每隔多少 tick 执行一次换装起飞链（0 = 每 tick）。减少服务器状态震荡，回弹时调大。")
            .defaultValue(0)
            .min(0)
            .max(20)
            .visible(() -> isArmorMode() && armorMode.get() == ElytraFlySupport.ArmorMode.Grim)
            .build()
        );

        correctionBackoff = sgGrim.add(new IntSetting.Builder()
            .name("回弹退避")
            .description("Grim 模式：收到服务器位置纠正（回弹）后暂停换装多少 tick，避免继续震荡。")
            .defaultValue(6)
            .min(0)
            .max(40)
            .visible(() -> isArmorMode() && armorMode.get() == ElytraFlySupport.ArmorMode.Grim)
            .build()
        );

        // ====== 合法平飞：飞行配置 ======
        SettingGroup sgLegal = self.settings.createGroup("合法平飞");

        autoFirework = sgLegal.add(new BoolSetting.Builder()
            .name("自动烟花")
            .description("飞行中自动释放烟花加速")
            .defaultValue(true)
            .visible(this::isLegalMode)
            .build()
        );

        autoSwapElytra = sgLegal.add(new BoolSetting.Builder()
            .name("自动替换鞘翅")
            .description("空中按跳跃键自动换上鞘翅起飞落地自动换回胸甲。")
            .defaultValue(false)
            .visible(this::isLegalMode)
            .build()
        );

        backpackFirework = sgLegal.add(new BoolSetting.Builder()
            .name("背包烟花")
            .description("自动烟花允许使用背包中的烟花")
            .defaultValue(false)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        backpackMode = sgLegal.add(new EnumSetting.Builder<BackpackUse.Mode>()
            .name("背包使用模式")
            .description("背包烟花的交换发包模式。1p：SWAP 2包;2p：PICKUP 4 包。除特殊原因，请使用2p更稳定")
            .defaultValue(BackpackUse.Mode.PICKUP)
            .visible(() -> isLegalMode() && autoFirework.get() && backpackFirework.get())
            .build()
        );

        fwPriorityLv1 = sgLegal.add(new IntSetting.Builder()
            .name("1级烟花优先级")
            .description("1 级烟花的优先级，优先级高的烟花优先使用")
            .defaultValue(1)
            .min(1)
            .max(3)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwPriorityLv2 = sgLegal.add(new IntSetting.Builder()
            .name("2级烟花优先级")
            .description("2 级烟花的优先级，优先级高的烟花优先使用")
            .defaultValue(1)
            .min(1)
            .max(3)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwPriorityLv3 = sgLegal.add(new IntSetting.Builder()
            .name("3级烟花优先级")
            .description("3 级烟花的优先级，优先级高的烟花优先使用")
            .defaultValue(1)
            .min(1)
            .max(3)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwIntervalLv1 = sgLegal.add(new IntSetting.Builder()
            .name("1级烟花间隔")
            .description("1 级烟花的释放间隔（tick）")
            .defaultValue(30)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwIntervalLv2 = sgLegal.add(new IntSetting.Builder()
            .name("2级烟花间隔")
            .description("2 级烟花的释放间隔（tick）")
            .defaultValue(40)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwIntervalLv3 = sgLegal.add(new IntSetting.Builder()
            .name("3级烟花间隔")
            .description("3 级烟花的释放间隔（tick）")
            .defaultValue(50)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        // ====== 合法平飞：悬停配置 ======
        SettingGroup sgHover = self.settings.createGroup("悬停");

        hoverMode = sgHover.add(new EnumSetting.Builder<ElytraFlySupport.HoverMode>()
            .name("悬停模式")
            .description("不输入时如何悬停。悬停：直接浮在原地，不推荐。|冻结：开启冻结模块的效果（完全静止，不发位置移动包），推荐")
            .defaultValue(ElytraFlySupport.HoverMode.Hover)
            .visible(this::isLegalMode)
            .build()
        );

        hoverFirework = sgHover.add(new BoolSetting.Builder()
            .name("悬停时自动烟花")
            .description("悬停期间按间隔静默释放快捷栏烟花，仅为保持滑翔状态正常防止反作弊拦截，不影响悬停。")
            .defaultValue(false)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover)
            .build()
        );

        hoverFwIntervalLv1 = sgHover.add(new IntSetting.Builder()
            .name("悬停1级烟花间隔")
            .description("悬停时 1 级烟花的释放间隔（tick）")
            .defaultValue(30)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
            .build()
        );

        hoverFwIntervalLv2 = sgHover.add(new IntSetting.Builder()
            .name("悬停2级烟花间隔")
            .description("悬停时 2 级烟花的释放间隔（tick）")
            .defaultValue(40)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
            .build()
        );

        hoverFwIntervalLv3 = sgHover.add(new IntSetting.Builder()
            .name("悬停3级烟花间隔")
            .description("悬停时 3 级烟花的释放间隔（tick）")
            .defaultValue(50)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
            .build()
        );

        discardMomentum = sgHover.add(new BoolSetting.Builder()
            .name("丢弃动量")
            .description("勾选后冻结清空玩家动量，解除冻结后动量清零。反作弊不拦截情况下推荐开启，提示飞行精确度")
            .defaultValue(false)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Freeze)
            .build()
        );

        freezeFirework = sgHover.add(new BoolSetting.Builder()
            .name("冻结烟花")
            .description("冻结期间冻结烟花使用，解冻后继续使用\"还未使用完\"的烟花")
            .defaultValue(false)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Freeze)
            .build()
        );

        notGlidingUnfreeze = sgHover.add(new BoolSetting.Builder()
            .name("不在滑翔解冻")
            .description("不在滑翔状态时立即解除冻结")
            .defaultValue(false)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Freeze)
            .build()
        );

        // 注入设置引用到支持类
        ElytraFlySupport.flightMode = flightMode;
        ElytraFlySupport.armorMode = armorMode;
        ElytraFlySupport.muteSounds = muteSounds;
        ElytraFlySupport.moveToHotbar = moveToHotbar;
        ElytraFlySupport.firework = firework;
        ElytraFlySupport.fireworkDelay = fireworkDelay;
        ElytraFlySupport.grimDelay = grimDelay;
        ElytraFlySupport.correctionBackoff = correctionBackoff;
        ElytraFlySupport.autoFirework = autoFirework;
        ElytraFlySupport.autoSwapElytra = autoSwapElytra;
        ElytraFlySupport.backpackFirework = backpackFirework;
        ElytraFlySupport.backpackMode = backpackMode;
        ElytraFlySupport.fwPriorityLv1 = fwPriorityLv1;
        ElytraFlySupport.fwPriorityLv2 = fwPriorityLv2;
        ElytraFlySupport.fwPriorityLv3 = fwPriorityLv3;
        ElytraFlySupport.discardMomentum = discardMomentum;
        ElytraFlySupport.freezeFirework = freezeFirework;
        ElytraFlySupport.fwIntervalLv1 = fwIntervalLv1;
        ElytraFlySupport.fwIntervalLv2 = fwIntervalLv2;
        ElytraFlySupport.fwIntervalLv3 = fwIntervalLv3;
        ElytraFlySupport.hoverMode = hoverMode;
        ElytraFlySupport.notGlidingUnfreeze = notGlidingUnfreeze;
        ElytraFlySupport.hoverFirework = hoverFirework;
        ElytraFlySupport.hoverFwIntervalLv1 = hoverFwIntervalLv1;
        ElytraFlySupport.hoverFwIntervalLv2 = hoverFwIntervalLv2;
        ElytraFlySupport.hoverFwIntervalLv3 = hoverFwIntervalLv3;
    }

    /**
     * 隐藏官方其他模式的设置（甲飞/合法平飞接管时无意义），保留官方「模式」设置本身。
     * Setting.visible 是 private final 字段，Java 17+ 反射无法修改，用 Unsafe 直接写。
     */
    @Unique
    private void hideOfficialSettings(ElytraFly self) {
        try {
            Field visibleField = Setting.class.getDeclaredField("visible");
            visibleField.setAccessible(true);
            long offset = getUnsafe().objectFieldOffset(visibleField);

            for (SettingGroup group : self.settings) {
                for (Setting<?> setting : group) {
                    // 官方模式设置本身必须保留可见（否则切不回官方模式）
                    if (setting.name.equals("mode")) continue;

                    IVisible original = (IVisible) visibleField.get(setting);
                    getUnsafe().putObject(setting, offset, (IVisible) () -> !isCustomMode() && (original == null || original.isVisible()));
                }
            }
        } catch (Exception e) {
            // 隐藏失败不影响主功能
        }
    }

    @Unique
    private static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            return sun.misc.Unsafe.getUnsafe();
        }
    }

    // ====== 事件接管：官方模式为甲飞/合法平飞时跳过官方逻辑 ======

    @Inject(method = "onPreTick", at = @At("HEAD"), cancellable = true)
    private void onPreTick(TickEvent.Pre event, CallbackInfo ci) {
        if (!isCustomMode()) return;
        ElytraFlySupport.onTick();
        ci.cancel();
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void onTick(TickEvent.Post event, CallbackInfo ci) {
        if (!isCustomMode()) return;
        ci.cancel();
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void onPlayerMove(PlayerMoveEvent event, CallbackInfo ci) {
        if (!isCustomMode()) return;
        // 甲飞/合法平飞不干预移动包内的移动向量（合法平飞靠服务器视角 + 滑翔物理），跳过官方逻辑
        ci.cancel();
    }

    @Inject(method = "onPacketSend", at = @At("HEAD"), cancellable = true)
    private void onPacketSend(PacketEvent.Send event, CallbackInfo ci) {
        if (!isCustomMode()) return;
        ElytraFlySupport.onPacketSend(event);
        ci.cancel();
    }

    @Inject(method = "onPacketReceive", at = @At("HEAD"), cancellable = true)
    private void onPacketReceive(PacketEvent.Receive event, CallbackInfo ci) {
        if (!isCustomMode()) return;
        ElytraFlySupport.onPacketReceive(event);
        ci.cancel();
    }

    // ====== 生命周期：追加初始化/清理（官方逻辑保留） ======

    @Inject(method = "onActivate", at = @At("TAIL"))
    private void onActivate(CallbackInfo ci) {
        ElytraFlySupport.onActivate();
    }

    @Inject(method = "onDeactivate", at = @At("TAIL"))
    private void onDeactivate(CallbackInfo ci) {
        ElytraFlySupport.onDeactivate();
    }

    // ====== HUD 显示 ======

    @Inject(method = "getInfoString", at = @At("HEAD"), cancellable = true)
    private void onGetInfoString(CallbackInfoReturnable<String> cir) {
        if (isCustomMode()) {
            cir.setReturnValue(flightMode.get().toString());
        }
    }
}
