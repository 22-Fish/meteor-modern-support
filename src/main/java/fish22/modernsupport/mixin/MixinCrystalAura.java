package fish22.modernsupport.mixin;

import com.google.common.util.concurrent.AtomicDouble;
import fish22.modernsupport.modules.GhostMine;
import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.LegalCrystal;
import fish22.modernsupport.utils.LegalPlace;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(value = CrystalAura.class, remap = false)
public abstract class MixinCrystalAura {

    /** 追加的枚举常量名（运行时才由 MixinCrystalAuraSupportMode 追上去，编译期取不到，只能按名字比） */
    @Unique
    private static final String ONLY_LEGIT_MODE = "OnlyLegit";

    /**
     * 放置距离留的余量（格）。
     *
     * <p>算角度时用的是「这一 tick 走完之后」的预判眼睛，和服务器真正拿到的那一帧总差一点点
     * （移动、重力、量化都有误差）。贴着极限距离放，射线就差这零点几格打不到方块，
     * Grim 的 RotationPlace 直接判定「没在看着方块」→ 触发它的 resync（就是你感觉到的卡脚回弹）。
     * 所以真正能放的距离要按「比服务器手长再退一点」算。
     */
    @Unique
    private static final double PLACE_REACH_MARGIN = 0.3;

    /** 已有支撑块位置被更有伤害的支撑块位置替换，至少要高出的伤害 */
    @Unique
    private static final double SUPPORT_SWITCH_MARGIN = 3.0;

    @Shadow
    private Setting<CrystalAura.SupportMode> support;

    @Shadow
    private SettingGroup sgPlace;

    @Shadow
    private SettingGroup sgSwitch;

    @Shadow
    private SettingGroup sgBreak;

    @Shadow
    private SettingGroup sgGeneral;

    @Shadow
    private Setting<Double> placeRange;

    @Shadow
    private Setting<Integer> supportDelay;

    @Shadow
    private Setting<Boolean> rotate;

    @Shadow
    private Setting<CrystalAura.AutoSwitchMode> autoSwitch;

    @Shadow
    public Setting<CrystalAura.SwingMode> swingMode;

    @Shadow
    private float getDamageToTargets(Vec3 vec3d, BlockPos obsidianPos, boolean breaking, boolean fast) { throw new AssertionError(); }

    @Shadow
    private void placeCrystal(BlockHitResult result, double damage, BlockPos supportBlock) { throw new AssertionError(); }

    /** 支撑块空中放置开关（默认关闭） */
    @Unique
    private Setting<Boolean> allowAirPlace;

    /** 背包水晶（切换分组） */
    @Unique
    private Setting<Boolean> backpackCrystal;

    /** 背包放置发包方式，水晶和支撑块共用 */
    @Unique
    private Setting<BackpackUse.Mode> backpackMode;

    /** 背包放置目标槽位，水晶和支撑块共用 */
    @Unique
    private Setting<BackpackUse.TargetSlot> backpackSlot;

    /** 自动放支撑块时允许用背包里的黑曜石 */
    @Unique
    private Setting<Boolean> backpackSupport;

    /** 支撑块自己的背包发包方式（和水晶分开配） */
    @Unique
    private Setting<BackpackUse.Mode> supportBackpackMode;

    /** 支撑块自己的背包目标槽位（和水晶分开配） */
    @Unique
    private Setting<BackpackUse.TargetSlot> supportBackpackSlot;

    /** 仅有利：只选对自己伤害小于对敌人伤害的位置 */
    @Unique
    private Setting<Boolean> onlyProfitable;

    /** 官方那一步算出来的「对自己伤害」（顺手记下来，「仅有利」用它） */
    @Unique
    private float candidateSelfDamage;

    /** 支撑块那格放不下黑曜石（是实体方块）时，自动把它挖掉（走「发包挖掘」） */
    @Unique
    private Setting<Boolean> autoMineSupport;

    /** 这一下 placeCrystal 用的是背包物品（快捷栏找不到、背包找到了） */
    @Unique
    private boolean backpackFake;

    /** 这一下要从背包用的物品 */
    @Unique
    private Item backpackFakeItem;

    /** placeCrystal 这一下真正要点的位置（已经过合法点替换） */
    @Unique
    private BlockHitResult placeHitResult;

    /** placeCrystal 这一下的预计伤害 */
    @Unique
    private double currentPlaceDamage;

    /** 这一下支撑块没放上（官方不看 BlockUtils.place 的返回值，得自己拦住后面的水晶） */
    @Unique
    private boolean supportPlaceFailed;

    /** 当前 doPlace 已经选出一个候选 */
    @Unique
    private boolean supportHasBest;

    /** 当前选出的候选是不是「要放支撑块」的位置 */
    @Unique
    private boolean supportBestIsSupport;

    /** 正在比较的候选是不是「要放支撑块」的位置 */
    @Unique
    private boolean supportCandidateIsSupport;

    /** 官方 isSupport 这个原子布尔（比较时顺手记下来，选出新候选时同步成我们跟踪的类型） */
    @Unique
    private AtomicBoolean supportIsSupportAtomic;

    @Unique
    private Setting<LegalRotation.Mode> legitMode;

    @Unique
    private Setting<Integer> legitPriority;

    @Unique
    private Setting<Boolean> rotateSupport;

    @Unique
    private Setting<Boolean> rotatePlace;

    @Unique
    private Setting<Boolean> rotateBreak;

    /** 调试输出（排查用，默认关） */
    @Unique
    private Setting<Boolean> debug;

    /** placeCrystal 收到的命中结果（「仅合法」放支撑块时直接拿它发包，和转头角度是同一份） */
    @Unique
    private BlockHitResult legitResult;

    /** 这一下放置有没有算到合法角度（没算到 = 官方的兜底结果，没转头） */
    @Unique
    private boolean legitAimFound;

    /** 这一下放置是不是按合法那一套来的（算了角度才算「要合法」，用来决定仅合法模式放不放） */
    @Unique
    private boolean legitAimRequired;

    /** 正在被破坏的那颗水晶（破坏那个调用点算合法角度要用它，见 redirectBreakRotate） */
    @Unique
    private Entity breakTarget;

    /** 当前 getBreakDamage 正在检查的水晶（范围判定要按它的碰撞箱算） */
    @Unique
    private Entity breakRangeEntity;

    /** legitResult 是不是「水晶自己那一下」用的（支撑块那一下点的是旁边的方块，不能拿去放水晶） */
    @Unique
    private boolean legitResultIsCrystal;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        CrystalAura self = (CrystalAura) (Object) this;

        // 支撑块模式多了一个「仅合法」（MixinCrystalAuraSupportMode 追到官方枚举上），
        // 这里只加一个空中放置开关
        allowAirPlace = new BoolSetting.Builder()
            .name("空中放置")
            .description("开启后允许把空气本身当可点击面；关闭时空气格必须有合法的相邻支撑面才放")
            .defaultValue(false)
            .visible(() -> support.get() != CrystalAura.SupportMode.Disabled)
            .build();
        insertAfter(sgPlace, "support-delay", allowAirPlace);

        backpackCrystal = new BoolSetting.Builder()
            .name("背包水晶")
            .description("允许使用背包中的水晶")
            .defaultValue(true)
            .build();
        insertAfter(sgSwitch, "anti-weakness", backpackCrystal);

        backpackSupport = new BoolSetting.Builder()
            .name("背包支撑块")
            .description("自动放置支撑块时允许使用背包中的黑曜石")
            .defaultValue(true)
            .visible(() -> support.get() != CrystalAura.SupportMode.Disabled)
            .build();
        insertAfter(sgPlace, "空中放置", backpackSupport);

        // 支撑块这格不是黑曜石、也放不下黑曜石（是个实体方块）时，先用「发包挖掘」把它挖掉
        autoMineSupport = new BoolSetting.Builder()
            .name("自动挖掘")
            .description("挑中的支撑位放不下黑曜石（是实体方块）时，先用「发包挖掘」把它挖掉，挖掉之后再放黑曜石")
            .defaultValue(false)
            .visible(() -> support.get() != CrystalAura.SupportMode.Disabled)
            .build();
        insertAfter(sgPlace, "support-delay", autoMineSupport);

        backpackMode = new EnumSetting.Builder<BackpackUse.Mode>()
            .name("背包放置发包模式")
            .description("背包水晶用，2次SWAP点击；4 次PICKUP点击")
            .defaultValue(BackpackUse.Mode.SWAP)
            .visible(backpackCrystal::get)
            .build();
        insertAfter(sgSwitch, "背包水晶", backpackMode);

        backpackSlot = new EnumSetting.Builder<BackpackUse.TargetSlot>()
            .name("背包放置目标槽位")
            .description("背包水晶用，背包物品切换的槽位")
            .defaultValue(BackpackUse.TargetSlot.OFFHAND)
            .visible(backpackCrystal::get)
            .build();
        insertAfter(sgSwitch, "背包放置发包模式", backpackSlot);

        // 支撑块的背包放置单独一份配置，和水晶那份互不影响
        supportBackpackMode = new EnumSetting.Builder<BackpackUse.Mode>()
            .name("支撑块背包发包模式")
            .description("背包支撑块用，2次SWAP点击；4 次PICKUP点击")
            .defaultValue(BackpackUse.Mode.SWAP)
            .visible(backpackSupport::get)
            .build();
        insertAfter(sgSwitch, "背包放置目标槽位", supportBackpackMode);

        supportBackpackSlot = new EnumSetting.Builder<BackpackUse.TargetSlot>()
            .name("支撑块背包目标槽位")
            .description("背包支撑块用，背包物品切换的槽位")
            .defaultValue(BackpackUse.TargetSlot.OFFHAND)
            .visible(backpackSupport::get)
            .build();
        insertAfter(sgSwitch, "支撑块背包发包模式", supportBackpackSlot);

        // 仅有利：对自己伤害不低于对敌人伤害的位置直接跳过（挂在官方伤害设置旁边）
        onlyProfitable = new BoolSetting.Builder()
            .name("仅有利")
            .description("只选择对自己造成的伤害小于对敌人造成的伤害的位置")
            .defaultValue(false)
            .build();
        insertAfter(sgGeneral, "anti-suicide", onlyProfitable);

        // 官方的「旋转」总开关从界面上拿掉，改成三个分开的开关，各归各的分组
        removeSetting(sgGeneral, "rotate");

        rotatePlace = new BoolSetting.Builder()
            .name("放置旋转")
            .description("放水晶时用合法转头（关闭就不转）")
            .defaultValue(true)
            .build();
        insertAfter(sgPlace, "place-delay", rotatePlace);

        rotateSupport = new BoolSetting.Builder()
            .name("支持块旋转")
            .description("放支撑块时用合法转头（关闭就不转）")
            .defaultValue(true)
            .visible(() -> support.get() != CrystalAura.SupportMode.Disabled)
            .build();
        insertAfter(sgPlace, "空中放置", rotateSupport);

        rotateBreak = new BoolSetting.Builder()
            .name("破坏旋转")
            .description("破坏水晶时用合法转头，攻击包排在移动包之后（关闭就不转）")
            .defaultValue(true)
            .build();
        insertAfter(sgBreak, "break-delay", rotateBreak);

        // 合法转头本体（模式和优先级）挂在设置最下面
        SettingGroup sgLegit = self.settings.createGroup("合法转头");

        legitMode = sgLegit.add(new EnumSetting.Builder<LegalRotation.Mode>()
            .name("合法转头")
            .description("合法转头模式。静默：移动方向还是跟着视角（推荐，水晶光环每 tick 都在转，严格模式会把走路方向也带跑）")
            .defaultValue(LegalRotation.Mode.QUIET)
            .build()
        );

        legitPriority = sgLegit.add(new IntSetting.Builder()
            .name("合法转头优先级")
            .description("同一 tick 里和其它模块抢转向时的优先级，大的赢")
            .defaultValue(0)
            .sliderRange(-20, 20)
            .build()
        );

        debug = sgLegit.add(new BoolSetting.Builder()
            .name("调试输出")
            .description("在聊天栏输出每一次放置的合法角度/转头/发包结果，排查用")
            .defaultValue(false)
            .onChanged(value -> LegalCrystal.debug = value)
            .build()
        );
        LegalCrystal.debug = debug.get();
    }

    /** 把设置从分组里拿掉（官方那个旋转总开关不显示也不存档了） */
    @Unique
    private static void removeSetting(SettingGroup group, String name) {
        List<Setting<?>> settings = ((SettingGroupAccessor) (Object) group).getSettings();
        settings.removeIf(setting -> setting.name.equals(name));
    }

    /** 把设置插到分组内指定名字的设置之后（找不到就追加到末尾） */
    @Unique
    private static void insertAfter(SettingGroup group, String afterName, Setting<?> setting) {
        List<Setting<?>> settings = ((SettingGroupAccessor) (Object) group).getSettings();
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).name.equals(afterName)) {
                settings.add(i + 1, setting);
                return;
            }
        }
        group.add(setting);
    }

    /**
     * 官方那个旋转总开关已经从界面上拿掉了，但它自己的放置回调里还在读它，
     * 这里按「放置旋转 / 支持块旋转」把它的值同步过去（哪个开着就转）。
     */
    @Inject(method = "doPlace", at = @At("HEAD"))
    private void onDoPlaceHead(CallbackInfo ci) {
        boolean wanted = rotatePlace.get() || rotateSupport.get();
        if (rotate.get() != wanted) rotate.set(wanted);

        supportHasBest = false;
        supportBestIsSupport = false;
        supportCandidateIsSupport = false;
        supportIsSupportAtomic = null;
    }

    /** 背包里有水晶时，官方那条「快捷栏没有水晶直接退」放行 */
    @Redirect(
        method = "doPlace",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/InvUtils;testInHotbar([Lnet/minecraft/world/item/Item;)Z"
        )
    )
    private boolean redirectDoPlaceHasCrystal(Item... items) {
        if (InvUtils.testInHotbar(items)) return true;
        return backpackCrystal.get() && InvUtils.find(Items.END_CRYSTAL).found();
    }

    /** auto-switch 关掉时，背包水晶也照样允许放置 */
    @Redirect(
        method = "doPlace",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/settings/Setting;get()Ljava/lang/Object;"
        )
    )
    private Object redirectDoPlaceAutoSwitch(Setting<?> setting) {
        Object value = setting.get();
        if (setting == autoSwitch && value == CrystalAura.AutoSwitchMode.None
            && backpackCrystal.get() && InvUtils.find(Items.END_CRYSTAL).found()) {
            return CrystalAura.AutoSwitchMode.Normal;
        }
        return value;
    }

    // ====== 支撑块选点：已有支撑块时，只有高出 3 点伤害的新支撑位才会替换 ======

    /**
     * 官方的 {@code isSupport} 既当「支撑块模式开没开」又当「当前最优是不是支撑位」用。
     * 一旦扫到现有黑曜石/基岩它就变成 false，后面的空气支撑位会被最上面那句
     * {@code !isSupport.get()} 直接跳过，于是变成「范围内有支撑块就不主动放」。
     *
     * <p>这里把两个用途拆开：这个调用只管模式开没开（模式开着就继续允许空气支撑位进入比较），
     * 当前最优是不是支撑位由下面那几个字段跟踪。
     */
    @Redirect(
        method = "lambda$doPlace$0(Ljava/util/concurrent/atomic/AtomicBoolean;Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicBoolean;get()Z", ordinal = 0)
    )
    private boolean redirectSupportGuard(AtomicBoolean isSupport) {
        supportIsSupportAtomic = isSupport;
        return support.get() != CrystalAura.SupportMode.Disabled;
    }

    /**
     * 比较条件里的那次 {@code isSupport.get()}：只在还没选出任何候选时保留官方的
     * 「优先拿一个现成支撑块垫底」，选出候选之后交给伤害比较（支撑位替换要过 3 点阈值）。
     */
    @Redirect(
        method = "lambda$doPlace$0(Ljava/util/concurrent/atomic/AtomicBoolean;Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicBoolean;get()Z", ordinal = 1)
    )
    private boolean redirectSupportCompare(AtomicBoolean isSupport) {
        supportIsSupportAtomic = isSupport;
        return !supportHasBest;
    }

    /** 这一步拿到候选的预计伤害，同时记下它是现成支撑块还是需要新放的支撑位 */
    @Redirect(
        method = "lambda$doPlace$0(Ljava/util/concurrent/atomic/AtomicBoolean;Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/systems/modules/combat/CrystalAura;getDamageToTargets(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/core/BlockPos;ZZ)F"
        )
    )
    private float redirectSupportCandidateDamage(CrystalAura self, Vec3 vec3d, BlockPos obsidianPos, boolean breaking, boolean fast) {
        float damage = getDamageToTargets(vec3d, obsidianPos, breaking, fast);
        BlockState state = mc.level.getBlockState(obsidianPos);
        supportCandidateIsSupport = !state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK);

        // 仅有利：对自己伤害不低于对敌人伤害的位置当它不存在（负伤害过不了官方那句最小伤害判定）
        if (onlyProfitable.get() && candidateSelfDamage >= damage) return -1.0f;

        return damage;
    }

    /**
     * 官方算「对自己伤害」那一步，顺手把值记下来（「仅有利」要比它和「对敌人伤害」的大小）。
     * 这里只记不再算一遍，官方怎么算的还是怎么算。
     */
    @Redirect(
        method = "lambda$doPlace$0(Ljava/util/concurrent/atomic/AtomicBoolean;Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/entity/DamageUtils;crystalDamage(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;ZLnet/minecraft/core/BlockPos;)F"
        )
    )
    private float redirectCandidateSelfDamage(LivingEntity target, Vec3 crystal, boolean predictMovement, BlockPos ignored) {
        float selfDamage = DamageUtils.crystalDamage(target, crystal, predictMovement, ignored);
        candidateSelfDamage = selfDamage;
        return selfDamage;
    }

    /**
     * 「自动挖掘」开着时，把「挖得动、又不是黑曜石/基岩」的实体方块也当成可替换：
     * 这种位置会照常进入伤害比较（划算就选中），真选中了再到 {@link #onPlaceCrystal} 里把它挖掉。
     * 挖不动的（基岩、屏障这些）照旧不算位置。
     */
    @Redirect(
        method = "lambda$doPlace$0(Ljava/util/concurrent/atomic/AtomicBoolean;Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;canBeReplaced()Z")
    )
    private boolean redirectSupportReplaceable(BlockState state) {
        if (state.canBeReplaced()) return true;
        if (!autoMineSupport.get() || support.get() == CrystalAura.SupportMode.Disabled) return false;
        return canAutoMine(state);
    }

    /** 已有现成支撑块时，新支撑位要比它高 3 点伤害才让官方那句比较通过 */
    @Redirect(
        method = "lambda$doPlace$0(Ljava/util/concurrent/atomic/AtomicBoolean;Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(value = "INVOKE", target = "Lcom/google/common/util/concurrent/AtomicDouble;get()D")
    )
    private double redirectCompareBestDamage(AtomicDouble bestDamage) {
        double best = bestDamage.get();
        if (support.get() != CrystalAura.SupportMode.Disabled
            && supportHasBest && !supportBestIsSupport && supportCandidateIsSupport) {
            return best + SUPPORT_SWITCH_MARGIN;
        }
        return best;
    }

    /** 官方写出新最优时，同步记录「最优是不是支撑位」，并把 isSupport 跟着修正 */
    @Redirect(
        method = "lambda$doPlace$0(Ljava/util/concurrent/atomic/AtomicBoolean;Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(value = "INVOKE", target = "Lcom/google/common/util/concurrent/AtomicDouble;set(D)V")
    )
    private void redirectSetBestDamage(AtomicDouble bestDamage, double value) {
        bestDamage.set(value);
        supportHasBest = true;
        supportBestIsSupport = supportCandidateIsSupport;
        if (supportIsSupportAtomic != null) supportIsSupportAtomic.set(supportCandidateIsSupport);
    }

    /** 官方每遇到一个现成支撑块都会把 isSupport 置 false，这里改成「当前最优是不是支撑位」 */
    @Redirect(
        method = "lambda$doPlace$0(Ljava/util/concurrent/atomic/AtomicBoolean;Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicBoolean;set(Z)V")
    )
    private void redirectSetIsSupport(AtomicBoolean isSupport, boolean value) {
        isSupport.set(supportHasBest && supportBestIsSupport);
    }

    /** 破坏这条路读官方旋转开关的地方，换成「破坏旋转」 */
    @Redirect(
        method = "doBreak(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/settings/Setting;get()Ljava/lang/Object;"
        )
    )
    private Object redirectBreakRotate(Setting<?> setting) {
        if (setting == rotate) return rotateBreak.get();
        return setting.get();
    }

    /**
     * 「保持上一次朝向」（onPreTickLast）读官方旋转开关的地方。
     *
     * <p><b>合法转头开着时这里必须返回 false，把官方的保持关掉。</b>
     * 官方的保持是在移动包发出去的那一刻，把上一次的朝向（还没动手时就是上一颗方块/水晶的朝向）
     * 塞进移动包（Meteor 的 Rotations 默认还保持 4 tick）。这一 tick 的移动是按玩家视角算的，
     * 服务器却被告知你在看别处 —— 它自己一预测方向就对不上，直接拉回（就是你感觉到的卡脚）。
     * 瞄准角度离视角越远（比如目标在侧后方）分叉越大，越容易触发。
     *
     * <p>合法转头自己会在动手那一 tick 把朝向写对（而且和移动方向是同一帧），
     * 中间没动作的 tick 就该老实跟着视角，不该再端着旧朝向。
     * 合法转头关掉时按官方原样（放置/破坏任一开着就保持）。
     */
    @Redirect(
        method = "onPreTickLast",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/settings/Setting;get()Ljava/lang/Object;"
        )
    )
    private Object redirectHoldRotate(Setting<?> setting) {
        if (setting == rotate) {
            return !legitRotationOn() && (rotatePlace.get() || rotateBreak.get());
        }
        return setting.get();
    }

    /** 空中放置关掉时，没有合法可点面的空气格不当支撑位（草、雪这些可替换方块照旧） */
    @Redirect(
        method = "doPlace",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockIterator;register(IILjava/util/function/BiConsumer;)V"
        )
    )
    private void redirectRegister(int horizontalRadius, int verticalRadius, BiConsumer<BlockPos, BlockState> callback) {
        if (allowAirPlace.get()) {
            BlockIterator.register(horizontalRadius, verticalRadius, callback);
            return;
        }

        BlockIterator.register(horizontalRadius, verticalRadius, (pos, state) -> {
            if (state.isAir() && LegalPlace.supportFaces(pos).isEmpty()) return;
            callback.accept(pos, state);
        });
    }

    // ====== 范围：脚底改成眼睛 ======

    /** isOutOfRange 里那个「是不是放置」的参数（范围判定要用它区分放置/破坏） */
    @Unique
    private boolean rangeCheckIsPlace;

    @Inject(method = "getBreakDamage", at = @At("HEAD"))
    private void onGetBreakDamageHead(Entity entity, boolean checkCrystalAge, CallbackInfoReturnable<Float> cir) {
        breakRangeEntity = entity;
    }

    @Inject(method = "getBreakDamage", at = @At("RETURN"))
    private void onGetBreakDamageReturn(Entity entity, boolean checkCrystalAge, CallbackInfoReturnable<Float> cir) {
        breakRangeEntity = null;
    }

    @Inject(method = "isOutOfRange", at = @At("HEAD"))
    private void onIsOutOfRangeHead(Vec3 vec3d, BlockPos blockPos, boolean place, CallbackInfoReturnable<Boolean> cir) {
        rangeCheckIsPlace = place;
    }

    /**
     * 官方的距离判定用的是玩家脚底坐标，这里换成眼睛到目标点的距离
     *
     * <p>放置用眼睛到目标点，破坏用眼睛到水晶碰撞箱最近点的距离
     *
     * <p>放置还要再收一层：超过服务器手长的距离本来就放不上去（Grim 更严，按它自己的手长算射线），
     * 硬要放就是回弹，所以放置的判定距离取「设置值」和「服务器手长退一点」里更小的那个
     */
    @Redirect(
        method = "isOutOfRange",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/PlayerUtils;isWithin(Lnet/minecraft/world/phys/Vec3;D)Z"
        )
    )
    private boolean redirectRangeCheck(Vec3 target, double range) {
        if (mc.player == null) return PlayerUtils.isWithin(target, range);

        double limit = rangeCheckIsPlace ? Math.min(range, placeReach()) : range;

        // 破坏跟杀戮光环一样：只要水晶碰撞箱在范围里就算够得着
        if (!rangeCheckIsPlace && breakRangeEntity != null) {
            return breakRangeEntity.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) <= limit * limit;
        }

        return mc.player.getEyePosition().distanceToSqr(target) <= limit * limit;
    }

    /**
     * 放置能真正生效的距离上限：设置值和服务器手长取小的那个，再退 {@link #PLACE_REACH_MARGIN} 格。
     *
     * <p>算角度（{@link LegalPlace}）和范围判定都用它，保证「模块愿意放的位置」和
     * 「服务器/Grim 认的位置」是同一个范围。
     */
    @Unique
    private double placeReach() {
        double serverReach = mc.player == null ? 4.5 : mc.player.blockInteractionRange();
        return Math.max(Math.min(placeRange.get(), serverReach) - PLACE_REACH_MARGIN, 0.0);
    }

    // ====== 放置：合法角度 + 命中点 ======

    /**
     * 官方挑完位置后调这个方法拿命中结果（朝下方那个方块的六个面中心各打一条射线）。
     * 打不中任何一面时它会原地编一个结果（方向只按高度猜），那个结果拿去发包对不上
     * 服务器对「你在看哪」的判定。这里换成 LegalPlace 提前算好合法角度，等这一下
     * 真的转了合法角度（见 modifyPlaceResult / redirectSupportPlace）再用这份数据发包。
     */
    @Inject(method = "getPlaceInfo", at = @At("RETURN"))
    private void onGetPlaceInfo(BlockPos blockPos, CallbackInfoReturnable<BlockHitResult> cir) {
        legitAimFound = false;
        legitAimRequired = false;
        legitResult = null;
        legitResultIsCrystal = true;

        BlockHitResult result = cir.getReturnValue();
        if (result == null || mc.player == null || mc.level == null) return;
        if (!legitRotationOn()) return;

        BlockPos clicked = result.getBlockPos();
        BlockState state = mc.level.getBlockState(clicked);
        // 下面这格不是黑曜石/基岩 → 这一下要先把支撑块放上去，点的是它旁边的方块
        boolean supportPath = !state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK);
        if (supportPath ? !rotateSupport.get() : !rotatePlace.get()) return;

        legitAimRequired = true;

        LegalPlace.Aim aim = supportPath
            ? findSupportAim(clicked)
            : findCrystalAim(clicked, result.getDirection());
        if (aim == null) {
            LegalCrystal.log("放置 算不出合法角度: 路径=%s 官方给的朝向=%s",
                supportPath ? "支撑块" : "水晶", result.getDirection());
            return;
        }

        legitResult = new BlockHitResult(aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock(), false);
        legitResultIsCrystal = !supportPath;
        LegalCrystal.setPending(aim.yaw(), aim.pitch(), legitMode.get(), legitPriority.get());
        legitAimFound = true;

        LegalCrystal.log("放置 合法角度: 路径=%s yaw=%.1f pitch=%.1f 面=%s 本来就看得到=%s",
            supportPath ? "支撑块" : "水晶", aim.yaw(), aim.pitch(), aim.face(), !aim.rotated());
    }

    /**
     * 偏航步骤检查说不转（返回 false）时，官方的旋转和回调都不会执行，
     * 这份角度也就用不上了：立刻作废，免得被这一 tick 里别的模块那次旋转认领。
     */
    @Inject(method = "doYawSteps(DD)Z", at = @At("RETURN"))
    private void onYawSteps(double targetYaw, double targetPitch, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return;

        LegalCrystal.clear();
        legitAimFound = false;
    }

    /**
     * 水晶直接放在黑曜石/基岩上：水晶落在「点到的那一格的上方」，点哪一面都行，
     * 所以六个面各算一次，先试官方本来想点的那面。
     *
     * <p>LegalPlace 的参数含义是「目标位置 → 被点方块的方向」，这里目标是水晶要落下去
     * 那一格（被点方块的上面），被点方块就是下面那块黑曜石本身。
     */
    @Unique
    private LegalPlace.Aim findCrystalAim(BlockPos clicked, Direction preferred) {
        double reach = placeReach();
        if (reach <= 0.0) return null;

        if (preferred != null) {
            LegalPlace.Aim aim = LegalPlace.compute(
                clicked.relative(preferred), List.of(preferred.getOpposite()), true, 0.0, reach);
            if (aim != null) return aim;
        }

        for (Direction face : Direction.values()) {
            if (face == preferred) continue;

            LegalPlace.Aim aim = LegalPlace.compute(
                clicked.relative(face), List.of(face.getOpposite()), true, 0.0, reach);
            if (aim != null) return aim;
        }

        return null;
    }

    /**
     * 支撑块要放到的是一格空气/可替换方块，操作方式是右键它旁边的实体方块、
     * 让方块落进这一格（水晶和别的方块不同，点下面那个支撑块就能放）。
     * 所以这里算的是「点旁边哪个方块、点它的哪个面」。
     */
    @Unique
    private LegalPlace.Aim findSupportAim(BlockPos target) {
        List<Direction> faces = LegalPlace.supportFaces(target);
        if (faces.isEmpty()) return null;

        double reach = placeReach();
        if (reach <= 0.0) return null;

        return LegalPlace.compute(target, faces, true, 0.0, reach);
    }

    @Inject(method = "placeCrystal", at = @At("HEAD"), cancellable = true)
    private void onPlaceCrystal(BlockHitResult result, double damage, BlockPos supportBlock, CallbackInfo ci) {
        currentPlaceDamage = damage;
        supportPlaceFailed = false;
        backpackFake = false;
        backpackFakeItem = null;

        // 「自动挖掘」：支撑块那格是实体方块（放不下黑曜石）→ 先让「发包挖掘」把它挖掉，这一下不放。
        // 挖掉之后这格变成空气，后面几 tick 官方那条支撑路自己会把黑曜石和水晶放上去
        if (supportBlock != null && autoMineSupport.get() && needsAutoMine(supportBlock)) {
            queueSupportMine(supportBlock);
            ci.cancel();
            return;
        }

        Item targetItem = supportBlock == null ? Items.END_CRYSTAL : Items.OBSIDIAN;
        if (backpackEnabledFor(targetItem) && !InvUtils.findInHotbar(targetItem).found()) {
            FindItemResult inv = InvUtils.find(targetItem);
            if (inv.found()) {
                backpackFake = true;
                backpackFakeItem = targetItem;
            }
        }

        // 仅合法模式下算不出合法角度 → 这一下不放（宁可少放一下，也不发一个对不上的包）
        if (isOnlyLegit() && legitAimRequired && !legitAimFound) {
            ci.cancel();
        }
    }

    /**
     * 发包用的命中点换成算出来的那个合法点：只有这一下真的转了合法角度才换，
     * 没转（比如偏航步骤拦下、或者合法转头关着）就用官方的结果，朝向和发包始终一致。
     */
    @ModifyVariable(method = "placeCrystal", at = @At("HEAD"), argsOnly = true, index = 1)
    private BlockHitResult modifyPlaceResult(BlockHitResult result) {
        boolean substitute = legitAimFound && legitResultIsCrystal && legitResult != null && LegalCrystal.appliedThisTick();

        LegalCrystal.log("放置发包: 命中点=%s 算到角度=%s 转头成功=%s",
            substitute ? "合法点" : "官方", legitAimFound, LegalCrystal.appliedThisTick());

        placeHitResult = substitute ? legitResult : result;
        return placeHitResult;
    }

    /** 快捷栏里没有时，给官方一个「假装在手持格」的结果，后面发包再换成背包交换 */
    @Redirect(
        method = "placeCrystal",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/InvUtils;findInHotbar([Lnet/minecraft/world/item/Item;)Lmeteordevelopment/meteorclient/utils/player/FindItemResult;"
        )
    )
    private FindItemResult redirectFindPlaceItem(Item... items) {
        FindItemResult normal = InvUtils.findInHotbar(items);
        if (normal.found() || !backpackFake || backpackFakeItem == null) return normal;

        FindItemResult inv = InvUtils.find(backpackFakeItem);
        if (!inv.found()) return normal;
        return new FindItemResult(mc.player.getInventory().getSelectedSlot(), inv.count());
    }

    /** 水晶这一下本来要发 useItemOn；背包模式改成同一 tick 换进目标槽位后再用 */
    @Redirect(
        method = "placeCrystal",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V"
        )
    )
    private void redirectCrystalPlacePacket(MultiPlayerGameMode gameMode, ClientLevel level, PredictiveAction action) {
        if (backpackFake && backpackFakeItem == Items.END_CRYSTAL && placeHitResult != null) {
            BackpackUse.place(stack -> stack.is(backpackFakeItem), placeHitResult,
                backpackMode.get(), backpackSlot.get(), false);
            return;
        }

        gameMode.startPrediction(level, action);
    }

    /**
     * 放支撑块：官方走 BlockUtils.place（自己再找一次该点哪一面，可能和我们算的那个面
     * 不是同一个）。仅合法下换成用刚才算好的合法命中点直接发包。
     */
    @Redirect(
        method = "placeCrystal",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;place(Lnet/minecraft/core/BlockPos;Lmeteordevelopment/meteorclient/utils/player/FindItemResult;ZIZZZ)Z"
        )
    )
    private boolean redirectSupportPlace(BlockPos pos, FindItemResult item, boolean rotate, int priority,
                                         boolean swing, boolean checkEntities, boolean swapBack) {
        if (backpackFake && backpackFakeItem == Items.OBSIDIAN) {
            BlockHitResult hit = supportPlaceHit(pos);
            if (hit == null) {
                supportPlaceFailed = true;
                return false;
            }

            boolean placed = BackpackUse.place(stack -> stack.is(backpackFakeItem), hit,
                supportBackpackMode.get(), supportBackpackSlot.get(), swing);
            if (!placed) supportPlaceFailed = true;
            if (placed) tryPlaceCrystalAfterSupport(pos);
            return placed;
        }

        boolean ownPacket = !legitResultIsCrystal && legitAimFound && legitResult != null && LegalCrystal.appliedThisTick();
        LegalCrystal.log("支撑块发包: %s", ownPacket ? "用合法点自己发" : "走官方BlockUtils");

        if (!ownPacket) {
            boolean placed = BlockUtils.place(pos, item, rotate, priority, swing, checkEntities, swapBack);
            if (!placed) supportPlaceFailed = true;
            if (placed) tryPlaceCrystalAfterSupport(pos);
            return placed;
        }

        InteractionHand hand = item.getHand();
        if (hand == null) {
            supportPlaceFailed = true;
            return false;
        }

        // 目标方块和面都是算好的那一份，直接发（调用方此时已经排在移动包之后）
        mc.gameMode.startPrediction(mc.level, sequence -> new ServerboundUseItemOnPacket(hand, legitResult, sequence));

        if (swingMode.get().client()) mc.player.swing(hand);
        if (swingMode.get().packet()) mc.getConnection().send(new ServerboundSwingPacket(hand));

        tryPlaceCrystalAfterSupport(pos);
        return true;
    }

    /**
     * 支撑块和水晶分成两 tick 放：这一 tick 只动一次手，而且水晶那一下得等服务器
     * 真把支撑块放上去了才点得动。所以走我们自己的支撑块放置时，支撑延迟至少当 1 用。
     */
    @Redirect(
        method = "placeCrystal",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/settings/Setting;get()Ljava/lang/Object;"
        )
    )
    private Object redirectGetInPlaceCrystal(Setting<?> setting) {
        Object value = setting.get();

        if (setting == supportDelay && !legitResultIsCrystal && legitResult != null
            && LegalCrystal.appliedThisTick() && value instanceof Integer ticks && ticks < 1) {
            return 1;
        }

        if (setting == supportDelay && !legitResultIsCrystal && supportPlaceFailed
            && value instanceof Integer ticks && ticks < 1) {
            return 1;
        }

        return value;
    }

    // ====== 破坏：先合法转头，攻击包排在移动包之后 ======

    // ====== 两个旋转调用点：精确换成合法转头 ======

    @Inject(method = "doBreak(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void onBreakHead(Entity crystal, CallbackInfo ci) {
        breakTarget = crystal;
    }

    /**
     * 破坏跟杀戮光环一样：本 tick 先算合法角度（瞄水晶碰撞箱上离眼睛最近的点），
     * 再把它交给合法转头，回调（攻击包）自然排在移动包之后。
     *
     * <p>直接替换官方的 {@code Rotations.rotate} 调用点，不经过 Meteor 那套静默旋转
     *（全局拦截那条留作兜底，见 {@link MixinRotations}）。
     */
    @Redirect(
        method = "doBreak(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V"
        )
    )
    private void redirectBreakRotate(double yaw, double pitch, int priority, Runnable callback) {
        if (prepareBreakRotation() && useLegitRotation(callback, "破坏")) return;
        Rotations.rotate(yaw, pitch, priority, callback);
    }

    /**
     * 放置那条一样：官方挑完位置、算好角度之后调 {@code Rotations.rotate}，
     * 这里换成合法转头（角度是 {@link #onGetPlaceInfo} 提前算好的那份）。
     *
     * <p>{@code require = 0}：这是编译生成的 lambda 方法名，万一哪天换了名字，
     * 这一条安静地不生效，还有 {@link MixinRotations} 那个稳定的全局兜底。
     */
    @Redirect(
        method = "lambda$doPlace$1(Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;Ljava/util/concurrent/atomic/AtomicBoolean;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V"
        ),
        require = 0
    )
    private void redirectPlaceRotate(double yaw, double pitch, int priority, Runnable callback) {
        if (useLegitRotation(callback, "放置")) return;
        Rotations.rotate(yaw, pitch, priority, callback);
    }

    /** 破坏用的合法角度：瞄碰撞箱上离眼睛最近的点 */
    @Unique
    private boolean prepareBreakRotation() {
        if (!legitRotationOn() || !rotateBreak.get()) return false;
        if (mc.player == null || breakTarget == null) return false;

        Vec3 point = closestPointOnBox(mc.player.getEyePosition(), breakTarget.getBoundingBox());
        LegalCrystal.setPending(
            Rotations.getYaw(point), Rotations.getPitch(point),
            legitMode.get(), legitPriority.get()
        );
        return true;
    }

    /**
     * 把排好的合法角度交给 {@link LegalRotation}（朝向跟着移动包走、回调排在移动包之后）。
     *
     * @return true = 已经走合法转头，调用方不要再调官方那句；false = 没角度或被顶掉，回退官方
     */
    @Unique
    private boolean useLegitRotation(Runnable callback, String what) {
        LegalCrystal.Pending pending = LegalCrystal.take();
        if (pending == null) return false;

        if (LegalRotation.rotate(pending.yaw(), pending.pitch(), pending.mode(), pending.priority(), callback)) {
            LegalCrystal.markApplied();
            LegalCrystal.log("%s 转头: 走合法转头API yaw=%.1f pitch=%.1f 模式=%s",
                what, pending.yaw(), pending.pitch(), pending.mode());
            return true;
        }

        LegalCrystal.log("%s 转头: 被更高优先级顶掉，回退官方旋转", what);
        return false;
    }

    @Unique
    private boolean legitRotationOn() {
        return legitMode != null && legitMode.get() != LegalRotation.Mode.OFF;
    }

    /** 支撑块模式是不是「仅合法」 */
    @Unique
    private boolean isOnlyLegit() {
        return support.get().name().equals(ONLY_LEGIT_MODE);
    }

    /** 这个物品走不走背包 */
    @Unique
    private boolean backpackEnabledFor(Item item) {
        if (item == Items.END_CRYSTAL) return backpackCrystal.get();
        if (item == Items.OBSIDIAN) return backpackSupport.get();
        return false;
    }

    /** 背包放支撑块用的命中点：优先用已经算好的合法点，没有就重新算一个 */
    @Unique
    private BlockHitResult supportPlaceHit(BlockPos support) {
        if (!legitResultIsCrystal && legitAimFound && legitResult != null && LegalCrystal.appliedThisTick()) {
            return legitResult;
        }

        Direction side = BlockUtils.getPlaceSide(support);
        if (side != null) {
            BlockPos clicked = support.relative(side);
            Vec3 hitPos = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
            return new BlockHitResult(hitPos, side.getOpposite(), clicked, false);
        }

        LegalPlace.Aim aim = findSupportAim(support);
        if (aim == null) return null;
        return new BlockHitResult(aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock(), false);
    }

    /** 支撑块放完，当前朝向还能对着它就把水晶也补上 */
    @Unique
    private void tryPlaceCrystalAfterSupport(BlockPos support) {
        if (placeHitResult == null) return;

        int delay = supportDelay.get();
        boolean legalSupport = !legitResultIsCrystal && legitResult != null && legitAimFound && LegalCrystal.appliedThisTick();
        if (delay == 0 && !legalSupport) return;

        if (canAimAtSupportNow(support, placeHitResult.getDirection())) {
            placeCrystal(placeHitResult, currentPlaceDamage, null);
        }
    }

    /** 当前服务器视角（没在合法转头就是玩家视角）还能不能打中新放支撑块的指定面 */
    @Unique
    private boolean canAimAtSupportNow(BlockPos support, Direction face) {
        if (mc.player == null || face == null) return false;

        double reach = placeReach();
        if (reach <= 0.0) return false;

        Vec3 eye = mc.player.getEyePosition();
        AABB box = new AABB(support);
        if (box.distanceToSqr(eye) > reach * reach) return false;

        float yaw = LegalRotation.isRotating() ? LegalRotation.getRealYaw() : mc.player.getYRot();
        float pitch = LegalRotation.isRotating() ? LegalRotation.getRealPitch() : mc.player.getXRot();
        Vec3 end = eye.add(mc.player.calculateViewVector(pitch, yaw).scale(reach));

        var hit = box.clip(eye, end);
        if (hit.isEmpty()) return false;
        return faceAt(support, hit.get()) == face;
    }

    /** 命中点落在方块的哪一面 */
    @Unique
    private static Direction faceAt(BlockPos pos, Vec3 point) {
        double west = Math.abs(point.x - pos.getX());
        double east = Math.abs(point.x - (pos.getX() + 1.0));
        double down = Math.abs(point.y - pos.getY());
        double up = Math.abs(point.y - (pos.getY() + 1.0));
        double north = Math.abs(point.z - pos.getZ());
        double south = Math.abs(point.z - (pos.getZ() + 1.0));
        double min = Math.min(Math.min(west, east), Math.min(Math.min(down, up), Math.min(north, south)));

        if (min == west) return Direction.WEST;
        if (min == east) return Direction.EAST;
        if (min == down) return Direction.DOWN;
        if (min == up) return Direction.UP;
        if (min == north) return Direction.NORTH;
        return Direction.SOUTH;
    }

    /** 碰撞箱上离眼睛最近的点（和杀戮光环那边用的是同一套算法） */
    @Unique
    private static Vec3 closestPointOnBox(Vec3 from, AABB box) {
        return new Vec3(
            Mth.clamp(from.x(), box.minX, box.maxX),
            Mth.clamp(from.y(), box.minY, box.maxY),
            Mth.clamp(from.z(), box.minZ, box.maxZ)
        );
    }

    // ====== 支撑块「自动挖掘」 ======

    /** 这格还放不下黑曜石（是实体方块），得先挖掉它 */
    @Unique
    private boolean needsAutoMine(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.canBeReplaced() && canAutoMine(state);
    }

    /** 这个方块挖得动吗（挖不动的：基岩这类、液体、空气） */
    @Unique
    private static boolean canAutoMine(BlockState state) {
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getBlock().defaultDestroyTime() < 0) return false;
        return !GhostMine.unbreakableBlocks.contains(state.getBlock());
    }

    /**
     * 把这格塞进「发包挖掘」的挖掘位（挖掘延迟、切工具、收尾都由它那套逻辑走）。
     *
     * <p>不开重挖框：这格挖掉之后是要放黑曜石的，留着框它会把黑曜石又挖掉。
     * <p>两个挖掘位都占着就这一 tick 不挖，下一 tick 再来（这时它还在挖别的方块）。
     */
    @Unique
    private void queueSupportMine(BlockPos pos) {
        GhostMine ghostMine = GhostMine.getInstance();
        if (ghostMine == null || !ghostMine.isActive()) {
            LegalCrystal.log("自动挖掘: 「发包挖掘」没开，这一格不挖 %s", pos.toShortString());
            return;
        }
        if (!ghostMine.ignoreRangeWhileMining.get() && PlayerUtils.distanceTo(pos) > ghostMine.range.get()) return;
        if (sameAsQueued(GhostMine.firstBlockDate, pos) || sameAsQueued(GhostMine.secondBlockDate, pos)) return;
        if (ghostMine.hasRebreakFrame(pos)) return;

        Direction side = BlockUtils.getDirection(pos);
        if (GhostMine.firstBlockDate == null) {
            GhostMine.firstBlockDate = ghostMine.getBlockDate(pos, side, false);
            return;
        }

        if (ghostMine.doubleBreak.get() && GhostMine.secondBlockDate == null) {
            GhostMine.secondBlockDate = ghostMine.getBlockDate(pos, side, false);
        }
    }

    @Unique
    private static boolean sameAsQueued(GhostMine.BlockDate block, BlockPos pos) {
        return block != null && block.pos.equals(pos);
    }
}
