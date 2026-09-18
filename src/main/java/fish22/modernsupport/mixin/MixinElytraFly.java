package fish22.modernsupport.mixin;

import fish22.modernsupport.ModernSupport;
import fish22.modernsupport.modules.ElytraBounce;
import fish22.modernsupport.modules.ElytraPitch40;
import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.ElytraFlySupport;
import fish22.modernsupport.utils.InfiniteElytraSupport;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

/**
 * Meteor 官方「鞘翅飞行」（ElytraFly）模块增强 mixin
 *
 * <p>官方模式列表（{@link MixinElytraFlightModes}）只保留 关闭 / 原版 / 发包 / 合法，
 * 俯仰40 与 弹跳 已拆成独立模块（{@link ElytraPitch40} / {@link ElytraBounce}）。
 * 本 mixin 把模块设置界面整理成三大块：
 *
 * <pre>
 * 〔简单控制〕简单控制模式（关闭 / 原版 / 发包 / 合法，默认关闭）+ 官方原版/发包设置 + 合法模式设置（含悬停）
 * 〔甲飞〕    甲飞模式（关闭 / 普通 / Grim模式）+ 允许在岩浆中飞行 + 换装、音效、落地防摔等设置
 * 〔无限鞘翅〕无限鞘翅（照搬 AEfish）+ 周期 / 静音
 * </pre>
 *
 * <p>互斥关系：
 * <ul>
 *   <li>「关闭」（默认）↔ 不互斥：模块本身不做任何飞行控制，甲飞 / 无限鞘翅 照常可用；</li>
 *   <li>「原版」↔ 甲飞：<b>不互斥</b>。官方的原版控制照常跑（甲飞只是换装维持滑翔），
 *       方法开头那道「胸甲槽要有滑翔组件」的守卫由
 *       {@link #modernsupport$gliderForVanillaArmor} 放行；</li>
 *   <li>甲飞 ↔ 无限鞘翅（开一个自动关另一个）；</li>
 *   <li>发包模式 ↔ 甲飞 / 无限鞘翅（选发包会把这两个关掉，反之自动切回原版）。</li>
 * </ul>
 *
 * <p>具体业务逻辑在 {@link ElytraFlySupport} 与 {@link InfiniteElytraSupport}。
 * 官方 ElytraFly 的事件方法 onPlayerMove / onPreTick / onTick / onPacketSend / onPacketReceive
 * 在「合法」以及「甲飞且非原版」时注入 HEAD 拦截（原版 + 甲飞要留着官方那套控制）；
 * onActivate / onDeactivate 追加初始化/清理。
 */
@Mixin(value = ElytraFly.class, remap = false)
public abstract class MixinElytraFly {

    /** 官方模式设置（已改名为「简单控制模式」，值：原版/发包/合法） */
    @Shadow
    public Setting<ElytraFlightModes> flightMode;

    /** 官方模式设置改名后的内部名（配置键 / 翻译键都用它） */
    @Unique
    private static final String SIMPLE_MODE_SETTING = "simple-mode";

    @Unique
    private Setting<ElytraFlySupport.ArmorMode> armorMode;

    @Unique
    private Setting<Boolean> lavaFlight;

    @Unique
    private Setting<Boolean> muteSounds;

    @Unique
    private Setting<Boolean> spaceBlockInAir;

    @Unique
    private Setting<Boolean> landingNoFall;

    @Unique
    private Setting<ElytraFlySupport.LandingNoFallMode> landingNoFallMode;

    @Unique
    private Setting<Boolean> grimInputSequence;

    @Unique
    private Setting<Integer> armorSwapInterval;

    @Unique
    private Setting<Boolean> autoFirework;

    @Unique
    private Setting<Integer> legalRotationPriority;

    @Unique
    private Setting<Boolean> autoSwapElytra;

    @Unique
    private Setting<Boolean> backpackFirework;

    @Unique
    private Setting<BackpackUse.Mode> backpackMode;

    @Unique
    private Setting<BackpackUse.TargetSlot> backpackTarget;

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
    private Setting<ElytraFlySupport.AntiKickMode> hoverAntiKick;

    @Unique
    private Setting<Integer> hoverAntiKickInterval;

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

    /** 无限鞘翅总开关 */
    @Unique
    private Setting<Boolean> infiniteElytra;

    /** 无限鞘翅刷新周期（tick） */
    @Unique
    private Setting<Integer> infinitePeriod;

    /** 无限鞘翅静音 */
    @Unique
    private Setting<Boolean> infiniteMute;

    // ====== 模式判断（「合法」是追加的枚举值，编译期不可见，用 name 判断） ======

    @Unique
    private boolean isLegalMode() {
        return flightMode.get().name().equals("Legal");
    }

    /** 「简单控制模式」是否为「关闭」（模块不做任何飞行控制） */
    @Unique
    private boolean isSimpleControlOff() {
        return ElytraFlySupport.isSimpleControlOff();
    }

    /** 「甲飞模式」是否不为关闭 */
    @Unique
    private boolean isArmorMode() {
        return armorMode != null && armorMode.get() != ElytraFlySupport.ArmorMode.Off;
    }

    /** 官方逻辑是否被我们接管（合法 / 甲飞） */
    @Unique
    private boolean isCustomMode() {
        return isLegalMode() || isArmorMode();
    }

    /** 当前是不是官方「原版」模式（Meteor 官方那套飞行控制） */
    @Unique
    private boolean isVanillaMode() {
        return ElytraFlySupport.isVanillaMode();
    }

    /**
     * 官方那套飞行控制是否要拦下。
     *
     * <ul>
     *   <li>「关闭」：模块不做任何飞行控制，官方逻辑同样不跑；</li>
     *   <li>「合法」：方向控制由本模组接管（服务器视角 + 滑翔物理），官方逻辑不跑；</li>
     *   <li>「原版」：官方原版那套控制照常跑 —— <b>开着甲飞也照常跑</b>
     *       （原版 + 甲飞 不互斥：甲飞只管换装维持滑翔，飞行控制仍旧是官方原版那套）；</li>
     *   <li>「发包」：官方发包逻辑照常跑（发包与甲飞本就互斥）。</li>
     * </ul>
     */
    @Unique
    private boolean suppressOfficialLogic() {
        if (isSimpleControlOff()) return true;
        if (isLegalMode()) return true;
        // 甲飞：只有「原版」模式要留着官方逻辑（两者叠加），其余模式仍然拦下
        return isArmorMode() && !isVanillaMode();
    }

    /** 当前是不是「发包」模式 */
    @Unique
    private boolean isPacketMode() {
        return flightMode.get().name().equals("Packet");
    }

    // ====== 初始化 ======

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        ElytraFly self = (ElytraFly) (Object) this;

        // 1. 官方 General / Inventory / Autopilot 三块设置合并成「简单控制」块（默认分组改名 + 设置搬家）
        SettingGroup sgSimple = mergeOfficialGroups(self);

        // 2. 官方「模式」设置改名「简单控制模式」（可选值由 MixinElytraFlightModes 收敛为 关闭/原版/发包/合法）
        renameSetting(flightMode, SIMPLE_MODE_SETTING);
        filterFlightModeValues();

        // 2.5 简单控制默认「关闭」：官方构造时写死的默认值是「原版」，这里改成「关闭」
        applyDefaultOffMode();

        // 3. 隐藏官方其他模式的设置：必须在创建我们自己的设置之前调用，
        //    否则刚建好的设置也会被一起套上「非接管模式才可见」的条件
        hideOfficialSettings(self);

        // 4. 发包模式与（甲飞 / 无限鞘翅）互斥：官方模式设置的回调只能改写，别无入口
        wrapFlightModeOnChanged();

        // ====== 简单控制：合法模式设置（含悬停） ======

        legalRotationPriority = sgSimple.add(new IntSetting.Builder()
            .name("合法转头优先级")
            .description("合法转头的优先级")
            .defaultValue(0)
            .sliderRange(-20, 20)
            .visible(this::isLegalMode)
            .build()
        );

        autoFirework = sgSimple.add(new BoolSetting.Builder()
            .name("自动烟花")
            .description("飞行中自动释放烟花加速（与「悬停时自动烟花」相互独立）")
            .defaultValue(true)
            .onChanged(this::onAutoFireworkChanged)
            .visible(this::isLegalMode)
            .build()
        );

        autoSwapElytra = sgSimple.add(new BoolSetting.Builder()
            .name("自动替换鞘翅")
            .description("空中按跳跃键自动换上鞘翅起飞落地自动换回胸甲。")
            .defaultValue(false)
            .visible(this::isLegalMode)
            .build()
        );

        backpackFirework = sgSimple.add(new BoolSetting.Builder()
            .name("背包烟花")
            .description("自动烟花允许使用背包中的烟花")
            .defaultValue(false)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        backpackMode = sgSimple.add(new EnumSetting.Builder<BackpackUse.Mode>()
            .name("背包使用发包")
            .description("背包烟花的交换发包方式。SWAP：2 次 SWAP 点击（背包槽与目标格互换）；PICKUP：4 次 PICKUP 点击（走光标，背包满也能换）")
            .defaultValue(BackpackUse.Mode.SWAP)
            .visible(() -> isLegalMode() && autoFirework.get() && backpackFirework.get())
            .build()
        );

        backpackTarget = sgSimple.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
            .name("目标槽位")
            .description("背包烟花换到哪一格使用。副手：换到副手使用（不碰手上那一格）；主手：换到手持那一格；快捷栏：换到除手持那一格以外的一个快捷栏格（空手 > 工具 > 方块 > 物品）")
            .defaultValue(BackpackUse.TargetSlot.OFFHAND)
            .visible(() -> isLegalMode() && autoFirework.get() && backpackFirework.get())
            .build()
        );

        fwPriorityLv1 = sgSimple.add(new IntSetting.Builder()
            .name("1级烟花优先级")
            .description("1 级烟花的优先级，优先级高的烟花优先使用")
            .defaultValue(1)
            .min(1)
            .max(3)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwPriorityLv2 = sgSimple.add(new IntSetting.Builder()
            .name("2级烟花优先级")
            .description("2 级烟花的优先级，优先级高的烟花优先使用")
            .defaultValue(1)
            .min(1)
            .max(3)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwPriorityLv3 = sgSimple.add(new IntSetting.Builder()
            .name("3级烟花优先级")
            .description("3 级烟花的优先级，优先级高的烟花优先使用")
            .defaultValue(1)
            .min(1)
            .max(3)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwIntervalLv1 = sgSimple.add(new IntSetting.Builder()
            .name("1级烟花间隔")
            .description("1 级烟花的释放间隔（tick）")
            .defaultValue(30)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwIntervalLv2 = sgSimple.add(new IntSetting.Builder()
            .name("2级烟花间隔")
            .description("2 级烟花的释放间隔（tick）")
            .defaultValue(40)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        fwIntervalLv3 = sgSimple.add(new IntSetting.Builder()
            .name("3级烟花间隔")
            .description("3 级烟花的释放间隔（tick）")
            .defaultValue(50)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && autoFirework.get())
            .build()
        );

        // ----- 悬停（合法模式的不输入行为） -----

        hoverMode = sgSimple.add(new EnumSetting.Builder<ElytraFlySupport.HoverMode>()
            .name("悬停模式")
            .description("不输入时如何悬停。")
            .defaultValue(ElytraFlySupport.HoverMode.Hover)
            .visible(this::isLegalMode)
            .build()
        );

        hoverAntiKick = sgSimple.add(new EnumSetting.Builder<ElytraFlySupport.AntiKickMode>()
            .name("防踢模式")
            .description("防止因为\"本服务器未启用飞行\"被踢出服务器")
            .defaultValue(ElytraFlySupport.AntiKickMode.Off)
            .visible(() -> isLegalMode()
                && isArmorMode()
                && hoverMode.get() == ElytraFlySupport.HoverMode.Freeze)
            .build()
        );

        hoverAntiKickInterval = sgSimple.add(new IntSetting.Builder()
            .name("防踢间隔")
            .description("")
            .defaultValue(20)
            .min(5)
            .max(36)
            .sliderRange(5, 36)
            .visible(() -> isLegalMode()
                && isArmorMode()
                && hoverMode.get() == ElytraFlySupport.HoverMode.Freeze
                && hoverAntiKick.get() != ElytraFlySupport.AntiKickMode.Off)
            .build()
        );

        hoverFirework = sgSimple.add(new BoolSetting.Builder()
            .name("悬停时自动烟花")
            .description("悬停期间按间隔释放烟花（与「自动烟花」相互独立）")
            .defaultValue(false)
            .onChanged(this::onHoverFireworkChanged)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover)
            .build()
        );

        hoverFwIntervalLv1 = sgSimple.add(new IntSetting.Builder()
            .name("悬停1级烟花间隔")
            .description("悬停时 1 级烟花的释放间隔（tick）")
            .defaultValue(30)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
            .build()
        );

        hoverFwIntervalLv2 = sgSimple.add(new IntSetting.Builder()
            .name("悬停2级烟花间隔")
            .description("悬停时 2 级烟花的释放间隔（tick）")
            .defaultValue(40)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
            .build()
        );

        hoverFwIntervalLv3 = sgSimple.add(new IntSetting.Builder()
            .name("悬停3级烟花间隔")
            .description("悬停时 3 级烟花的释放间隔（tick）")
            .defaultValue(50)
            .min(1)
            .max(100)
            .sliderMax(100)
            .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
            .build()
        );

        discardMomentum = sgSimple.add(new BoolSetting.Builder()
            .name("丢弃动量")
            .description("勾选后冻结清空玩家动量，解除冻结后动量清零。反作弊不拦截情况下推荐开启，提示飞行精确度")
            .defaultValue(false)
            .visible(() -> isLegalMode() && hoverMode.get() != ElytraFlySupport.HoverMode.Hover)
            .build()
        );

        freezeFirework = sgSimple.add(new BoolSetting.Builder()
            .name("冻结烟花")
            .description("冻结期间冻结烟花使用，解冻后继续使用\"还未使用完\"的烟花")
            .defaultValue(false)
            .visible(() -> isLegalMode() && hoverMode.get() != ElytraFlySupport.HoverMode.Hover)
            .build()
        );

        notGlidingUnfreeze = sgSimple.add(new BoolSetting.Builder()
            .name("不在滑翔解冻")
            .description("不在滑翔状态时立即解除冻结")
            .defaultValue(false)
            .visible(() -> isLegalMode() && hoverMode.get() != ElytraFlySupport.HoverMode.Hover)
            .build()
        );

        // ====== 甲飞 ======

        SettingGroup sgArmor = self.settings.createGroup("甲飞");

        armorMode = sgArmor.add(new EnumSetting.Builder<ElytraFlySupport.ArmorMode>()
            .name("甲飞模式")
            .description("甲飞的模式")
            .defaultValue(ElytraFlySupport.ArmorMode.Off)
            .onChanged(this::onArmorModeChanged)
            .build()
        );

        lavaFlight = sgArmor.add(new BoolSetting.Builder()
            .name("允许在岩浆中飞行")
            .description("开启后岩浆里也照常换装维持滑翔、按滑翔运算继续飞；关闭则和以前一样进岩浆就收工（换回胸甲 + 清滑翔 + 按岩浆移动）。水里一律不工作，等离开水面再继续")
            .defaultValue(true)
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

        spaceBlockInAir = sgArmor.add(new BoolSetting.Builder()
            .name("空中屏蔽空格")
            .description("空中屏蔽空格输入")
            .defaultValue(false)
            .visible(this::isArmorMode)
            .build()
        );

        landingNoFall = sgArmor.add(new BoolSetting.Builder()
            .name("落地防摔")
            .description("防止你被摔死")
            .defaultValue(false)
            .visible(this::isArmorMode)
            .build()
        );

        landingNoFallMode = sgArmor.add(new EnumSetting.Builder<ElytraFlySupport.LandingNoFallMode>()
            .name("落地防摔方式")
            .description("防止你摔死的方式")
            .defaultValue(ElytraFlySupport.LandingNoFallMode.Grim)
            .visible(() -> isArmorMode() && landingNoFall.get())
            .build()
        );

        grimInputSequence = sgArmor.add(new BoolSetting.Builder()
            .name("兼容grim输入检测")
            .description("模拟真实按键输入")
            .defaultValue(false)
            .visible(this::isArmorMode)
            .build()
        );

        armorSwapInterval = sgArmor.add(new IntSetting.Builder()
            .name("换甲间隔")
            .description("换甲的间隔")
            .defaultValue(1)
            .min(1)
            .max(20)
            .sliderMax(10)
            .visible(this::isArmorMode)
            .build()
        );

        // ====== 无限鞘翅（照搬 AEfish 的 InfiniteElytra） ======

        SettingGroup sgInfinite = self.settings.createGroup("无限鞘翅");

        infiniteElytra = sgInfinite.add(new BoolSetting.Builder()
            .name("无限鞘翅")
            .description("滑翔时定期脱下再穿上鞘翅刷新滑翔计时，让鞘翅无限耐久")
            .defaultValue(false)
            .onChanged(this::onInfiniteElytraChanged)
            .build()
        );

        infinitePeriod = sgInfinite.add(new IntSetting.Builder()
            .name("周期")
            .description("脱下鞘翅的周期")
            .defaultValue(16)
            .range(1, 100)
            .sliderRange(1, 40)
            .visible(infiniteElytra::get)
            .build()
        );

        infiniteMute = sgInfinite.add(new BoolSetting.Builder()
            .name("静音")
            .description("开启后屏蔽装备穿戴音效，换甲不再响。")
            .defaultValue(true)
            .visible(infiniteElytra::get)
            .build()
        );

        // 注入设置引用到支持类
        ElytraFlySupport.flightMode = flightMode;
        ElytraFlySupport.armorMode = armorMode;
        ElytraFlySupport.lavaFlight = lavaFlight;
        ElytraFlySupport.muteSounds = muteSounds;
        ElytraFlySupport.spaceBlockInAir = spaceBlockInAir;
        ElytraFlySupport.landingNoFall = landingNoFall;
        ElytraFlySupport.landingNoFallMode = landingNoFallMode;
        ElytraFlySupport.grimInputSequence = grimInputSequence;
        ElytraFlySupport.armorSwapInterval = armorSwapInterval;
        ElytraFlySupport.autoFirework = autoFirework;
        ElytraFlySupport.legalRotationPriority = legalRotationPriority;
        ElytraFlySupport.autoSwapElytra = autoSwapElytra;
        ElytraFlySupport.backpackFirework = backpackFirework;
        ElytraFlySupport.backpackMode = backpackMode;
        ElytraFlySupport.backpackTarget = backpackTarget;
        ElytraFlySupport.fwPriorityLv1 = fwPriorityLv1;
        ElytraFlySupport.fwPriorityLv2 = fwPriorityLv2;
        ElytraFlySupport.fwPriorityLv3 = fwPriorityLv3;
        ElytraFlySupport.discardMomentum = discardMomentum;
        ElytraFlySupport.freezeFirework = freezeFirework;
        ElytraFlySupport.fwIntervalLv1 = fwIntervalLv1;
        ElytraFlySupport.fwIntervalLv2 = fwIntervalLv2;
        ElytraFlySupport.fwIntervalLv3 = fwIntervalLv3;
        ElytraFlySupport.hoverMode = hoverMode;
        ElytraFlySupport.hoverAntiKick = hoverAntiKick;
        ElytraFlySupport.hoverAntiKickInterval = hoverAntiKickInterval;
        ElytraFlySupport.notGlidingUnfreeze = notGlidingUnfreeze;
        ElytraFlySupport.hoverFirework = hoverFirework;
        ElytraFlySupport.hoverFwIntervalLv1 = hoverFwIntervalLv1;
        ElytraFlySupport.hoverFwIntervalLv2 = hoverFwIntervalLv2;
        ElytraFlySupport.hoverFwIntervalLv3 = hoverFwIntervalLv3;

        InfiniteElytraSupport.infiniteElytra = infiniteElytra;
        InfiniteElytraSupport.period = infinitePeriod;
        InfiniteElytraSupport.mute = infiniteMute;
    }

    // ====== 分组整理 ======

    /**
     * 把官方 General / Inventory / Autopilot 三块设置合并成一个「简单控制」块：
     * 默认分组（General）直接改名成「简单控制」，另外两块设置搬进来后把空分组删掉。
     */
    @Unique
    private static SettingGroup mergeOfficialGroups(ElytraFly self) {
        SettingGroup simple = self.settings.getDefaultGroup();

        for (String groupName : new String[] { "Inventory", "Autopilot" }) {
            SettingGroup group = self.settings.getGroup(groupName);
            if (group == null) continue;
            moveSettings(group, simple);
            self.settings.groups.remove(group);
        }

        setGroupName(simple, "简单控制");
        return simple;
    }

    /** 把 from 分组里的设置全部搬到 to 分组末尾（Meteor 只有末尾追加，没有跨分组搬家） */
    @Unique
    private static void moveSettings(SettingGroup from, SettingGroup to) {
        if (from == to) return;
        List<Setting<?>> source = ((SettingGroupAccessor) from).getSettings();
        List<Setting<?>> target = ((SettingGroupAccessor) to).getSettings();
        target.addAll(source);
        source.clear();
    }

    /** 改写设置内部名（配置键/翻译键都用它）：name 是 final 字段，用 Unsafe 直接写 */
    @Unique
    private static void renameSetting(Setting<?> setting, String name) {
        try {
            Field field = Setting.class.getDeclaredField("name");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = getUnsafe();
            unsafe.putObject(setting, unsafe.objectFieldOffset(field), name);
        } catch (Exception e) {
            ModernSupport.LOG.warn("重命名 ElytraFly 模式设置失败", e);
        }
    }

    /**
     * 把「简单控制模式」的默认值与初始值都改成「关闭」。
     *
     * <p>官方 {@link ElytraFly} 构造设置时给的是 {@code defaultValue(Vanilla)}，两个地方要改：
     * <ul>
     *   <li>{@code defaultValue}（{@code protected final}，用 Unsafe 写）：设置界面的「重置」用的就是它；</li>
     *   <li>当前值（{@code flightMode.set(off)}）：新装 / 配置里没有这一项时模块默认什么都不做。</li>
     * </ul>
     *
     * <p>已保存的配置在 {@code Modules#load}（{@code Systems.load()}）里覆盖回来，
     * 那一步在模块构造之后，不会把玩家自己的选择冲掉。
     */
    @Unique
    private void applyDefaultOffMode() {
        ElytraFlightModes off = ElytraFlySupport.offMode();
        if (off == null) return;

        try {
            Field field = Setting.class.getDeclaredField("defaultValue");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = getUnsafe();
            unsafe.putObject(flightMode, unsafe.objectFieldOffset(field), off);
        } catch (Exception e) {
            ModernSupport.LOG.warn("改写 ElytraFly 模式默认值失败", e);
        }

        flightMode.set(off);
    }

    /**
     * 收敛「简单控制模式」的候选值：运行时枚举里还有官方的 俯仰40 / 弹跳（不能从 $VALUES 里删，
     * 官方 switch 的 $SwitchMap 按 values().length 分配，删了会数组越界崩溃），
     * 所以这里改 {@code EnumSetting.values}（命令解析/候选）与 {@code suggestions}，
     * 下拉框的过滤由 {@link MixinDefaultSettingsWidgetFactory} 负责。
     */
    @Unique
    private void filterFlightModeValues() {
        try {
            sun.misc.Unsafe unsafe = getUnsafe();
            ElytraFlightModes[] allowed = ElytraFlySupport.simpleControlModes();

            Field valuesField = EnumSetting.class.getDeclaredField("values");
            valuesField.setAccessible(true);
            unsafe.putObject(flightMode, unsafe.objectFieldOffset(valuesField), allowed);

            Field suggestionsField = EnumSetting.class.getDeclaredField("suggestions");
            suggestionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> suggestions = (List<String>) suggestionsField.get(flightMode);
            suggestions.clear();
            for (ElytraFlightModes mode : allowed) suggestions.add(mode.toString());
        } catch (Exception e) {
            ModernSupport.LOG.warn("收敛 ElytraFly 模式候选值失败", e);
        }
    }

    /** 改写分组名（就是 GUI 里的分组标题）：name 是 final 字段，用 Unsafe 直接写 */
    @Unique
    private static void setGroupName(SettingGroup group, String name) {
        try {
            Field field = SettingGroup.class.getDeclaredField("name");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = getUnsafe();
            unsafe.putObject(group, unsafe.objectFieldOffset(field), name);
        } catch (Exception e) {
            ModernSupport.LOG.warn("重命名 ElytraFly 设置分组失败", e);
        }
    }

    /**
     * 隐藏官方其他模式的设置（合法 / 甲飞 / 关闭 时它们都没意义），保留「简单控制模式」设置本身。
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
                    if (setting == flightMode) continue;

                    IVisible original = (IVisible) visibleField.get(setting);
                    getUnsafe().putObject(setting, offset,
                        (IVisible) () -> !suppressOfficialLogic() && (original == null || original.isVisible()));
                }
            }
        } catch (Exception e) {
            // 隐藏失败不影响主功能，但设置界面会显示官方设置，记录日志便于排查
            ModernSupport.LOG.warn("隐藏 ElytraFly 官方设置失败", e);
        }
    }

    // ====== 互斥 ======

    /**
     * 接管官方模式设置的回调：官方只允许构造时指定 onChanged，
     * 这里用 Unsafe 把原回调包一层，实现「发包 ↔ 甲飞 / 无限鞘翅」互斥。
     */
    @Unique
    private void wrapFlightModeOnChanged() {
        try {
            Field field = Setting.class.getDeclaredField("onChanged");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = getUnsafe();
            long offset = unsafe.objectFieldOffset(field);

            @SuppressWarnings("unchecked")
            Consumer<ElytraFlightModes> original = (Consumer<ElytraFlightModes>) unsafe.getObject(flightMode, offset);

            unsafe.putObject(flightMode, offset, (Consumer<ElytraFlightModes>) mode -> {
                if (original != null) original.accept(mode);
                onFlightModeChanged(mode);
            });
        } catch (Exception e) {
            ModernSupport.LOG.warn("接管 ElytraFly 模式设置回调失败（互斥可能不生效）", e);
        }
    }

    /** 选到「发包」：把互斥的 甲飞 / 无限鞘翅 关掉 */
    @Unique
    private void onFlightModeChanged(ElytraFlightModes mode) {
        if (mode == null || !mode.name().equals("Packet")) return;

        if (isArmorMode()) armorMode.set(ElytraFlySupport.ArmorMode.Off);
        if (infiniteElytra != null && infiniteElytra.get()) infiniteElytra.set(false);
    }

    /** 开甲飞：关掉无限鞘翅；「发包」模式互斥，自动切回原版 */
    @Unique
    private void onArmorModeChanged(ElytraFlySupport.ArmorMode mode) {
        if (mode == null || mode == ElytraFlySupport.ArmorMode.Off) return;

        if (infiniteElytra != null && infiniteElytra.get()) infiniteElytra.set(false);
        if (isPacketMode()) flightMode.set(ElytraFlightModes.Vanilla);
    }

    /** 开无限鞘翅：关掉甲飞；「发包」模式互斥，自动切回原版 */
    @Unique
    private void onInfiniteElytraChanged(Boolean enabled) {
        if (enabled == null || !enabled) return;

        if (isArmorMode()) armorMode.set(ElytraFlySupport.ArmorMode.Off);
        if (isPacketMode()) flightMode.set(ElytraFlightModes.Vanilla);
    }

    /**
     * 关掉「自动烟花」：清掉已经排队的自动烟花（甲飞换装窗口里待释放的那一发），
     * 开关立刻生效，不会在关掉之后又放出一发。
     */
    @Unique
    private void onAutoFireworkChanged(Boolean enabled) {
        if (enabled != null && enabled) return;
        ElytraFlySupport.cancelPendingAutoFirework();
    }

    /** 关掉「悬停时自动烟花」：同样清掉排队中的那一发，悬停立刻不再放烟花 */
    @Unique
    private void onHoverFireworkChanged(Boolean enabled) {
        if (enabled != null && enabled) return;
        ElytraFlySupport.cancelPendingAutoFirework();
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

    // ====== 事件接管：合法 / 甲飞 由我们接管，「关闭」时官方逻辑同样跳过 ======

    @Inject(method = "onPreTick", at = @At("HEAD"), cancellable = true)
    private void onPreTick(TickEvent.Pre event, CallbackInfo ci) {
        // 空中屏蔽空格的收尾：模式切走/关掉选项时也要把真实输入补发回服务器
        ElytraFlySupport.onPreTickAlways();
        // 我们自己的每 tick 逻辑只在 合法 / 甲飞 时跑（「关闭」没有任何飞行控制要跑）
        if (isCustomMode()) ElytraFlySupport.onTick();
        if (suppressOfficialLogic()) ci.cancel();
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void onTick(TickEvent.Post event, CallbackInfo ci) {
        // 无限鞘翅不分模式：关闭 / 原版 / 发包 / 合法 / 甲飞 下都能刷新服务端滑翔计时
        InfiniteElytraSupport.onTick();
        if (suppressOfficialLogic()) ci.cancel();
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void onPlayerMove(PlayerMoveEvent event, CallbackInfo ci) {
        // 甲飞/合法不干预移动包内的移动向量（合法靠服务器视角 + 滑翔物理），跳过官方逻辑
        // 「关闭」同理：官方的飞行控制（含 原版 / 发包）一律不跑
        if (suppressOfficialLogic()) ci.cancel();
    }

    /**
     * 「原版 + 甲飞」兼容：放行官方原版控制开头那道「胸甲槽要有滑翔组件」的守卫。
     *
     * <p>官方的「原版」控制全程建立在「客户端此刻穿着鞘翅、真的在滑翔」之上，方法开头
     * 就是一句 {@code getItemBySlot(CHEST).has(GLIDER)}，不满足直接整块返回。甲飞穿的是
     * 胸甲（鞘翅只在换装窗口那一两 tick 出现在胸甲槽上），这道守卫会把原版控制整体挡掉，
     * 表现就是「原版 + 甲飞」只剩甲飞的换装滑翔，WASD / 空格完全没有反应。
     *
     * <p>这里只把这<b>一处判定</b>改成「有滑翔组件」（仅当
     * {@link ElytraFlySupport#shouldProvideGliderForVanillaArmor()} 为真，也就是甲飞正按
     * 滑翔运算移动的那些 tick）：不换装、不动背包、不改滑翔状态，甲飞的换装时序 / 发包 /
     * 姿势锁定一律不变，官方原版控制照常接管本 tick 的移动向量。
     *
     * <p>{@code require = 0}：官方要是改了方法结构，注入失败也只是「原版 + 甲飞」没有原版
     * 控制，不会让游戏起不来。
     */
    @Redirect(
        method = "onPlayerMove",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;has(Lnet/minecraft/core/component/DataComponentType;)Z"
        ),
        require = 0
    )
    private boolean modernsupport$gliderForVanillaArmor(ItemStack stack, DataComponentType<?> component) {
        if (ElytraFlySupport.shouldProvideGliderForVanillaArmor()) return true;
        return stack.has(component);
    }

    @Inject(method = "onPacketSend", at = @At("HEAD"), cancellable = true)
    private void onPacketSend(PacketEvent.Send event, CallbackInfo ci) {
        InfiniteElytraSupport.onPacketSend(event);
        if (isCustomMode()) ElytraFlySupport.onPacketSend(event);
        if (suppressOfficialLogic()) ci.cancel();
    }

    @Inject(method = "onPacketReceive", at = @At("HEAD"), cancellable = true)
    private void onPacketReceive(PacketEvent.Receive event, CallbackInfo ci) {
        if (isCustomMode()) ElytraFlySupport.onPacketReceive(event);
        if (suppressOfficialLogic()) ci.cancel();
    }

    // ====== 生命周期：追加初始化/清理（官方逻辑保留） ======

    @Inject(method = "onActivate", at = @At("TAIL"))
    private void onActivate(CallbackInfo ci) {
        ElytraFlySupport.onActivate();
        InfiniteElytraSupport.reset();
        // 与拆出去的「鞘翅Pitch40 / 鞘翅弹跳」互斥：两边都在控制滑翔
        disableModule(ElytraPitch40.class);
        disableModule(ElytraBounce.class);
    }

    @Inject(method = "onDeactivate", at = @At("TAIL"))
    private void onDeactivate(CallbackInfo ci) {
        ElytraFlySupport.onDeactivate();
        InfiniteElytraSupport.reset();
    }

    @Unique
    private static void disableModule(Class<? extends Module> moduleClass) {
        Module module = Modules.get().get(moduleClass);
        if (module != null && module.isActive()) module.toggle();
    }

    // ====== HUD 显示 ======

    @Inject(method = "getInfoString", at = @At("HEAD"), cancellable = true)
    private void onGetInfoString(CallbackInfoReturnable<String> cir) {
        if (isLegalMode()) {
            cir.setReturnValue(isArmorMode() ? "合法+甲飞" : "合法");
        } else if (isArmorMode()) {
            cir.setReturnValue(isVanillaMode() ? "原版+甲飞" : "甲飞");
        } else if (isSimpleControlOff()) {
            // 关闭：官方那套 currentMode 的 HUD 名字会残留上一个模式，这里直接标成关闭 / 无限鞘翅
            boolean infinite = infiniteElytra != null && infiniteElytra.get();
            cir.setReturnValue(infinite ? "无限鞘翅" : "关闭");
        }
    }
}
