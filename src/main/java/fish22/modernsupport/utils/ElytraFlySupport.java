package fish22.modernsupport.utils;

import fish22.modernsupport.modules.Freeze;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
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

    /** 甲飞方式 */
    public enum ArmorMode {
        Normal("普通"),
        Grim("Grim");

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

    // ====== 设置引用（MixinElytraFly 创建设置后注入） ======

    /** 官方模式设置（甲飞/合法平飞为 MixinElytraFlightModes 追加的枚举值，用 name 判断） */
    public static Setting<ElytraFlightModes> flightMode;
    public static Setting<ArmorMode> armorMode;
    public static Setting<Boolean> muteSounds;
    public static Setting<Boolean> moveToHotbar;
    public static Setting<Boolean> firework;
    public static Setting<Integer> fireworkDelay;
    public static Setting<Integer> grimDelay;
    public static Setting<Integer> correctionBackoff;
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

    // ====== 常量 ======

    /** 胸甲槽容器坐标（玩家容器） */
    private static final int CHEST_SLOT = 6;

    /** 热栏第 9 格（挪背包鞘翅时的目标格，热栏索引 8） */
    private static final int MOVE_TO_HOTBAR = 8;

    /** fastRecover 触发的最低下落速度（向下超过此值才认为滑翔掉了） */
    private static final double RECOVER_MIN_DOWN_VEL = 0.03;

    /** 放烟花后鞘翅保持的 tick 数（覆盖烟花寿命 ~1.5 秒，让烟花每 tick 持续加速） */
    private static final int FIREWORK_HOLD_TICKS = 25;

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

    /** 距下次换装起飞链的剩余 tick 数（Grim） */
    private static int packetDelayCount = 0;

    /** 回弹退避剩余 tick 数（Grim，期间不执行换装链） */
    private static int suppressTicks = 0;

    /** 放烟花后鞘翅保持剩余 tick 数（Grim，期间不换装） */
    private static int holdElytraTicks = 0;

    /** 上次使用烟花的时间戳（Grim，毫秒） */
    private static long lastFireworkMs = 0;

    /** 距下次自动烟花的剩余 tick 数（合法平飞，飞行/悬停共用） */
    private static int legalFwCooldown = 0;

    /** 起飞烟花已排队标志：同一 tick 的飞行/悬停自动烟花检查到此标志直接跳过，避免一次起飞双放烟花 */
    private static boolean takeoffFireworkPending = false;

    /** 音效屏蔽监听器（甲飞换装音效） */
    private static final SoundListener SOUND_LISTENER = new SoundListener();

    private ElytraFlySupport() {
    }

    // ====== 生命周期（由 MixinElytraFly 调用） ======

    public static void onActivate() {
        wasFlying = false;
        packetDelayCount = 0;
        suppressTicks = 0;
        holdElytraTicks = 0;
        lastFireworkMs = 0;
        legalFwCooldown = 0;
        takeoffFireworkPending = false;
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
    }

    /** 发包监听（由 MixinElytraFly 拦截官方 onPacketSend 后调用） */
    public static void onPacketSend(PacketEvent.Send event) {
        // 合法平飞悬停冻结时拦截位置移动包（旋转包照发，可正常转头）
        if (!isLegalMode() || hoverMode.get() != HoverMode.Freeze) return;
        if (!Freeze.isFrozen()) return;
        if (event.packet instanceof ServerboundMovePlayerPacket movePacket && movePacket.hasPosition()) {
            event.cancel();
        }
    }

    /** 收包监听（由 MixinElytraFly 拦截官方 onPacketReceive 后调用） */
    public static void onPacketReceive(PacketEvent.Receive event) {
        // 服务器位置纠正（回弹）后暂停换装，避免继续震荡（仅甲飞 Grim）
        if (!isArmorMode() || armorMode.get() != ArmorMode.Grim) return;
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            suppressTicks = correctionBackoff.get();
        }
    }

    // ====== 甲飞逻辑 ======

    private static void armorTick() {
        // 打开容器/界面时不动手，避免误点
        if (mc.player.containerMenu.containerId != 0) return;

        // 落地/进水：恢复正常状态（胸甲槽若还是鞘翅则换回胸甲）
        if (mc.player.onGround() || mc.player.isInWater()) {
            if (isElytraInChest()) {
                FindItemResult elytra = InvUtils.findInHotbar(Items.ELYTRA);
                if (elytra.found()) {
                    sendSwapPacket(elytra.slot());
                }
            }
            wasFlying = false;
            packetDelayCount = 0;
            suppressTicks = 0;
            holdElytraTicks = 0;
            return;
        }

        // Grim 模式走独立逻辑
        if (armorMode.get() == ArmorMode.Grim) {
            grimTick();
            return;
        }

        // ====== 普通模式 ======

        // 服务器当前滑翔状态：onTick 时本地 flag 是网络同步后的值，反映服务器状态
        boolean serverFlying = mc.player.isFallFlying();

        // 初始起飞需要按跳跃键（避免走路、下台阶误起飞）；
        // 已在飞行中（wasFlying）则无论按键状态都自动重启/维持
        if (!serverFlying && !wasFlying && !mc.options.keyJump.isDown()) {
            return;
        }

        // 本地强制滑翔：客户端始终按滑翔物理计算（服务器同步的取消会被下一 tick 覆盖）
        if (!mc.player.isFallFlying()) {
            mc.player.startFallFlying();
        }
        wasFlying = true;

        // 确保鞘翅在热栏（必要时从背包挪）
        FindItemResult elytra = ensureElytraInHotbar();
        if (elytra == null) return;

        // 每 tick 闪换：鞘翅上位 → 起飞包 → 换回胸甲。
        // 服务器处理顺序保证移动包处理时处于滑翔状态（详见类注释），
        // 起飞失败（如服务器还认为在地面）下一 tick 自动重试。
        sendSwapPacket(elytra.slot());
        sendStartFlying();
        sendSwapPacket(elytra.slot());
    }

    // ====== Grim 模式 ======

    private static void grimTick() {
        if (suppressTicks > 0) suppressTicks--;

        // 放烟花后的鞘翅保持期：保持服务器滑翔状态，让烟花实体每 tick 持续加速；
        // 若期间发生回弹（服务器位置纠正）则立即中断保持，避免加速-回弹死循环
        if (suppressTicks > 0 && holdElytraTicks > 0) {
            holdElytraTicks = 0;
            FindItemResult elytra = InvUtils.findInHotbar(Items.ELYTRA);
            if (elytra.found()) {
                sendSwapPacket(elytra.slot());
            }
        }
        if (holdElytraTicks > 0) {
            holdElytraTicks--;
            if (holdElytraTicks == 0) {
                // 保持结束：换回胸甲
                FindItemResult elytra = InvUtils.findInHotbar(Items.ELYTRA);
                if (elytra.found()) {
                    sendSwapPacket(elytra.slot());
                }
            }
            return;
        }

        // 服务器真实滑翔状态（网络同步后的值）
        boolean realFlying = mc.player.isFallFlying();

        // 初始起飞需要按跳跃键；已在飞行中（wasFlying）自动维持/恢复
        if (!realFlying && !wasFlying && !mc.options.keyJump.isDown()) {
            return;
        }
        wasFlying = true;

        // fastRecover：滑翔状态掉了且在下落 → 立即执行换装起飞链（不等延迟）
        if (!realFlying && mc.player.getDeltaMovement().y < -RECOVER_MIN_DOWN_VEL) {
            grimChain();
            return;
        }

        // 按设置间隔执行换装起飞链，减少服务器状态震荡
        packetDelayCount++;
        if (packetDelayCount <= grimDelay.get()) return;
        packetDelayCount = 0;

        if (suppressTicks <= 0) {
            grimChain();
        }
    }

    /** 换装起飞链：换鞘翅 → 起飞包 + 本地滑翔 →（烟花）→ 换回胸甲 */
    private static void grimChain() {
        FindItemResult elytra = ensureElytraInHotbar();
        if (elytra == null) return;

        sendSwapPacket(elytra.slot());
        sendStartFlying();
        if (!mc.player.isFallFlying()) {
            mc.player.startFallFlying();
        }

        // 烟花使用包在起飞包之后发出（服务器处理顺序保证 useItem 时处于滑翔）。
        // 原版烟花加速是持续的（附着期间每 tick 检查滑翔并加速），烟花寿命约 1.5 秒，
        // 因此放烟花后鞘翅保持 FIREWORK_HOLD_TICKS tick（覆盖烟花寿命），
        // 期间服务器持续滑翔、烟花每 tick 加速；保持结束换回胸甲。
        // 没放烟花（冷却中/没烟花）则立即换回胸甲。
        if (firework.get() && canUseFirework()) {
            useFirework();
            lastFireworkMs = System.currentTimeMillis();
            holdElytraTicks = FIREWORK_HOLD_TICKS;
        } else {
            sendSwapPacket(elytra.slot());
        }
    }

    /** 能否使用烟花：不在使用物品且冷却结束 */
    private static boolean canUseFirework() {
        return !mc.player.isUsingItem()
            && System.currentTimeMillis() - lastFireworkMs >= fireworkDelay.get();
    }

    /** 使用烟花（Grim）：主手/副手优先，其次热栏静默切换，背包烟花自动挪到主手（用后不换回） */
    private static void useFirework() {
        if (mc.player.getMainHandItem().is(Items.FIREWORK_ROCKET)) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            return;
        }
        if (mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            return;
        }

        FindItemResult firework = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (firework.found()) {
            InvUtils.swap(firework.slot(), true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
            return;
        }

        // 热栏没有：从背包挪到主手槽（与原物品互换）再使用
        FindItemResult inv = InvUtils.find(Items.FIREWORK_ROCKET);
        if (!inv.found()) return;
        int invSlot = inv.slot() < 9 ? inv.slot() + 36 : inv.slot();
        sendSwapPacket(invSlot, mc.player.getInventory().getSelectedSlot());
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
    }

    // ====== 合法平飞逻辑（参考 Epsilon ElytraFly Control 模式） ======

    private static void legalTick() {
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

    /**
     * 按优先级选择要使用的烟花等级；没有可用烟花返回 -1。
     * 同时存在多个等级时用优先级高的；多个等级同优先级时遵循原逻辑
     * （快捷栏第一个烟花的等级）。
     */
    private static int selectFireworkLevel() {
        int bestPriority = -1;
        int bestLevel = -1;
        int tieCount = 0;
        for (int level = 1; level <= 3; level++) {
            if (!hasFireworkOfLevel(level)) continue;
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
        // 多个等级同优先级 → 原逻辑（快捷栏第一个烟花的等级）
        return tieCount > 1 ? getHotbarFireworkLevel() : bestLevel;
    }

    /** 指定等级的烟花优先级 */
    private static int priorityOf(int level) {
        return switch (level) {
            case 2 -> fwPriorityLv2.get();
            case 3 -> fwPriorityLv3.get();
            default -> fwPriorityLv1.get();
        };
    }

    /** 是否存在指定等级的烟花（背包烟花开启时查全背包，否则只查快捷栏） */
    private static boolean hasFireworkOfLevel(int level) {
        Predicate<ItemStack> pred = fireworkOfLevel(level);
        if (backpackFirework.get()) {
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
     * 按指定等级释放烟花：背包烟花开启走背包交换使用（含换回确认重试），
     * 否则快捷栏静默使用（副手优先，其次热栏静默切换释放后换回）。返回是否成功。
     */
    private static boolean tryUseFireworkOfLevel(int level) {
        Predicate<ItemStack> pred = fireworkOfLevel(level);
        if (backpackFirework.get()) {
            return BackpackUse.use(pred, backpackMode.get());
        }

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
            if (!isArmorMode() || !muteSounds.get()) return;

            // 屏蔽盔甲装备音效与鞘翅飞行音效
            String path = event.sound.getIdentifier().getPath();
            if (path.startsWith("item.armor.equip") || path.equals("item.elytra.flying")) {
                event.cancel();
            }
        }
    }

    // ====== 甲飞辅助（换装） ======

    /** 确保鞘翅在热栏；返回热栏鞘翅位置，找不到返回 null */
    private static FindItemResult ensureElytraInHotbar() {
        FindItemResult elytra = InvUtils.findInHotbar(Items.ELYTRA);
        if (elytra.found()) return elytra;

        if (!moveToHotbar.get()) return null;
        FindItemResult inv = InvUtils.find(Items.ELYTRA);
        if (!inv.found()) return null;
        // 玩家库存索引转容器坐标：热栏 0-8 → 36-44，主背包 9-35 不变
        int invSlot = inv.slot() < 9 ? inv.slot() + 36 : inv.slot();
        sendSwapPacket(invSlot, MOVE_TO_HOTBAR);
        return new FindItemResult(MOVE_TO_HOTBAR, 1);
    }

    /** 胸甲槽当前是否穿着鞘翅（读本地玩家容器） */
    private static boolean isElytraInChest() {
        return mc.player.containerMenu.getSlot(CHEST_SLOT).getItem().is(Items.ELYTRA);
    }

    /** SWAP 单包：热栏槽 hotbarSlot 与胸甲槽互换 */
    private static void sendSwapPacket(int hotbarSlot) {
        sendSwapPacket(CHEST_SLOT, hotbarSlot);
    }

    /** SWAP 点击包（直接构造发送，本地不执行容器点击，避免与服务器广播互相覆盖）：
     *  目标槽 slotIndex 与热栏槽 buttonNum 互换，服务器自己执行交换。
     *  changedSlots / carriedItem 传空，服务器按自己状态执行后广播同步。 */
    private static void sendSwapPacket(int slotIndex, int hotbarSlot) {
        mc.getConnection().send(new ServerboundContainerClickPacket(
            mc.player.containerMenu.containerId,
            mc.player.containerMenu.getStateId(),
            (short) slotIndex,
            (byte) hotbarSlot,
            ContainerInput.SWAP,
            new Int2ObjectOpenHashMap<>(),
            HashedStack.EMPTY
        ));
    }

    /** 直接发起飞包（不经过 tryToStartFallFlying，本地不检查 canGlide） */
    private static void sendStartFlying() {
        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }
}
