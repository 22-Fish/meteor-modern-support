package fish22.modernsupport.utils;

import fish22.modernsupport.modules.Freeze;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅飞行增强逻辑（注入到 Meteor 官方 ElytraFly 模块，不新建模块）
 *
 * <p>通过 {@link fish22.modernsupport.mixin.MixinElytraFly} 给 Meteor 官方「鞘翅飞行」模块
 * （ElytraFly）添加「模式：[关闭/甲飞/合法平飞]」设置，本类承载全部业务逻辑。
 *
 * <h3>甲飞（穿胸甲假飞）</h3>
 * 鞘翅放热栏、胸甲槽穿胸甲，空中按跳跃键起飞，落地自动换回胸甲。分「普通/Grim」两种方式。
 *
 * <h3>合法平飞（参考 Epsilon ElytraFly Control 模式）</h3>
 * 穿真鞘翅，用 WASD/空格/潜行精确控制飞行方向（跟创造飞行一样），不输入时悬停原地。
 * 完全走原版滑翔机制（服务器旋转 + 滑翔物理 + 烟花加速），不换装不伪造。
 * <ul>
 *   <li>每 tick 按 WASD 相对当前视角计算目标偏航（8 方向），按空格/潜行计算目标俯仰
 *       （空格看天上升、潜行看地下降、仅移动时微俯 -1.9° 保持滑翔速度）</li>
 *   <li>通过 {@link MovementCorrection}（严格模式）把服务器视角转到目标角度，
 *       客户端视角不动——原版滑翔物理朝服务器视角方向自然加速</li>
 *   <li>无任何输入时悬停原地：悬停模式 = 每 tick 把速度置 (0, 0.02, 0)（抵消重力），
 *       可同时按间隔静默放烟花（仅为保持滑翔状态正常，防止反作弊拦截，不影响悬停）；
 *       冻结模式 = 复用 Freeze 模块的冻结效果（travel 取消 + 输入屏蔽 + 位置包拦截），完全静止</li>
 *   <li>自动烟花按烟花等级（1/2/3）分别配置间隔；烟花实体在服务器端沿服务器视角方向加速
 *       （寿命 1级≈20-31t、2级≈30-41t、3级≈40-51t）</li>
 * </ul>
 *
 * <p>玩家容器槽位（26.1）：盔甲 5-8（5 头盔 6 胸甲 7 腿甲 8 靴子）、
 * 主背包 9-35、热栏 36-44、副手 45。SWAP 包：目标槽 = 胸甲槽(6)，
 * 按钮 = 热栏索引 0-8（与胸甲槽互换）。
 */
public class ElytraFlySupport {

    // ====== 模式判断（追加的枚举值在编译期不可见，用 name 判断） ======

    /** 官方模式是否为「甲飞」 */
    public static boolean isArmorMode() {
        return flightMode != null && flightMode.get().name().equals("Armor");
    }

    /** 官方模式是否为「合法平飞」 */
    public static boolean isLegalMode() {
        return flightMode != null && flightMode.get().name().equals("Legal");
    }

    /** 官方模式是否为追加的（甲飞/合法平飞） */
    public static boolean isCustomMode() {
        return isArmorMode() || isLegalMode();
    }

    /** 当前是否处于甲飞状态（甲飞模块，或合法平飞开启了甲飞模式） */
    public static boolean isArmorFlyActive() {
        return isArmorMode()
            || (isLegalMode() && legalArmorMode != null && legalArmorMode.get() != LegalArmorMode.Off);
    }

    /** 甲飞方式 */
    public enum ArmorMode {
        Normal("普通"),
        Lazy("懒换"),
        TickLegacy("来回闪换"),
        Tick("每tick闪换");

        private final String displayName;

        ArmorMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 合法平飞悬停模式 */
    public enum HoverMode {
        Hover("悬停"),
        Freeze("冻结");

        private final String displayName;

        HoverMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 合法平飞的甲飞模式（关闭/普通/懒换/来回闪换/每tick闪换） */
    public enum LegalArmorMode {
        Off("关闭"),
        Normal("普通"),
        Lazy("懒换"),
        TickLegacy("来回闪换"),
        Tick("每tick闪换");

        private final String displayName;

        LegalArmorMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    // ====== 设置引用（MixinElytraFly 创建设置后注入） ======

    /** 官方模式设置（甲飞/合法平飞为 MixinElytraFlightModes 追加的枚举值，用 name 判断） */
    public static Setting<ElytraFlightModes> flightMode;
    public static Setting<ArmorMode> armorMode;
    public static Setting<Boolean> muteSounds;
    public static Setting<Boolean> autoFirework;
    public static Setting<Integer> fwIntervalLv1;
    public static Setting<Integer> fwIntervalLv2;
    public static Setting<Integer> fwIntervalLv3;
    public static Setting<Boolean> backpackFirework;
    public static Setting<BackpackUse.Mode> backpackMode;
    public static Setting<Integer> fwPriorityLv1;
    public static Setting<Integer> fwPriorityLv2;
    public static Setting<Integer> fwPriorityLv3;
    public static Setting<HoverMode> hoverMode;
    public static Setting<Boolean> notGlidingUnfreeze;
    public static Setting<Boolean> hoverFirework;
    public static Setting<Integer> hoverFwIntervalLv1;
    public static Setting<Integer> hoverFwIntervalLv2;
    public static Setting<Integer> hoverFwIntervalLv3;
    public static Setting<Boolean> autoSwapElytra;
    public static Setting<Boolean> discardMomentum;
    public static Setting<Boolean> freezeFirework;

    /** 合法平飞甲飞模式（关闭/普通/懒换/来回闪换/每tick闪换） */
    public static Setting<LegalArmorMode> legalArmorMode;
    /** 合法平飞甲飞的静音开关 */
    public static Setting<Boolean> legalMuteSounds;
    /** 一键烟花快捷键 */
    public static Setting<Keybind> oneKeyFirework;
    /** 一键烟花是否允许使用背包中的烟花 */
    public static Setting<Boolean> oneKeyBackpackFirework;

    // ====== 常量 ======

    /** 胸甲槽容器坐标（玩家容器） */
    private static final int CHEST_SLOT = 6;

    /** 热栏第 9 格（挪背包鞘翅时的目标格，热栏索引 8） */
    private static final int MOVE_TO_HOTBAR = 8;

    // ====== 状态 ======

    /** 上一 tick 是否处于飞行（区分"初始起飞需按跳跃"和"起飞失败/被取消后自动重启"） */
    private static boolean wasFlying = false;

    /** 上一 tick 跳跃键是否按下（检测按下事件，按下瞬间才触发起飞） */
    private static boolean jumpWasDown = false;

    /** 起飞请求进行中（按下跳跃后持续尝试，直到起飞成功或落地） */
    private static boolean takeoffRequested = false;

    /** 起飞包重试间隔剩余 tick 数（服务器拒绝后隔一段时间自动重发，避免反复滑翔/取消） */
    private static int takeoffRetryTicks = 0;

    /** 上一 tick 是否滑翔（检测滑翔状态转变，起飞成功瞬间放烟花） */
    private static boolean prevFlying = false;

    /** 来回闪换（TICK_LEGACY）当前胸甲槽是否鞘翅：true=鞘翅在上待换回，false=胸甲在上待换鞘翅 */
    private static boolean legacyElytraOn = false;

    /** 来回闪换记住的热栏鞘翅槽位（换鞘翅后鞘翅跑到胸甲槽，换回必须用此槽位而非重新查找） */
    private static int legacyElytraSlot = -1;

    /** 被拦截待重发的烟花使用包（手动右键烟花时拦截，延迟到换鞘翅+起飞后重发） */
    private static ServerboundUseItemPacket pendingFireworkPacket = null;

    /** 正在重发烟花使用包（重发会再次触发 onPacketSend，置此标志避免重复拦截） */
    private static boolean flushingFirework = false;

    /** 距下次自动烟花的剩余 tick 数（合法平飞，飞行/悬停共用） */
    private static int legalFwCooldown = 0;

    /** 起飞烟花已排队标志：同一 tick 的飞行/悬停自动烟花检查到此标志直接跳过，避免一次起飞双放烟花 */
    private static boolean takeoffFireworkPending = false;

    /** 一键烟花待释放：甲飞开启且按下快捷键时不在滑翔，延后到下次滑翔再释放 */
    private static boolean oneKeyPending = false;

    /** 音效屏蔽监听器（甲飞换装音效） */
    private static final SoundListener SOUND_LISTENER = new SoundListener();

    private ElytraFlySupport() {
    }

    // ====== 生命周期（由 MixinElytraFly 调用） ======

    public static void onActivate() {
        wasFlying = false;
        legacyElytraOn = false;
        legacyElytraSlot = -1;
        pendingFireworkPacket = null;
        legalFwCooldown = 0;
        takeoffFireworkPending = false;
        oneKeyPending = false;
        jumpWasDown = false;
        takeoffRequested = false;
        takeoffRetryTicks = 0;
        prevFlying = false;
        Freeze.setExternalFrozen(false);
        MeteorClient.EVENT_BUS.subscribe(SOUND_LISTENER);
    }

    public static void onDeactivate() {
        // 解除合法平飞可能挂上的外部冻结
        Freeze.setExternalFrozen(false);
        MeteorClient.EVENT_BUS.unsubscribe(SOUND_LISTENER);
    }

    /** 每 tick 主逻辑（TickEvent.Pre，由 MixinElytraFly 拦截官方 onPreTick 后调用） */
    public static void onTick() {
        if (mc.player == null) return;

        if (isArmorMode()) {
            armorTick();
        } else if (isLegalMode()) {
            legalTick();
        }

        // 一键烟花：甲飞开启时按下快捷键不在滑翔，延后到下次滑翔再释放
        checkOneKeyPending();
    }

    /** 发包监听（由 MixinElytraFly 拦截官方 onPacketSend 后调用） */
    public static void onPacketSend(PacketEvent.Send event) {
        // 甲飞：拦截手动烟花使用包，延迟到换鞘翅+起飞后重发。
        // 手动右键烟花时客户端已因本地强制滑翔（isFallFlying=true）发出使用包，
        // 但此时服务器可能已停飞（穿胸甲），直接发出去服务器不发射；卡到滑翔窗口再发。
        if (isArmorFlyActive() && event.packet instanceof ServerboundUseItemPacket useItem) {
            if (!flushingFirework && isFireworkInHand(useItem.getHand())) {
                pendingFireworkPacket = useItem;
                event.cancel();
            }
            return;
        }

        // 合法平飞悬停冻结时拦截位置移动包（旋转包照发，可正常转头）
        if (!isLegalMode() || hoverMode.get() != HoverMode.Freeze) return;
        if (!Freeze.isFrozen()) return;
        if (event.packet instanceof ServerboundMovePlayerPacket movePacket && movePacket.hasPosition()) {
            event.cancel();
        }
    }

    /** 收包监听（由 MixinElytraFly 拦截官方 onPacketReceive 后调用） */
    public static void onPacketReceive(PacketEvent.Receive event) {
        // 甲飞不再有 Grim 回弹退避逻辑，收包无需处理
    }

    // ====== 甲飞逻辑 ======

    private static void armorTick() {
        // 打开容器/界面时不动手，避免误点
        if (mc.player.containerMenu.containerId != 0) return;

        // 落地/进水：恢复正常状态（胸甲槽若还是鞘翅则换回胸甲）
        if (mc.player.onGround() || mc.player.isInWater()) {
            swapBackChestplate();
            wasFlying = false;
            legacyElytraOn = false;
            legacyElytraSlot = -1;
            return;
        }

        armorFlySwap(armorMode.get());
    }

    /** 按甲飞方式分派换装逻辑（甲飞模块与合法平飞甲飞共用） */
    private static void armorFlySwap(ArmorMode mode) {
        switch (mode) {
            case Lazy -> lazyTick();
            case TickLegacy -> tickLegacyTick();
            case Tick -> tickTick();
            default -> normalTick();
        }
    }

    // ====== 普通模式 ======

    /** 普通：每 tick 闪换 + 本地强制滑翔（适合原版服务器） */
    private static void normalTick() {
        // 空中（onGround=false，armorTick 已判）自动强制滑翔 + 换装，无需按跳跃
        if (!mc.player.isFallFlying()) {
            mc.player.startFallFlying();
        }
        wasFlying = true;

        FindItemResult elytra = findElytra();
        if (elytra == null) return;

        // 每 tick 闪换：鞘翅上位 → 起飞包 → 换回胸甲（PICKUP 移动交换，鞘翅可在背包）
        swapElytra(elytra.slot());
        sendStartFlying();
        flushPendingFirework();
        swapElytra(elytra.slot());
    }

    // ====== 懒换（LAZY）======

    /** 懒换：滑翔中不动，只在服务器判定停飞时才做一次「换鞘翅 → 起飞 → 换回」 */
    private static void lazyTick() {
        // 已在滑翔：什么都不做（懒）
        if (mc.player.isFallFlying()) {
            wasFlying = true;
            return;
        }

        // 空中停飞：自动换装（无需按跳跃）
        wasFlying = true;

        FindItemResult elytra = findElytra();
        if (elytra == null) return;

        swapElytra(elytra.slot());
        sendStartFlying();
        flushPendingFirework();
        swapElytra(elytra.slot());
    }

    // ====== 来回闪换（TICK_LEGACY）======

    /** 来回闪换：每 tick 交替换鞘翅/换回胸甲，配合起飞包维持服务器滑翔状态 */
    private static void tickLegacyTick() {
        wasFlying = true;

        // 首次找鞘翅并记住槽位；PICKUP 互换两次回到原位，后续都用这个固定槽位，
        // 不能每 tick 重新查找——换鞘翅后鞘翅已跑到胸甲槽，背包里找不到鞘翅会导致换回失败
        if (legacyElytraSlot == -1) {
            FindItemResult elytra = findElytra();
            if (elytra == null) return;
            legacyElytraSlot = elytra.slot();
        }

        if (!legacyElytraOn) {
            // 胸甲在上：换鞘翅并起飞
            swapElytra(legacyElytraSlot);
            sendStartFlying();
            flushPendingFirework();
            legacyElytraOn = true;
        } else {
            // 鞘翅在上：换回胸甲
            swapElytra(legacyElytraSlot);
            legacyElytraOn = false;
        }
    }

    // ====== 每 tick 闪换（TICK）======

    /** 每 tick 闪换：换鞘翅 → 起飞 → 换回 */
    private static void tickTick() {
        wasFlying = true;

        FindItemResult elytra = findElytra();
        if (elytra == null) return;

        swapElytra(elytra.slot());
        sendStartFlying();
        flushPendingFirework();
        swapElytra(elytra.slot());
    }

    // ====== 合法平飞逻辑（参考 Epsilon ElytraFly Control 模式） ======

    private static void legalTick() {
        // 合法平飞开启了甲飞模式：走甲飞换装 + 方向控制
        if (legalArmorMode != null && legalArmorMode.get() != LegalArmorMode.Off) {
            legalArmorTick();
            return;
        }
        legalNormalTick();
    }

    /** 合法平飞（真鞘翅） */
    private static void legalNormalTick() {
        // 打开容器/界面时不动手
        if (mc.player.containerMenu.containerId != 0) return;

        // 死亡/死亡画面：解除冻结并取消起飞请求，避免死亡后冻结状态残留（重生后卡住动不了）
        if (mc.player.isDeadOrDying()) {
            Freeze.setExternalFrozen(false);
            takeoffRequested = false;
            takeoffRetryTicks = 0;
            return;
        }

        // 跳跃键按下事件（上升沿）：按下瞬间才触发起飞，按住不重复触发
        boolean jumpPressed = mc.options.keyJump.isDown() && !jumpWasDown;
        jumpWasDown = mc.options.keyJump.isDown();

        // 移动输入（起飞分支解冻判断用，飞行分支复用）
        boolean forward = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();

        // 滑翔状态转变（起飞成功瞬间）：立即释放一次烟花，不等自动烟花间隔
        boolean flying = mc.player.isFallFlying();
        if (flying && !prevFlying && autoFirework.get()) {
            tryFireworkOnce();
        }
        prevFlying = flying;

        // 落地/进水：解除冻结；开了自动替换则把鞘翅换回胸甲
        if (mc.player.onGround() || mc.player.isInWater()) {
            // 滑翔贴地/入水：本地滑翔状态还在时不动手，等服务器广播停止滑翔后下 tick 再处理
            // （避免把滑翔擦地误判成落地，导致换装来回切换）
            Freeze.setExternalFrozen(false);
            if (mc.player.isFallFlying()) {
                takeoffRequested = false;
                takeoffRetryTicks = 0;
                return;
            }
            takeoffRequested = false;    // 落地取消起飞请求
            takeoffRetryTicks = 0;
            if (autoSwapElytra.get()) {
                swapBackChestplate();
            }
            return;
        }

        // 起飞：空中未滑翔
        if (!mc.player.isFallFlying()) {
            // 不在滑翔（落地/珍珠传送打断等）：按移动键（WASD/跳跃）即解除冻结，
            // 不需要起飞放烟花；开启「不在滑翔解冻」则无条件解除（后续功能预留）。
            // 冻结时 travel 被取消、onGround 不更新，落地分支可能检测不到，
            // 这里用服务器同步的滑翔状态 + 移动输入兜底
            if (notGlidingUnfreeze.get() || forward || back || left || right || mc.options.keyJump.isDown()) {
                Freeze.setExternalFrozen(false);
            }
            if (autoSwapElytra.get()) {
                // 自动替换：跳跃键按下瞬间发起起飞请求；
                // 起飞未成功前按间隔自动重试（服务器拒绝起飞包时等几 tick 再重发），直到成功或落地。
                // 本地不假滑翔：等服务器广播滑翔状态后再走滑翔物理，
                // 避免本地速度突变与服务器未滑翔状态不同步（近地回弹根因）
                if (jumpPressed) {
                    takeoffRequested = true;
                }
                if (!takeoffRequested) return;
                if (isElytraEquipped()) {
                    // 鞘翅已穿（PICKUP 移动交换本地同步执行，立即生效）：按间隔重发起飞包
                    if (takeoffRetryTicks <= 0) {
                        sendStartFlying();
                        takeoffRetryTicks = 5;
                    } else {
                        takeoffRetryTicks--;
                    }
                } else {
                    // 换鞘翅：PICKUP 移动交换（Meteor ChestSwap 同款）把热栏/背包任意位置的鞘翅
                    // 一步换到胸甲槽，被换下的胸甲进鼠标携带由 close 包放回背包；
                    // 不需要空位，背包满也不会失败；起飞包同批发出（服务器按包序：换装→起飞）
                    FindItemResult elytra = InvUtils.find(Items.ELYTRA);
                    if (!elytra.found()) {
                        takeoffRequested = false;
                        return;
                    }
                    InvUtils.move().from(elytra.slot()).toArmor(2);
                    mc.getConnection().send(new ServerboundContainerClosePacket(0));
                    sendStartFlying();
                    takeoffRetryTicks = 5;
                }
                // 服务器广播滑翔状态后视为起飞成功
                if (mc.player.isFallFlying()) {
                    takeoffRequested = false;
                    takeoffRetryTicks = 0;
                }
                return;
            }
            if (!isElytraEquipped()) return;
            if (!jumpPressed) return;
            tryStartFallFlying();
            return;
        }

        legalFlightControl(forward, back, left, right);
    }

    /** 合法平飞 + 甲飞：用甲飞换装维持滑翔，叠加合法平飞方向控制 */
    private static void legalArmorTick() {
        if (mc.player.containerMenu.containerId != 0) return;

        if (mc.player.isDeadOrDying()) {
            Freeze.setExternalFrozen(false);
            return;
        }

        boolean forward = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();

        // 滑翔状态转变（起飞成功瞬间）放一次烟花
        boolean flying = mc.player.isFallFlying();
        if (flying && !prevFlying && autoFirework.get()) {
            tryFireworkOnce();
        }
        prevFlying = flying;

        // 落地/进水：解除冻结并换回胸甲
        if (mc.player.onGround() || mc.player.isInWater()) {
            Freeze.setExternalFrozen(false);
            if (mc.player.isFallFlying()) return;
            swapBackChestplate();
            legacyElytraOn = false;
            legacyElytraSlot = -1;
            return;
        }

        // 甲飞换装维持滑翔（按甲飞模式）
        armorFlySwap(toArmorMode(legalArmorMode.get()));

        // 滑翔中应用方向控制
        if (mc.player.isFallFlying()) {
            legalFlightControl(forward, back, left, right);
        }
    }

    /** 合法平飞飞行/悬停方向控制（真鞘翅与甲飞模式共用） */
    private static void legalFlightControl(boolean forward, boolean back, boolean left, boolean right) {
        boolean jump = mc.options.keyJump.isDown();
        boolean sneak = mc.options.keyShift.isDown();

        // 相反方向键同时按：输入互相抵消，视为未按（W+S / A+D 不会斜下坠）
        if (forward && back) {
            forward = false;
            back = false;
        }
        if (left && right) {
            left = false;
            right = false;
        }

        // ====== 无输入：悬停原地 ======
        // 纯向上限制（无烟花+低速时空格不转向上 → 走悬停）已注释掉：
        // 空格按着时悬停会被下一 tick 的输入判断立刻取消，逻辑有问题，等有好方案再启用。
        // boolean upBlocked = noDirection && jump && !sneak
        //     && !hasActiveFirework() && mc.player.getDeltaMovement().length() < 1.0;
        // if (hover || upBlocked) {
        // 空格+潜行（无方向键）也视为悬停：原地不动，而不是向前平飞
        boolean noDirection = !forward && !back && !left && !right;
        boolean hover = noDirection && !jump && !sneak;
        boolean hoverCombo = noDirection && jump && sneak;
        if (hover || hoverCombo) {
            if (hoverMode.get() == HoverMode.Freeze) {
                // 冻结悬停：完全静止（travel 取消 + 输入屏蔽由 Freeze 外部冻结提供，
                // 位置移动包由 onPacketSend 拦截，旋转包照发可正常转头）
                Freeze.setExternalFrozen(true);
                // 丢弃动量：冻结期间清空速度（含重力累积），解冻后从零开始
                if (discardMomentum.get()) {
                    mc.player.setDeltaMovement(0, 0, 0);
                }
                // 冻结烟花：开启时冻结期间冷却暂停（前后加起来算一次完整烟花周期）；
                // 关闭时冷却照常递减（烟花实体在冻结期间也会消耗寿命），
                // 解除冻结后冷却已到就会正常立即释放，不会出现冷却到了却不放的问题
                if (!freezeFirework.get() && legalFwCooldown > 0) {
                    legalFwCooldown--;
                }
            } else {
                // 悬停：抵消重力停在空中（参考 Epsilon：每 tick 覆盖速度）；
                // 悬停自动烟花仅为保持滑翔状态正常（防反作弊拦截），不影响悬停
                Freeze.setExternalFrozen(false);
                if (hoverFirework.get()) {
                    tickHoverFirework();
                }
                mc.player.setDeltaMovement(0, 0.02, 0);
            }
            return;
        }

        // ====== 有输入：飞行 ======
        // 先转向再解除冻结（同 tick）：避免解除冻结后先沿旧朝向移动再转头
        float targetYaw = calcLegalYaw(forward, back, left, right);
        float targetPitch = calcLegalPitch(jump, sneak);
        MovementCorrection.rotate(targetYaw, targetPitch, MovementCorrection.Mode.SEVERE);
        Freeze.setExternalFrozen(false);

        // 飞行中自动烟花（释放延后到移动包发送后，烟花加速方向才能跟随服务器视角）
        if (autoFirework.get()) {
            tickFlightFirework();
        }
    }

    /** 计算目标偏航：相对当前视角的 8 方向（W 前 / S 后 / A 左 / D 右 / 斜向 45°） */
    private static float calcLegalYaw(boolean forward, boolean back, boolean left, boolean right) {
        float yaw = mc.player.getYRot();
        if (forward && !back) {
            if (left && !right) {
                yaw -= 45;
            } else if (right && !left) {
                yaw += 45;
            }
        } else if (back && !forward) {
            yaw += 180;
            if (left && !right) {
                yaw += 45;
            } else if (right && !left) {
                yaw -= 45;
            }
        } else if (left && !right) {
            yaw -= 90;
        } else if (right && !left) {
            yaw += 90;
        }
        return Mth.wrapDegrees(yaw);
    }

    /**
     * 计算目标俯仰：空格看天、潜行看地；配合方向键移动时斜着升/降（±45），
     * 单独按（不移动）垂直升/降（∓90）；空格+潜行平飞（-3）、
     * 仅移动时微俯 -1.9°（原版滑翔需要微俯视才有水平加速，保持速度不掉）。
     * 纯向上限制（无烟花+低速时空格不转向上）在飞行分支入口以悬停方式处理。
     */
    private static float calcLegalPitch(boolean jump, boolean sneak) {
        float pitch = mc.player.getXRot();
        boolean moving = PlayerUtils.isMoving();
        if (sneak && jump) {
            pitch = -3;
        } else if (jump) {
            pitch = moving ? -45 : -90;
        } else if (sneak) {
            pitch = moving ? 45 : 90;
        } else if (moving) {
            pitch = -1.9f;
        }
        return Mth.clamp(pitch, -90, 90);
    }

    /**
     * 是否有附着在自己身上的活跃烟花实体（有烟花加速中）。
     * 纯向上限制使用，已随该功能一并注释，待有方案再启用。
     */
    /* 已注释：纯向上限制暂停启用
    private static boolean hasActiveFirework() {
        if (mc.level == null || mc.player == null) return false;
        for (FireworkRocketEntity rocket : mc.level.getEntitiesOfClass(FireworkRocketEntity.class, mc.player.getBoundingBox().inflate(16.0))) {
            if (rocket.isAlive() && rocket.getOwner() == mc.player) return true;
        }
        return false;
    }
    */

    /** 飞行中自动烟花：间隔到且有烟花 → 延后到移动包发送后释放（烟花加速方向跟随服务器视角） */
    private static void tickFlightFirework() {
        // 起飞烟花已排队：同一 tick 的自动烟花跳过，避免两个回调同 tick 都执行导致双放
        if (takeoffFireworkPending) return;
        if (legalFwCooldown > 0) {
            legalFwCooldown--;
            return;
        }
        int level = selectFireworkLevel();
        if (level == -1) return;
        int interval = fwIntervalForLevel(level);
        MovementCorrection.runAfterSend(() -> {
            if (tryUseFireworkOfLevel(level)) {
                legalFwCooldown = interval;
            }
        });
    }

    /** 起飞后立即释放一次烟花（延后到移动包发送后，方向跟随服务器视角），并重置冷却 */
    private static void tryFireworkOnce() {
        int level = selectFireworkLevel();
        if (level == -1) return;
        int interval = fwIntervalForLevel(level);
        // 起飞烟花排队中：本 tick 的自动烟花（飞行/悬停分支）检查到此标志直接跳过，
        // 防止两个回调同 tick 都执行（runAfterSend 队列化后都会执行）导致一次起飞双放
        takeoffFireworkPending = true;
        MovementCorrection.runAfterSend(() -> {
            takeoffFireworkPending = false;
            if (tryUseFireworkOfLevel(level)) {
                legalFwCooldown = interval;
            }
        });
    }

    /** 悬停中自动烟花：间隔到且有烟花 → 延后到移动包发送后释放 */
    private static void tickHoverFirework() {
        // 起飞烟花已排队：同一 tick 的自动烟花跳过，避免双放
        if (takeoffFireworkPending) return;
        if (legalFwCooldown > 0) {
            legalFwCooldown--;
            return;
        }
        int level = selectFireworkLevel();
        if (level == -1) return;
        int interval = hoverFwIntervalForLevel(level);
        MovementCorrection.runAfterSend(() -> {
            if (tryUseFireworkOfLevel(level)) {
                legalFwCooldown = interval;
            }
        });
    }

    /** 按烟花等级取飞行间隔 */
    private static int fwIntervalForLevel(int level) {
        return switch (level) {
            case 2 -> fwIntervalLv2.get();
            case 3 -> fwIntervalLv3.get();
            default -> fwIntervalLv1.get();
        };
    }

    /** 按烟花等级取悬停间隔 */
    private static int hoverFwIntervalForLevel(int level) {
        return switch (level) {
            case 2 -> hoverFwIntervalLv2.get();
            case 3 -> hoverFwIntervalLv3.get();
            default -> hoverFwIntervalLv1.get();
        };
    }

    /** 自动烟花用：按「背包烟花」设置决定是否查背包 */
    private static int selectFireworkLevel() {
        return selectFireworkLevel(backpackFirework.get());
    }

    /**
     * 按优先级选择要使用的烟花等级；没有可用烟花返回 -1。
     * searchBackpack 决定是否把背包中的烟花也纳入考虑。
     * 同时存在多个等级时用优先级高的；多个等级同优先级时遵循原逻辑（快捷栏第一个烟花的等级）。
     */
    private static int selectFireworkLevel(boolean searchBackpack) {
        int bestPriority = -1;
        int bestLevel = -1;
        int tieCount = 0;
        for (int level = 1; level <= 3; level++) {
            if (!hasFireworkOfLevel(level, searchBackpack)) continue;
            int priority = priorityOf(level);
            if (priority > bestPriority) {
                bestPriority = priority;
                bestLevel = level;
                tieCount = 1;
            } else if (priority == bestPriority) {
                tieCount++;
            }
        }
        if (bestLevel == -1) return -1;
        // 多个等级同优先级 → 原逻辑（快捷栏第一个烟花的等级）；
        // 快捷栏没有可用烟花时回退优先级最高的等级（否则背包 2/3 级烟花永远选不中）
        if (tieCount > 1) {
            int hotbarLevel = getHotbarFireworkLevel();
            if (hasFireworkOfLevel(hotbarLevel, searchBackpack)) return hotbarLevel;
        }
        return bestLevel;
    }

    /** 指定等级的烟花优先级 */
    private static int priorityOf(int level) {
        return switch (level) {
            case 2 -> fwPriorityLv2.get();
            case 3 -> fwPriorityLv3.get();
            default -> fwPriorityLv1.get();
        };
    }

    /** 是否存在指定等级的烟花（searchBackpack 为 true 查全背包，否则只查快捷栏） */
    private static boolean hasFireworkOfLevel(int level, boolean searchBackpack) {
        Predicate<ItemStack> pred = fireworkOfLevel(level);
        if (searchBackpack) {
            return pred.test(mc.player.getOffhandItem())
                || pred.test(mc.player.getMainHandItem())
                || InvUtils.find(pred).found();
        }
        return InvUtils.findInHotbar(pred).found();
    }

    /** 指定等级的烟花判断 */
    private static Predicate<ItemStack> fireworkOfLevel(int level) {
        return stack -> stack.is(Items.FIREWORK_ROCKET) && fireworkLevel(stack) == level;
    }

    /** 烟花等级（飞行时间 1/2/3）；非烟花返回 1 */
    private static int fireworkLevel(ItemStack stack) {
        Fireworks component = stack.get(DataComponents.FIREWORKS);
        return component != null ? component.flightDuration() : 1;
    }

    /**
     * 按指定等级释放烟花：背包烟花开启走背包交换使用，否则快捷栏静默使用。返回是否成功。
     */
    private static boolean tryUseFireworkOfLevel(int level) {
        Predicate<ItemStack> pred = fireworkOfLevel(level);
        if (backpackFirework.get()) {
            return BackpackUse.use(pred, backpackMode.get());
        }
        return useFireworkFromHotbar(pred);
    }

    /** 快捷栏静默使用烟花（副手优先，其次热栏静默切换释放后换回） */
    private static boolean useFireworkFromHotbar(Predicate<ItemStack> pred) {
        if (pred.test(mc.player.getOffhandItem())) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            mc.player.swing(InteractionHand.OFF_HAND);
            return true;
        }
        FindItemResult firework = InvUtils.findInHotbar(pred);
        if (!firework.found()) return false;
        if (firework.isMainHand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        InvUtils.swap(firework.slot(), true);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        InvUtils.swapBack();
        return true;
    }

    // ====== 一键烟花 ======

    /** 一键烟花快捷键触发：甲飞开启且不在滑翔时延后到下次滑翔，否则立即释放 */
    public static void fireworkOnce() {
        if (mc.player == null) return;
        if (isArmorFlyActive() && !mc.player.isFallFlying()) {
            oneKeyPending = true;
            return;
        }
        releaseFireworkOnce();
    }

    /** 每 tick 末尾检查：甲飞开启时按下快捷键不在滑翔，滑翔后立即补放 */
    private static void checkOneKeyPending() {
        if (oneKeyPending && mc.player.isFallFlying()) {
            oneKeyPending = false;
            releaseFireworkOnce();
        }
    }

    /** 释放一次烟花（一键烟花专用，可选背包，按一键烟花的背包开关） */
    private static void releaseFireworkOnce() {
        int level = selectFireworkLevel(oneKeyBackpackFirework.get());
        if (level == -1) return;
        Predicate<ItemStack> pred = fireworkOfLevel(level);
        if (oneKeyBackpackFirework.get()) {
            BackpackUse.use(pred, backpackMode.get());
        } else {
            useFireworkFromHotbar(pred);
        }
    }

    /** 读取快捷栏第一个烟花的等级（飞行时间 1/2/3，默认 1）；没有烟花返回 1 */
    private static int getHotbarFireworkLevel() {
        FindItemResult firework = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!firework.found()) return 1;

        ItemStack stack = firework.isOffhand()
            ? mc.player.getOffhandItem()
            : mc.player.getInventory().getItem(firework.slot());
        return fireworkLevel(stack);
    }

    /** 当前胸甲槽是否穿着鞘翅 */
    private static boolean isElytraEquipped() {
        return mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    /** 自动替换：落地后把胸甲槽的鞘翅换回胸甲。
     *  直接用 PICKUP 移动交换（Meteor ChestSwap 同款）：热栏/背包的胸甲一步换到胸甲槽，
     *  被换下的鞘翅进鼠标携带，由 close 包放回背包；不需要空位，背包满也不会失败。
     *  移动在本地同步执行，本地容器立即更新，天然防重复触发。 */
    private static void swapBackChestplate() {
        if (!isElytraInChest()) return;

        // 优先热栏找胸甲（起飞时胸甲被换出），其次背包
        FindItemResult chest = InvUtils.findInHotbar(stack -> isChestplate(stack) && !stack.is(Items.ELYTRA));
        if (!chest.found()) {
            chest = InvUtils.find(stack -> isChestplate(stack) && !stack.is(Items.ELYTRA));
        }
        if (!chest.found()) return;

        InvUtils.move().from(chest.slot()).toArmor(2);
        mc.getConnection().send(new ServerboundContainerClosePacket(0));
    }

    /** 是否为胸甲（可装备且装备槽为胸甲，26.1 用 Equippable 组件判断） */
    private static boolean isChestplate(ItemStack stack) {
        net.minecraft.world.item.equipment.Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
    }

    /** 尝试起飞：本地检查 + 发包 */
    private static void tryStartFallFlying() {
        if (mc.player.tryToStartFallFlying()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    // ====== 音效屏蔽（甲飞换装音效） ======

    private static class SoundListener {
        @EventHandler
        private void onPlaySound(PlaySoundEvent event) {
            // 甲飞模块看「静音」，合法平飞甲飞看自己的「静音」开关
            boolean mute;
            if (isArmorMode()) mute = muteSounds.get();
            else if (isLegalMode()) mute = legalMuteSounds != null && legalMuteSounds.get();
            else return;
            if (!mute) return;

            // 屏蔽盔甲装备音效与鞘翅飞行音效
            String path = event.sound.getIdentifier().getPath();
            if (path.startsWith("item.armor.equip") || path.equals("item.elytra.flying")) {
                event.cancel();
            }
        }
    }

    // ====== 甲飞辅助（换装） ======

    /** 找鞘翅（热栏/背包任意位置）；找不到返回 null */
    private static FindItemResult findElytra() {
        FindItemResult elytra = InvUtils.find(Items.ELYTRA);
        return elytra.found() ? elytra : null;
    }

    /** PICKUP（2p 模式）换甲：把指定槽位物品与胸甲槽互换（Meteor ChestSwap 同款，
     *  本地同步执行点击 + close 包，鞘翅在背包也能直接换） */
    private static void swapElytra(int slot) {
        InvUtils.move().from(slot).toArmor(2);
        mc.getConnection().send(new ServerboundContainerClosePacket(0));
    }

    /** 合法平飞甲飞模式 → 甲飞方式（Off 兜底普通） */
    private static ArmorMode toArmorMode(LegalArmorMode mode) {
        return switch (mode) {
            case Lazy -> ArmorMode.Lazy;
            case TickLegacy -> ArmorMode.TickLegacy;
            case Tick -> ArmorMode.Tick;
            default -> ArmorMode.Normal;
        };
    }

    /** 胸甲槽当前是否穿着鞘翅（读本地玩家容器） */
    private static boolean isElytraInChest() {
        return mc.player.containerMenu.getSlot(CHEST_SLOT).getItem().is(Items.ELYTRA);
    }

    /** 直接发起飞包（不经过 tryToStartFallFlying，本地不检查 canGlide） */
    private static void sendStartFlying() {
        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    /** 指定手是否手持烟花 */
    private static boolean isFireworkInHand(InteractionHand hand) {
        return mc.player.getItemInHand(hand).is(Items.FIREWORK_ROCKET);
    }

    /** 重发被拦截的烟花使用包（在换鞘翅 + 起飞后调用，卡服务器滑翔窗口） */
    private static void flushPendingFirework() {
        if (pendingFireworkPacket != null) {
            // 重发的包会再次触发 onPacketSend，置标志避免再次被拦截造成死循环
            flushingFirework = true;
            try {
                mc.getConnection().send(pendingFireworkPacket);
            } finally {
                flushingFirework = false;
            }
            pendingFireworkPacket = null;
        }
    }
}
