package fish22.modernsupport.utils;

import fish22.modernsupport.modules.Freeze;
import fish22.modernsupport.mixin.LivingEntityGlideInvoker;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;

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
 *   <li>通过 {@link LegalRotation}（严格模式）把服务器视角转到目标角度，
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

    /** 当前甲飞是否真的在运行（模式是甲飞 且 鞘翅飞行模块处于开启状态） */
    public static boolean isArmorFlyEnabled() {
        if (!isArmorFlyActive()) return false;
        ElytraFly module = Modules.get().get(ElytraFly.class);
        return module != null && module.isActive();
    }

    // ====== 视角高度锁定（甲飞时本地不认服务器同步过来的滑翔姿势） ======

    /**
     * 甲飞时本 tick 是否把本地姿势锁成「未滑翔」
     * （由 {@link fish22.modernsupport.mixin.MixinElytraPoseLock} 在 {@code Player#getDesiredPose} 入口调用）
     *
     * <p>甲飞本地大部分时间穿胸甲，服务器只有换装窗口那一两 tick 认滑翔；而服务器会把玩家自己的
     * 标志位（滑翔 bit）与姿势同步回客户端（{@code ServerEntity#sendChanges} →
     * {@code sendToTrackingPlayersAndSelf}），窗口开/关来回同步 → 本地 {@code isFallFlying()}
     * 一 tick 真一 tick 假 → 姿势在 FALL_FLYING（视高 0.4）与 STANDING（1.62）之间来回切，
     * {@code Camera#tick} 又按 50% 去追这个视高，于是视角高度一直上下抖。
     *
     * <p>甲飞时不认这个滑翔姿势：姿势保持站立/潜行，视高（相机高度）稳定，
     * 碰撞箱也和服务器结算完那一 tick 的姿势一致。
     * <b>只改本地姿势</b>：不动 {@code isFallFlying} 标志位、不发包，
     * 滑翔运算（{@link #travelAsElytra}）、换装时序、烟花逻辑都不受影响。
     */
    public static boolean shouldLockPose(Player player) {
        if (player == null || player != mc.player) return false;
        if (!isArmorFlyEnabled()) return false;

        // 地面/流体/骑乘/旁观/创造飞行：姿势本来就是正常的，不干预
        if (player.onGround() || player.isInWater() || player.isInLava()) return false;
        if (player.isPassenger() || player.isSpectator() || player.getAbilities().flying) return false;

        // 只有滑翔姿势会被服务器标志位来回覆盖，其它姿势照常
        return player.isFallFlying();
    }

    // ====== 甲飞移动运算（空中始终按原版滑翔运算移动，不改滑翔状态） ======

    /**
     * 本 tick 用原版滑翔运算完成移动（由
     * {@link fish22.modernsupport.mixin.MixinElytraTravel} 在 {@code Player#travel} 入口调用）
     *
     * <p>甲飞本地大部分时间不是滑翔状态（胸甲在身上，只有换装的那一两个 tick 服务器才认），
     * 原版这时走普通空中运算：WASD 直接加速、水平阻力 0.91、重力照常。
     * 而服务器（Grim）是按滑翔运算预测的（忽略输入、水平阻力 0.99 + 滑翔抬升），
     * 两端对不上就会出现回弹，烟花给的动量也会被空气阻力几 tick 吃干净
     * （表现为「放得出烟花但不加速」）。所以飞行期间统一按滑翔运算移动。
     *
     * <p>这里是原版 {@code travelFallFlying} 的等价实现：速度走原版私有的
     * {@code updateFallFlyingMovement}（同一个方法，数值完全一致），
     * 然后 {@code move(SELF, ...)}；撞墙伤害只在服务端算，客户端不做。
     *
     * <p>接管范围严格对齐服务端此刻认定的滑翔状态：
     * 本地是滑翔状态（服务器已同步），或本 tick 刚发过起飞包（服务器处理本 tick
     * 移动包时已经进入滑翔）才走滑翔运算。起跳那一 tick 模块还没发起飞包
     * （按跳跃时人还站着），走原版空中运算，和服务器/反作弊的预测一致，
     * 不会出现「起跳瞬间被判成回弹」。
     *
     * <p><b>只改移动运算，不伪造滑翔状态</b>：不碰 {@code isFallFlying}（共享标志位）、
     * 不发额外起飞包，换装时序与烟花延迟重发机制完全不受影响。
     *
     * @return true 表示已经完成本 tick 移动运算，调用方应跳过原版 travel
     */
    public static boolean travelAsElytra(Player player) {
        if (!shouldUseElytraMovement(player)) return false;

        // 移动方向必须跟服务器朝向一致（aiStep 内部可能把 yRot 改回视觉值）
        LegalRotation.forceRotationBeforeMove(player);

        // 原版 travelFallFlying 的顺序：先算滑翔速度写回动量，再按这个动量 move
        Vec3 movement = ((LivingEntityGlideInvoker) player).meteor$updateFallFlyingMovement(player.getDeltaMovement());
        player.setDeltaMovement(movement);
        player.move(MoverType.SELF, movement);
        return true;
    }

    /** 甲飞空中移动运算是否改走原版滑翔运算 */
    private static boolean shouldUseElytraMovement(Player player) {
        // 只处理本地玩家
        if (player == null || player != mc.player) return false;

        // 模块真的在运行（模式是甲飞但模块没开时保持原版行为）
        if (!isArmorFlyEnabled()) return false;

        // 冻结（悬停）：完全静止，移动运算由冻结逻辑接管
        if (Freeze.isFrozen()) return false;

        // 服务端此刻是否把这一 tick 当作滑翔：本地已同步滑翔，或本 tick / 上一 tick 发过起飞包
        // （服务端清滑翔标志要等一个同步往返，隔 tick 起飞时中间那一 tick 服务器仍然认为在滑翔）。
        // 不满足（例如刚起跳、模块没换装成功、开容器时）就按原版空中运算走，
        // 和服务器（Grim 按未滑翔预测）保持一致。
        if (!serverSeesGliding()) return false;

        // 地面/流体/骑乘/旁观/创造飞行：走原版对应运算，不干预
        if (player.onGround() || player.isInWater() || player.isInLava()) return false;
        if (player.isPassenger() || player.isSpectator() || player.isDeadOrDying()) return false;
        if (player.getAbilities().flying) return false;

        // 可攀爬方块（梯子/藤蔓）：原版走攀爬运算。
        // 但原版滑翔时「可滑翔穿过」的方块（藤蔓类）不算可攀爬，甲飞本地没有滑翔状态，
        // 这里补上同样判断，避免贴着藤蔓飞时突然掉回普通空中运算。
        if (player.onClimbable() && !isGlideThroughBlock(player)) return false;

        // 没有鞘翅时换装无法进行、服务器也不会滑翔，保持原版空中运算避免和服务器较劲
        return isElytraAvailable();
    }

    /**
     * 服务端此刻是否把这一 tick 当作滑翔（本地已同步滑翔，或本 tick / 上一 tick 发过起飞包）。
     *
     * <p>甲飞本地大多时候不是滑翔状态（胸甲在身上，只有换装窗口那一两个 tick 服务器才认），
     * 服务器认滑翔靠的是我们发出去的起飞包；兼容 grim 输入检测开启时起飞包隔 tick 发，
     * 中间那一 tick 服务器仍然认为在滑翔（清滑翔标志要等一个同步往返），
     * 所以要把上一 tick 的起飞也算进「滑翔窗口」。
     *
     * <p>移动运算（{@link #travelAsElytra}）与甲飞模式下的方向控制
     * （{@link #legalArmorTick}）共用这个判断：两者必须同时生效，
     * 否则客户端移动方向、服务器朝向、烟花加速方向会分叉。
     */
    private static boolean serverSeesGliding() {
        return mc.player != null
            && (mc.player.isFallFlying() || startedGlidingThisTick || startedGlidingLastTick);
    }

    /** 身上或背包里是否有鞘翅（换装窗口内鞘翅在胸甲槽，其余时候在背包） */
    private static boolean isElytraAvailable() {
        return mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
            || mc.player.getOffhandItem().is(Items.ELYTRA)
            || InvUtils.find(Items.ELYTRA).found();
    }

    /** 玩家当前所在方块是否属于「可滑翔穿过」标签（藤蔓类） */
    private static boolean isGlideThroughBlock(Player player) {
        return player.level().getBlockState(player.blockPosition()).is(BlockTags.CAN_GLIDE_THROUGH);
    }

    // ====== 空中屏蔽空格 ======

    /**
     * 空中屏蔽空格：本 tick 是否把跳跃键当作没按
     * （由 {@link fish22.modernsupport.mixin.MixinElytraKeyboardInput} 在
     * {@code KeyboardInput#tick} 末尾调用，直接改本地按键输入）
     *
     * <p>为什么不改包：服务器只能从输入包（{@code ServerboundPlayerInputPacket}）知道按键状态，
     * 但客户端是「按键状态变了才发包」。拦包再补发会让同一 tick 出现两个输入包
     * （Grim BadPacketsZ「同一 tick 重复输入包」），而且补发/收尾时机很难和客户端的
     * 发包记账对齐。直接改本地按键输入：客户端只发一个输入包，内容就是 jump=false，
     * 服务器自然不知道你按着空格，也不会有多余发包。
     *
     * <p>用途：服务端（Grim ElytraB）把「启动滑翔时按着跳跃」判为异常并取消起飞包，
     * 换装窗口里的烟花随之发不出去。屏蔽后服务器看到的跳跃键是松开的。
     *
     * <p>调用点在 {@code LocalPlayer.aiStep} 内、跳跃真正施加之前，所以这里的
     * {@code onGround()} 还是上一 tick 的结果：<b>地面起跳的那一下不会被屏蔽</b>
     * （服务器要看到这次跳跃才能预测），真正在空中按住空格才屏蔽。
     */
    public static boolean shouldHideJumpInput() {
        // 「兼容 grim 输入检测」开启时也要屏蔽：起飞包之间那一 tick 必须是松开状态，
        // 否则下一次起飞包会被 Grim 看到「按着跳跃」（no release）而被取消
        boolean grim = grimInputSequenceOn();
        // 「空中屏蔽空格」是甲飞模块自己的选项：合法平飞的甲飞用自己的「兼容 grim 输入检测」，
        // 不受甲飞模块里那个隐藏选项影响
        boolean spaceBlock = isArmorMode() && spaceBlockInAir != null && spaceBlockInAir.get();
        if (!grim && !spaceBlock) return false;
        if (!isArmorFlyActive() || mc.player == null) return false;

        // 地面/流体/骑乘：不干预（起跳那一下必须让服务器看到）；兼容模式重置「已看到松开」状态
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || mc.player.isPassenger()) {
            if (grim) jumpInputReleasedForStart = false;
            return false;
        }

        // 本 tick 发过起飞包 → 走「按下」分支（见 shouldPressJumpInput），这里不屏蔽
        if (grim && startedGlidingThisTick) {
            jumpInputReleasedForStart = false;
            return false;
        }

        // 其余空中 tick 一律松开；兼容模式记下「服务端已经看到松开」，
        // 下一次起飞包要等这个状态成立才允许发（见 skipStartThisTick）
        if (grim) jumpInputReleasedForStart = true;
        return true;
    }

    /**
     * 甲飞换装期间是否屏蔽移动/疾跑按键输入（输入包层面）
     *
     * <p>甲飞每 tick 都要发容器点击包（换鞘翅 → 起飞 → 换回胸甲）。Grim 的
     * MultiActionsC「移动中点击背包」与 MultiActionsD「移动中关闭背包」只要输入包里的
     * 移动键（{@code moving()} = 前后左右 <b>或跳跃</b>）或疾跑状态为真，就会把点击包
     * <b>直接取消</b>——服务端背包不变，换装随之落空（表现就是「甲飞不换甲」）；
     * 滑翔中疾跑本身在 Grim 里独立成检（SprintE/SprintF）也是异常。
     *
     * <p>原版滑翔运算完全不看输入（{@link #travelAsElytra} 用的是同一套运算），
     * 在服务器看来「滑翔时没有按移动键、没有疾跑」才是正常状态，
     * 所以直接把移动键与疾跑键从输入包里抹掉：客户端只发一个输入包（内容全松开），
     * 不产生额外发包（不像拦包重发那样触发 BadPacketsZ）。
     *
     * <p>地面/流体/骑乘/不在滑翔窗口时不屏蔽，保证走路、起跳、地面的输入照常上报。
     */
    public static boolean shouldHideMoveInput() {
        if (mc.player == null || !isArmorFlyEnabled()) return false;
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || mc.player.isPassenger()) return false;
        return serverSeesGliding();
    }

    // ====== 兼容 grim 输入检测（起飞包配一对跳跃输入包） ======

    /**
     * Grim ElytraB 对「启动滑翔」的输入要求（照源码）：收到起飞包那一刻，输入包里的跳跃键
     * 必须是<b>松开</b>的，否则报 {@code [no release]} 并且<b>直接取消起飞包</b>
     * （服务器那一刻不会进滑翔，换装窗口里的烟花随之失效）；而起飞包之后的第一个 update 包
     * （移动包 / tick 结束包）必须看到跳跃键<b>按下</b>，否则报 {@code [no jump]}。
     *
     * <p>也就是原版顺序：<b>起飞包 → 按下跳跃包</b>，再下一 tick 松开。
     * 反过来「按下 → 起飞 → 松开」在起飞包那一刻会被看到「按着跳跃」，直接踩 no release。
     *
     * <p>所以这里做成：发过起飞包的那一 tick，输入包带 jump=true（输入包在
     * {@code LocalPlayer.tick} 里、起飞包之后才发出去，顺序天然正确）；
     * 没发起飞包的 tick 输入带 jump=false（松开），既是下一次起飞需要的「松开状态」，
     * 也是 no jump 检查之后的状态还原。一个 tick 最多一个输入包，不会触发 BadPacketsZ。
     */
    public static boolean shouldPressJumpInput() {
        if (!grimInputSequenceOn()) return false;
        if (mc.player == null || mc.player.onGround()) return false;
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.isPassenger()) return false;
        if (!startedGlidingThisTick) return false;

        // 本地胸甲槽此刻是鞘翅时不能强制按下：原版 LocalPlayer 会在「按着跳跃」时
        // 自己再发一个起飞包（它只看本地是否穿鞘翅），一个 tick 两个起飞包会被
        // Grim 判成 ElytraC「起飞过频」。甲飞常规换装在本 tick 已换回胸甲，不受影响。
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return false;

        // 已经按下：下一次起飞包之前必须先发一个松开包
        jumpInputReleasedForStart = false;
        return true;
    }

    /**
     * 兼容 grim 输入检测：本 tick 是否跳过起飞（上一 tick 刚发过起飞包）
     *
     * <p>要让每次起飞包前面都有一个「松开」输入包，起飞包必须等上一次的松开包发出去之后再发，
     * 所以开启后起飞包隔 tick 发一次：起飞包 tick（输入：按下）→ 只发松开包的 tick → 再起飞。
     * 中间那一 tick 服务器仍然认为在滑翔（清滑翔标志要等一个同步往返），
     * 所以移动运算照旧按滑翔走，见 {@link #travelAsElytra}。
     *
     * <p>起跳后的第一个空中 tick 也在这个「等松开」范围内：地面上按跳跃那一下（服务器已经看到
     * 按下）必须先用一个松开包覆盖掉，之后才允许发起飞包。
     */
    public static boolean skipStartThisTick() {
        return grimInputSequenceOn() && !jumpInputReleasedForStart;
    }

    /**
     * 兼容 grim 输入检测是否生效：对应模式自己的选项开启 + 甲飞正在运行。
     * 甲飞模块看自己的「兼容 grim 输入检测」，合法平飞的甲飞模式看它自己的同名选项。
     */
    private static boolean grimInputSequenceOn() {
        if (!isArmorFlyEnabled()) return false;
        if (isArmorMode()) return grimInputSequence != null && grimInputSequence.get();
        if (isLegalMode()) return legalGrimInputSequence != null && legalGrimInputSequence.get();
        return false;
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
    public static Setting<Boolean> spaceBlockInAir;
    public static Setting<Boolean> grimInputSequence;
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
    /** 合法平飞甲飞的「兼容 grim 输入检测」开关 */
    public static Setting<Boolean> legalGrimInputSequence;
    /** 合法平飞甲飞的静音开关 */
    public static Setting<Boolean> legalMuteSounds;
    /** 一键烟花快捷键 */
    public static Setting<Keybind> oneKeyFirework;
    /** 一键烟花是否允许使用背包中的烟花 */
    public static Setting<Boolean> oneKeyBackpackFirework;
    /** 一键烟花背包交换的发包模式（1p = SWAP 2包 / 2p = PICKUP 4 包） */
    public static Setting<BackpackUse.Mode> oneKeyBackpackMode;

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

    /** 被拦截待重发的烟花使用包（手动右键烟花时拦截，延迟到换鞘翅+起飞后重发，见 {@link #flushPendingFirework}） */
    private static ServerboundUseItemPacket pendingFireworkPacket = null;

    /** 正在内部发送烟花使用包（延迟重发 / 换装窗口内释放；置此标志避免再次被 onPacketSend 拦截） */
    private static boolean bypassFireworkIntercept = false;

    /** 距下次自动烟花的剩余 tick 数（合法平飞，飞行/悬停共用） */
    private static int legalFwCooldown = 0;

    /** 甲飞模式下待释放的自动烟花等级（-1 表示无）：换装窗口内释放，见 {@link #releaseWindowFirework} */
    private static int windowFwPendingLevel = -1;

    /** 甲飞模式下待释放的自动烟花的间隔（释放成功后写回冷却） */
    private static int windowFwPendingInterval = 0;

    /** 起飞烟花已排队标志：同一 tick 的飞行/悬停自动烟花检查到此标志直接跳过，避免一次起飞双放烟花 */
    private static boolean takeoffFireworkPending = false;

    /** 一键烟花待释放：甲飞等换装窗口释放、合法平飞等移动包发送后释放（见 fireworkOnce） */
    private static boolean oneKeyPending = false;

    /** 本 tick 是否发过起飞包（服务器处理本 tick 移动包时已经在滑翔，本地移动运算据此对齐） */
    private static boolean startedGlidingThisTick = false;

    /** 上一 tick 是否发过起飞包（隔 tick 起飞 + 起飞后仍按滑翔运算用） */
    private static boolean startedGlidingLastTick = false;

    /** 「兼容 grim 输入检测」用：服务端最后一次看到的跳跃键是不是松开（松开才允许发起飞包） */
    private static boolean jumpInputReleasedForStart = false;

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
        windowFwPendingLevel = -1;
        windowFwPendingInterval = 0;
        takeoffFireworkPending = false;
        oneKeyPending = false;
        jumpWasDown = false;
        takeoffRequested = false;
        takeoffRetryTicks = 0;
        prevFlying = false;
        startedGlidingThisTick = false;
        startedGlidingLastTick = false;
        jumpInputReleasedForStart = false;
        Freeze.setExternalFrozen(false);
        MeteorClient.EVENT_BUS.subscribe(SOUND_LISTENER);
    }

    public static void onDeactivate() {
        // 解除合法平飞可能挂上的外部冻结
        Freeze.setExternalFrozen(false);
        MeteorClient.EVENT_BUS.unsubscribe(SOUND_LISTENER);
    }

    /**
     * 每 tick 都要跑的处理（不分模式，由 MixinElytraFly 在官方 onPreTick 最前面调用）。
     * 目前只有「本 tick 是否发过起飞包」的清零：切回官方模式时也要清，避免残留。
     */
    public static void onPreTickAlways() {
        startedGlidingLastTick = startedGlidingThisTick;
        startedGlidingThisTick = false;
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
        // 甲飞飞行中拦截疾跑包：滑翔中疾跑对 Grim 是异常（SprintE/SprintF），
        // 而且 MultiActionsC 的 sprinting=true 同样会取消换装点击包（换装又会落空）。
        // 拦截后同步停掉本地疾跑：不然客户端已经记下「发过疾跑」（wasSprinting），
        // 服务端却永远收不到，落地跑动时预测速度会对不上。
        if (event.packet instanceof ServerboundPlayerCommandPacket cmd
            && cmd.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING
            && shouldHideMoveInput()) {
            event.cancel();
            mc.player.setSprinting(false);
            return;
        }

        // 甲飞：拦截手动烟花使用包，延迟到换鞘翅+起飞后重发。
        // 手动右键烟花时客户端已因本地强制滑翔（isFallFlying=true）发出使用包，
        // 但此时服务器可能已停飞（穿胸甲），直接发出去服务器不发射；卡到滑翔窗口再发。
        // 模块内部发出的使用包（延迟重发、换装窗口内释放一键烟花）带 bypassFireworkIntercept
        // 标志，直接放行——它们在窗口内发出时烟花就在手上，再延后重发反而会失效。
        if (isArmorFlyActive() && event.packet instanceof ServerboundUseItemPacket useItem) {
            if (!bypassFireworkIntercept && isFireworkInHand(useItem.getHand())) {
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
            // 服务器清滑翔标志要等一个同步往返，本地主动清掉，落地即取消甲飞
            cancelLocalGliding();
            swapBackChestplate();
            wasFlying = false;
            legacyElytraOn = false;
            legacyElytraSlot = -1;
            windowFwPendingLevel = -1;
            return;
        }

        // 兼容 grim 输入检测：起飞包隔 tick 发（本 tick 只发松开包，让下次起飞前是「松开」状态）
        if (!skipStartThisTick()) {
            armorFlySwap(armorMode.get());
        }
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

        // 每 tick 闪换：鞘翅上位 → 起飞包 → 换回胸甲（三击换装，鞘翅可在背包）
        flashSwapElytra();
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

        flashSwapElytra();
    }

    // ====== 来回闪换（TICK_LEGACY）======

    /** 来回闪换：每 tick 交替换鞘翅/换回胸甲，配合起飞包维持服务器滑翔状态 */
    private static void tickLegacyTick() {
        wasFlying = true;

        // 首次找鞘翅并记住槽位；三击互换做两次回到原位，后续都用这个固定槽位，
        // 不能每 tick 重新查找——换鞘翅后鞘翅已跑到胸甲槽，背包里找不到鞘翅会导致换回失败
        if (legacyElytraSlot == -1) {
            FindItemResult elytra = findElytra();
            if (elytra == null) return;
            legacyElytraSlot = elytra.slot();
        }

        if (!legacyElytraOn) {
            // 胸甲在上：换鞘翅并起飞
            swapWithChest(legacyElytraSlot);
            sendStartFlying();
            flushPendingFirework();
            legacyElytraOn = true;
            releaseOneKeyFirework();
            releaseWindowFirework();
        } else {
            // 鞘翅在上：换回胸甲
            swapWithChest(legacyElytraSlot);
            legacyElytraOn = false;
        }
    }

    // ====== 每 tick 闪换（TICK）======

    /** 每 tick 闪换：换鞘翅 → 起飞 → 换回 */
    private static void tickTick() {
        wasFlying = true;

        flashSwapElytra();
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
                    // 鞘翅已穿（换装点击本地同步执行，立即生效）：按间隔重发起飞包
                    if (takeoffRetryTicks <= 0) {
                        sendStartFlying();
                        takeoffRetryTicks = 5;
                    } else {
                        takeoffRetryTicks--;
                    }
                } else {
                    // 换鞘翅：把热栏/主背包/副手的鞘翅一步换到胸甲槽（三击互换，光标内容原样保留）；
                    // 不需要空位，背包满也不会失败；起飞包同批发出（服务器按包序：换装→起飞）
                    FindItemResult elytra = findElytra();
                    if (elytra == null) {
                        takeoffRequested = false;
                        return;
                    }
                    swapWithChest(elytra.slot());
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

        // 滑翔状态转变（起飞成功瞬间）放一次烟花。
        // 甲飞不能用 mc.player.isFallFlying() 判断：本地这个标志位被服务器同步的滑翔 bit
        // 一 tick 真一 tick 假地来回覆盖（换装窗口开/关），会误判成反复「起飞成功」；
        // 用「服务器认滑翔」的窗口判断（本地滑翔标志 或 本 tick/上一 tick 发过起飞包）。
        boolean flying = serverSeesGliding();
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
            windowFwPendingLevel = -1;
            // 落地重置冷却：下一次真正起飞时立刻补一发烟花（起飞烟花本身受冷却约束，见 tryFireworkOnce）
            legalFwCooldown = 0;
            return;
        }

        // 甲飞换装维持滑翔（按甲飞模式）；兼容 grim 输入检测时起飞包隔 tick 发
        if (!skipStartThisTick()) {
            armorFlySwap(toArmorMode(legalArmorMode.get()));
        }

        // 滑翔中应用方向控制
        // 不能用 mc.player.isFallFlying() 判断：甲飞本地大多时候不是滑翔状态
        // （懒换/来回闪换/每tick闪换都不会把本地滑翔标志设真，只有普通模式会本地强制滑翔），
        // 用「服务器认滑翔」的窗口判断（本地滑翔标志 或 本 tick/上一 tick 发过起飞包）。
        // 否则甲飞模式下方向控制根本不会执行：WASD 横向移动无效、
        // 烟花加速方向会跟着视觉朝向（俯仰也是相机俯仰）而不是平飞目标朝向。
        if (serverSeesGliding()) {
            legalFlightControl(forward, back, left, right);
        }
    }

    /** 合法平飞飞行/悬停方向控制（真鞘翅与甲飞模式共用） */
    private static void legalFlightControl(boolean forward, boolean back, boolean left, boolean right) {
        // 一键烟花：延后到移动包发送后释放（烟花加速方向跟随服务器视角）。
        // 甲飞模式由换装窗口结束后释放（见 releaseOneKeyFirework）；冻结悬停期间不释放（会和服务端静止状态打架）。
        if (oneKeyPending && !isArmorFlyActive() && !Freeze.isFrozen()) {
            oneKeyPending = false;
            LegalRotation.runAfterSend(ElytraFlySupport::releaseFireworkOnce);
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
        LegalRotation.rotate(targetYaw, targetPitch, LegalRotation.Mode.SEVERE);
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
        queueAutoFirework(level, interval);
    }

    /** 起飞后立即释放一次烟花（真鞘翅/官方：移动包发送后；甲飞：换装窗口内），并重置冷却 */
    private static void tryFireworkOnce() {
        int level = selectFireworkLevel();
        if (level == -1) return;
        int interval = fwIntervalForLevel(level);
        if (isArmorFlyActive()) {
            // 甲飞的「滑翔窗口」本来就随换装反复开合（懒换模式里本地滑翔为真时当 tick 不换装，
            // 服务器随即停飞 → serverSeesGliding() 会短暂变假，窗口重开时又被上层算作一次
            // 「起飞成功」，见 legalArmorTick）。起飞烟花是不看间隔直接放的，不拦的话飞行中
            // 每个窗口都会补一发，表现就是「自动烟花延迟全按最低的、疯狂放烟花」。
            // 冷却没走完就不是真的起飞，等 tickFlightFirework 按间隔正常放。
            if (legalFwCooldown > 0) return;
            queueAutoFirework(level, interval);
            return;
        }
        // 起飞烟花排队中：本 tick 的自动烟花（飞行/悬停分支）检查到此标志直接跳过，
        // 防止两个回调同 tick 都执行（runAfterSend 队列化后都会执行）导致一次起飞双放
        takeoffFireworkPending = true;
        LegalRotation.runAfterSend(() -> {
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
        queueAutoFirework(level, interval);
    }

    /**
     * 排队一次自动烟花释放。
     *
     * <p>真鞘翅/官方模式：延后到移动包发送后释放（烟花加速方向才能跟随服务器视角）。
     *
     * <p>甲飞：改由换装窗口内释放（见 {@link #releaseWindowFirework}）。
     * 甲飞窗口外发出去的烟花使用包会被 {@link #onPacketSend} 的甲飞拦截取下，
     * 等换装窗口重发时烟花已经换回背包/原槽位，服务端只会当成使用手上原本的物品——
     * 这正是「合法平飞的自动烟花在开启甲飞后不生效」的原因。
     * 一键烟花已经改成窗口内释放，自动烟花（起飞/飞行/悬停三处）按同一方式处理。
     */
    private static void queueAutoFirework(int level, int interval) {
        if (isArmorFlyActive()) {
            // 已有待释放的（例如本 tick 的起飞烟花）：不重复排队，避免同一窗口放两次
            if (windowFwPendingLevel != -1) return;
            windowFwPendingLevel = level;
            windowFwPendingInterval = interval;
            return;
        }
        LegalRotation.runAfterSend(() -> {
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

    /**
     * 一键烟花快捷键触发（Meteor 的按键设置在按键松开时回调，不在 tick 内）。
     *
     * <p>甲飞：服务器只在换装窗口（鞘翅在胸甲槽 + 起飞包已发）认滑翔，使用包交给
     * 换装窗口结束后释放（见 {@link #releaseOneKeyFirework}）——这里提前发包会被延迟机制拦下，
     * 重发时烟花已经换回背包/原槽位，服务器只会当成使用手上原本的物品（按键没反应）。
     *
     * <p>合法平飞：延后到移动包发送后释放（烟花加速方向跟随服务器视角，见
     * {@link #legalFlightControl}）。官方模式：立即释放。
     */
    public static void fireworkOnce() {
        if (mc.player == null) return;
        if (isArmorFlyEnabled() || isLegalMode()) {
            oneKeyPending = true;
            return;
        }
        releaseFireworkOnce();
    }

    /**
     * 每 tick 末尾检查一键烟花待释放请求。
     * 甲飞由换装窗口释放、合法平飞由移动包发送后释放，这里只兜底「按键后中途切回官方模式」的残留请求。
     */
    private static void checkOneKeyPending() {
        if (!oneKeyPending) return;
        if (isArmorFlyEnabled()) return;
        if (isLegalMode()) return;
        oneKeyPending = false;
        releaseFireworkOnce();
    }

    /** 释放一次烟花（一键烟花专用，可选背包，按一键烟花的背包开关与背包使用模式） */
    private static void releaseFireworkOnce() {
        int level = selectFireworkLevel(oneKeyBackpackFirework.get());
        if (level == -1) return;
        Predicate<ItemStack> pred = fireworkOfLevel(level);
        if (oneKeyBackpackFirework.get()) {
            // 背包烟花：按一键烟花自己的「背包使用模式」交换到手使用（1p SWAP / 2p PICKUP）
            BackpackUse.use(pred, oneKeyBackpackMode.get());
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
     *  三击互换（同 {@link #swapWithChest(int)}）：热栏/主背包的胸甲一步换到胸甲槽，
     *  换下的鞘翅回到胸甲原来的槽位；不需要空位，背包满也不会失败。
     *  点击在本地同步执行，本地容器立即更新，天然防重复触发。 */
    private static void swapBackChestplate() {
        if (!isElytraInChest()) return;

        // 优先热栏找胸甲（起飞时胸甲被换出），其次主背包
        FindItemResult chest = InvUtils.findInHotbar(stack -> isChestplate(stack) && !stack.is(Items.ELYTRA));
        if (!chest.found()) {
            chest = InvUtils.find(stack -> isChestplate(stack) && !stack.is(Items.ELYTRA), SlotUtils.MAIN_START, SlotUtils.MAIN_END);
        }
        if (!chest.found()) return;

        swapWithChest(chest.slot());
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

    /** 找背包里的鞘翅（快捷栏 0-8 / 主背包 9-35 / 副手 40）；找不到返回 null。
     *  不查装备槽：胸甲槽已经穿着鞘翅时无需再换，而且 26.1 的 Inventory 把护甲放在
     *  36-39（36=脚…39=头），Meteor SlotUtils 仍按旧版顺序换算菜单槽位，
     *  拿装备槽索引去点击会落到别的护甲槽上。 */
    private static FindItemResult findElytra() {
        FindItemResult elytra = InvUtils.find(stack -> stack.is(Items.ELYTRA), SlotUtils.HOTBAR_START, SlotUtils.MAIN_END);
        if (elytra.found()) return elytra;

        ItemStack offhand = mc.player.getOffhandItem();
        return offhand.is(Items.ELYTRA) ? new FindItemResult(SlotUtils.OFFHAND, offhand.getCount()) : null;
    }

    /**
     * 甲飞闪换一次：鞘翅上位 → 起飞包 → 换回胸甲。
     *
     * <p>背包里找不到鞘翅时（说明鞘翅已经穿在胸甲槽上：刚开模块、或上一次换装没走完）
     * 先把胸甲换回来，下一次闪换才有可用的背包槽位；身上和背包都没有鞘翅时什么也不做。
     */
    private static void flashSwapElytra() {
        FindItemResult elytra = findElytra();
        if (elytra == null) {
            swapBackChestplate();
            return;
        }

        swapWithChest(elytra.slot());
        sendStartFlying();
        flushPendingFirework();
        swapWithChest(elytra.slot());
        releaseOneKeyFirework();
        releaseWindowFirework();
    }

    /**
     * 把指定背包槽位与胸甲槽互换（PICKUP 三击：拿起 → 放胸甲槽 → 把换下的放回原槽）。
     *
     * <p>不能用 {@link InvUtils#move()}：它只在「操作开始时光标为空」的前提下才会补发第三击，
     * 把从胸甲槽换出来的胸甲/鞘翅放回原槽；玩家在背包里拿起物品（光标非空）时会跳过，
     * 换出来的东西就留在光标上，紧接着的 close 包会把它丢进背包第一个空槽（快捷栏优先），
     * 表现为「甲飞时一整理背包，胸甲/鞘翅被脱到快捷栏」。
     *
     * <p>三击写全后无论光标空不空，结果都一致：源槽 ↔ 胸甲槽互换、光标内容原样保留
     * （玩家正拖着的物品最多闪一下）。点击在本地同步执行，容器立即更新，天然防重复触发。
     *
     * @param slot 背包槽位索引（{@link InvUtils#find} 的返回值：快捷栏 0-8 / 主背包 9-35 / 副手 40）
     */
    private static void swapWithChest(int slot) {
        InvUtils.click().slot(slot);      // 1. 拿起源槽物品（与光标互换）
        InvUtils.click().slotArmor(2);    // 2. 放进胸甲槽（原胸甲进光标）
        InvUtils.click().slot(slot);      // 3. 原胸甲放回源槽（光标恢复原样）
        closeInventoryIfIdle();
    }

    /** 关包：让服务端把容器状态刷新回背包（原逻辑用于放回光标遗留物）。
     *  只在光标为空时发——光标非空说明玩家正在背包里拖物品，发出去会把它丢进
     *  背包第一个空槽（快捷栏优先）。 */
    private static void closeInventoryIfIdle() {
        if (mc.player.containerMenu.getCarried().isEmpty()) {
            mc.getConnection().send(new ServerboundContainerClosePacket(0));
        }
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
        // 服务器处理本 tick 的移动包时已经在滑翔（起飞包先于位置包发出），本地移动运算据此对齐
        startedGlidingThisTick = true;
        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    /**
     * 落地/进水时取消本地滑翔状态
     *
     * <p>甲飞的滑翔状态是「服务器同步的闪烁状态」，落地后服务器清掉标志位要等一个
     * 同步往返；不主动清的话本地会多滑翔一两 tick（滑翔运算、姿势、音效都还在），
     * 表现为「落地了但甲飞没取消」。这里在落地分支直接清掉本地标志，
     * 人物立刻回到普通地面移动。服务器那边同样会因为 onGround 自己清掉，不会打架。
     */
    private static void cancelLocalGliding() {
        if (mc.player != null && mc.player.isFallFlying()) {
            mc.player.stopFallFlying();
        }
    }

    /** 指定手是否手持烟花 */
    private static boolean isFireworkInHand(InteractionHand hand) {
        return mc.player.getItemInHand(hand).is(Items.FIREWORK_ROCKET);
    }

    /** 重发被拦截的烟花使用包（在换鞘翅 + 起飞后调用，卡服务器滑翔窗口） */
    private static void flushPendingFirework() {
        if (pendingFireworkPacket != null) {
            // 重发的包会再次触发 onPacketSend，置标志避免再次被拦截造成死循环
            bypassFireworkIntercept = true;
            try {
                mc.getConnection().send(pendingFireworkPacket);
            } finally {
                bypassFireworkIntercept = false;
            }
            pendingFireworkPacket = null;
        }
    }

    /**
     * 一键烟花：换装窗口结束后（鞘翅已换回胸甲）当场释放。
     *
     * <p>为什么不能插在换装两个包中间（换鞘翅 → 起飞 → 换回胸甲）：一键烟花要用背包时得发
     * 容器点击包，这些点击和换装的点击混在同一批里，客户端/服务端的背包状态一旦分叉，
     * 换回胸甲的点击就可能落空 → 鞘翅留在胸甲槽 → 服务器一直认为在滑翔 → 下一次起飞包
     * 会被判成「已经滑翔」而<b>停飞</b>（原版 {@code START_FALL_FLYING} 失败即 stopFallFlying，
     * Grim 那边是 ElytraA）。所以换装的两个包必须紧挨着发完，烟花放在换装结束之后。
     *
     * <p>时机仍在滑翔窗口内：服务端先处理完这一批包（tickConnection）再做实体 tick，
     * 所以此刻服务器依旧认为玩家在滑翔，使用包照常发射。
     *
     * <p>也不提前发包等拦截重发：重发时烟花已经换回背包/原槽位，服务器只会当成使用手上
     * 原本的物品（表现为按了没反应）。
     */
    private static void releaseOneKeyFirework() {
        if (!oneKeyPending) return;
        // 本 tick 没发过起飞包（服务器不认滑翔）就不放，等下一个换装窗口
        if (!startedGlidingThisTick) return;
        oneKeyPending = false;

        bypassFireworkIntercept = true;
        try {
            releaseFireworkOnce();
        } finally {
            bypassFireworkIntercept = false;
        }
    }

    /**
     * 甲飞：换装窗口结束后（鞘翅已换回胸甲）当场释放排队的自动烟花。
     *
     * <p>与一键烟花同理（见 {@link #releaseOneKeyFirework}）：窗口外发出的烟花使用包会被
     * {@link #onPacketSend} 拦截取下，重发时烟花已经换回背包/原槽位，
     * 服务端只会当成使用手上原本的物品（表现为「自动烟花不生效」）。
     * 时机仍在滑翔窗口内：服务端先处理完这一批包再做实体 tick，
     * 此刻服务器依旧认为玩家在滑翔，使用包照常发射。
     */
    private static void releaseWindowFirework() {
        if (windowFwPendingLevel == -1) return;
        // 本 tick 没发过起飞包（服务器不认滑翔）就不放，等下一个换装窗口
        if (!startedGlidingThisTick) return;

        int level = windowFwPendingLevel;
        int interval = windowFwPendingInterval;
        windowFwPendingLevel = -1;

        bypassFireworkIntercept = true;
        try {
            if (tryUseFireworkOfLevel(level)) {
                legalFwCooldown = interval;
            }
        } finally {
            bypassFireworkIntercept = false;
        }
    }
}
