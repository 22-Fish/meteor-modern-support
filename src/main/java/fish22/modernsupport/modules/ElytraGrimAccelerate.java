package fish22.modernsupport.modules;

import fish22.modernsupport.utils.ElytraFlySupport;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅滑翔加速（独立模块，移动分类）
 *
 * <p>移植自史莱姆 mod（SlimefunHelper）的 {@code ElytraGrimAcc}
 * （配置路径 {@code elytra/elytra-flight-legit/grim-accelerate}），
 * 数值与判定条件照抄原实现，只把发包管线换成本模组的钩子。
 *
 * <h3>原理</h3>
 * 滑翔速度掉到「最小加速速度」以下时进入加速模式：这一 tick <b>不发</b>原版的位置移动包，
 * 改成在移动包发完之后补一个假包 —— X/Z 还是真实坐标，Y 抬高
 * {@code 2.5 × (tick % 3 + 1)}（2.5 / 5 / 7.5 格循环）。服务端（Grim）拿到这种位移会判非法、
 * 把我们拉回，而拉回的整个过程里服务端不会用它的滑翔模拟去重置我们的速度；
 * 再把服务端发来的、「会让我们减速或清零」的速度更新包丢掉（见 {@link #onPacketReceive}），
 * 滑翔速度就不会被服务端压下去 —— 这就是「加速」。
 * 速度涨回「最大加速速度」以上自动退出加速模式，掉下来再进去。
 *
 * <h3>发包时序（对应史莱姆的 createStorePacket / postModify）</h3>
 * <ul>
 *   <li>加速模式里，{@link SendMovementPacketsEvent.Pre} 到 {@code Post} 之间发出去的原版位置包
 *       （{@code LocalPlayer#sendPosition} 那一发）一律在 {@link PacketEvent.Send} 里压掉；
 *       只压这一发：服务器拉回时原版立刻补的那发位置确认包、别的模块自己发的移动包都不受影响。</li>
 *   <li>{@link SendMovementPacketsEvent.Post}（原版移动包发完之后）补发假包，时序与史莱姆的
 *       postProgress 一致：服务端这一批包先处理完，再看到假包。</li>
 * </ul>
 *
 * <h3>两个门槛（照抄史莱姆）</h3>
 * <ul>
 *   <li><b>等拉回</b>：补一发假包后，20 tick 内不补第二发 —— 正常情况下服务端马上会拉回，
 *       拉回包（客户端回的 {@link ServerboundAcceptTeleportationPacket}）一到就把这个计时解掉，
 *       下一 tick 才能再补；服务端不拉回时最多 20 tick 补一发。</li>
 *   <li><b>防延迟踢修复</b>：超过 10 tick 没发过正常位置包（一直没收到拉回、也没退出加速模式）
 *       时停 5 tick 不接管（原版位置包照常发），避免服务端那边一直收不到正常位移。</li>
 * </ul>
 *
 * <h3>与史莱姆实现的差异</h3>
 * <ul>
 *   <li><b>判定只看本地滑翔状态</b>：史莱姆用的是 {@code mc.player.isFallFlying()}，
 *       这里照抄，不去看别的模块（甲飞 / 鞘翅飞行）开没开、设了什么。</li>
 *   <li><b>「防延迟踢」暂停期间不接管</b>：史莱姆暂停期间照样压包 + 回退坐标（客户端继续什么都不发、
 *       位置被钉住），而且那个暂停计时不会刷新，一旦服务端不回弹就会一直卡在原地。
 *       这里改成暂停的 5 tick 里完全不接管、原版位置包照常发，暂停结束自己恢复；
 *       服务端回弹正常时这条分支根本不会走到。</li>
 *   <li><b>回弹后补回自家动量（26.1 协议差异，必须补）</b>：史莱姆那个年代，服务器的位置纠正包
 *       <b>不碰速度</b>，所以「拉回只改坐标、自己的动量留着」是天然成立的；26.1 的位置纠正包自带
 *       {@code deltaMovement} 字段，原版客户端收到就直接 {@code setDeltaMovement(包里的值)}，
 *       我们自己的速度会被清成包里的值（Grim 那边是 0）。所以这里在收到回弹时先记下自己的动量，
 *       等这帧的包处理完再写回去；如果回弹附带的那发速度包我们按史莱姆的规则<b>收下了</b>
 *       （不亏的那种），就不补，交给服务器的值。</li>
 * </ul>
 *
 * <h3>本地坐标回退（史莱姆的 playerStatus.restorePos）</h3>
 * 加速模式里，除了压包，还会把<b>本地</b>坐标退回本 tick 开始时的位置（顺带把
 * 「在流体里」的两个标记和细雪标记一起退回去），这一 tick 移动运算算出来的位移就丢掉了。
 * 也就是说加速期间本地不会自己往前跑：位置只在服务器把我们从非法位移处拉回来/传送过来之后
 * 跟着变。这是史莱姆原本的行为，原样保留。
 */
public class ElytraGrimAccelerate extends Module {

    /** 触发服务器拉回的方式（史莱姆的 set-back-mode） */
    public enum SetBackMode {
        SIMULATION("模拟"),
        CRASH_PACKETS("崩溃包");

        private final String displayName;

        SetBackMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 史莱姆的 set-back-mode，默认模拟 */
    private final Setting<SetBackMode> setBackMode = sgGeneral.add(new EnumSetting.Builder<SetBackMode>()
        .name("触发方式")
        .description("补发的假包怎么触发服务器拉回。模拟：Y 抬高 2.5~7.5 格；崩溃包：再把 X/Z 换成 3.9999999E7 的极端值。")
        .defaultValue(SetBackMode.SIMULATION)
        .build()
    );

    /** 史莱姆的 max-accelerate-velocity，默认 6.0 */
    private final Setting<Double> maxAccelerateVelocity = sgGeneral.add(new DoubleSetting.Builder()
        .name("最大加速速度")
        .description("速度超过该值时退出加速模式，直到速度降下来。")
        .defaultValue(6.0)
        .range(0.0, 100.0)
        .noSlider()
        .build()
    );

    /** 史莱姆的 min-accelerate-velocity，默认 3.6 */
    private final Setting<Double> minAccelerateVelocity = sgGeneral.add(new DoubleSetting.Builder()
        .name("最小加速速度")
        .description("速度低于该值时进入加速模式。")
        .defaultValue(3.6)
        .range(0.0, 100.0)
        .noSlider()
        .build()
    );

    /** 史莱姆的 fix-kick-from-lag，默认开启 */
    private final Setting<Boolean> fixKickFromLag = sgGeneral.add(new BoolSetting.Builder()
        .name("防延迟踢修复")
        .description("太久没收到服务器拉回（10 tick）时暂停加速 5 tick，避免服务端长时间收不到正常位移。")
        .defaultValue(true)
        .build()
    );

    /** 排查用：在聊天栏打印状态变化（不含坐标） */
    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("调试信息")
        .description("在聊天栏打印加速状态（补发假包 / 收到拉回 / 暂停），排查问题用。")
        .defaultValue(false)
        .build()
    );

    /**
     * 史莱姆原行为：加速期间把本地坐标退回 tick 开始的位置（本地不会自己往前跑）。
     * 关掉 = 只做发包替换（压原版位置包 + 补假包），本地照常飞 —— 排查用，方便分辨
     * 「被钉住」和「在飞但没变快」这两种情况。
     */
    private final Setting<Boolean> restoreLocalPosition = sgGeneral.add(new BoolSetting.Builder()
        .name("本地坐标回退")
        .description("加速期间把本地坐标退回 tick 开始。关掉：只压包 + 补假包，本地照常飞。")
        .defaultValue(true)
        .build()
    );

    // ====== 状态（与史莱姆的字段一一对应） ======

    /** 「永远没发生过」的计时哨兵（史莱姆用一个很大的全局 tick 计数，0 就等于很久以前） */
    private static final int NEVER = -100000;

    /** 本模块自己的 tick 计数（对应史莱姆的 Tasks.getTick()） */
    private int tick;

    /** 最近一次「加速模式候选」的 tick（史莱姆的 lastWorkingTick） */
    private int lastWorkingTick = NEVER;

    /** 最近一次「服务器那边有正常位移」的 tick（史莱姆的 lastMoveTick） */
    private int lastMoveTick = NEVER;

    /** 最近一次补出假包、正在等服务端拉回的 tick（史莱姆的 lastSendMoveAndWaitSetBackTick） */
    private int lastSendAndWaitSetBackTick = NEVER;

    /** 延迟保护暂停到哪个 tick（史莱姆的 stopBecauseOfLagTillTick） */
    private int stopBecauseOfLagUntilTick = NEVER;

    /** 最近一次看到「自己身上有活着的烟花」的 tick（史莱姆的 lastFireworkRocketTick） */
    private int lastFireworkTick = NEVER;

    /**
     * 回弹前自己的动量（26.1 的位置纠正包自带速度字段，原版客户端收到就 {@code setDeltaMovement(包里的值)}，
     * 会把我们自己的速度清掉；1.20 那会儿拉回不动速度，史莱姆那套「保留自己的动量」就靠这个前提）。
     */
    private Vec3 ownMomentum = Vec3.ZERO;

    /** 这一帧收到了位置纠正包（回弹），等着下一 tick 把自家动量补回去 */
    private boolean setBackTeleportPending;

    /** 回弹附带的那发速度包我们接下了（没拦）：那就不补自家动量，按史莱姆的过滤结果走 */
    private boolean acceptedSetBackVelocity;

    /** 本 tick 是不是加速模式的候选（史莱姆的 currentTryWorking） */
    private boolean tryWorking;

    /** 本 tick 是不是真的在加速（速度门槛，史莱姆的 currentWorking） */
    private boolean working;

    /** 本 tick 要补发假包（史莱姆的 storedPacket 非空） */
    private boolean fakePacketPending;

    /** 本 tick 压掉原版位置包（史莱姆的 event.cancel()） */
    private boolean suppressVanillaMove;

    /** 正处于「原版移动包发送窗口」内（sendPosition 的 Pre~Post 之间） */
    private boolean inSendPosition;

    /** 正在发我们自己补的假包：放行，别被自己的拦截吃掉 */
    private boolean sendingFakePacket;

    /** 累补发过多少发假包 / 收到过多少次服务器拉回（调试信息用） */
    private int fakePacketCount;
    private int setBackCount;

    // ====== 本地坐标回退用的快照（史莱姆 EntityMovementStatus 里 preProgress 抓的那一份） ======

    /** 本 tick 开始时的坐标 */
    private double tickStartX;
    private double tickStartY;
    private double tickStartZ;

    /** 本 tick 开始时的「在流体/细雪里」标记（史莱姆的 touchingWater / submergedInWater / inPowderSnow） */
    private boolean tickStartTouchingWater;
    private boolean tickStartEyeInWater;
    private boolean tickStartInPowderSnow;

    public ElytraGrimAccelerate() {
        super(Categories.Movement, "鞘翅滑翔加速",
            "史莱姆的 Grim 鞘翅加速：滑翔速度掉下来时压掉原版位置包、补发抬高 Y 的假坐标触发服务器拉回，并丢掉会减速的服务端速度包，让滑翔速度不被压下去。真鞘翅滑翔时生效。");
    }

    @Override
    public void onActivate() {
        resetState();
        tick = 0;
        // 史莱姆的管线在模块没开的时候也每 tick 跑（lastMoveTick 一直是新鲜的），
        // Meteor 模块没开就收不到事件，所以开模块时补一次；不然第一 tick 就会被
        // 「防延迟踢」判成「10 tick 没发过正常位移」，假包一发都发不出去。
        lastMoveTick = 0;
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        tryWorking = false;
        working = false;
        fakePacketPending = false;
        suppressVanillaMove = false;
        inSendPosition = false;
        sendingFakePacket = false;
        lastWorkingTick = NEVER;
        lastMoveTick = NEVER;
        lastSendAndWaitSetBackTick = NEVER;
        stopBecauseOfLagUntilTick = NEVER;
        lastFireworkTick = NEVER;
        fakePacketCount = 0;
        setBackCount = 0;
        ownMomentum = Vec3.ZERO;
        setBackTeleportPending = false;
        acceptedSetBackVelocity = false;
    }

    /** HUD 上显示当前滑翔速度（史莱姆的 getInfoString） */
    @Override
    public String getInfoString() {
        if (!working || mc.player == null) return null;
        return String.format("[%.2f]", mc.player.getDeltaMovement().length());
    }

    // ====== tick 前半段：决定这一 tick 要不要加速 ======

    /**
     * 对应史莱姆的 applyPreTickModify：只有「本地在滑翔 + 没落地 + 没骑乘 + 烟花没在推我」
     * 才有资格进入加速模式。注意只是资格，真正进不进加速模式看速度门槛（移动包那一阶段才算）。
     */
    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        tick++;

        if (mc.player == null || mc.level == null) {
            resetState();
            return;
        }

        // 兜底：上一 tick 万一没走到 Post（异常/换世界），别把「压包」状态留到这一 tick
        suppressVanillaMove = false;
        inSendPosition = false;
        fakePacketPending = false;

        // 坐标快照：加速模式要把本地坐标回退到这里（史莱姆的 preProgress）
        // 注意 Meteor 的 TickEvent.Pre 在 Minecraft.tick() 开头，收到的包（含服务器拉回的传送）
        // 已经在进 tick 之前处理完了，所以快照拿到的是「服务器传送之后」的位置，和史莱姆一致。
        tickStartX = mc.player.getX();
        tickStartY = mc.player.getY();
        tickStartZ = mc.player.getZ();
        tickStartTouchingWater = mc.player.wasTouchingWater;
        tickStartEyeInWater = mc.player.wasEyeInWater;
        tickStartInPowderSnow = mc.player.isInPowderSnow;

        // 上一帧（收到的包已经处理完了）吃了服务器回弹：把回弹前自己的动量补回去。
        // 26.1 的位置纠正包带速度字段，原版收到就直接 setDeltaMovement(包里的值)，
        // 我们自己的速度会被清掉 —— 而史莱姆那套「拉回不动速度、保留自己的动量」正是靠这一点成立的。
        // 那发附带的「速度包」我们没拦（接下了服务器的值）时不补，交给史莱姆的过滤规则决定。
        if (setBackTeleportPending) {
            setBackTeleportPending = false;
            if (!acceptedSetBackVelocity && mc.player.isFallFlying() && !mc.player.onGround()
                && ownMomentum.lengthSqr() > 1.0E-6) {
                mc.player.setDeltaMovement(ownMomentum);
                if (debug.get()) info("回弹后补回自家动量 %.2f", ownMomentum.length());
            }
        }
        acceptedSetBackVelocity = false;

        boolean gliding = mc.player.isFallFlying();
        // 史莱姆的 canFireworkControlMotion：身上有活着的烟花（或刚消失 3 tick 内）时不加速
        boolean fireworkControlling = false;
        if (gliding) {
            fireworkControlling = ElytraFlySupport.hasActiveOwnedFirework() || tick - lastFireworkTick < 3;
            if (fireworkControlling) lastFireworkTick = tick;
        }

        tryWorking = gliding
            && !mc.player.onGround()
            && !mc.player.isPassenger()
            && !fireworkControlling;

        if (tryWorking) lastWorkingTick = tick;
    }

    // ====== 移动包阶段：压包 + 决定补不补假包 ======

    /**
     * 对应史莱姆的 applyBeforeMovementPacketModify：算速度门槛、压掉原版位置包、
     * 决定这一 tick 要不要补假包。
     *
     * <p>阈值是速度的<b>模长</b>（史莱姆用的是 {@code velocity.length()}，不是水平速度）。
     */
    @EventHandler
    private void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (mc.player == null) return;
        inSendPosition = true;

        if (!tryWorking) {
            working = false;
            lastMoveTick = tick;
            return;
        }

        double speed = mc.player.getDeltaMovement().length();
        if (working) {
            if (speed > maxAccelerateVelocity.get()) working = false;
        } else if (speed < minAccelerateVelocity.get()) {
            working = true;
        }

        if (!working) {
            // 没在加速就是老实发真实位置：服务端那边有正常位移，计时刷新
            lastMoveTick = tick;
            return;
        }

        // 「防延迟踢」暂停期内不接管：原版位置包照常发（这样 lastMoveTick 会刷新，5 tick 后自己恢复）
        if (stopBecauseOfLagUntilTick > tick) {
            working = false;
            lastMoveTick = tick;
            return;
        }

        // 超过 10 tick 没发过正常位移（一直没等到服务器拉回）：暂停 5 tick
        if (fixKickFromLag.get() && tick > lastMoveTick + 10) {
            stopBecauseOfLagUntilTick = tick + 5;
            working = false;
            lastMoveTick = tick;
            if (debug.get()) info("10 tick 没等到拉回，暂停加速 5 tick");
            return;
        }

        // 加速模式：这一 tick 的原版位置包压掉（不管补不补假包，史莱姆都是 cancel），
        // 并把本地坐标退回本 tick 开始的位置（史莱姆的 playerStatus.restorePos，顺序也一样：先回退、再决定补包）
        suppressVanillaMove = true;
        if (restoreLocalPosition.get()) restoreLocalPos();

        if (shouldStoreFakePacket()) fakePacketPending = true;
    }

    /**
     * 史莱姆的 {@code playerStatus.restorePos()}：把本地坐标回退到本 tick 开始时的值，
     * 并把「在流体里」的两个标记和细雪标记一起退回去（史莱姆的 EntityMovementStatus 就是这么写的）。
     *
     * <p>回退丢掉的是这一 tick 移动运算的结果：本地不会自己往前跑，位置只在服务器把我们
     * 传送过来之后跟着变。玩家的速度（{@code deltaMovement}）、视角都不动。
     */
    private void restoreLocalPos() {
        if (mc.player == null) return;
        mc.player.setPos(tickStartX, tickStartY, tickStartZ);
        mc.player.wasTouchingWater = tickStartTouchingWater;
        mc.player.wasEyeInWater = tickStartEyeInWater;
        mc.player.setIsInPowderSnow(tickStartInPowderSnow);
    }

    /**
     * 对应史莱姆 createStorePacket 的最后一个门槛：上一发假包还在等服务端拉回就先别再补。
     *
     * <p>（「10 tick 没正常位移」那个门槛在 {@link #onSendMovementPacketsPre} 里，因为那个分支
     * 连压包都不做。）
     */
    private boolean shouldStoreFakePacket() {
        return !fixKickFromLag.get() || tick >= lastSendAndWaitSetBackTick + 20;
    }

    /**
     * 对应史莱姆的 postModify：原版移动包发完之后补发假包。
     *
     * <p>发的是完整移动包（位置 + 朝向）：坐标取当前真实坐标，Y 抬高
     * {@code 2.5 × (tick % 3 + 1)}；「崩溃包」方式再把 X/Z 换成 3.9999999E7。
     * 客户端自己的坐标、动量、视角都不动。
     */
    @EventHandler
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        inSendPosition = false;

        if (fakePacketPending && mc.player != null && mc.getConnection() != null) {
            fakePacketPending = false;
            sendFakePacket();
            // 记下「什么时候补的包」：服务端拉回之前不再补第二发
            lastSendAndWaitSetBackTick = tick;
            fakePacketCount++;
            if (debug.get()) {
                info("补发假包 #%d（tick %d，速度 %.2f）", fakePacketCount, tick,
                    mc.player.getDeltaMovement().length());
            }
        } else {
            fakePacketPending = false;
        }

        suppressVanillaMove = false;
    }

    /** 补发假包（史莱姆 createStorePacket + postModify 里那一次 sendPacket） */
    private void sendFakePacket() {
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        boolean horizontalCollision = mc.player.horizontalCollision;

        // 每 3 tick 循环一次的 Y 抬高量：2.5 / 5 / 7.5
        double fakeY = y + 2.5 * ((tick % 3) + 1);

        ServerboundMovePlayerPacket packet = setBackMode.get() == SetBackMode.CRASH_PACKETS
            ? new ServerboundMovePlayerPacket.PosRot(3.9999999E7D, fakeY, 3.9999999E7D, yaw, pitch, true, horizontalCollision)
            : new ServerboundMovePlayerPacket.PosRot(x, fakeY, z, yaw, pitch, mc.player.onGround(), horizontalCollision);

        sendingFakePacket = true;
        try {
            mc.getConnection().send(packet);
        } finally {
            sendingFakePacket = false;
        }
    }

    // ====== 发包 / 收包 ======

    /**
     * 发包侧：
     * <ul>
     *   <li>加速模式里压掉这一 tick 的原版位置包（对应史莱姆的 event.cancel()）；
     *       只压「原版移动包发送窗口」内、带位置的那一发 —— 服务器拉回后原版立刻补的
     *       位置确认包、别的模块自己发的移动包都放行；</li>
     *   <li>收到服务器拉回后，客户端会回一个 {@link ServerboundAcceptTeleportationPacket}，
     *       拿它当「拉回已到达」的信号（史莱姆的 onSetBackReceive）：解掉「等拉回」计时。</li>
     * </ul>
     */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundAcceptTeleportationPacket) {
            lastMoveTick = tick;
            lastSendAndWaitSetBackTick = NEVER;
            setBackCount++;
            if (debug.get()) info("收到服务器拉回 #%d（tick %d）", setBackCount, tick);
            return;
        }

        if (!suppressVanillaMove || !inSendPosition || sendingFakePacket) return;
        if (event.packet instanceof ServerboundMovePlayerPacket move && move.hasPosition()) {
            event.cancel();
        }
    }

    /**
     * 收包侧（史莱姆的 onVcUpdate）：滑翔中，服务器发来的「速度更新」包只要会让我们减速就丢掉。
     *
     * <p>两种丢掉的情况：水平速度几乎是 0（会清掉我们的速度），
     * 或者方向和当前滑翔方向相反（会拖速度）。
     * 26.1 里这个包是 {@link ClientboundSetEntityMotionPacket}，速度已经换算好（Vec3）。
     */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        // 服务器的位置纠正包（回弹 / 传送）：记下回弹前自己的动量，
        // 等这一帧的包都处理完（下一 tick 开头）再补回去，见 onTickPre
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            if (working && mc.player.isFallFlying()) {
                ownMomentum = mc.player.getDeltaMovement();
                setBackTeleportPending = true;
                acceptedSetBackVelocity = false;
            }
            return;
        }

        if (!(event.packet instanceof ClientboundSetEntityMotionPacket motion)) return;
        if (motion.id() != mc.player.getId()) return;
        if (!mc.player.isFallFlying()) return;
        // 史莱姆的 (enable || lastWorkingTick + 10 > tick)：模块开着就为真
        if (!isActive() && lastWorkingTick + 10 <= tick) return;

        Vec3 serverVelocity = motion.movement();

        // 服务器想把我们的速度清零
        if (serverVelocity.horizontalDistanceSqr() < 1E-2) {
            event.cancel();
            return;
        }

        // 服务器给的速度和当前飞行方向相反：接了就减速
        Vec3 own = mc.player.getDeltaMovement();
        if (own.horizontalDistanceSqr() > 1E-2
            && own.x * serverVelocity.x + own.z * serverVelocity.z < 0.0) {
            event.cancel();
            return;
        }

        // 这发没拦（服务器的动量不亏）：不用补自家动量，按史莱姆的规则照单收下
        acceptedSetBackVelocity = true;
    }

}
