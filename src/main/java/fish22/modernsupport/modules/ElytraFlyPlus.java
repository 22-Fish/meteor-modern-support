package fish22.modernsupport.modules;

import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.ElytraFlySupport;
import fish22.modernsupport.utils.ElytraLagSyncSupport;
import fish22.modernsupport.utils.InfiniteElytraSupport;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.BlockBehaviourAccessor;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅飞行（独立模块，移动分类）
 *
 * <p>以前这套东西是「mixin 进 Meteor 官方 ElytraFly + 三个支持类」，mixin 开发 / 调试太痛苦，
 * 现在整个搬成我们自己的模块：设置、事件、飞行控制全部在本类里（包括照搬过来的
 * 「原版 / 发包」两套官方飞行控制），Meteor 官方那个由
 * {@link fish22.modernsupport.mixin.MixinDisableMeteorElytraFly} 关掉、并从模块列表里摘掉。
 *
 * <p>老配置（模块名 elytra-fly、设置名、键位）由 {@code MixinModules} + {@code MixinSettings}
 * 在加载时改名迁移过来。
 *
 * <h3>三大块</h3>
 * <pre>
 * 〔简单控制〕简单控制模式（关闭 / 原版 / 合法，默认关闭）
 *            原版 = 照搬 Meteor 官方的原版控制（本类里自带实现）
 *            合法 = 我们的合法平飞（服务器视角 + 原版滑翔物理）
 * 〔甲飞〕    甲飞模式（关闭 / 普通 / Grim模式 / Grim Lag）+ 换装、音效、落地防摔等设置
 * 〔无限鞘翅〕无限鞘翅模式（关闭 / 换甲 / 发包）+ 周期 / 静音
 * </pre>
 *
 * <h3>互斥</h3>
 * <ul>
 *   <li>「关闭」「原版」「合法」互相切换，不关别的；</li>
 *   <li>「甲飞」不与简单控制互斥（原版 + 甲飞 = 官方控制 + 换装维持滑翔，合法 + 甲飞同理）；</li>
 *   <li>无限鞘翅「换甲」与甲飞互斥（两边都在换装）；</li>
 *   <li>无限鞘翅「发包」与甲飞、简单控制（非关闭）互斥：发包自己就是一套平飞控制。</li>
 * </ul>
 *
 * <p>业务逻辑在 {@link ElytraFlySupport}（甲飞 / 合法 / 烟花等）、
 * {@link InfiniteElytraSupport}（无限鞘翅）、{@link ElytraLagSyncSupport}（Grim Lag），
 * 本类负责创建设置、注入引用、收发事件，以及自带那套「原版 / 发包」飞行控制。
 */
public class ElytraFlyPlus extends Module {

    private final SettingGroup sgSimple = settings.createGroup("简单控制");
    private final SettingGroup sgArmor = settings.createGroup("甲飞");
    private final SettingGroup sgInfinite = settings.createGroup("无限鞘翅");

    // ====== 简单控制：模式 ======

    /** 简单控制模式（关闭 / 原版 / 合法） */
    public final Setting<ElytraFlySupport.FlyMode> simpleMode = sgSimple.add(new EnumSetting.Builder<ElytraFlySupport.FlyMode>()
        .name("simple-mode")
        .description("关闭（默认）：模块不做任何飞行控制，甲飞 / 无限鞘翅照常可用。"
            + "原版 = Meteor 官方那套原版控制（与甲飞不互斥），合法 = 我们的合法平飞（可叠加甲飞换装）")
        .defaultValue(ElytraFlySupport.FlyMode.Off)
        .onChanged(this::onSimpleModeChanged)
        .build()
    );

    // ====== 简单控制：原版（照搬 Meteor 官方，可见性跟着「原版」模式） ======

    public final Setting<Boolean> autoTakeOff = sgSimple.add(new BoolSetting.Builder()
        .name("auto-take-off")
        .description("按住跳跃键时自动起飞，无需双跳")
        .defaultValue(false)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Double> fallMultiplier = sgSimple.add(new DoubleSetting.Builder()
        .name("fall-multiplier")
        .description("控制你自然下降的速度")
        .defaultValue(0.01)
        .min(0)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Double> horizontalSpeed = sgSimple.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("前后移动的速度")
        .defaultValue(1)
        .min(0)
        .visible(this::officialSpeedsVisible)
        .build()
    );

    public final Setting<Double> verticalSpeed = sgSimple.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("上下移动的速度")
        .defaultValue(1)
        .min(0)
        .visible(this::officialSpeedsVisible)
        .build()
    );

    public final Setting<Boolean> acceleration = sgSimple.add(new BoolSetting.Builder()
        .name("acceleration")
        .defaultValue(false)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Double> accelerationStep = sgSimple.add(new DoubleSetting.Builder()
        .name("acceleration-step")
        .min(0.1)
        .max(5)
        .defaultValue(1)
        .visible(() -> isVanillaMode() && acceleration.get())
        .build()
    );

    public final Setting<Double> accelerationMin = sgSimple.add(new DoubleSetting.Builder()
        .name("acceleration-start")
        .min(0.1)
        .defaultValue(0)
        .visible(() -> isVanillaMode() && acceleration.get())
        .build()
    );

    public final Setting<Boolean> stopInWater = sgSimple.add(new BoolSetting.Builder()
        .name("stop-in-water")
        .description("在水中停止飞行")
        .defaultValue(true)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Boolean> dontGoIntoUnloadedChunks = sgSimple.add(new BoolSetting.Builder()
        .name("no-unloaded-chunks")
        .description("阻止你飞进未加载的区块")
        .defaultValue(true)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Boolean> autoHover = sgSimple.add(new BoolSetting.Builder()
        .name("auto-hover")
        .description("按住潜行时自动悬停在地面上方 0.3 格")
        .defaultValue(false)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Boolean> noCrash = sgSimple.add(new BoolSetting.Builder()
        .name("no-crash")
        .description("阻止你撞到墙上")
        .defaultValue(false)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Integer> crashLookAhead = sgSimple.add(new IntSetting.Builder()
        .name("crash-look-ahead")
        .description("飞行时向前看的距离")
        .defaultValue(5)
        .range(1, 15)
        .sliderMin(1)
        .visible(() -> isVanillaMode() && noCrash.get())
        .build()
    );

    public final Setting<Boolean> instaDrop = sgSimple.add(new BoolSetting.Builder()
        .name("insta-drop")
        .description("让你立刻退出飞行")
        .defaultValue(false)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Boolean> replace = sgSimple.add(new BoolSetting.Builder()
        .name("elytra-replace")
        .description("用新的鞘翅替换破损的鞘翅")
        .defaultValue(false)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Integer> replaceDurability = sgSimple.add(new IntSetting.Builder()
        .name("replace-durability")
        .description("鞘翅剩余耐久掉到这个值时被替换")
        .defaultValue(2)
        .sliderRange(1, 500)
        .visible(() -> isVanillaMode() && replace.get())
        .build()
    );

    public final Setting<ChestSwapMode> chestSwap = sgSimple.add(new EnumSetting.Builder<ChestSwapMode>()
        .name("chest-swap")
        .description("切换这个模块时是否换鞘翅 / 胸甲")
        .defaultValue(ChestSwapMode.Never)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Boolean> autoReplenish = sgSimple.add(new BoolSetting.Builder()
        .name("replenish-fireworks")
        .description("把烟花移动到指定的快捷栏格")
        .defaultValue(false)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Integer> replenishSlot = sgSimple.add(new IntSetting.Builder()
        .name("replenish-slot")
        .description("烟花自动移动到哪一格")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(() -> isVanillaMode() && autoReplenish.get())
        .build()
    );

    public final Setting<Boolean> autoPilot = sgSimple.add(new BoolSetting.Builder()
        .name("auto-pilot")
        .description("滑翔时自动向前移动")
        .defaultValue(false)
        .visible(this::isVanillaMode)
        .build()
    );

    public final Setting<Boolean> useFireworks = sgSimple.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("每隔你选择的秒数使用一次烟花")
        .defaultValue(false)
        .visible(() -> isVanillaMode() && autoPilot.get())
        .build()
    );

    public final Setting<Double> autoPilotFireworkDelay = sgSimple.add(new DoubleSetting.Builder()
        .name("firework-delay")
        .description("使用烟花之间的间隔秒数")
        .min(1)
        .defaultValue(8)
        .sliderMax(20)
        .visible(() -> isVanillaMode() && autoPilot.get() && useFireworks.get())
        .build()
    );

    public final Setting<Double> autoPilotMinimumHeight = sgSimple.add(new DoubleSetting.Builder()
        .name("minimum-height")
        .description("自动驾驶的最低高度")
        .defaultValue(120)
        .min(-128)
        .sliderMax(260)
        .visible(() -> isVanillaMode() && autoPilot.get())
        .build()
    );

    // ====== 简单控制：合法模式设置 ======

    public final Setting<Integer> legalRotationPriority = sgSimple.add(new IntSetting.Builder()
        .name("合法转头优先级")
        .description("合法转头的优先级")
        .defaultValue(0)
        .sliderRange(-20, 20)
        .visible(this::isLegalMode)
        .build()
    );

    public final Setting<Boolean> autoFirework = sgSimple.add(new BoolSetting.Builder()
        .name("自动烟花")
        .description("飞行中自动释放烟花加速（与「悬停时自动烟花」相互独立）")
        .defaultValue(true)
        .onChanged(this::onAutoFireworkChanged)
        .visible(this::isLegalMode)
        .build()
    );

    public final Setting<Boolean> autoSwapElytra = sgSimple.add(new BoolSetting.Builder()
        .name("自动替换鞘翅")
        .description("空中按跳跃键自动换上鞘翅起飞（用背包里剩余耐久最高的那件），滑翔结束后自动换回胸甲。")
        .defaultValue(false)
        .visible(this::isLegalMode)
        .build()
    );

    public final Setting<Boolean> backpackFirework = sgSimple.add(new BoolSetting.Builder()
        .name("背包烟花")
        .description("自动烟花允许使用背包中的烟花")
        .defaultValue(false)
        .visible(() -> isLegalMode() && autoFirework.get())
        .build()
    );

    public final Setting<BackpackUse.Mode> backpackMode = sgSimple.add(new EnumSetting.Builder<BackpackUse.Mode>()
        .name("背包使用发包")
        .description("背包烟花的交换发包方式。SWAP：2 次 SWAP 点击（背包槽与目标格互换）；PICKUP：4 次 PICKUP 点击（走光标，背包满也能换）")
        .defaultValue(BackpackUse.Mode.SWAP)
        .visible(() -> isLegalMode() && autoFirework.get() && backpackFirework.get())
        .build()
    );

    public final Setting<BackpackUse.TargetSlot> backpackTarget = sgSimple.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
        .name("目标槽位")
        .description("背包烟花换到哪一格使用。副手：换到副手使用（不碰手上那一格）；主手：换到手持那一格；快捷栏：换到除手持那一格以外的一个快捷栏格（空手 > 工具 > 方块 > 物品）")
        .defaultValue(BackpackUse.TargetSlot.OFFHAND)
        .visible(() -> isLegalMode() && autoFirework.get() && backpackFirework.get())
        .build()
    );

    public final Setting<Integer> fwPriorityLv1 = sgSimple.add(new IntSetting.Builder()
        .name("1级烟花优先级")
        .description("1 级烟花的优先级，优先级高的烟花优先使用")
        .defaultValue(1)
        .min(1)
        .max(3)
        .visible(() -> isLegalMode() && autoFirework.get())
        .build()
    );

    public final Setting<Integer> fwPriorityLv2 = sgSimple.add(new IntSetting.Builder()
        .name("2级烟花优先级")
        .description("2 级烟花的优先级，优先级高的烟花优先使用")
        .defaultValue(1)
        .min(1)
        .max(3)
        .visible(() -> isLegalMode() && autoFirework.get())
        .build()
    );

    public final Setting<Integer> fwPriorityLv3 = sgSimple.add(new IntSetting.Builder()
        .name("3级烟花优先级")
        .description("3 级烟花的优先级，优先级高的烟花优先使用")
        .defaultValue(1)
        .min(1)
        .max(3)
        .visible(() -> isLegalMode() && autoFirework.get())
        .build()
    );

    public final Setting<Integer> fwIntervalLv1 = sgSimple.add(new IntSetting.Builder()
        .name("1级烟花间隔")
        .description("1 级烟花的释放间隔（tick）")
        .defaultValue(30)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> isLegalMode() && autoFirework.get())
        .build()
    );

    public final Setting<Integer> fwIntervalLv2 = sgSimple.add(new IntSetting.Builder()
        .name("2级烟花间隔")
        .description("2 级烟花的释放间隔（tick）")
        .defaultValue(40)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> isLegalMode() && autoFirework.get())
        .build()
    );

    public final Setting<Integer> fwIntervalLv3 = sgSimple.add(new IntSetting.Builder()
        .name("3级烟花间隔")
        .description("3 级烟花的释放间隔（tick）")
        .defaultValue(50)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> isLegalMode() && autoFirework.get())
        .build()
    );

    public final Setting<ElytraFlySupport.HoverMode> hoverMode = sgSimple.add(new EnumSetting.Builder<ElytraFlySupport.HoverMode>()
        .name("悬停模式")
        .description("不输入时如何悬停。")
        .defaultValue(ElytraFlySupport.HoverMode.Hover)
        .visible(this::isLegalMode)
        .build()
    );

    public final Setting<ElytraFlySupport.AntiKickMode> hoverAntiKick = sgSimple.add(new EnumSetting.Builder<ElytraFlySupport.AntiKickMode>()
        .name("防踢模式")
        .description("防止因为\"本服务器未启用飞行\"被踢出服务器")
        .defaultValue(ElytraFlySupport.AntiKickMode.Off)
        .visible(() -> isLegalMode()
            && isArmorMode()
            && hoverMode.get() == ElytraFlySupport.HoverMode.Freeze)
        .build()
    );

    public final Setting<Integer> hoverAntiKickInterval = sgSimple.add(new IntSetting.Builder()
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

    public final Setting<Boolean> hoverFirework = sgSimple.add(new BoolSetting.Builder()
        .name("悬停时自动烟花")
        .description("悬停期间按间隔释放烟花（与「自动烟花」相互独立）")
        .defaultValue(false)
        .onChanged(this::onAutoFireworkChanged)
        .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover)
        .build()
    );

    public final Setting<Integer> hoverFwIntervalLv1 = sgSimple.add(new IntSetting.Builder()
        .name("悬停1级烟花间隔")
        .description("悬停时 1 级烟花的释放间隔（tick）")
        .defaultValue(30)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
        .build()
    );

    public final Setting<Integer> hoverFwIntervalLv2 = sgSimple.add(new IntSetting.Builder()
        .name("悬停2级烟花间隔")
        .description("悬停时 2 级烟花的释放间隔（tick）")
        .defaultValue(40)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
        .build()
    );

    public final Setting<Integer> hoverFwIntervalLv3 = sgSimple.add(new IntSetting.Builder()
        .name("悬停3级烟花间隔")
        .description("悬停时 3 级烟花的释放间隔（tick）")
        .defaultValue(50)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> isLegalMode() && hoverMode.get() == ElytraFlySupport.HoverMode.Hover && hoverFirework.get())
        .build()
    );

    public final Setting<Boolean> discardMomentum = sgSimple.add(new BoolSetting.Builder()
        .name("丢弃动量")
        .description("勾选后冻结清空玩家动量，解除冻结后动量清零。反作弊不拦截情况下推荐开启，提示飞行精确度")
        .defaultValue(false)
        .visible(() -> isLegalMode() && hoverMode.get() != ElytraFlySupport.HoverMode.Hover)
        .build()
    );

    public final Setting<Boolean> freezeFirework = sgSimple.add(new BoolSetting.Builder()
        .name("冻结烟花")
        .description("冻结期间冻结烟花使用，解冻后继续使用\"还未使用完\"的烟花")
        .defaultValue(false)
        .visible(() -> isLegalMode() && hoverMode.get() != ElytraFlySupport.HoverMode.Hover)
        .build()
    );

    public final Setting<Boolean> notGlidingUnfreeze = sgSimple.add(new BoolSetting.Builder()
        .name("不在滑翔解冻")
        .description("不在滑翔状态时立即解除冻结")
        .defaultValue(false)
        .visible(() -> isLegalMode() && hoverMode.get() != ElytraFlySupport.HoverMode.Hover)
        .build()
    );

    // ====== 甲飞 ======

    public final Setting<ElytraFlySupport.ArmorMode> armorMode = sgArmor.add(new EnumSetting.Builder<ElytraFlySupport.ArmorMode>()
        .name("甲飞模式")
        .description("甲飞的模式")
        .defaultValue(ElytraFlySupport.ArmorMode.Off)
        .onChanged(this::onArmorModeChanged)
        .build()
    );

    public final Setting<Boolean> lavaFlight = sgArmor.add(new BoolSetting.Builder()
        .name("允许在岩浆中飞行")
        .description("开启后岩浆里也照常换装维持滑翔、按滑翔运算继续飞；关闭则和以前一样进岩浆就收工（换回胸甲 + 清滑翔 + 按岩浆移动）。水里一律不工作，等离开水面再继续")
        .defaultValue(true)
        .visible(this::isArmorMode)
        .build()
    );

    public final Setting<Boolean> muteSounds = sgArmor.add(new BoolSetting.Builder()
        .name("静音")
        .description("屏蔽换装音效")
        .defaultValue(true)
        .visible(this::isArmorMode)
        .build()
    );

    public final Setting<Boolean> spaceBlockInAir = sgArmor.add(new BoolSetting.Builder()
        .name("空中屏蔽空格")
        .description("空中屏蔽空格输入")
        .defaultValue(false)
        .visible(this::isArmorMode)
        .build()
    );

    public final Setting<Boolean> landingNoFall = sgArmor.add(new BoolSetting.Builder()
        .name("落地防摔")
        .description("防止你被摔死")
        .defaultValue(false)
        .visible(this::isArmorMode)
        .build()
    );

    public final Setting<ElytraFlySupport.LandingNoFallMode> landingNoFallMode = sgArmor.add(new EnumSetting.Builder<ElytraFlySupport.LandingNoFallMode>()
        .name("落地防摔方式")
        .description("防止你摔死的方式")
        .defaultValue(ElytraFlySupport.LandingNoFallMode.Grim)
        .visible(() -> isArmorMode() && landingNoFall.get())
        .build()
    );

    public final Setting<Boolean> grimInputSequence = sgArmor.add(new BoolSetting.Builder()
        .name("兼容grim输入检测")
        .description("模拟真实按键输入")
        .defaultValue(false)
        .visible(this::isArmorMode)
        .build()
    );

    public final Setting<Integer> armorSwapInterval = sgArmor.add(new IntSetting.Builder()
        .name("换甲间隔")
        .description("换甲的间隔")
        .defaultValue(1)
        .min(1)
        .max(20)
        .sliderMax(10)
        .visible(this::isArmorMode)
        .build()
    );

    public final Setting<Integer> startFlyingWindow = sgArmor.add(new IntSetting.Builder()
        .name("换鞘翅后维持滑翔")
        .description("服务端命令停止滑翔后，维持多少tick的滑翔")
        .defaultValue(1)
        .min(0)
        .max(20)
        .sliderMax(10)
        .visible(this::isArmorMode)
        .build()
    );

    public final Setting<Boolean> grimLagDelay = sgArmor.add(new BoolSetting.Builder()
        .name("Grim Lag: 启用延后同步")
        .description("史莱姆的「飞行状态同步延后刻数」（max-delay-ticks），默认不启用。"
            + "启用后：发出起飞包之后这么多tick内，扣住服务端说停滑的同步包和它的transaction ping，"
            + "本地滑翔位不变假、反作弊那边也一直认我们在滑翔，换甲频率因此降到每这么多tick一次。"
            + "关掉 = 就是Grim模式本身")
        .defaultValue(true)
        .visible(this::isGrimLagMode)
        .build()
    );

    public final Setting<Integer> grimLagSyncDelay = sgArmor.add(new IntSetting.Builder()
        .name("Grim Lag: 延后同步刻数")
        .description("扣包窗口长度（史莱姆默认值 5），也就是换甲的最小间隔tick。"
            + "越大换甲越少、越不容易被频率判定抓到；太大时窗口内本地滑翔位靠旧状态撑着，容易被服务端位置纠正拉回")
        .defaultValue(5)
        .min(0)
        .max(20)
        .sliderMax(10)
        .visible(() -> isGrimLagMode() && grimLagDelay.get())
        .build()
    );

    public final Setting<Boolean> grimLagAntiKick = sgArmor.add(new BoolSetting.Builder()
        .name("Grim Lag: 反踢出")
        .description("发出起飞包的那一tick，客户端自己的移动包换成带完整坐标的那一发"
            + "（史莱姆的 anti-kick / resyncPos），服务端不会攒出「悬浮过久」")
        .defaultValue(true)
        .visible(this::isGrimLagMode)
        .build()
    );

    public final Setting<Boolean> grimLagPong = sgArmor.add(new BoolSetting.Builder()
        .name("Grim Lag: 补pong")
        .description("扣住transaction ping之后，起飞包后补一发id为MIN_VALUE的pong、"
            + "放包时再补一发同id的pong（史莱姆的重复ping + GrimBadPacket修复），Grim预设里默认开")
        .defaultValue(true)
        .visible(this::isGrimLagMode)
        .build()
    );

    public final Setting<Boolean> grimLagHandControl = sgArmor.add(new BoolSetting.Builder()
        .name("Grim Lag: 允许手动切换暂停")
        .description("胸甲槽连续5tick是真鞘翅就认为你自己在穿鞘翅飞，暂停换甲（史莱姆的 enable-manually-swap）")
        .defaultValue(true)
        .visible(this::isGrimLagMode)
        .build()
    );

    // ====== 无限鞘翅 ======

    /** 无限鞘翅模式（关闭 / 换甲 / 发包） */
    public final Setting<InfiniteElytraSupport.InfiniteMode> infiniteMode = sgInfinite.add(new EnumSetting.Builder<InfiniteElytraSupport.InfiniteMode>()
        .name("无限鞘翅模式")
        .description("换甲：滑翔时定期脱下再穿上鞘翅刷新滑翔计时（照搬 AEfish，鞘翅无限耐久）；"
            + "发包：Meteor 官方发包模式（不用烟花，靠起飞包 + 移动包一直平飞）")
        .defaultValue(InfiniteElytraSupport.InfiniteMode.Off)
        .onChanged(this::onInfiniteModeChanged)
        .build()
    );

    public final Setting<Integer> infinitePeriod = sgInfinite.add(new IntSetting.Builder()
        .name("周期")
        .description("脱下鞘翅的周期")
        .defaultValue(16)
        .range(1, 100)
        .sliderRange(1, 40)
        .visible(() -> infiniteMode.get() == InfiniteElytraSupport.InfiniteMode.Swap)
        .build()
    );

    public final Setting<Boolean> infiniteMute = sgInfinite.add(new BoolSetting.Builder()
        .name("静音")
        .description("开启后屏蔽装备穿戴音效，换甲不再响。")
        .defaultValue(true)
        .visible(() -> infiniteMode.get() == InfiniteElytraSupport.InfiniteMode.Swap)
        .build()
    );

    // ====== 原版 / 发包 的飞行状态（照搬 Meteor 的 ElytraFlightMode + Vanilla + Packet） ======

    private double velX, velY, velZ;
    private Vec3 forward = Vec3.ZERO;
    private Vec3 right = Vec3.ZERO;
    private double accelerationValue;
    private double ticksLeft;
    private int jumpTimer;
    private boolean incrementJumpTimer;
    private boolean lastJumpPressed;
    private boolean lastForwardPressed;

    /** 发包模式的累加速度（Meteor 原样） */
    private final Vec3 packetVec = new Vec3(0, 0, 0);

    /** 发包模式把本地设成了创造飞行（退出时要收掉） */
    private boolean packetFlightSet;

    public ElytraFlyPlus() {
        super(Categories.Movement, "鞘翅飞行", "让你对鞘翅有更多的控制", "elytra-fly+");

        // 设置引用注入到支持类（支持类只认这些静态字段）
        ElytraFlySupport.simpleMode = simpleMode;
        ElytraFlySupport.armorMode = armorMode;
        ElytraFlySupport.lavaFlight = lavaFlight;
        ElytraFlySupport.muteSounds = muteSounds;
        ElytraFlySupport.spaceBlockInAir = spaceBlockInAir;
        ElytraFlySupport.landingNoFall = landingNoFall;
        ElytraFlySupport.landingNoFallMode = landingNoFallMode;
        ElytraFlySupport.grimInputSequence = grimInputSequence;
        ElytraFlySupport.armorSwapInterval = armorSwapInterval;
        ElytraFlySupport.startFlyingWindow = startFlyingWindow;
        ElytraFlySupport.allowManualSwap = grimLagHandControl;
        ElytraLagSyncSupport.enabled = grimLagDelay;
        ElytraLagSyncSupport.syncDelayTicks = grimLagSyncDelay;
        ElytraLagSyncSupport.antiKick = grimLagAntiKick;
        ElytraLagSyncSupport.pongFix = grimLagPong;
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

        InfiniteElytraSupport.mode = infiniteMode;
        InfiniteElytraSupport.period = infinitePeriod;
        InfiniteElytraSupport.mute = infiniteMute;
    }

    // ====== 模式判断 ======

    private boolean isVanillaMode() {
        return simpleMode.get() == ElytraFlySupport.FlyMode.Vanilla;
    }

    private boolean isLegalMode() {
        return simpleMode.get() == ElytraFlySupport.FlyMode.Legal;
    }

    private boolean isArmorMode() {
        return armorMode.get() != ElytraFlySupport.ArmorMode.Off;
    }

    private boolean isGrimLagMode() {
        return armorMode.get() == ElytraFlySupport.ArmorMode.GrimLag;
    }

    /** 无限鞘翅 = 发包 */
    private boolean isPacketFly() {
        return infiniteMode.get() == InfiniteElytraSupport.InfiniteMode.Packet;
    }

    /**
     * 「无限鞘翅·发包」此刻是否按发包模式在飞（判定照搬官方 {@code ElytraFly#canPacketEfly}）
     *
     * <p>Meteor 自己的两个 mixin（{@code LivingEntityMixin#isGlidingHook} / {@code EntityMixin#getPoseHook}）
     * 会拿这个值把本地 {@code isFallFlying()} 与姿势伪装成滑翔 —— 官方发包模式本来就靠它让本地
     * 已算滑翔，所以官方那个入口不能没有实现（见 MixinDisableMeteorElytraFly）。
     */
    public static boolean isPacketFlyActive() {
        ElytraFlyPlus module = Modules.get().get(ElytraFlyPlus.class);
        if (module == null || !module.isActive() || !module.isPacketFly()) return false;
        if (MeteorClient.mc.player == null) return false;
        return MeteorClient.mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)
            && !MeteorClient.mc.player.onGround();
    }

    /** 官方那套速度设置（原版模式 / 无限鞘翅发包模式都要用） */
    private boolean officialSpeedsVisible() {
        return isVanillaMode() || isPacketFly();
    }

    // ====== 互斥 ======

    /** 选「原版 / 合法」：和无限鞘翅·发包互斥 */
    private void onSimpleModeChanged(ElytraFlySupport.FlyMode mode) {
        if (mode == null || mode == ElytraFlySupport.FlyMode.Off) return;
        if (isPacketFly()) infiniteMode.set(InfiniteElytraSupport.InfiniteMode.Off);
    }

    /** 开甲飞：关掉无限鞘翅（两种模式都和甲飞抢滑翔状态） */
    private void onArmorModeChanged(ElytraFlySupport.ArmorMode mode) {
        if (mode == null || mode == ElytraFlySupport.ArmorMode.Off) return;
        if (infiniteMode.get() != InfiniteElytraSupport.InfiniteMode.Off) {
            infiniteMode.set(InfiniteElytraSupport.InfiniteMode.Off);
        }
    }

    /** 选无限鞘翅模式：换甲 / 发包 都和甲飞互斥；发包还要把简单控制让出来 */
    private void onInfiniteModeChanged(InfiniteElytraSupport.InfiniteMode mode) {
        if (mode != InfiniteElytraSupport.InfiniteMode.Packet && packetFlightSet) {
            clearPacketFlightState();
            packetFlightSet = false;
        }

        if (mode == null || mode == InfiniteElytraSupport.InfiniteMode.Off) return;

        if (isArmorMode()) armorMode.set(ElytraFlySupport.ArmorMode.Off);
        if (mode == InfiniteElytraSupport.InfiniteMode.Packet && simpleMode.get() != ElytraFlySupport.FlyMode.Off) {
            simpleMode.set(ElytraFlySupport.FlyMode.Off);
        }
    }

    /** 从「发包」切走：发包模式会把本地设成创造飞行，切走时收掉（Meteor 那边是关模块才收） */
    private void clearPacketFlightState() {
        if (mc.player == null) return;
        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().mayfly = false;
    }

    /** 关掉自动烟花（或悬停烟花）：清掉排队中的那一发 */
    private void onAutoFireworkChanged(Boolean enabled) {
        if (enabled != null && enabled) return;
        ElytraFlySupport.cancelPendingAutoFirework();
    }

    // ====== 生命周期 ======

    @Override
    public void onActivate() {
        resetFlightState();
        ElytraFlySupport.onActivate();
        InfiniteElytraSupport.reset();

        // 和拆出去的「鞘翅Pitch40 / 鞘翅弹跳」互斥：两边都在控制滑翔
        disableModule(ElytraPitch40.class);
        disableModule(ElytraBounce.class);

        if (mc.player != null
            && (chestSwap.get() == ChestSwapMode.Always || chestSwap.get() == ChestSwapMode.WaitForGround)
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            ChestSwap chestSwapModule = Modules.get().get(ChestSwap.class);
            if (chestSwapModule != null) chestSwapModule.swap();
        }
    }

    @Override
    public void onDeactivate() {
        if (autoPilot.get()) mc.options.keyUp.setDown(false);

        if (mc.player != null) {
            if (chestSwap.get() == ChestSwapMode.Always
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
                ChestSwap chestSwapModule = Modules.get().get(ChestSwap.class);
                if (chestSwapModule != null) chestSwapModule.swap();
            } else if (chestSwap.get() == ChestSwapMode.WaitForGround) {
                enableGroundListener();
            }

            if (mc.player.isFallFlying() && instaDrop.get()) enableInstaDropListener();
        }

        // 发包模式把本地创造飞行状态收掉（Meteor 原样）
        if (packetFlightSet) {
            clearPacketFlightState();
            packetFlightSet = false;
        }

        ElytraFlySupport.onDeactivate();
        InfiniteElytraSupport.reset();
        resetFlightState();
    }

    private void resetFlightState() {
        velX = velY = velZ = 0;
        forward = Vec3.ZERO;
        right = Vec3.ZERO;
        accelerationValue = 0;
        ticksLeft = 0;
        jumpTimer = 0;
        incrementJumpTimer = false;
        lastJumpPressed = false;
        lastForwardPressed = false;
    }

    private static void disableModule(Class<? extends Module> moduleClass) {
        Module module = Modules.get().get(moduleClass);
        if (module != null && module.isActive()) module.toggle();
    }

    // ====== 事件 ======

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        // 空中屏蔽空格的收尾（不分模式）+ Grim Lag 的扣包窗口
        ElytraFlySupport.onPreTickAlways();
        if (ElytraFlySupport.isCustomMode()) ElytraFlySupport.onTick();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // 无限鞘翅（换甲 / 发包都不分简单控制模式）
        InfiniteElytraSupport.onTick();

        if (isVanillaMode()) {
            vanillaInventoryTick();
            if (useFireworks.get()) handleAutopilotFirework();
        } else if (isPacketFly()) {
            packetTick();
        }
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null) return;

        // 无限鞘翅·发包：本地按创造飞行算（Meteor 原样）
        if (isPacketFly()) {
            mc.player.getAbilities().flying = true;
            mc.player.getAbilities().setFlyingSpeed(horizontalSpeed.get().floatValue() / 20);
            packetFlightSet = true;
        }

        if (!isVanillaMode()) return;

        // 甲飞时胸甲槽上大多不是鞘翅，这里按「有滑翔组件」放行（原官方里是 Redirect 掉这一句）
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)
            && !ElytraFlySupport.shouldProvideGliderForVanillaArmor()) {
            return;
        }

        autoTakeoff();

        if (mc.player.isFallFlying()) {
            if (isPacketFly()) return;

            velX = 0;
            velY = event.movement.y;
            velZ = 0;
            forward = Vec3.directionFromRotation(0, mc.player.getYRot()).scale(0.1);
            right = Vec3.directionFromRotation(0, mc.player.getYRot() + 90).scale(0.1);

            if (mc.player.isInWater() && stopInWater.get()) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                return;
            }

            handleFallMultiplier();
            handleAutopilot();
            handleAcceleration();
            handleHorizontalSpeed();
            handleVerticalSpeed();

            int chunkX = (int) ((mc.player.getX() + velX) / 16);
            int chunkZ = (int) ((mc.player.getZ() + velZ) / 16);
            if (dontGoIntoUnloadedChunks.get()) {
                if (mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    ((IVec3) event.movement).meteor$set(velX, velY, velZ);
                } else {
                    accelerationValue = 0;
                    ((IVec3) event.movement).meteor$set(0, velY, 0);
                }
            } else {
                ((IVec3) event.movement).meteor$set(velX, velY, velZ);
            }
        } else {
            if (lastForwardPressed) {
                mc.options.keyUp.setDown(false);
                lastForwardPressed = false;
            }
        }

        if (noCrash.get() && mc.player.isFallFlying()) {
            Vec3 lookAheadPos = mc.player.position().add(
                mc.player.getDeltaMovement().normalize().scale(crashLookAhead.get()));
            ClipContext clipContext = new ClipContext(
                mc.player.position(),
                new Vec3(lookAheadPos.x(), mc.player.getY(), lookAheadPos.z()),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
            BlockHitResult hitResult = mc.level.clip(clipContext);
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                ((IVec3) event.movement).meteor$set(0, velY, 0);
            }
        }

        if (autoHover.get()
            && mc.player.input.keyPresses.shift()
            && !Modules.get().get(Freecam.class).isActive()
            && mc.player.isFallFlying()) {
            BlockState underState = mc.level.getBlockState(mc.player.blockPosition().below());
            Block under = underState.getBlock();
            BlockState under2State = mc.level.getBlockState(mc.player.blockPosition().below().below());
            Block under2 = under2State.getBlock();

            final boolean underCollidable = ((BlockBehaviourAccessor) under).meteor$isHasCollision()
                || !underState.getFluidState().isEmpty();
            final boolean under2Collidable = ((BlockBehaviourAccessor) under2).meteor$isHasCollision()
                || !under2State.getFluidState().isEmpty();

            if (!underCollidable && under2Collidable) {
                ((IVec3) event.movement).meteor$set(event.movement.x, -0.1f, event.movement.z);
                mc.player.setXRot(Mth.clamp(mc.player.getXRot(0), -50.f, 20.f));
            }

            if (underCollidable) {
                ((IVec3) event.movement).meteor$set(event.movement.x, -0.03f, event.movement.z);
                mc.player.setXRot(Mth.clamp(mc.player.getXRot(0), -50.f, 20.f));

                if (mc.player.position().y <= mc.player.blockPosition().below().getY() + 1.34f) {
                    ((IVec3) event.movement).meteor$set(event.movement.x, 0, event.movement.z);
                    mc.player.setShiftKeyDown(false);
                }
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        InfiniteElytraSupport.onPacketSend(event);
        if (ElytraFlySupport.isCustomMode()) ElytraFlySupport.onPacketSend(event);

        // 发包模式：每发一个移动包就跟一发起飞包（Meteor 原样）
        if (isPacketFly() && event.packet instanceof ServerboundMovePlayerPacket && !event.isCancelled()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) accelerationValue = 0;
        if (ElytraFlySupport.isCustomMode()) ElytraFlySupport.onPacketReceive(event);
    }

    // ====== 原版模式（照搬 Meteor ElytraFlightMode + Vanilla） ======

    /** 官方 base onTick：自动补充烟花 + 自动替换鞘翅 */
    private void vanillaInventoryTick() {
        if (autoReplenish.get()) {
            FindItemResult fireworks = InvUtils.find(Items.FIREWORK_ROCKET);
            if (fireworks.found() && !fireworks.isHotbar()) {
                InvUtils.move().from(fireworks.slot()).toHotbar(replenishSlot.get() - 1);
            }
        }

        if (replace.get()) {
            ItemStack chestStack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (chestStack.getItem() == Items.ELYTRA
                && chestStack.getMaxDamage() - chestStack.getDamageValue() <= replaceDurability.get()) {
                FindItemResult elytra = InvUtils.find(stack ->
                    stack.getItem() == Items.ELYTRA
                        && stack.getMaxDamage() - stack.getDamageValue() > replaceDurability.get());
                if (elytra.found()) InvUtils.move().from(elytra.slot()).toArmor(2);
            }
        }
    }

    /** 官方 autoTakeoff：按住跳跃键满 8 tick 自动起飞 */
    private void autoTakeoff() {
        if (incrementJumpTimer) jumpTimer++;

        boolean jumpPressed = mc.options.keyJump.isDown();
        if (autoTakeOff.get() && jumpPressed) {
            if (!lastJumpPressed && !mc.player.isFallFlying()) {
                jumpTimer = 0;
                incrementJumpTimer = true;
            }

            if (jumpTimer >= 8) {
                jumpTimer = 0;
                incrementJumpTimer = false;
                mc.player.setJumping(false);
                mc.player.setSprinting(true);
                mc.player.jumpFromGround();
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
        }

        lastJumpPressed = jumpPressed;
    }

    /** 官方 handleAutopilot 的自动驾驶部分（自动烟花那半在 handleAutopilotFirework） */
    private void handleAutopilot() {
        if (autoPilot.get() && mc.player.getY() > autoPilotMinimumHeight.get()) {
            mc.options.keyUp.setDown(true);
            lastForwardPressed = true;
        }
    }

    /** 官方 handleAutopilot 的自动烟花部分 */
    private void handleAutopilotFirework() {
        if (!mc.player.isFallFlying()) return;
        if (ticksLeft > 0) {
            ticksLeft--;
            return;
        }

        ticksLeft = autoPilotFireworkDelay.get() * 20;

        FindItemResult itemResult = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!itemResult.found()) return;

        if (itemResult.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            mc.player.swing(InteractionHand.OFF_HAND);
        } else {
            InvUtils.swap(itemResult.slot(), true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
        }
    }

    private void handleHorizontalSpeed() {
        boolean a = false;
        boolean b = false;

        if (mc.options.keyUp.isDown()) {
            velX += forward.x * getSpeed() * 10;
            velZ += forward.z * getSpeed() * 10;
            a = true;
        } else if (mc.options.keyDown.isDown()) {
            velX -= forward.x * getSpeed() * 10;
            velZ -= forward.z * getSpeed() * 10;
            a = true;
        }

        if (mc.options.keyRight.isDown()) {
            velX += right.x * getSpeed() * 10;
            velZ += right.z * getSpeed() * 10;
            b = true;
        } else if (mc.options.keyLeft.isDown()) {
            velX -= right.x * getSpeed() * 10;
            velZ -= right.z * getSpeed() * 10;
            b = true;
        }

        if (a && b) {
            double diagonal = 1 / Math.sqrt(2);
            velX *= diagonal;
            velZ *= diagonal;
        }
    }

    private void handleVerticalSpeed() {
        if (mc.options.keyJump.isDown()) velY += 0.5 * verticalSpeed.get();
        else if (mc.options.keyShift.isDown()) velY -= 0.5 * verticalSpeed.get();
    }

    private void handleFallMultiplier() {
        if (velY < 0) velY *= fallMultiplier.get();
        else if (velY > 0) velY = 0;
    }

    private void handleAcceleration() {
        if (acceleration.get()) {
            if (!PlayerUtils.isMoving()) accelerationValue = 0;
            accelerationValue = Math.min(
                accelerationValue + accelerationMin.get() + accelerationStep.get() * .1,
                horizontalSpeed.get()
            );
        } else {
            accelerationValue = 0;
        }
    }

    private double getSpeed() {
        return acceleration.get() ? accelerationValue : horizontalSpeed.get();
    }

    // ====== 无限鞘翅·发包（照搬 Meteor 的 Packet 模式） ======

    private void packetTick() {
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA
            || mc.player.fallDistance <= 0.2
            || mc.options.keyShift.isDown()) {
            return;
        }

        if (mc.options.keyUp.isDown()) {
            packetVec.add(0, 0, horizontalSpeed.get());
            packetVec.yRot(-(float) Math.toRadians(mc.player.getYRot()));
        } else if (mc.options.keyDown.isDown()) {
            packetVec.add(0, 0, horizontalSpeed.get());
            packetVec.yRot((float) Math.toRadians(mc.player.getYRot()));
        }

        if (mc.options.keyJump.isDown()) {
            packetVec.add(0, verticalSpeed.get(), 0);
        } else {
            packetVec.add(0, -verticalSpeed.get(), 0);
        }

        mc.player.setDeltaMovement(packetVec);
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
    }

    // ====== 落地换回胸甲 / 立刻退出飞行（离线监听，Meteor 原样） ======

    private class GroundListener {
        @EventHandler
        private void onPlayerMove(PlayerMoveEvent event) {
            if (mc.player != null && mc.player.onGround()
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
                ChestSwap chestSwapModule = Modules.get().get(ChestSwap.class);
                if (chestSwapModule != null) chestSwapModule.swap();
                disableGroundListener();
            }
        }
    }

    private class InstaDropListener {
        @EventHandler
        private void onPostTick(TickEvent.Post event) {
            if (mc.player != null && mc.player.isFallFlying()) {
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
            } else {
                disableInstaDropListener();
            }
        }
    }

    private final GroundListener groundListener = new GroundListener();
    private final InstaDropListener instaDropListener = new InstaDropListener();

    private void enableGroundListener() {
        MeteorClient.EVENT_BUS.subscribe(groundListener);
    }

    private void disableGroundListener() {
        MeteorClient.EVENT_BUS.unsubscribe(groundListener);
    }

    private void enableInstaDropListener() {
        MeteorClient.EVENT_BUS.subscribe(instaDropListener);
    }

    private void disableInstaDropListener() {
        MeteorClient.EVENT_BUS.unsubscribe(instaDropListener);
    }

    // ====== HUD ======

    @Override
    public String getInfoString() {
        if (ElytraFlySupport.isLegalMode()) {
            return ElytraFlySupport.isArmorFlyActive() ? "合法+甲飞" : "合法";
        }
        if (ElytraFlySupport.isArmorFlyActive()) {
            return isVanillaMode() ? "原版+甲飞" : "甲飞";
        }
        if (isVanillaMode()) return "原版";

        return switch (infiniteMode.get()) {
            case Swap -> "无限鞘翅·换甲";
            case Packet -> "无限鞘翅·发包";
            case Off -> "关闭";
        };
    }

    /** 官方「胸甲切换」三档（名字与 Meteor 一致，老配置读得回来） */
    public enum ChestSwapMode {
        Never,
        Always,
        WaitForGround
    }
}
