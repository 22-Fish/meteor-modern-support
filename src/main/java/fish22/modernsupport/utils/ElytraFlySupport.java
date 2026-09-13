package fish22.modernsupport.utils;

import fish22.modernsupport.ModernSupport;
import fish22.modernsupport.modules.Freeze;
import fish22.modernsupport.modules.FireworkBoost;
import fish22.modernsupport.mixin.FireworkRocketEntityAccess;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅飞行增强逻辑（注入到 Meteor 官方 ElytraFly 模块，不新建模块）
 *
 * <p>通过 {@link fish22.modernsupport.mixin.MixinElytraFly} 把 Meteor 官方「鞘翅飞行」模块
 * （ElytraFly）的设置整理成三大块，本类承载「简单控制」与「甲飞」两块的全部业务逻辑：
 *
 * <ul>
 *   <li><b>简单控制</b>：简单控制模式（关闭 / 原版 / 发包 / 合法，默认<b>关闭</b>）+ 各模式的配置。
 *       「关闭」= 模块不做任何飞行控制（官方那两套也不跑），甲飞 / 无限鞘翅 照常可用；
 *       「合法」= 原来的合法平飞改名，真鞘翅时走「合法方向控制 + 原版滑翔物理」，
 *       「原版 / 发包」还是 Meteor 官方那两套逻辑（本类不接管）；</li>
 *   <li><b>甲飞</b>：甲飞模式（关闭 / 普通 / Grim模式），换装维持滑翔，可与上面的
 *       关闭 / 原版 / 合法 模式叠加（与「发包」模式互斥）。其中：
 *       <ul>
 *         <li>关闭 + 甲飞：只有换装维持滑翔，没有任何额外的方向控制；</li>
 *         <li>原版 + 甲飞：官方那套「原版」控制照常生效（WASD / 空格速度控制、自动驾驶…），
 *             甲飞只负责换装维持滑翔 —— 官方控制开头那道「胸甲槽要有滑翔组件」的守卫由
 *             {@link fish22.modernsupport.mixin.MixinElytraFly} 放行，
 *             见 {@link #shouldProvideGliderForVanillaArmor()}；</li>
 *         <li>合法 + 甲飞：本类的合法方向控制（服务器视角 + 滑翔物理）+ 换装维持滑翔。</li>
 *       </ul></li>
 *   <li><b>无限鞘翅</b>：照搬 AEfish 的无限鞘翅，逻辑在 {@link InfiniteElytraSupport}。</li>
 * </ul>
 *
 * <p>俯仰40 与 弹跳 已从官方模式列表拆成独立模块
 * （{@link fish22.modernsupport.modules.ElytraPitch40} / {@link fish22.modernsupport.modules.ElytraBounce}），
 * 「一键烟花」也拆成了独立模块（{@link fish22.modernsupport.modules.FireworkUse}）。
 *
 * <p>「烟花加速」是独立模块（{@link fish22.modernsupport.modules.FireworkBoost}），
 * 本类只提供它用到的史莱姆原版运算实现与重缩放状态记账，不再由 ElytraFly 触发。
 *
 * <h3>甲飞（穿胸甲假飞）</h3>
 * 鞘翅放热栏、胸甲槽穿胸甲，空中按跳跃键起飞，落地自动换回胸甲。分「普通/Grim」两种方式。
 * 另有「落地防摔」开关（甲飞、合法平飞甲飞各一个，默认关闭）+「落地防摔方式」，
 * 目前只有 Grim 一种方式：快接地那一 tick 先补一发「服务端已知坐标 +
 * {@value #LANDING_NOFALL_DELTA_Y}」的位置包，触发服务端 {@code movedUpwards} 判定
 * 把累计摔伤距离清零（见 {@link #tickLandingNoFall}），甲飞高速下降接地也不会摔死；
 * 只在真的会摔掉血的落地补这一发，不是常开无摔伤。
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
 *       冻结模式 = 复用 Freeze 模块的冻结效果（travel 取消 + 输入屏蔽 + 位置包拦截），完全静止。
 *       冻结期间甲飞换装照常进行（维持服务端滑翔状态），但不释放任何排队的自动/一键烟花
 *       （见 {@link #releaseWindowFirework()}）；
 *       悬停+ = 冻结的包策略（不报位置）再补上「每 tick 至少一发只带朝向的移动包」，
 *       服务端每 tick 都能把「悬浮过久」计数结算成「正在滑翔」，反作弊那边又没有任何
 *       位移可比（见 {@link #sendHoverPlusLookPacket()}）</li>
 *   <li>甲飞时悬停/冻结不发位置包，原版会按旧值判「悬浮过久」踢出（约 4 秒）。悬停板块的
 *       「防踢模式」（仅合法平飞 + 甲飞 + 悬停模式=冻结时显示，默认关闭）开了以后，冻结期间
 *       按「防踢间隔」（默认 {@link #FLOATING_CHECK_REFRESH_TICKS} tick）补一发移动包把该计数
 *       清零：下降脉冲 = 先比真实位置低 {@link #FLOATING_CHECK_DESCENT}、下一 tick 再回位，
 *       服务端按「正在下降」结算；空包防踢 = 换装时把鞘翅多留一 tick 再补一发原地包，
 *       服务端按「正在滑翔」结算（见 {@link #planAntiKick()} / {@link #tickAntiKick()}）。
 *       「悬停+」模式自带每 tick 的朝向包，不需要这两个防踢方式</li>
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

    /** 官方模式是否为「合法」（原合法平飞，改名后显示为「合法」） */
    public static boolean isLegalMode() {
        return flightMode != null && flightMode.get().name().equals("Legal");
    }

    /**
     * 官方模式是否为「关闭」（简单控制不做任何飞行控制）。
     *
     * <p>「关闭」下官方那套飞行控制（原版 / 发包）也不跑，本模组的 甲飞 / 无限鞘翅
     * 照常按各自开关生效；本类自己的合法平飞逻辑同样不跑。
     */
    public static boolean isSimpleControlOff() {
        return flightMode != null && flightMode.get().name().equals("Off");
    }

    /**
     * 官方模式是否为「原版」（Meteor 官方那套飞行控制：WASD / 空格速度控制、自动驾驶等）。
     *
     * <p>「原版」和甲飞不互斥：开着甲飞时官方那套原版控制照常跑（甲飞只负责换装维持滑翔），
     * 见 {@link #shouldProvideGliderForVanillaArmor()}。
     */
    public static boolean isVanillaMode() {
        return flightMode != null && flightMode.get() == ElytraFlightModes.Vanilla;
    }

    /** 甲飞是否开启（「甲飞模式」不为关闭） */
    public static boolean isArmorFlyActive() {
        return armorMode != null && armorMode.get() != ArmorMode.Off;
    }

    /** 官方逻辑是否被我们接管（合法 / 甲飞 都要接管；原版 / 发包 / 关闭 都不走本类的逻辑） */
    public static boolean isCustomMode() {
        return isLegalMode() || isArmorFlyActive();
    }

    /**
     * 「简单控制模式」下拉里显示的候选值：关闭 / 原版 / 发包 / 合法。
     *
     * <p>「关闭」是默认值：模块不做任何飞行控制，甲飞 / 无限鞘翅 照常可用。
     * 俯仰40 与 弹跳 已拆成独立模块（{@code 鞘翅Pitch40 / 鞘翅弹跳}），
     * 不再出现在「鞘翅飞行」的模式下拉里（见
     * {@link fish22.modernsupport.mixin.MixinDefaultSettingsWidgetFactory} 与
     * {@link fish22.modernsupport.mixin.MixinElytraFly} 的候选值收敛）。
     */
    public static ElytraFlightModes[] simpleControlModes() {
        List<ElytraFlightModes> modes = new ArrayList<>(4);

        ElytraFlightModes off = offMode();
        if (off != null) modes.add(off);
        modes.add(ElytraFlightModes.Vanilla);
        modes.add(ElytraFlightModes.Packet);

        ElytraFlightModes legal = legalMode();
        if (legal != null) modes.add(legal);

        return modes.toArray(new ElytraFlightModes[0]);
    }

    /** 追加的「合法」枚举常量（编译期不可见，按 name 找） */
    public static ElytraFlightModes legalMode() {
        for (ElytraFlightModes mode : ElytraFlightModes.values()) {
            if (mode.name().equals("Legal")) return mode;
        }
        return null;
    }

    /** 追加的「关闭」枚举常量（编译期不可见，按 name 找；找不到返回 null） */
    public static ElytraFlightModes offMode() {
        for (ElytraFlightModes mode : ElytraFlightModes.values()) {
            if (mode.name().equals("Off")) return mode;
        }
        return null;
    }

    /** 当前甲飞是否真的在运行（甲飞模式非关闭 且 鞘翅飞行模块处于开启状态） */
    public static boolean isArmorFlyEnabled() {
        if (!isArmorFlyActive()) return false;
        ElytraFly module = Modules.get().get(ElytraFly.class);
        return module != null && module.isActive();
    }

    /**
     * 客户端本地烟花加速方向是否必须对齐服务端朝向。
     *
     * <p>合法平飞 / 甲飞会把服务端朝向伪造成目标角，而客户端视觉朝向（相机）保持原样。
     * 原版烟花实体的加速是客户端、服务端各算一次：服务端用服务器朝向，客户端本地用
     * {@code getLookAngle()}。不改的话两端方向分叉，本地位置被服务端纠正（表现就是回弹）。
     *
     * <p>{@link fish22.modernsupport.mixin.MixinFireworkRocketEntity} 用它决定是否改用
     * {@link LegalRotation#getServerLook(net.minecraft.world.entity.Entity)}。
     */
    public static boolean shouldAlignFireworkBoostWithServer() {
        if (!isCustomMode()) return false;
        ElytraFly module = Modules.get().get(ElytraFly.class);
        if (module == null || !module.isActive() || mc.player == null) return false;
        // 真鞘翅看本地滑翔状态；甲飞本地标志会闪，看「服务器认滑翔」的窗口
        return mc.player.isFallFlying() || (isArmorFlyActive() && serverSeesGliding());
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

        // 移动运算窗口：这一整段（烟花加速方向 + 滑翔速度运算）都用合法转头的真实角度
        LegalRotation.pushMoveWindow();
        try {
            // 烟花加速（独立模块）：滑翔运算之前套用，方向 = 本 tick 实际运算朝向
            FireworkBoost.beforeMove(player);

            // 原版 travelFallFlying 的顺序：先算滑翔速度写回动量，再按这个动量 move
            Vec3 movement = ((LivingEntityGlideInvoker) player).meteor$updateFallFlyingMovement(player.getDeltaMovement());
            player.setDeltaMovement(movement);
            player.move(MoverType.SELF, movement);
        } finally {
            LegalRotation.popMoveWindow();
        }
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

        // 服务端此刻是否把这一 tick 当作滑翔：本地已同步滑翔，或最近 4 tick 内发出过起飞包
        // （见 serverSeesGliding：服务端清滑翔标志要跟 transaction 绕一个往返才生效，这段延迟里仍认滑翔）。
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
     * 「原版 + 甲飞」：本 tick 是否要让官方那套原版控制看到「胸甲槽有滑翔组件」。
     *
     * <p>由 {@link fish22.modernsupport.mixin.MixinElytraFly} 拦下官方 {@code onPlayerMove}
     * 开头那句 {@code getItemBySlot(CHEST).has(GLIDER)} 判定之后调用：返回 true 时那一处判定
     * 直接算「有滑翔组件」，官方原版控制照常接管本 tick 的移动向量。
     *
     * <p>官方原版控制全程假设「客户端穿着鞘翅、真的在滑翔」，甲飞穿的是胸甲
     * （鞘翅只在换装窗口那一两 tick 在胸甲槽上），不放行的话整套原版控制会被挡掉，
     * 表现就是「原版 + 甲飞」只剩甲飞的换装滑翔、WASD / 空格完全没反应。
     *
     * <p>放行条件与 {@link #travelAsElytra} 做滑翔运算的条件<b>完全相同</b>
     * （{@link #shouldUseElytraMovement}）：两边必须同开同关，
     * 否则「本 tick 用什么运算移动」和「官方要不要接管移动向量」会对不上。
     *
     * <p>只影响官方那一处判定：不换装、不动背包、不改滑翔状态，甲飞的换装时序与发包不变。
     */
    public static boolean shouldProvideGliderForVanillaArmor() {
        return isVanillaMode() && shouldUseElytraMovement(mc.player);
    }

    /**
     * 服务端此刻是否把这一 tick 当作滑翔：本地已同步滑翔，
     * 或最近 {@link #START_FLYING_WINDOW_TICKS} tick 内发出过起飞包。
     *
     * <p>甲飞本地大多时候不是滑翔状态（胸甲在身上，只有换装窗口那一两个 tick 服务器才认），
     * 服务器认滑翔靠的是我们发出去的起飞包。而服务端清滑翔标志不是立刻生效的：
     * 那个广播要跟着 transaction 绕一个往返才应用（见 {@link #START_FLYING_WINDOW_TICKS}），
     * 这段时间里服务端/反作弊那边仍然在按滑翔预测，所以客户端也要继续按滑翔运算移动。
     *
     * <p>窗口固定 {@value #START_FLYING_WINDOW_TICKS} tick（硬编码）：
     * 超过窗口还没有新的起飞包，就当作服务端已经不认滑翔，退回原版空中运算。
     *
     * <p>移动运算（{@link #travelAsElytra}）与甲飞模式下的方向控制
     * （{@link #legalArmorTick}）共用这个判断：两者必须同时生效，
     * 否则客户端移动方向、服务器朝向、烟花加速方向会分叉。
     */
    private static boolean serverSeesGliding() {
        // 连续回弹自愈的重新对齐窗口（见 tickRubberbandWatch）：这几 tick 按「没滑翔」算，
        // 等三边的滑翔状态重新对齐，不要单边滑翔
        if (glidingRealignTicks > 0) return false;

        return mc.player != null
            && (mc.player.isFallFlying() || ticksSinceStartFlying <= START_FLYING_WINDOW_TICKS);
    }

    /**
     * 连续回弹自愈（每 tick 调用，见 {@link #onTick()}）。
     *
     * <p>甲飞的滑翔状态是「每 tick 换装 + 发起飞包」撑起来的，服务端/反作弊那边只要有一次
     * 没认（换装点击被丢弃、起飞包被判成「已经滑翔」或「还在地面」……），我们这边就会继续
     * 按滑翔运算移动，而它按普通空中运算预测 → 每 tick 位移都不一样 → 服务端每 tick 都发
     * 位置纠正，也就是「连续回弹」。
     *
     * <p>这种错位本身没有可靠的包能立刻看出来（起飞包被取消不会有回执，滑翔 bit 也可能
     * 根本没变过、收不到广播），所以这里用「短时间内连续收到位置纠正」当信号：命中就当
     * 滑翔状态已经分叉，停下换装与起飞包 {@value #GLIDING_REALIGN_TICKS} tick（期间按原版
     * 空中运算移动，换装也一并停），让客户端 / 服务端 / 反作弊三边重新落到同一个状态上，
     * 再从干净状态重新起飞。冷却 {@value #RUBBERBAND_REALIGN_COOLDOWN_TICKS} tick，
     * 避免一直停在「重新对齐」里。
     *
     * <p>注意：真实传送（珍珠、/tp）也可能发位置纠正包，但那种一次就结束，凑不满窗口。
     */
    private static void tickRubberbandWatch() {
        if (!isArmorFlyEnabled() || mc.player == null) {
            rubberbandCount = 0;
            rubberbandWindow = 0;
            rubberbandCooldown = 0;
            return;
        }

        if (rubberbandCooldown > 0) rubberbandCooldown--;
        if (rubberbandWindow > 0 && --rubberbandWindow == 0) rubberbandCount = 0;

        if (rubberbandCooldown > 0 || glidingRealignTicks > 0) return;
        if (rubberbandCount < RUBBERBAND_COUNT) return;
        // 落地/流体/骑乘时服务端本来就会停滑翔，不当作分叉
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || mc.player.isPassenger()) return;

        rubberbandCount = 0;
        rubberbandWindow = 0;
        rubberbandCooldown = RUBBERBAND_REALIGN_COOLDOWN_TICKS;
        glidingRealignTicks = GLIDING_REALIGN_TICKS;
        cancelLocalGliding();
        ModernSupport.LOG.info("[甲飞] 连续回弹：滑翔状态和服务端对不上，"
            + "暂停 {} tick 重新对齐后再起飞", GLIDING_REALIGN_TICKS);
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
        // 「空中屏蔽空格」是甲飞板块自己的选项（合法/原版下的甲飞共用同一个开关）
        boolean spaceBlock = isArmorFlyActive() && spaceBlockInAir != null && spaceBlockInAir.get();
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

        // 「空包防踢」保持鞘翅的那一发：本地胸甲槽此刻还是鞘翅，但这一发同样是完整的起飞，
        // 必须按下跳跃——否则 Grim ElytraB 在起飞包之后的第一个 update 包上看到 jump=false，报 [no jump]。
        // 不会因此多出一个起飞包：那一 tick 本地已经置成滑翔（见 flashSwapElytra），
        // 原版 tryToStartFallFlying 会因「已在滑翔」直接返回 false。
        // 普通甲飞换装这一 tick 已经换回胸甲，走的仍是下面那条老判断，逻辑不变。
        if (holdElytraPacketThisTick) {
            jumpInputReleasedForStart = false;
            return true;
        }

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
        return grimInputSequence != null && grimInputSequence.get();
    }

    /**
     * 当前生效的换甲间隔（tick）：甲飞模块看自己的选项，合法平飞的甲飞看它自己的同名选项。
     * 设置未注入（单测/初始化前）时按 1 处理，即「每 tick 都允许换装」的旧行为。
     */
    private static int armorSwapIntervalTicks() {
        return armorSwapInterval == null ? 1 : Math.max(1, armorSwapInterval.get());
    }

    /** 甲飞模式（Off = 关闭；常量名 Lazy = 旧「懒换」，现显示为「Grim模式」） */
    public enum ArmorMode {
        Off("关闭"),
        Normal("普通"),
        Lazy("Grim模式");

        private final String displayName;

        ArmorMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * 合法平飞悬停模式
     *
     * <p><b>悬停</b>：每 tick 把速度覆盖成 (0, 0.02, 0)，位置包照发。服务端/反作弊那边
     * 会看到一个和自己预测（滑翔运算）对不上的位移，严格的反作弊会回弹。
     *
     * <p><b>冻结</b>：复用 Freeze 的冻结（travel 取消 + 输入屏蔽 + 位置包拦截），完全静止、
     * 服务端没有任何位移可比，所以不会被回弹；代价是服务端「悬浮过久」的计数没人给它清零，
     * 需要悬停板块的「防踢模式」按间隔补包（见 {@link #hoverAntiKick}）。
     *
     * <p><b>悬停+</b>（史莱姆式）：同样不报位置（反作弊没有位移可比，不会回弹），但每 tick
     * 保证有一发「只带朝向、不带坐标」的移动包被服务端处理。它排在甲飞每 tick 的起飞包之后，
     * 服务端处理它时 {@code isFallFlying} 为真 → 原版 {@code clientIsFloating} 每 tick 都被
     * 置假、悬浮计数每 tick 清零 → 不会被「Flying is not enabled」踢出，也就不需要防踢脉冲。
     * 见 {@link #sendHoverPlusLookPacket()}。
     */
    public enum HoverMode {
        Hover("悬停"),
        Freeze("冻结"),
        HoverPlus("悬停+");

        private final String displayName;

        HoverMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * 防踢模式（悬停板块，仅合法平飞 + 甲飞 + 悬停模式=冻结时可见）
     *
     * <p>只给「冻结」用：冻结期间一个位置包都不发，服务端那条「悬浮过久」判定
     * （见 {@link #tickAntiKick()}）得不到重算机会，只能靠这里补包清零。
     * 悬停模式（位置包照发）和「悬停+」（每 tick 一发朝向包）都不需要它。
     *
     * <p>关闭：不发任何额外包。
     * <p>下降脉冲移动包：冻结期间按「防踢间隔」（默认 20 tick）补一对移动包 —— 第一发比真实位置低
     * {@link #FLOATING_CHECK_DESCENT}（服务端按「正在下降」结算，原版「悬浮过久」计数
     * 直接清零），下一 tick 再补一发真实坐标回位。坐标偏差 3 厘米、只持续 1 tick，
     * 不产生位移漂移，也不依赖服务端此刻认不认滑翔（见 {@link #tickAntiKick()}）。
     * <p>空包防踢：冻结期间按「防踢间隔」让甲飞的换装把鞘翅<b>多留一 tick</b>
     * （开了「兼容 grim 输入检测」时多留两 tick，见 {@link #elytraHoldTicks}），
     * 并补一发原地不动（真实坐标）的移动包。服务端处理这一包时 {@code isFallFlying} 为真，
     * 原版 {@code clientIsFloating} 直接置假、悬浮计数清零，之后再把胸甲换回来。
     * 坐标、朝向、动量一点都不动，代价是每个间隔有一两 tick 穿的是鞘翅而不是胸甲，
     * 且要等服务端真的认滑翔（换装没跑成时会推迟到下一个换装窗口，见 {@link #tickAntiKick()}）。
     * 这一发也是完整的「起飞」：开了「兼容 grim 输入检测」时，同一 tick 的输入包照常带
     * 跳跃按下、本地置成滑翔避免原版补第二个起飞包（见 {@link #shouldPressJumpInput()}），
     * 不会被 Grim ElytraB 判 [no jump] / ElytraC 起飞过频。
     */
    public enum AntiKickMode {
        Off("关闭"),
        // 常量名沿用旧值：Meteor 配置按枚举常量名保存，改名会让老配置里的防踢模式失效
        ZeroDeltaMove("下降脉冲移动包"),
        HoldElytra("空包防踢");

        private final String displayName;

        AntiKickMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 烟花加速自动重缩放算法（史莱姆 mod 的 V1/V2） */
    public enum FireworkRescaleAlgorithm {
        V1("V1"),
        V2("V2");

        private final String displayName;

        FireworkRescaleAlgorithm(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * 「落地防摔」的补包方式（只有开关打开时才生效）。
     *
     * <p><b>Grim</b>：快接地那一 tick 先补一发「服务端已知坐标 +
     * {@value #LANDING_NOFALL_DELTA_Y}」的位置包（{@code onGround=false}），
     * 触发服务端 {@code movedUpwards} 判定把累计摔伤距离清零，
     * 紧接着处理落地包时就摔不掉血（见 {@link #tickLandingNoFall}）。
     */
    public enum LandingNoFallMode {
        Grim("Grim");

        private final String displayName;

        LandingNoFallMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    // ====== 设置引用（MixinElytraFly 创建设置后注入） ======

    /** 官方模式设置（「简单控制模式」：原版/发包 + MixinElytraFlightModes 追加的「合法」） */
    public static Setting<ElytraFlightModes> flightMode;
    /** 甲飞模式（关闭/普通/Grim模式） */
    public static Setting<ArmorMode> armorMode;
    public static Setting<Boolean> muteSounds;
    public static Setting<Boolean> spaceBlockInAir;
    /** 甲飞：「落地防摔」开关（落地瞬间重置服务端摔伤距离，默认关闭） */
    public static Setting<Boolean> landingNoFall;
    /** 甲飞：「落地防摔」方式（Grim） */
    public static Setting<LandingNoFallMode> landingNoFallMode;
    public static Setting<Boolean> grimInputSequence;
    /** 甲飞换甲间隔（tick）：两次换装之间至少间隔的 tick 数，1 = 每 tick 都允许换（旧行为） */
    public static Setting<Integer> armorSwapInterval;
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

    /** 防踢模式（悬停板块）：冻结期间补「下降脉冲」移动包清零服务端悬浮计数 */
    public static Setting<AntiKickMode> hoverAntiKick;

    /** 防踢脉冲间隔（tick）：默认 {@link #FLOATING_CHECK_REFRESH_TICKS} */
    public static Setting<Integer> hoverAntiKickInterval;

    /** 一键烟花（独立模块「一键烟花」注入）：是否允许使用背包中的烟花 */
    public static Setting<Boolean> oneKeyBackpackFirework;
    /** 一键烟花（独立模块「一键烟花」注入）：背包交换的发包模式（1p = SWAP 2包 / 2p = PICKUP 4 包） */
    public static Setting<BackpackUse.Mode> oneKeyBackpackMode;
    /** 烟花加速值（史莱姆 mod 默认 1.7） */
    public static Setting<Double> fireworkBoostSpeed;
    /** 史莱姆的 auto-rescale-firework-box */
    public static Setting<Boolean> fireworkRescale;
    /** 史莱姆的 auto-rescale-firework-amount */
    public static Setting<Double> fireworkRescaleAmount;
    /** 史莱姆的 auto-rescale-firework-al */
    public static Setting<FireworkRescaleAlgorithm> fireworkRescaleAlgorithm;
    /** 史莱姆的 auto-rescale-axis-zero-point-three（Y 轴分支） */
    public static Setting<Double> fireworkRescaleExtraY;
    /** 史莱姆的 auto-rescale-axis-zero-point-three（XZ 轴分支） */
    public static Setting<Double> fireworkRescaleExtraXZ;
    /** 史莱姆的 firework-boost-use-rescale */
    public static Setting<Boolean> fireworkBoostUseRescale;

    // ====== 常量 ======

    /** 胸甲槽容器坐标（玩家容器） */
    private static final int CHEST_SLOT = 6;

    /** 热栏第 9 格（挪背包鞘翅时的目标格，热栏索引 8） */
    private static final int MOVE_TO_HOTBAR = 8;

    /**
     * 冻结时补发防踢脉冲的默认间隔（tick）：实际生效值见设置「防踢间隔」
     * （{@link #hoverAntiKickInterval}，设置未注入时用这个兜底）。
     *
     * <p>两个上限取小的：原版「悬浮过久」踢出是 80 tick（玩家重力 0.08），
     * 反作弊 Grim 的 {@code BadPacketsR}「位置饥饿」是 2 秒（40 tick）内必须出现过一个
     * 带坐标的移动包。取 20（1 秒）＝ 4 倍 / 2 倍余量：补发的包要等服务端 tick 处理、
     * 还会被丢包/延迟影响，间隔太贴上限时（比如 36）连续两发没生效就会踩到 80 tick 被踢。
     *
     * <p>「间隔」就是服务端悬浮计数的峰值：判定标志位只在收到那发下降包的瞬间清零，
     * 清零后（下一 tick 的回位包）又开始涨，所以间隔多大、计数最多就涨到多大——
     * 30 tick 仍然安全（离 80 还有 2.6 倍余量），但 Grim 的「位置饥饿」（40 tick）
     * 就只剩 10 tick 余量，客户端掉帧/丢包时容易被抓，所以默认 20。
     *
     * <p>另外 {@code BadPacketsE} 是「连续 19 个不带坐标的移动包」就报，玩家一直转头时
     * 旋转包会很快把这个计数顶上去，所以还要看
     * {@link #FLOATING_CHECK_MAX_NO_POSITION_PACKETS} 提前补发，不能只靠这个间隔。
     */
    private static final int FLOATING_CHECK_REFRESH_TICKS = 20;

    /**
     * 连续放出多少个「不带坐标的移动包」就必须补一发防踢包（Grim {@code BadPacketsE} 上限是 19）。
     * 冻结期间只有旋转包会真的发出去，玩家不转头时这个计数根本不涨。
     */
    private static final int FLOATING_CHECK_MAX_NO_POSITION_PACKETS = 18;

    /**
     * 防踢脉冲「下降包」比真实位置低多少（方块）。
     *
     * <p>原版 {@code ServerGamePacketListenerImpl#handleMovePlayer} 判悬浮的条件是
     * {@code oyDist >= -0.03125}（大于等于，不是大于），所以这一包只要比服务端记的
     * 上一包低 0.03125 以上，服务端就按「正在下降」结算：{@code clientIsFloating}
     * 直接置假、悬浮计数清零。这一步<b>不看服务端此刻认不认滑翔</b>，
     * 不像零位移包那样要正好落在甲飞换装窗口里（服务端 tick 批次和客户端 tick
     * 对不上时就落空，甚至反过来被判成悬浮）。
     *
     * <p>取 0.0313 与原版 Meteor 的 Flight 防踢一致：比阈值多一点点，肉眼不可见，
     * 下一 tick 立刻用真实坐标回位，不会像「每发都往下挪一点」那样漂移。
     */
    private static final double FLOATING_CHECK_DESCENT = 0.0313;

    /**
     * 「落地防摔（Grim）」补包的上抬量（方块）。
     *
     * <p>原版服务端 {@code ServerGamePacketListenerImpl#handleMovePlayer} 里有一条
     * {@code if (movedUpwards) this.player.resetFallDistance()}，其中
     * {@code movedUpwards = 本包 Y > 服务端记住的上一包 Y}。也就是说：只要补发一发
     * 「服务端已知坐标 + 一丁点上抬」的位置包，服务端就会把这条玩家累计的摔伤距离清零
     * ——这就是 Grim 无摔伤（Lazy Bypass）走的逻辑。
     *
     * <p>取 9E-8（史莱姆同款）：既要严格大于服务端记的坐标（判定是 {@code > 0}，一点点就够），
     * 又要小到不触发反作弊的移动判定（Grim {@code getMovementThreshold()} 在 1.18.2+ 是
     * 0.0002，这一包在服务端/Grim 看来几乎就是「零位移」）。
     */
    private static final double LANDING_NOFALL_DELTA_Y = 9.0E-8;

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

    /**
     * 换甲间隔计时：>0 时本 tick 不允许换装，每 tick 递减一次（见 {@link #onTick()}）。
     * 与「兼容 grim 输入检测」的隔 tick 跳换装相互独立——那个是输入序列约束，
     * 这个是玩家设置的最小间隔，两者同时开启时取更严的那个。
     */
    private static int armorSwapCooldown = 0;

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

    /** 上一 tick 是否发过起飞包（「起飞包窗口」计时用，见 {@link #START_FLYING_WINDOW_TICKS}） */
    private static boolean startedGlidingLastTick = false;

    /**
     * 【硬编码】起飞包有效期（tick）：发出起飞包后这么久之内，本地仍然按滑翔运算移动。
     *
     * <p>服务端广播的滑翔标志不是立刻生效的：Grim 那边把它挂在 transaction 上
     * （{@code PacketSelfMetadataListener} → {@code addRealTimeTask}），要等这个 transaction
     * 从客户端绕回来才应用，延迟 ≈ 一个 RTT。这段时间里服务端/反作弊那边仍然按滑翔预测，
     * 本地保持滑翔运算才不会两边分叉（无限鞘翅脱鞘翅那几 tick 不回弹就是这个道理）。
     *
     * <p>超过这个窗口还没有新的起飞包，说明换装 → 起飞包那条链断了，本地就退回原版空中运算，
     * 和「服务端已经不认滑翔」的预测保持一致：宁可顿一下，也不要单边滑翔吃回弹。
     */
    private static final int START_FLYING_WINDOW_TICKS = 4;

    /** 距上一次发出起飞包经过的 tick 数（发出起飞包那一 tick 记 0；窗口外保持大于窗口的值） */
    private static int ticksSinceStartFlying = START_FLYING_WINDOW_TICKS + 1;

    /**
     * 「服务端刚把滑翔停掉」之后的重新对齐等待（tick）。
     *
     * <p>取值要够服务端与反作弊把这一个往返走完：Grim 把实体元数据挂在 transaction 上
     * （{@code PacketSelfMetadataListener} → {@code addRealTimeTask}），我们收到广播之后
     * 还要等它把这 1 个往返走完才会按「没滑翔」预测。等待期间我们这边也按原版空中运算移动
     * （见 {@link #serverSeesGliding()}），宁可顿一下，也不要单边滑翔吃连续回弹。
     *
     * <p>只由「连续收到位置纠正」触发（见 {@link #tickRubberbandWatch()}）。
     * 不用服务端广播的滑翔 bit 当信号：那个 bit 在正常飞行里本来就会随换装一 tick 真一 tick 假
     * （服务端每个 aiStep 都会按胸甲槽重算），分不出「服务端把滑翔停掉了」。
     */
    private static final int GLIDING_REALIGN_TICKS = 3;

    /**
     * 重新对齐剩余 tick：>0 时这几 tick 不换装、不发起飞包、按原版空中运算移动
     * （连续回弹自愈，见 {@link #tickRubberbandWatch()}）。
     */
    private static int glidingRealignTicks = 0;

    /**
     * 连续回弹自愈：窗口内收到这么多次服务端位置纠正就认为滑翔状态已分叉
     * （见 {@link #tickRubberbandWatch()}）。
     *
     * <p>取 3 是有意的：偶尔一两发位置纠正（传送、轻撞、反作弊小幅拉回）不算分叉，
     * 只有「连着被打回来」才是甲飞的滑翔状态掉了。调大到 4 以上或把下面的冷却调长，
     * 自愈就会更少触发。
     */
    private static final int RUBBERBAND_COUNT = 3;

    /** 连续回弹自愈的判定窗口（tick） */
    private static final int RUBBERBAND_WINDOW_TICKS = 6;

    /** 连续回弹自愈的冷却（tick）：避免一直停在「重新对齐」里 */
    private static final int RUBBERBAND_REALIGN_COOLDOWN_TICKS = 40;

    /** 当前判定窗口内收到的位置纠正次数 */
    private static int rubberbandCount = 0;

    /** 判定窗口剩余 tick */
    private static int rubberbandWindow = 0;

    /** 连续回弹自愈的剩余冷却 tick */
    private static int rubberbandCooldown = 0;

    /** 「兼容 grim 输入检测」用：服务端最后一次看到的跳跃键是不是松开（松开才允许发起飞包） */
    private static boolean jumpInputReleasedForStart = false;

    /**
     * 距下次补发防踢脉冲的剩余 tick（见 {@link #tickAntiKick()}）。
     * -1 = 计时未支起（没在冻结）：进冻结时先支起计时器，等满一个间隔再补第一发。
     */
    private static int floatingCheckRefreshTicks = -1;

    /** 冻结期间已经放行了多少个「不带坐标的移动包」（旋转包），用于提前补防踢包 */
    private static int flyingPacketsWithoutPosition = 0;

    /** 本 tick 由防踢包顶替客户端的移动包：这一 tick 客户端自己的移动包全部吞掉 */
    private static boolean antiKickPacketThisTick = false;

    /** 防踢脉冲：上一 tick 发过下降包，本 tick 要补一发真实坐标的回位包 */
    private static boolean floatingPulseRestorePending = false;

    /** 本 tick 排了一发防踢脉冲：由 {@link #planAntiKick()} 判定、{@link #tickAntiKick()} 发包 */
    private static boolean antiKickPulseQueued = false;

    /** 「空包防踢」：本 tick 请求换装把鞘翅多留一 tick（由 {@link #flashSwapElytra()} 消费） */
    private static boolean holdElytraRequested = false;

    /**
     * 「空包防踢」：鞘翅还要再保持多少 tick，减到 0 的那一 tick 开头换回胸甲（见 {@link #onTick()}）。
     * 0 = 没在保持。
     *
     * <p>开「兼容 grim 输入检测」时取 2，不是 1：那一发起飞包同 tick 的输入包是「按下跳跃」
     * （Grim ElytraB 要求），而 Grim 的 {@code MultiActionsC} 会把「输入里有移动键
     * （{@code KnownInput#moving()} 含 {@code jump}）时点背包」的容器点击包直接取消。
     * 换回胸甲的三次点击排下一 tick 的话，正好撞在这份 jump=true 上被取消，
     * 鞘翅就再也换不下来了。多等一 tick，先让「松开」输入包发出去，点击才不会被取消。
     */
    private static int elytraHoldTicks = 0;

    /** 「空包防踢」：本 tick 真的把鞘翅留住了吗——留住了这一 tick 才补原地移动包 */
    private static boolean holdElytraPacketThisTick = false;

    /** 正在发模块自己补的防踢脉冲：这一发要穿过冻结拦截，不能被自己拦掉 */
    private static boolean bypassFreezeIntercept = false;

    /**
     * 「悬停+」：本 tick 处于包层面静止的悬停（不报位置），要在这一 tick 的移动包之后
     * 保证有一发只带朝向的移动包发出去（见 {@link #sendHoverPlusLookPacket()}）。
     */
    private static boolean hoverPlusThisTick = false;

    /**
     * 本 tick 是否已经有「不带坐标的移动包」发出去（客户端自己的旋转包、我们补的朝向包
     * 都会置这一位）。「悬停+」靠它判断这一 tick 还需不需要补，避免一 tick 两个朝向包。
     */
    private static boolean noPositionPacketThisTick = false;

    /** 最近一发「自己发出去的带坐标移动包」的坐标（= 服务端记住的坐标，见 {@link #tickLandingNoFall}） */
    private static double lastSentX;
    private static double lastSentY;
    private static double lastSentZ;

    /** 最近一发移动包声称的落地状态（用于识别「上一包在空中 → 这一包落地」这一瞬间） */
    private static boolean lastSentOnGround = true;

    /** 本 tick 移动之前客户端累计的下落距离（落地包发出时已被清零，只能提前记） */
    private static double fallDistanceBeforeTick = 0.0;

    // 烟花加速重缩放状态（对应史莱姆 PlayerStateManager 的所需子集）
    private static double fireworkRescaleLastX;
    private static double fireworkRescaleLastY;
    private static double fireworkRescaleLastZ;
    private static float fireworkRescaleLastPitch;
    private static float fireworkRescaleLastYaw;
    private static Vec3 fireworkRescaleLastRealMovement = Vec3.ZERO;
    private static boolean fireworkRescaleLastInWater;
    private static boolean fireworkRescaleLastInLava;
    private static Input fireworkRescaleLastInput = Input.EMPTY;
    private static Vec3 fireworkRescaleOverride = null;

    /** 音效屏蔽监听器（甲飞换装音效） */
    private static final SoundListener SOUND_LISTENER = new SoundListener();

    private ElytraFlySupport() {
    }

    // ====== 烟花加速（所有模式通用） ======

    /**
     * 史莱姆 mod「烟花加速」原逻辑：
     * 滑翔中且自己的烟花存活时，每 tick 把速度设为当前朝向 × 加速值。
     *
     * <p>由独立模块 {@link fish22.modernsupport.modules.FireworkBoost} 在「移动运算之前」
     * 调用（原版 travel 入口 / 甲飞的滑翔移动入口）；调用时实体朝向已经是
     * 本 tick 移动运算会用的那一份，所以加速方向不会和移动方向分叉。
     */
    public static void applyFireworkBoost() {
        fireworkRescaleOverride = null;
        if (!shouldApplyFireworkBoost()) return;

        // 方向取「本 tick 移动运算会用的那一份朝向」：合法转头激活时就是真实角度
        Vec3 rotation = LegalRotation.getServerLook(mc.player);
        Vec3 modifiedVelocity = rotation.scale(fireworkBoostSpeed.get());
        mc.player.setDeltaMovement(modifiedVelocity);

        if (fireworkBoostUseRescale != null && fireworkBoostUseRescale.get()) {
            Vec3 limitedVelocity = applyFireworkRescale(
                modifiedVelocity,
                rotation,
                !mc.player.isNoGravity()
            );
            mc.player.setDeltaMovement(limitedVelocity);
            modifiedVelocity = fireworkRescaleOverride != null ? fireworkRescaleOverride : limitedVelocity;

            // 反 tick 跳过：让服务器看到角度有微小变化。
            // 合法转头激活时改真实角度（不动视角），否则维持原来的改视角行为。
            float deltaYaw = (mc.player.tickCount & 1) == 0 ? 0.01F : -0.01F;
            if (LegalRotation.isRotating()) {
                LegalRotation.nudgeRealYaw(deltaYaw);
            } else {
                mc.player.setYRot(mc.player.getYRot() + deltaYaw);
            }
        }

    }

    /**
     * 记录服务端最后一次收到的移动/输入状态。
     *
     * <p>对应史莱姆 PlayerStateManager 中烟花重缩放真正用到的子集：
     * 上一 tick 真实移动速度、最后一次发送的视角、水中/岩浆状态和最后输入。
     * 只记录实际发出去的包，取消的包不会更新。
     */
    public static void captureFireworkRescalePacket(PacketEvent.Send event) {
        if (event == null || event.isCancelled() || mc.player == null) return;

        if (event.packet instanceof ServerboundMovePlayerPacket move) {
            Vec3 oldPosition = new Vec3(
                fireworkRescaleLastX,
                fireworkRescaleLastY,
                fireworkRescaleLastZ
            );

            if (move.hasPosition()) {
                double x = move.getX(fireworkRescaleLastX);
                double y = move.getY(fireworkRescaleLastY);
                double z = move.getZ(fireworkRescaleLastZ);
                if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
                    fireworkRescaleLastX = x;
                    fireworkRescaleLastY = y;
                    fireworkRescaleLastZ = z;
                }
            }

            fireworkRescaleLastRealMovement = new Vec3(
                fireworkRescaleLastX - oldPosition.x,
                fireworkRescaleLastY - oldPosition.y,
                fireworkRescaleLastZ - oldPosition.z
            );

            if (move.hasRotation()) {
                fireworkRescaleLastPitch = move.getXRot(fireworkRescaleLastPitch);
                fireworkRescaleLastYaw = move.getYRot(fireworkRescaleLastYaw);
            }
            return;
        }

        if (event.packet instanceof ServerboundPlayerInputPacket inputPacket) {
            fireworkRescaleLastInput = inputPacket.input();
        }
    }

    /** 取出 V2 暂存的滑翔速度覆盖值（只在本次滑翔运算返回处使用一次） */
    public static Vec3 consumeFireworkRescaleOverride() {
        if (fireworkRescaleOverride == null) return null;
        Vec3 result = fireworkRescaleOverride;
        fireworkRescaleOverride = null;
        return result;
    }

    /** 服务器位置纠正后，上一 tick 真实移动速度按史莱姆的 SET_BACK 分支清零 */
    public static void captureFireworkRescaleReceive(PacketEvent.Receive event) {
        if (event == null || event.packet == null) return;
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            fireworkRescaleLastRealMovement = Vec3.ZERO;
            fireworkRescaleOverride = null;
        }
    }

    public static void resetFireworkRescaleState() {
        fireworkRescaleLastX = mc.player != null ? mc.player.getX() : 0.0;
        fireworkRescaleLastY = mc.player != null ? mc.player.getY() : 0.0;
        fireworkRescaleLastZ = mc.player != null ? mc.player.getZ() : 0.0;
        fireworkRescaleLastPitch = mc.player != null ? mc.player.getXRot() : 0.0F;
        fireworkRescaleLastYaw = mc.player != null ? mc.player.getYRot() : 0.0F;
        fireworkRescaleLastRealMovement = Vec3.ZERO;
        fireworkRescaleLastInWater = false;
        fireworkRescaleLastInLava = false;
        fireworkRescaleLastInput = mc.player != null ? mc.player.input.keyPresses : Input.EMPTY;
        fireworkRescaleOverride = null;
    }

    public static void updateFireworkRescaleState() {
        if (mc.player == null || mc.level == null) return;
        fireworkRescaleLastInWater = isInWaterRegion();
        fireworkRescaleLastInLava = mc.player.isInLava();
    }

    /** 对应史莱姆 PlayerStateManager#checkRegionFluid(WATER) */
    private static boolean isInWaterRegion() {
        AABB box = mc.player.getBoundingBox().deflate(0.001);
        int minX = Mth.floor(box.minX);
        int maxX = Mth.ceil(box.maxX);
        int minY = Mth.floor(box.minY);
        int maxY = Mth.ceil(box.maxY);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.ceil(box.maxZ);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    mutable.set(x, y, z);
                    FluidState fluidState = mc.level.getFluidState(mutable);
                    if (!fluidState.is(FluidTags.WATER)) continue;
                    double surface = (double) y + fluidState.getHeight(mc.level, mutable);
                    if (surface >= box.minY) return true;
                }
            }
        }
        return false;
    }

    /** 是否满足史莱姆 mod rocketBoost 的生效条件（活跃烟花 + 滑翔窗口） */
    private static boolean shouldApplyFireworkBoost() {
        if (fireworkBoostSpeed == null) return false;
        if (mc.player == null || mc.level == null) return false;
        // 甲飞时本地滑翔标志被服务器同步的滑翔 bit 反复覆盖（会闪成 false），
        // 这里跟移动运算用同一套判断：服务器认这一 tick 在滑翔就算滑翔。
        // 注意：这个窗口里服务器不一定真认滑翔，所以方向按严格模式另行处理
        // （见 applyFireworkBoost：不认滑翔时动量沿服务器已知朝向维持）。
        if (!mc.player.isFallFlying() && !shouldAlignFireworkBoostWithServer()) return false;
        return hasActiveOwnedFirework();
    }

    /** 是否有附着在自己身上且仍存活的烟花（对应史莱姆 mod 的 canFireworkControlMotion(0)） */
    private static boolean hasActiveOwnedFirework() {
        for (FireworkRocketEntity rocket : mc.level.getEntitiesOfClass(
            FireworkRocketEntity.class,
            mc.player.getBoundingBox().inflate(16.0)
        )) {
            if (!rocket.isAlive()) continue;
            OptionalInt attachedTo = rocket.getEntityData().get(
                FireworkRocketEntityAccess.meteor$getDataAttachedToTarget()
            );
            if (attachedTo.isPresent() && attachedTo.getAsInt() == mc.player.getId()) return true;
        }
        return false;
    }

    // ====== 烟花加速重缩放（史莱姆 applyAxisLimit V1/V2） ======

    private static Vec3 applyFireworkRescale(Vec3 currentMotion, Vec3 currentRotation, boolean applyGravity) {
        if (fireworkRescale == null || !fireworkRescale.get()) return currentMotion;
        FireworkRescaleAlgorithm algorithm = fireworkRescaleAlgorithm != null
            ? fireworkRescaleAlgorithm.get()
            : FireworkRescaleAlgorithm.V1;
        return algorithm == FireworkRescaleAlgorithm.V2
            ? applyFireworkRescaleV2(currentMotion, currentRotation)
            : applyFireworkRescaleV1(currentMotion, currentRotation, applyGravity);
    }

    /** 史莱姆 applyAxisLimit1：按当前/上一视角构造三轴范围，超出则压缩 */
    private static Vec3 applyFireworkRescaleV1(Vec3 currentMotion, Vec3 currentRotation, boolean applyGravity) {
        if (fireworkRescale == null || !fireworkRescale.get()) return currentMotion;
        if (currentMotion.lengthSqr() < 1E-6) return currentMotion;
        if (fireworkRescaleLastInWater) return currentMotion;

        Vec3 lastTickVelocity = fireworkRescaleLastRealMovement;
        Vec3 lastPitchYaw = mc.player.calculateViewVector(
            fireworkRescaleLastPitch,
            fireworkRescaleLastYaw
        );
        double antiTickSkipping = 0.05;
        Vec3 currentLook = currentRotation.normalize();
        Vec3 lastLook = lastPitchYaw.normalize();

        double minX = Math.min(-antiTickSkipping, currentLook.x) + Math.min(-antiTickSkipping, lastLook.x);
        double minY = Math.min(-antiTickSkipping, currentLook.y) + Math.min(-antiTickSkipping, lastLook.y);
        double minZ = Math.min(-antiTickSkipping, currentLook.z) + Math.min(-antiTickSkipping, lastLook.z);
        double maxX = Math.max(antiTickSkipping, currentLook.x) + Math.max(antiTickSkipping, lastLook.x);
        double maxY = Math.max(antiTickSkipping, currentLook.y) + Math.max(antiTickSkipping, lastLook.y);
        double maxZ = Math.max(antiTickSkipping, currentLook.z) + Math.max(antiTickSkipping, lastLook.z);

        double threshold = Math.min(fireworkRescaleAmount.get(), currentMotion.length());
        minX *= threshold;
        minY *= threshold;
        minZ *= threshold;
        maxX *= threshold;
        maxY *= threshold;
        maxZ *= threshold;
        minX = Math.max(-threshold, minX);
        minY = Math.max(-threshold, minY);
        minZ = Math.max(-threshold, minZ);
        maxX = Math.min(threshold, maxX);
        maxY = Math.min(threshold, maxY);
        maxZ = Math.min(threshold, maxZ);

        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        double gravity = getEffectiveGravity(mc.player);
        Vec3 usingMotion = currentMotion.add(0, -gravity, 0);

        double scaleY = Math.abs(usingMotion.y) < 1E-6
            ? 0
            : (usingMotion.y < 0
                ? ((-usingMotion.y - gravity + 0.02) / (-box.minY))
                : ((usingMotion.y + gravity + 0.02) / (box.maxY)));
        double scaleX = Math.abs(usingMotion.x) < 1E-6
            ? 0
            : (usingMotion.x < 0 ? (usingMotion.x / box.minX) : (usingMotion.x / box.maxX));
        double scaleZ = Math.abs(usingMotion.z) < 1E-6
            ? 0
            : (usingMotion.z < 0 ? (usingMotion.z / box.minZ) : (usingMotion.z / box.maxZ));

        double maxScale = Math.max(scaleY, Math.max(scaleX, scaleZ));
        if (maxScale < 1E-6 || maxScale > 1) return currentMotion;

        double limit = 1 / maxScale;
        return currentMotion.scale(limit);
    }

    /** 史莱姆 applyAxisLimit2：在 V1 的范围上再结合上一 tick 真实移动与滑翔模拟 */
    private static Vec3 applyFireworkRescaleV2(Vec3 currentMotion, Vec3 currentRotation) {
        fireworkRescaleOverride = null;
        if (fireworkRescale == null || !fireworkRescale.get()) return currentMotion;
        if (currentMotion.lengthSqr() < 1E-6) return currentMotion;

        Vec3 lastTickVelocity = fireworkRescaleLastRealMovement;
        Vec3 thisTickSimulationVelocity = fireworkRescaleLastInWater || fireworkRescaleLastInLava
            ? simulateTravelInFluidVelocity(
                lastTickVelocity,
                fireworkRescaleLastInWater,
                fireworkRescaleLastInLava
            )
            : calculateGlidingVelocity(mc.player, lastTickVelocity, currentRotation, true);

        Vec3 lastPitchYaw = mc.player.calculateViewVector(
            fireworkRescaleLastPitch,
            fireworkRescaleLastYaw
        );
        double antiTickSkipping = 0.05;
        Vec3 currentLook = currentRotation.normalize();
        Vec3 lastLook = lastPitchYaw.normalize();

        double minX = Math.min(-antiTickSkipping, currentLook.x) + Math.min(-antiTickSkipping, lastLook.x);
        double minY = Math.min(-antiTickSkipping, currentLook.y) + Math.min(-antiTickSkipping, lastLook.y);
        double minZ = Math.min(-antiTickSkipping, currentLook.z) + Math.min(-antiTickSkipping, lastLook.z);
        double maxX = Math.max(antiTickSkipping, currentLook.x) + Math.max(antiTickSkipping, lastLook.x);
        double maxY = Math.max(antiTickSkipping, currentLook.y) + Math.max(antiTickSkipping, lastLook.y);
        double maxZ = Math.max(antiTickSkipping, currentLook.z) + Math.max(antiTickSkipping, lastLook.z);

        double threshold = Math.min(fireworkRescaleAmount.get(), currentMotion.length());
        minX *= threshold;
        maxX *= threshold;
        minY *= threshold;
        maxY *= threshold;
        minZ *= threshold;
        maxZ *= threshold;
        minX = Math.max(-threshold, minX);
        maxX = Math.min(threshold, maxX);
        minY = Math.max(-threshold, minY);
        maxY = Math.min(threshold, maxY);
        minZ = Math.max(-threshold, minZ);
        maxZ = Math.min(threshold, maxZ);

        double eMinX = Math.min(0, minX - lastTickVelocity.x);
        double eMaxX = Math.max(0, maxX - lastTickVelocity.x);
        double eMinY = Math.min(0, minY - lastTickVelocity.y);
        double eMaxY = Math.max(0, maxY - lastTickVelocity.y);
        double eMinZ = Math.min(0, minZ - lastTickVelocity.z);
        double eMaxZ = Math.max(0, maxZ - lastTickVelocity.z);

        // 史莱姆原版这里硬编码 0.0（auto-rescale-axis-zero-point-three 的 XZ 分支在原版根本没被使用）：
        // 这个余量会同时放宽 uMin/uMax 的上下限，非 0 时压缩量会忽大忽小，表现为速度跳变，默认必须是 0。
        double zeroPointThreeTest = fireworkRescaleExtraXZ.get();
        double uMinX = thisTickSimulationVelocity.x + eMinX - zeroPointThreeTest;
        double uMaxX = thisTickSimulationVelocity.x + eMaxX + zeroPointThreeTest;
        double uMinY = thisTickSimulationVelocity.y + eMinY;
        double uMaxY = thisTickSimulationVelocity.y + eMaxY;
        double uMinZ = thisTickSimulationVelocity.z + eMinZ - zeroPointThreeTest;
        double uMaxZ = thisTickSimulationVelocity.z + eMaxZ + zeroPointThreeTest;

        if (uMaxY > 1E-6) {
            double length = currentMotion.length();
            double horizontalLength = currentMotion.horizontalDistance();
            if (horizontalLength < 0.04 * currentMotion.y) {
                double extraMaxY = calculateGlidingVelocity(
                    mc.player,
                    currentMotion.scale((length + fireworkRescaleExtraY.get()) / length),
                    currentRotation,
                    true
                ).y;
                uMaxY = Math.max(uMaxY, extraMaxY);
            }
        }

        double dx = currentMotion.x;
        double dy = currentMotion.y;
        double dz = currentMotion.z;
        double exceedX = 0.0;
        double exceedY = 0.0;
        double exceedZ = 0.0;

        if (dx > 0) exceedX = dx / uMaxX;
        else if (dx < 0) exceedX = dx / uMinX;

        if (dy > 0) exceedY = dy / uMaxY;
        else if (dy < 0) exceedY = dy / uMinY;

        if (dz > 0) exceedZ = dz / uMaxZ;
        else if (dz < 0) exceedZ = dz / uMinZ;

        double maxScale = Math.max(exceedX, Math.max(exceedY, exceedZ));
        if (maxScale < 1E-6) return currentMotion;

        Vec3 predictedMotion = calculateGlidingVelocity(mc.player, currentMotion, currentRotation, true);
        double limitScale = currentMotion.length() / predictedMotion.length();
        if (maxScale >= limitScale) return currentMotion;

        fireworkRescaleOverride = currentMotion.scale(1 / maxScale);
        return currentMotion;
    }

    private static double getEffectiveGravity(Player player) {
        double gravity = player.getGravity();
        return player.getDeltaMovement().y <= 0 && player.hasEffect(MobEffects.SLOW_FALLING)
            ? Math.min(gravity, 0.01)
            : gravity;
    }

    private static Vec3 calculateGlidingVelocity(Player player, Vec3 oldVelocity, Vec3 rotationVector, boolean hasGravity) {
        float pitch = (float) Math.toDegrees(Math.asin(-rotationVector.y));
        float pitchRad = pitch * 0.017453292F;
        double lookHorizontalLength = Math.sqrt(rotationVector.x * rotationVector.x + rotationVector.z * rotationVector.z);
        double initialHorizontalSpeed = oldVelocity.horizontalDistance();
        double gravity = hasGravity ? getEffectiveGravity(player) : 0;
        double cosPitchSq = Mth.square(Math.cos(pitchRad));

        double newY = oldVelocity.y + gravity * (cosPitchSq * 0.75 - 1.0);
        Vec3 velocity = new Vec3(oldVelocity.x, newY, oldVelocity.z);

        if (newY < 0.0 && lookHorizontalLength > 0.0) {
            double lift = newY * -0.1 * cosPitchSq;
            velocity = velocity.add(
                rotationVector.x * lift / lookHorizontalLength,
                lift,
                rotationVector.z * lift / lookHorizontalLength
            );
        }

        if (pitchRad < 0.0F && lookHorizontalLength > 0.0) {
            double dive = initialHorizontalSpeed * (-Math.sin(pitchRad)) * 0.04;
            velocity = velocity.add(
                -rotationVector.x * dive / lookHorizontalLength,
                dive * 3.2,
                -rotationVector.z * dive / lookHorizontalLength
            );
        }

        if (lookHorizontalLength > 0.0) {
            double targetScale = initialHorizontalSpeed / lookHorizontalLength;
            velocity = velocity.add(
                (rotationVector.x * targetScale - velocity.x) * 0.1,
                0.0,
                (rotationVector.z * targetScale - velocity.z) * 0.1
            );
        }

        return velocity.multiply(0.99, 0.98, 0.99);
    }

    private static Vec3 simulateTravelInFluidVelocity(
        Vec3 velocity,
        boolean lastInWater,
        boolean lastInLava
    ) {
        if (lastInWater) return simulateTravelInWaterVelocity(velocity);
        if (lastInLava) return simulateTravelInLavaVelocity(velocity);
        return velocity;
    }

    private static Vec3 simulateTravelInWaterVelocity(Vec3 velocity) {
        Input input = fireworkRescaleLastInput;
        Vec3 movementInput = new Vec3(
            inputSideways(input),
            inputUpward(input),
            inputForward(input)
        );
        boolean falling = velocity.y <= 0.0;
        double oldY = mc.player.getY();
        double gravity = getEffectiveGravity(mc.player);
        float drag = mc.player.isSprinting() ? 0.9F : 0.8F;
        float acceleration = 0.02F;
        float efficiency = (float) mc.player.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (!mc.player.onGround()) efficiency *= 0.5F;
        if (efficiency > 0.0F) {
            drag += (0.54600006F - drag) * efficiency;
            acceleration += (mc.player.getSpeed() - acceleration) * efficiency;
        }
        if (mc.player.hasEffect(MobEffects.DOLPHINS_GRACE)) {
            drag = 0.96F;
        }

        Vec3 nextVelocity = velocity.add(
            movementInputToVelocity(movementInput, acceleration, fireworkRescaleLastYaw)
        );
        if (mc.player.horizontalCollision && mc.player.onClimbable()) {
            nextVelocity = new Vec3(nextVelocity.x, 0.2, nextVelocity.z);
        }
        nextVelocity = nextVelocity.multiply(drag, 0.8F, drag);
        nextVelocity = mc.player.getFluidFallingAdjustedMovement(gravity, falling, nextVelocity);
        return simulateResetVerticalVelocityInFluid(nextVelocity, oldY);
    }

    private static Vec3 simulateTravelInLavaVelocity(Vec3 velocity) {
        Input input = fireworkRescaleLastInput;
        Vec3 movementInput = new Vec3(
            inputSideways(input),
            inputUpward(input),
            inputForward(input)
        );
        boolean falling = velocity.y <= 0.0;
        double oldY = mc.player.getY();
        double gravity = getEffectiveGravity(mc.player);
        Vec3 nextVelocity = velocity.add(
            movementInputToVelocity(movementInput, 0.02F, fireworkRescaleLastYaw)
        );

        if (mc.player.getFluidHeight(FluidTags.LAVA) <= mc.player.getFluidJumpThreshold()) {
            nextVelocity = nextVelocity.multiply(0.5, 0.8F, 0.5);
            nextVelocity = mc.player.getFluidFallingAdjustedMovement(gravity, falling, nextVelocity);
        } else {
            nextVelocity = nextVelocity.scale(0.5);
        }
        if (gravity != 0.0) {
            nextVelocity = nextVelocity.add(0.0, -gravity / 4.0, 0.0);
        }
        return simulateResetVerticalVelocityInFluid(nextVelocity, oldY);
    }

    private static Vec3 simulateResetVerticalVelocityInFluid(Vec3 velocity, double oldY) {
        if (mc.player.horizontalCollision
            && mc.player.isFree(
                velocity.x,
                velocity.y + 0.6000000238418579 - mc.player.getY() + oldY,
                velocity.z
            )) {
            return new Vec3(velocity.x, 0.30000001192092896, velocity.z);
        }
        return velocity;
    }

    private static Vec3 movementInputToVelocity(Vec3 movementInput, float speed, float yaw) {
        double lengthSqr = movementInput.lengthSqr();
        if (lengthSqr < 1.0E-7) return Vec3.ZERO;

        Vec3 direction = (lengthSqr > 1.0 ? movementInput.normalize() : movementInput).scale(speed);
        float sin = Mth.sin(yaw * 0.017453292F);
        float cos = Mth.cos(yaw * 0.017453292F);
        return new Vec3(
            direction.x * cos - direction.z * sin,
            direction.y,
            direction.z * cos + direction.x * sin
        );
    }

    private static int inputForward(Input input) {
        return input.forward() == input.backward() ? 0 : (input.forward() ? 1 : -1);
    }

    private static int inputSideways(Input input) {
        return input.left() == input.right() ? 0 : (input.left() ? 1 : -1);
    }

    private static int inputUpward(Input input) {
        return input.jump() == input.shift() ? 0 : (input.jump() ? 1 : -1);
    }

    // ====== 生命周期（由 MixinElytraFly 调用） ======

    public static void onActivate() {
        wasFlying = false;
        armorSwapCooldown = 0;
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
        ticksSinceStartFlying = START_FLYING_WINDOW_TICKS + 1;
        glidingRealignTicks = 0;
        rubberbandCount = 0;
        rubberbandWindow = 0;
        rubberbandCooldown = 0;
        jumpInputReleasedForStart = false;
        floatingCheckRefreshTicks = -1;
        flyingPacketsWithoutPosition = 0;
        antiKickPacketThisTick = false;
        floatingPulseRestorePending = false;
        antiKickPulseQueued = false;
        holdElytraRequested = false;
        elytraHoldTicks = 0;
        holdElytraPacketThisTick = false;
        bypassFreezeIntercept = false;
        hoverPlusThisTick = false;
        noPositionPacketThisTick = false;
        resetLandingNoFallTracking();
        Freeze.clearMovePacketBypass();
        Freeze.setExternalFrozen(false);
        MeteorClient.EVENT_BUS.subscribe(SOUND_LISTENER);
    }

    public static void onDeactivate() {
        // 解除合法平飞可能挂上的外部冻结
        Freeze.setExternalFrozen(false);
        // 「空包防踢」正好把鞘翅留在胸甲槽时被关掉：先把胸甲换回来，别把鞘翅留在身上
        if (elytraHoldTicks > 0 && mc.player != null) swapBackChestplate();
        floatingCheckRefreshTicks = -1;
        flyingPacketsWithoutPosition = 0;
        antiKickPacketThisTick = false;
        floatingPulseRestorePending = false;
        antiKickPulseQueued = false;
        holdElytraRequested = false;
        elytraHoldTicks = 0;
        holdElytraPacketThisTick = false;
        glidingRealignTicks = 0;
        rubberbandCount = 0;
        rubberbandWindow = 0;
        rubberbandCooldown = 0;
        bypassFreezeIntercept = false;
        hoverPlusThisTick = false;
        noPositionPacketThisTick = false;
        resetLandingNoFallTracking();
        Freeze.clearMovePacketBypass();
        MeteorClient.EVENT_BUS.unsubscribe(SOUND_LISTENER);
    }

    /**
     * 每 tick 都要跑的处理（不分模式，由 MixinElytraFly 在官方 onPreTick 最前面调用）。
     * 「本 tick 是否发过起飞包」的清零：切回官方模式时也要清，避免残留；
     * 以及冻结悬停防踢脉冲的计时（按 tick 递减，见 {@link #tickAntiKick()}）。
     */
    public static void onPreTickAlways() {
        startedGlidingLastTick = startedGlidingThisTick;
        startedGlidingThisTick = false;
        // 「起飞包窗口」计时：上一 tick 发过起飞包就清零，否则 +1（超过窗口后不再增长）
        ticksSinceStartFlying = startedGlidingLastTick
            ? 0
            : Math.min(ticksSinceStartFlying + 1, START_FLYING_WINDOW_TICKS + 1);
        antiKickPacketThisTick = false;
        hoverPlusThisTick = false;
        noPositionPacketThisTick = false;
        // 本 tick 移动之前累计的下落距离：落地包发出去时客户端已经把摔伤距离清零，
        // 只有提前记下来才知道这一摔值不值得补「落地防摔」包
        fallDistanceBeforeTick = mc.player != null ? mc.player.fallDistance : 0.0;
        // 冻结悬停防踢脉冲：按 tick 递减，到点这一 tick 由 tickAntiKick 补发
        if (floatingCheckRefreshTicks > 0) floatingCheckRefreshTicks--;
        // 兜底：上一 tick 万一没把「放行标记」用掉，别留到这一 tick 放行客户端的位置包
        Freeze.clearMovePacketBypass();
    }

    /** 每 tick 主逻辑（TickEvent.Pre，由 MixinElytraFly 拦截官方 onPreTick 后调用） */
    public static void onTick() {
        if (mc.player == null) return;

        // 连续回弹自愈：判定要排在换装之前（命中后这一 tick 就不换装、不发起飞包）
        tickRubberbandWatch();

        // 「空包防踢」：为了防踢多留的鞘翅在这里换回胸甲（保持几 tick 见 elytraHoldTicks）。
        // 必须排在正常换装之前——两件事挤在同一 tick 会有 9 次点击太扎眼，
        // 也让服务端有一 tick 正常「穿胸甲」的喘息（下一 tick 照常闪换）。
        boolean swappedBackHeldElytra = false;
        if (elytraHoldTicks > 0 && --elytraHoldTicks == 0) {
            swapBackChestplate();
            swappedBackHeldElytra = true;
        }

        // 换甲间隔计时：每 tick 递减一次（与「兼容 grim 输入检测」的隔 tick 跳换装无关）
        if (armorSwapCooldown > 0) armorSwapCooldown--;

        // 上面刚换回胸甲这一 tick 就不再闪换了，下一 tick 恢复
        if (swappedBackHeldElytra) armorSwapCooldown = Math.max(armorSwapCooldown, 1);

        // 防踢脉冲的判定必须排在换装之前：「空包防踢」要让本 tick 的换装把鞘翅多留一 tick
        planAntiKick();

        if (isLegalMode()) {
            // 合法：真鞘翅走合法方向控制，开了甲飞则叠加换装维持滑翔
            if (isArmorFlyActive()) legalArmorTick();
            else legalNormalTick();
        } else if (isArmorFlyActive()) {
            // 原版模式 + 甲飞 / 关闭模式 + 甲飞：只做换装维持滑翔，本类不改视角。
            // 「原版」时方向控制仍旧是官方那套原版逻辑（只需放行它开头那道滑翔组件守卫，
            // 见 shouldProvideGliderForVanillaArmor）；「关闭」时没有任何额外控制。
            armorTick();
        }

        // 一键烟花：甲飞开启时按下快捷键不在滑翔，延后到下次滑翔再释放
        checkOneKeyPending();

        // 冻结悬停防踢脉冲（放在最后：它要排在本 tick 所有交互包之后发出）
        tickAntiKick();

        // 服务端停滑翔后的重新对齐：本 tick 用完再递减，保证等待 tick 数正好
        // （判定在 serverSeesGliding / armorFlySwap 里，都跑在本行之前）
        if (glidingRealignTicks > 0) glidingRealignTicks--;
    }

    /** 发包监听（由 MixinElytraFly 拦截官方 onPacketSend 后调用） */
    public static void onPacketSend(PacketEvent.Send event) {
        // 「悬停+」：记账本 tick 有没有「不带坐标的移动包」出去（客户端自己的旋转包也算）。
        // 必须放在所有 return 之前——补包那一路（bypassFreezeIntercept）也要算进去。
        if (event.packet instanceof ServerboundMovePlayerPacket lookPacket && !lookPacket.hasPosition()) {
            noPositionPacketThisTick = true;
        }

        // 「落地防摔（Grim）」：补包必须排在客户端自己的落地包之前，所以放在最前面处理
        tickLandingNoFall(event);

        // 防踢脉冲是模块自己补的包，必须直接放行：下面的冻结拦截按「冻结期间不发位置包」
        // 无条件取消含位置的移动包，连它自己发的这一发也会被取消（防踢因此完全失效）。
        if (bypassFreezeIntercept) return;

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

        // 防踢包那一 tick：客户端自己的移动包全部吞掉，保证一 tick 只存在一个移动包。
        // Grim 的 TickTimer/Timer 都按 tick 数移动包（每包 50ms），多一个就是「发包过快」并 setback。
        if (antiKickPacketThisTick && event.packet instanceof ServerboundMovePlayerPacket) {
            event.cancel();
            return;
        }

        // 合法平飞「冻结 / 悬停+」悬停时拦截位置移动包（旋转包照发，可正常转头）
        if (!isLegalMode() || hoverMode.get() == HoverMode.Hover) return;
        if (!Freeze.isFrozen()) return;
        if (!(event.packet instanceof ServerboundMovePlayerPacket movePacket)) return;
        if (movePacket.hasPosition()) {
            event.cancel();
            return;
        }

        // 放行的旋转包：Grim 的 BadPacketsE 统计「连续多少个不带坐标的移动包」（>19 报），
        // 快顶到上限就让下一处换装窗口提前补一发防踢包（它带坐标，会把这个计数清零）
        if (++flyingPacketsWithoutPosition >= FLOATING_CHECK_MAX_NO_POSITION_PACKETS) {
            floatingCheckRefreshTicks = 0;
        }
    }

    /** 收包监听（由 MixinElytraFly 拦截官方 onPacketReceive 后调用） */
    public static void onPacketReceive(PacketEvent.Receive event) {
        // 服务端位置纠正（回弹/传送）：服务端记住的坐标立刻变成纠正后的坐标，
        // 「落地防摔」补包要跟着换，不然会补出一个离得很远的旧坐标
        if (event.packet instanceof ClientboundPlayerPositionPacket position && mc.player != null) {
            // 连着收到位置纠正 = 连续回弹，交给 tickRubberbandWatch 判定自愈
            rubberbandCount++;
            rubberbandWindow = RUBBERBAND_WINDOW_TICKS;

            Vec3 absolute = PositionMoveRotation.calculateAbsolute(
                new PositionMoveRotation(
                    mc.player.position(),
                    mc.player.getKnownMovement(),
                    mc.player.getYRot(),
                    mc.player.getXRot()
                ),
                position.change(),
                position.relatives()
            ).position();
            lastSentX = absolute.x;
            lastSentY = absolute.y;
            lastSentZ = absolute.z;
        }
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
            armorSwapCooldown = 0;
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
        // 连续回弹自愈的重新对齐窗口（见 tickRubberbandWatch）：这几 tick 不换装、不发起飞包。
        // 换装窗口和它的 tick 还是错位的话，发出去的起飞包还会被当成「已经在滑翔」，
        // 回弹就会一直连着；先停下等两边对齐，再重新起飞。
        if (glidingRealignTicks > 0) return;

        // 换甲间隔：两次换装之间至少隔 N tick（默认 1 = 每 tick 都允许，与旧行为一致）。
        // 计数在 onTick 里按 tick 递减，所以「兼容 grim 输入检测」跳过的那些 tick 也照常计时。
        if (armorSwapCooldown > 0) return;
        armorSwapCooldown = armorSwapIntervalTicks();

        switch (mode) {
            case Lazy -> lazyTick();
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

    // ====== Grim 模式（LAZY）======

    /** Grim 模式：滑翔中不动，只在服务器判定停飞时才做一次「换鞘翅 → 起飞 → 换回」 */
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

    // ====== 合法平飞逻辑（参考 Epsilon ElytraFly Control 模式） ======

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
            armorSwapCooldown = 0;
            windowFwPendingLevel = -1;
            // 落地重置冷却：下一次真正起飞时立刻补一发烟花（起飞烟花本身受冷却约束，见 tryFireworkOnce）
            legalFwCooldown = 0;
            return;
        }

        // 甲飞换装维持滑翔；兼容 grim 输入检测时起飞包隔 tick 发
        if (!skipStartThisTick()) {
            armorFlySwap(armorMode.get());
        }

        // 滑翔中应用方向控制
        // 不能用 mc.player.isFallFlying() 判断：甲飞本地大多时候不是滑翔状态
        // （Grim 模式不会把本地滑翔标志设真，只有普通模式会本地强制滑翔），
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
        // 甲飞模式由换装窗口结束后释放（见 releaseOneKeyFirework）。
        // 一键烟花是玩家主动动作，冻结期间照常放（自动烟花才需要在冻结期间拦住）。
        if (oneKeyPending && !isArmorFlyActive()) {
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
            if (hoverMode.get() != HoverMode.Hover) {
                // 冻结 / 悬停+：完全静止（travel 取消 + 输入屏蔽由 Freeze 外部冻结提供，
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

                // 悬停+（史莱姆 grim-floating 的同款）：位置包不发，但本 tick 的移动包之后
                // 必须补一发「只带朝向」的移动包，服务端才有东西把「悬浮过久」计数重算成
                // 「正在滑翔」（见 sendHoverPlusLookPacket）。排在移动包之后发，顺序和原版一致。
                if (hoverMode.get() == HoverMode.HoverPlus) {
                    hoverPlusThisTick = true;
                    LegalRotation.runAfterSend(ElytraFlySupport::sendHoverPlusLookPacket);
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
        // 冻结中（冻结悬停 / 独立冻结模块）：不排新的自动烟花。
        // 甲飞的换装窗口在冻结期间照常开合，服务器认可的「起飞成功」会被反复触发，
        // 排进去的烟花又会在窗口里被放掉——正是「冻结时自动烟花乱放」的来源。
        if (isFrozenNow()) return;

        int level = selectFireworkLevel();
        if (level == -1) return;
        int interval = fwIntervalForLevel(level);
        if (isArmorFlyActive()) {
            // 甲飞的「滑翔窗口」本来就随换装反复开合（Grim 模式里本地滑翔为真时当 tick 不换装，
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
        // 冻结中（冻结悬停 / 独立冻结模块）：冻结期间不放烟花。
        // 甲飞的换装窗口在冻结期间照常开合，不拦的话每次窗口都会把烟花放出去；
        // 真鞘翅模式则是队列回调在冻结期间直接发包。统一在这里拦掉。
        if (isFrozenNow()) return;

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
        // 只有「鞘翅飞行」开着且处于 甲飞 / 合法 时才需要延后到滑翔窗口
        // （甲飞 = 换装窗口结束后、合法 = 移动包发送后）；模块没开或原版/发包时立即释放
        if (elytraFlyActive() && (isArmorFlyEnabled() || isLegalMode())) {
            oneKeyPending = true;
            return;
        }
        releaseFireworkOnce();
    }

    /** 「鞘翅飞行」模块当前是否开启 */
    private static boolean elytraFlyActive() {
        ElytraFly module = Modules.get().get(ElytraFly.class);
        return module != null && module.isActive();
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

    /** 释放一次烟花（一键烟花模块专用，可选背包，按一键烟花的背包开关与背包使用模式） */
    private static void releaseFireworkOnce() {
        boolean backpack = oneKeyBackpackFirework != null && oneKeyBackpackFirework.get();
        int level = selectFireworkLevel(backpack);
        if (level == -1) return;
        Predicate<ItemStack> pred = fireworkOfLevel(level);
        if (backpack) {
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
            // 甲飞板块的「静音」、无限鞘翅板块的「静音」各管各的
            boolean armorMute = isArmorFlyActive() && muteSounds != null && muteSounds.get();
            boolean infiniteMute = InfiniteElytraSupport.shouldMuteArmorSounds();
            if (!armorMute && !infiniteMute) return;

            // 屏蔽盔甲装备音效；滑翔音只在甲飞静音时屏蔽（无限鞘翅不屏蔽滑翔音）
            String path = event.sound.getIdentifier().getPath();
            if (path.startsWith("item.armor.equip") || (armorMute && path.equals("item.elytra.flying"))) {
                event.cancel();
            }
        }
    }

    // ====== 甲飞辅助（换装） ======

    /**
     * 当前是否处于冻结状态（本模块的冻结悬停，或独立「冻结」模块）。
     *
     * <p>冻结期间甲飞换装照常进行（服务器要一直认为在滑翔，否则解冻时滑翔状态已经掉了），
     * 但烟花一律不在冻结期间放：服务端此刻认定你完全静止，放烟花会让它重新结算滑翔与速度。
     * 排队的自动/一键烟花保留到解冻后的换装窗口释放。
     */
    private static boolean isFrozenNow() {
        return Freeze.isFrozen();
    }

    /**
     * 防踢是否生效：合法平飞 + 甲飞 + 悬停模式=冻结 +「防踢模式」打开（两种防踢模式都算）。
     *
     * <p>只给「冻结」用：冻结悬停一个位置包都不发，服务端那条「悬浮过久」计数没有
     * 重算机会，只能靠补包清零（{@link #planAntiKick()}）。
     *
     * <p>悬停模式（位置包照发）不需要；「悬停+」每 tick 自带一发朝向包
     * （{@link #sendHoverPlusLookPacket()}）会替它把计数清零，也不需要；
     * 纯甲飞没有冻结/悬停概念、真鞘翅的合法平飞服务端始终认为在滑翔，都不需要。
     */
    private static boolean antiKickOn() {
        return isLegalMode()
            && isArmorFlyEnabled()
            && hoverMode != null
            && hoverMode.get() == HoverMode.Freeze
            && hoverAntiKick != null
            && hoverAntiKick.get() != AntiKickMode.Off;
    }

    /** 当前防踢模式是不是「空包防踢」（换装时把鞘翅多留一 tick + 补一发原地移动包） */
    private static boolean holdElytraAntiKickOn() {
        return antiKickOn() && hoverAntiKick.get() == AntiKickMode.HoldElytra;
    }

    /**
     * 当前生效的防踢脉冲间隔（tick）：设置「防踢间隔」，
     * 设置未注入（单测/初始化前）时按 {@link #FLOATING_CHECK_REFRESH_TICKS} 处理。
     */
    private static int antiKickIntervalTicks() {
        return hoverAntiKickInterval == null
            ? FLOATING_CHECK_REFRESH_TICKS
            : Math.max(1, hoverAntiKickInterval.get());
    }

    /**
     * 冻结悬停防踢脉冲：按设置「防踢间隔」（默认 {@link #FLOATING_CHECK_REFRESH_TICKS} tick）
     * 补一发带坐标的移动包，把服务端的原版「悬浮过久」计数清零。
     *
     * <p>原版 {@code ServerGamePacketListenerImpl#handleMovePlayer}：收到移动包时若没下降
     * （{@code oyDist >= -0.03125}）、没站在方块上、服务端不认为在滑翔……就把
     * {@code clientIsFloating} 置真；{@code tickPlayer()} 每 tick 给它 +1，超过
     * {@code getMaximumFlyingTicks}（玩家重力 0.08 → 80 tick ≈ 4 秒）直接以
     * 「Flying is not enabled on this server」踢出。而这个标志位<b>只在收到移动包时更新</b>：
     * 冻结刻意不发位置包，标志位就停在冻结前那一刻的值上；正好停在甲飞换装窗口之外
     * （服务端不认滑翔）就会一直停在 true，于是冻结久了必被踢。
     *
     * <p>把它压回假有两条路，就是「防踢模式」的两个选项（只对甲飞有意义：
     * 真鞘翅的合法平飞服务端一直认滑翔，冻结期本来就安全）：
     * <ul>
     *   <li><b>下降脉冲</b>（{@link AntiKickMode#ZeroDeltaMove}）：这一包的 Y 比服务端记的
     *       位置低 {@link #FLOATING_CHECK_DESCENT}（{@code oyDist < -0.03125}），服务端按
     *       「正在下降」结算，不管认不认滑翔都清零，下一 tick 再补一发真实坐标回位。
     *       和原版 Meteor 的 Flight 防踢同一个做法，不依赖换装窗口。</li>
     *   <li><b>空包防踢</b>（{@link AntiKickMode#HoldElytra}）：不伪造位置、不掉高度，
     *       而是让本 tick 的换装把鞘翅多留一 tick（见 {@link #flashSwapElytra()}），再补一发
     *       原地不动的包，让服务端按「正在滑翔」结算。换装没真的跑成时这一发会被推迟
     *       （见下），不会把「服务端此刻未必认滑翔」的零位移包硬发出去。</li>
     * </ul>
     *
     * 包必须是带坐标的：反作弊只认带坐标的移动包来清 {@code BadPacketsR}「位置饥饿」（2 秒）
     * 与 {@code BadPacketsE}「连续 19 个不带坐标的移动包」这两个计数。
     *
     * <p>发出的位置与时机：本 tick 的移动包之后（{@link LegalRotation#runAfterSend(Runnable)}）
     * ——反作弊要求「交互包（用物品/换装/攻击…）必须排在移动包之前」，插在换装中间会撞这条
     * 顺序检查；同时这一 tick 客户端自己的移动包会被吞掉（见 onPacketSend），
     * 保证一 tick 只有一个移动包（Grim {@code TickTimer}/{@code Timer} 按 tick 数移动包）。
     *
     * <p>间隔见 {@link #antiKickIntervalTicks()} 与
     * {@link #FLOATING_CHECK_MAX_NO_POSITION_PACKETS}；只有「冻结中」（客户端位置包被拦）
     * 且悬停板块的「防踢模式」开了才需要发——
     * 普通悬停模式下客户端自己每 tick 都在发位置包，不需要也不能再补。
     */
    private static void tickAntiKick() {
        // 上一 tick 发过下降包：本 tick 用真实坐标回位。不回位的话服务端记的位置一直低
        // 0.0313，下一发下降包相对它就变成零位移，又清不掉计数了（也不能一直往下挪：会漂）。
        // 这一步放在冻结判定之前：中途解冻的话也要把服务端的位置还回真实值。
        if (floatingPulseRestorePending) {
            floatingPulseRestorePending = false;
            antiKickPacketThisTick = true;
            LegalRotation.runAfterSend(() -> sendFloatingPulse(false));
            return;
        }

        if (!antiKickPulseQueued) return;
        antiKickPulseQueued = false;

        // 「空包防踢」：换装没真的把鞘翅留下来（换甲冷却 / 开着容器 / Grim 模式认为还在滑翔），
        // 这一发就不能补——服务端此刻未必认滑翔，零位移包反而会把悬浮标志置真。
        // 计时退回 0：下一 tick 接着试，直到换装真的把鞘翅留成。
        if (holdElytraAntiKickOn()) {
            if (!holdElytraPacketThisTick) {
                floatingCheckRefreshTicks = 0;
                return;
            }
            // 鞘翅已经多留了一 tick：补一发原地不动的包（真实坐标 + 真实朝向），
            // 服务端按「正在滑翔」结算，悬浮计数清零
            LegalRotation.runAfterSend(() -> sendFloatingPulse(false));
            return;
        }

        // 下降脉冲：本 tick 发下降包，下一 tick 由上面的回位分支补真实坐标
        floatingPulseRestorePending = true;
        LegalRotation.runAfterSend(() -> sendFloatingPulse(true));
    }

    /**
     * 防踢脉冲的本 tick 判定，必须排在换装之前（见 {@link #onTick()}）。
     *
     * <p>「空包防踢」要靠本 tick 的换装把鞘翅多留一 tick，所以「这一 tick 该不该补」
     * 得在换装之前定下来；实际发包仍然留在 tick 末尾（{@link #tickAntiKick()}）。
     *
     * <p>计时在 {@link #onPreTickAlways()} 里按 tick 递减，这里只做「到点没」的判定，
     * 以及模式/冻结状态变化时的兜底清零。
     */
    private static void planAntiKick() {
        holdElytraRequested = false;
        holdElytraPacketThisTick = false;

        // 只在「冻结中」且开了防踢时补发：冻结会拦掉客户端的全部位置包，才需要替它补；
        // 普通悬停模式下客户端自己每 tick 都在发位置包（不拦），再补一发反而多出一个移动包。
        if (!antiKickOn() || !Freeze.isFrozen()) {
            floatingCheckRefreshTicks = -1;
            flyingPacketsWithoutPosition = 0;
            return;
        }

        // 刚进入冻结：这一刻不需要补。飞行期间客户端每 tick 都发位置包，服务端每 tick 都在把
        // 悬浮计数清零（飞行段的计数本来就是 0），冻结开始后也不会凭空涨——先支起计时器，
        // 等满一个间隔（{@link #antiKickIntervalTicks()}）再补第一发。
        if (floatingCheckRefreshTicks < 0) {
            floatingCheckRefreshTicks = antiKickIntervalTicks();
            return;
        }
        // 计时按 tick 递减（见 onPreTickAlways），没到点就等
        if (floatingCheckRefreshTicks > 0) return;

        floatingCheckRefreshTicks = antiKickIntervalTicks();
        flyingPacketsWithoutPosition = 0;
        // 本 tick 由防踢包顶替客户端的移动包（见 onPacketSend 的吞包分支）
        antiKickPacketThisTick = true;
        antiKickPulseQueued = true;
        // 「空包防踢」：请本 tick 的换装把鞘翅多留一 tick（由 flashSwapElytra 消费）
        if (holdElytraAntiKickOn()) holdElytraRequested = true;
    }

    /**
     * 实际发出防踢脉冲包（由 {@link #tickAntiKick()} 排到本 tick 末尾）。
     *
     * <p>发的是 {@link ServerboundMovePlayerPacket.PosRot}：下降包把 Y 压到真实位置以下
     * {@link #FLOATING_CHECK_DESCENT}，服务端按「正在下降」结算、悬浮计数清零；
     * 回位包用真实坐标，把上一条包造成的 3 厘米偏差补回来（「空包防踢」补的原地包也是这一发）。
     * 旋转都取当前值，客户端自己的位置、视角、动量完全不动。
     *
     * 必须是「带坐标」的包：反作弊 Grim 的 {@code BadPacketsR}（2 秒内必须有带坐标的移动包）
     * 与 {@code BadPacketsE}（连续 19 个不带坐标的移动包就报）只认带坐标的包，
     * 只有旋转/只有落地标记的补发包反而会把这两个计数器顶上去。
     *
     * @param descend true = 下降包；false = 回位包
     */
    private static void sendFloatingPulse(boolean descend) {
        antiKickPacketThisTick = false;
        if (mc.player == null || mc.getConnection() == null) return;
        // 排队后这一 tick 内可能已经解冻/关掉防踢：下降包不必再发（不再需要清计数）；
        // 回位包照发，把坐标还给真实位置，不给服务端留下偏差
        if (descend && (!antiKickOn() || !Freeze.isFrozen())) return;

        double y = mc.player.getY() - (descend ? FLOATING_CHECK_DESCENT : 0.0);
        ServerboundMovePlayerPacket.PosRot packet = new ServerboundMovePlayerPacket.PosRot(
            new Vec3(mc.player.getX(), y, mc.player.getZ()),
            mc.player.getYRot(), mc.player.getXRot(),
            mc.player.onGround(), mc.player.horizontalCollision);

        // 这一发是模块自己补的：放行自己的冻结拦截，也放行独立「冻结」模块的拦截
        // （那个模块开着时含位置的移动包一律取消，防踢包同样会被它吃掉）
        bypassFreezeIntercept = true;
        Freeze.bypassNextMovePacket();
        try {
            mc.getConnection().send(packet);
        } finally {
            bypassFreezeIntercept = false;
        }
    }

    // ====== 悬停+（史莱姆式悬停：不报位置，但每 tick 一发朝向包） ======

    /**
     * 「悬停+」：本 tick 的移动包发完之后（由 {@link LegalRotation#runAfterSend(Runnable)}
     * 排进 {@code SendMovementPacketsEvent.Post}），补一发<b>只带朝向、不带坐标</b>的移动包。
     *
     * <p><b>为什么这么发：</b>原版 {@code ServerGamePacketListenerImpl} 那条「悬浮过久」
     * 踢出（{@code multiplayer.disconnect.flying}，默认重力下 80 tick ≈ 4 秒）只在处理
     * <b>移动包</b>的时候重算，而且 {@code isFallFlying} 为真时一定判假、计数清零。冻结悬停
     * 不发位置包，服务端那个判定就没有重算机会，只能靠防踢脉冲；这一发朝向包不带坐标，
     * 但对服务端来说同样是「移动包」，而且排在甲飞本 tick 的起飞包之后——服务端处理它时
     * 已经在滑翔（换装窗口），于是 {@code clientIsFloating} 每 tick 都被置假、悬浮计数每 tick
     * 清零，压根涨不到 80；同时没有任何坐标变化，Grim 的预测引擎也没有位移可比 → 不回弹。
     * 这就是史莱姆 {@code FloatingUtils} 那套「grim floating」的做法。
     *
     * <p>客户端自己这一 tick 已经发过旋转包时不补（{@link #noPositionPacketThisTick}），
     * 一 tick 最多一个不带坐标的移动包；也正因为每 tick 都有一发，所以不需要「防踢间隔」。
     */
    private static void sendHoverPlusLookPacket() {
        if (!hoverPlusThisTick || noPositionPacketThisTick) return;
        if (mc.player == null || mc.getConnection() == null) return;

        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
            mc.player.getYRot(),
            mc.player.getXRot(),
            mc.player.onGround(),
            mc.player.horizontalCollision
        ));
    }

    // ====== 落地防摔（Grim：落地那一 tick 先重置服务端摔伤距离） ======

    /**
     * 发包监听里最前面调用：补「落地防摔」重置包，并记录服务端此刻记住的坐标。
     *
     * <p>重置包必须排在客户端自己的落地包<b>之前</b>：服务端处理含 {@code onGround=true}
     * 的移动包时，先按累计摔伤距离结算伤害、再把距离清零；而能重置摔伤距离的
     * {@code movedUpwards} 分支排在这次结算<b>之后</b>（见原版
     * {@code ServerGamePacketListenerImpl#handleMovePlayer}）。所以必须「先补重置包、
     * 再发落地包」——同一 tick 里服务端先清零、后结算，这一摔的伤害就是 0。
     *
     * <p>包序天然成立：客户端自己的移动包在 tick 末尾才发（{@code LocalPlayer#sendPosition}），
     * 而 Meteor 的 {@code PacketEvent.Send} 发生在真正写包之前，在这里补发就排在落地包前面。
     * 补的坐标用 {@link #lastSentY}（自己最后一发带坐标移动包里的 Y）——那正是服务端记住的
     * 坐标；客户端此刻已经落地，直接用 {@code mc.player.getY()} 会低掉这一 tick 的下落量，
     * 服务端就不认「向上移动」，重置不生效。
     */
    private static void tickLandingNoFall(PacketEvent.Send event) {
        if (mc.player == null || !(event.packet instanceof ServerboundMovePlayerPacket move)) return;

        // 上一包还声称在空中、这一包声称落地 = 刚接地的这一瞬间
        boolean landing = move.isOnGround() && !lastSentOnGround;
        if (landing && shouldSendLandingNoFall() && landingNoFallMode() == LandingNoFallMode.Grim) {
            sendLandingReset();
        }

        // 记录「服务端记住的坐标」：只有真正发出去的带坐标移动包才会更新服务端的位置记录
        if (move.hasPosition()) {
            double x = move.getX(lastSentX);
            double y = move.getY(lastSentY);
            double z = move.getZ(lastSentZ);
            if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
                lastSentX = x;
                lastSentY = y;
                lastSentZ = z;
            }
        }
        lastSentOnGround = move.isOnGround();
    }

    /**
     * 这一 tick 的落地要不要补重置包。
     *
     * <ul>
     *   <li>只在甲飞（甲飞模块 / 合法平飞甲飞）的「落地防摔」开关打开、且模块真的在运行时；</li>
     *   <li>冻结（悬停）中不补：那期间位置包本来就该少发，而且人是静止的、谈不上摔伤；</li>
     *   <li>落地前累计的下落距离没超过安全下落距离（原版 3 格 + 属性加成）就不补——
     *       这一摔本来就不掉血，补了只是白白多发一个包。</li>
     * </ul>
     */
    private static boolean shouldSendLandingNoFall() {
        if (!landingNoFallOn() || !isArmorFlyEnabled()) return false;
        if (isFrozenNow()) return false;
        // 骑乘时服务端 handleMovePlayer 直接按乘客分支返回，补的位置包不会结算摔伤
        if (mc.player.isPassenger()) return false;
        return fallDistanceBeforeTick > mc.player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
    }

    /** 甲飞 / 合法平飞甲飞的「落地防摔」开关（只看当前模式自己那一个） */
    private static boolean landingNoFallOn() {
        return isArmorFlyActive() && landingNoFall != null && landingNoFall.get();
    }

    /** 当前「落地防摔」方式（设置还没注入时按 Grim 兜底，发包与否另由开关决定） */
    private static LandingNoFallMode landingNoFallMode() {
        LandingNoFallMode mode = landingNoFallMode != null ? landingNoFallMode.get() : null;
        return mode != null ? mode : LandingNoFallMode.Grim;
    }

    /**
     * 补发「重置摔伤距离」的位置包：坐标 = 服务端已知坐标 + {@link #LANDING_NOFALL_DELTA_Y}，
     * 声称仍在地面之上（{@code onGround=false}）。
     *
     * <p>服务端收到这一包时 {@code movedUpwards}（本包 Y > 它记住的上一包 Y）成立，
     * 会把这条玩家累计的摔伤距离清零；紧接着处理客户端的落地包（{@code onGround=true}）
     * 结算时摔伤就是 0。上抬量只有 {@value #LANDING_NOFALL_DELTA_Y}，对服务端与反作弊
     * 来说这一包几乎等于「零位移」；客户端自己的坐标、动量、视角都不动。
     */
    private static void sendLandingReset() {
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
            lastSentX,
            lastSentY + LANDING_NOFALL_DELTA_Y,
            lastSentZ,
            false,
            mc.player.horizontalCollision
        ));
    }

    /** 重置「落地防摔」的服务端坐标记录（模块开关时调用） */
    private static void resetLandingNoFallTracking() {
        lastSentX = mc.player != null ? mc.player.getX() : 0.0;
        lastSentY = mc.player != null ? mc.player.getY() : 0.0;
        lastSentZ = mc.player != null ? mc.player.getZ() : 0.0;
        lastSentOnGround = true;
        fallDistanceBeforeTick = 0.0;
    }

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

        // 「空包防踢」：本 tick 不马上换回胸甲，让「服务端认滑翔」跨过它自己的 tick 边界，
        // 末尾补的那发原地移动包就一定落在滑翔窗口里（下一 tick 开头换回胸甲，见 onTick）。
        // 换装没跑到这里（换甲冷却 / 开着容器 / Grim 模式认为还在滑翔）时请求留着，
        // 由 tickAntiKick 把计时退回 0，下一 tick 再试。
        if (holdElytraRequested) {
            holdElytraRequested = false;
            // 保持几 tick 见 elytraHoldTicks：Grim 兼容模式要多等一 tick 躲开 MultiActionsC
            elytraHoldTicks = grimInputSequenceOn() ? 2 : 1;
            holdElytraPacketThisTick = true;
            // 本地也置成滑翔（普通甲飞本来每 tick 就置，这里补齐 Grim 模式的空缺）：
            // 一是本地「服务端认滑翔」的滑翔运算/朝向跟着一致；
            // 二是原版 LocalPlayer 只有在「本地没在滑翔 + 穿着鞘翅 + 本 tick 按下跳跃」
            // 时才会自己补一个起飞包（Grim ElytraC 起飞过频，还会把刚开的滑翔又关掉），
            // 本地置成滑翔后 tryToStartFallFlying 直接返回 false，不会重复发包——
            // 这正是「兼容 grim 输入检测」这一 tick 能照常按下跳跃输入（防 ElytraB no jump）的前提。
            if (!mc.player.isFallFlying()) mc.player.startFallFlying();
            return;
        }

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

    /** 胸甲槽当前是否穿着鞘翅（读本地玩家容器） */
    private static boolean isElytraInChest() {
        return mc.player.containerMenu.getSlot(CHEST_SLOT).getItem().is(Items.ELYTRA);
    }

    /** 直接发起飞包（不经过 tryToStartFallFlying，本地不检查 canGlide） */
    private static void sendStartFlying() {
        // 服务器处理本 tick 的移动包时已经在滑翔（起飞包先于位置包发出），本地移动运算据此对齐
        startedGlidingThisTick = true;
        // 重置「起飞包窗口」：接下来 4 tick 内即使某个 tick 没换成鞘翅，本地也照样按滑翔运算移动
        ticksSinceStartFlying = 0;
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
        // 冻结中不释放：换装窗口在冻结期间照常开合（维持服务端滑翔状态），
        // 但排队中的自动烟花要留到解冻后的窗口再放——冻结期间放烟花会让服务端
        // 重新结算滑翔与速度，和「完全静止」打架（也正是冻结时烟花乱放的来源）。
        if (isFrozenNow()) return;
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
