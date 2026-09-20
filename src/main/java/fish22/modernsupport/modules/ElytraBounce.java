package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.player.Rotation;
import meteordevelopment.orbit.EventHandler;
import fish22.modernsupport.utils.LegalRotation;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅弹跳（独立模块，移动分类）
 *
 * <p>从 Meteor 官方「鞘翅飞行」的 Bounce 模式拆出来的独立模块（原逻辑来自 Elytra Recast，
 * 作者 Luna）：自动按住前进、锁定偏航与俯仰角持续滑翔，被反作弊回弹（收到位置纠正包）后
 * 按「重启延迟」自动重新起飞。
 *
 * <p>与「鞘翅飞行」互斥：两边都在控制滑翔，同时开只会互相打架。
 *
 * <h3>合法模式</h3>
 * 不每 tick 用起飞包维持滑翔，只在离地那一 tick 正常发一发起飞包（回弹重启、手动起飞排队
 * 的请求也在那里发）。两个子选项：
 * <ul>
 *   <li><b>兼容grim输入检测</b>：Grim 收到起飞包时要求输入里的跳跃键是松开的、紧接着的第一个
 *       移动包又要看到按下（ElytraB 的 no release / no jump），一直按着跳跃键由客户端维持滑翔
 *       会被直接驳回。开启后离地瞬间模拟按键输入包（先松开一 tick、再按下 + 起飞包）</li>
 *   <li><b>落地维持滑翔</b>（默认关）：落地也不停止滑翔，落地后本地强行维持滑翔状态 4 tick
 *       （服务端广播的停滑数据直接改回滑翔），弹跳离地瞬间直接接上滑翔运算，中间不掉速；
 *       地面那段靠 Grim 对停止滑翔的豁免</li>
 * </ul>
 *
 * <h3>固定相机</h3>
 * 偏航/俯仰锁定不再转本地视角，改走「合法转头API-严格模式」：只把服务器看到的朝向转到
 * 锁定值，相机不动。偏航锁定为「智能」时按<b>相机角度</b>挑最接近的那个 45° 锁定值。
 */
public class ElytraBounce extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 自动按住跳跃键（弹跳） */
    private final Setting<Boolean> autoJump = sgGeneral.add(new BoolSetting.Builder()
        .name("自动跳跃")
        .description("自动帮你按住跳跃键")
        .defaultValue(true)
        .build()
    );

    /** 偏航锁定方式 */
    private final Setting<Rotation.LockMode> yawLockMode = sgGeneral.add(new EnumSetting.Builder<Rotation.LockMode>()
        .name("偏航锁定")
        .description("锁定飞行偏航角的方式")
        .defaultValue(Rotation.LockMode.Smart)
        .build()
    );

    /** 简单锁定时的偏航角 */
    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("偏航角")
        .description("偏航锁定选「简单」时使用的角度")
        .defaultValue(0)
        .range(0, 360)
        .sliderRange(0, 360)
        .visible(() -> yawLockMode.get() == Rotation.LockMode.Simple)
        .build()
    );

    /** 锁定俯仰 */
    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("锁定俯仰")
        .description("锁定俯仰角")
        .defaultValue(true)
        .build()
    );

    /** 俯仰角 */
    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("俯仰角")
        .description("弹跳飞行时锁定的俯仰角")
        .defaultValue(85)
        .range(0, 90)
        .sliderRange(0, 90)
        .visible(lockPitch::get)
        .build()
    );

    /** 被回弹后自动重新起飞 */
    private final Setting<Boolean> restart = sgGeneral.add(new BoolSetting.Builder()
        .name("回弹重启")
        .description("被服务器回弹（位置纠正）后自动重新起飞")
        .defaultValue(true)
        .build()
    );

    /** 重启延迟 */
    private final Setting<Integer> restartDelay = sgGeneral.add(new IntSetting.Builder()
        .name("重启延迟")
        .description("被回弹后等多少 tick 再重新起飞")
        .defaultValue(7)
        .min(0)
        .sliderRange(0, 20)
        .visible(restart::get)
        .build()
    );

    /** 持续疾跑 */
    private final Setting<Boolean> sprint = sgGeneral.add(new BoolSetting.Builder()
        .name("持续疾跑")
        .description("一直保持疾跑")
        .defaultValue(true)
        .build()
    );

    /** 手动起飞：不自动起飞 */
    private final Setting<Boolean> manualTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("手动起飞")
        .description("不自动起飞")
        .defaultValue(false)
        .build()
    );

    /** 合法模式：不每 tick 用起飞包维持滑翔 */
    private final Setting<Boolean> legal = sgGeneral.add(new BoolSetting.Builder()
        .name("合法模式")
        .description("不每 tick 用起飞包维持滑翔，只在离地那一 tick 正常发一发起飞包")
        .defaultValue(false)
        .build()
    );

    /** 兼容 grim 输入检测（仅合法模式） */
    private final Setting<Boolean> grimInput = sgGeneral.add(new BoolSetting.Builder()
        .name("兼容grim输入检测")
        .description("离地瞬间模拟按键输入包（先松开再按下）后再起飞，躲开 Grim 起飞包的输入检测")
        .defaultValue(false)
        .visible(legal::get)
        .build()
    );

    /** 落地维持滑翔（仅合法模式） */
    private final Setting<Boolean> keepGlide = sgGeneral.add(new BoolSetting.Builder()
        .name("落地维持滑翔")
        .description("落地也不停止滑翔：落地后本地强行维持 4 tick 滑翔状态，弹跳离地瞬间直接接上滑翔运算")
        .defaultValue(false)
        .visible(legal::get)
        .build()
    );

    /** 固定相机 */
    private final Setting<Boolean> fixedCamera = sgGeneral.add(new BoolSetting.Builder()
        .name("固定相机")
        .description("朝向锁定改用合法转头API-严格模式：只改服务器看到的朝向，相机不动")
        .defaultValue(false)
        .build()
    );

    /** 落地维持滑翔：维持的 tick 数 */
    private final Setting<Integer> keepGlideTicksSetting = sgGeneral.add(new IntSetting.Builder()
        .name("维持tick")
        .description("落地后强行维持滑翔状态的 tick 数")
        .defaultValue(KEEP_GLIDE_TICKS)
        .min(1)
        .sliderRange(1, 20)
        .visible(() -> legal.get() && keepGlide.get())
        .build()
    );

    /** 是否刚被回弹（等重启延迟） */
    private boolean rubberbanded = false;

    private int tickDelay;

    /** 关闭前保存的 FOV 效果缩放（持续疾跑关掉时会被改掉） */
    private double prevFov;

    /** 兼容 grim 输入检测：本 tick 的输入包要按下跳跃（起飞那一 tick） */
    private boolean grimPressTick;

    /** 兼容 grim 输入检测：服务端最后一次看到的跳跃键是松开（松开才允许发起飞包） */
    private boolean grimJumpReleased;

    /** 兼容 grim 输入检测：手动起飞时玩家在空中按了跳跃，等下一个「松开」窗口再起飞 */
    private boolean grimTakeoffPending;

    /** 上一 tick 开头玩家是不是站在地上（判断「刚离地」那一 tick） */
    private boolean prevOnGround;

    /** 本 tick 是不是刚离地（上一 tick 还在地面） */
    private boolean justLeftGround;

    /** 落地维持滑翔：落地后强行维持本地滑翔状态的剩余 tick 数 */
    private int keepGlideTicks;

    /** 落地维持滑翔：落地后维持多少 tick */
    private static final int KEEP_GLIDE_TICKS = 4;

    /** 落地维持滑翔：水平速度低于这个值（m/s）就不维持，慢了服务端预测对得准，维持反而被拉回 */
    private static final double KEEP_GLIDE_MIN_SPEED = 15.0;

    /** 固定相机：每 tick 让锁定朝向差一点点，保证它一定随移动包发出去 */
    private static final float ROTATION_CONFIRM_EPSILON = 0.001f;

    /** 服务端同步数据 id（Entity 类 defineId 顺序固定，和 {@link fish22.modernsupport.utils.InfiniteElytraSupport} 一致） */
    private static final int ID_FLAGS = 0;
    private static final int ID_POSE = 6;
    private static final int FALL_FLYING_FLAG_INDEX = 7;
    private static final int SPRINTING_FLAG_INDEX = 3;

    /** 当前开启的实例：给 {@link fish22.modernsupport.mixin.MixinElytraKeyboardInput} 读按键状态用 */
    private static ElytraBounce active;

    /** 兼容 grim 输入检测：上一次真正发出去的跳跃键状态（由 MixinElytraKeyboardInput 记录） */
    private static boolean lastJumpInput;

    public ElytraBounce() {
        super(Categories.Movement, "鞘翅弹跳",
            "弹跳：自动按住前进 + 锁定偏航/俯仰持续滑翔");
    }

    @Override
    public void onActivate() {
        // 两边都在控制滑翔，同时开只会互相打架：开这个就把「鞘翅飞行」关掉
        Module elytraFly = Modules.get().get(ElytraFly.class);
        if (elytraFly != null && elytraFly.isActive()) elytraFly.toggle();

        active = this;
        prevFov = mc.options.fovEffectScale().get();
        rubberbanded = false;
        tickDelay = restartDelay.get();
        prevOnGround = mc.player != null && mc.player.onGround();
        keepGlideTicks = 0;
        resetGrimInput();
    }

    @Override
    public void onDeactivate() {
        unpress();
        rubberbanded = false;
        if (active == this) active = null;
        justLeftGround = false;
        keepGlideTicks = 0;
        resetGrimInput();
        if (prevFov != 0 && !sprint.get()) mc.options.fovEffectScale().set(prevFov);
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (checkConditions() && sprint.get()) mc.player.setSprinting(true);

        boolean onGround = mc.player.onGround();
        justLeftGround = prevOnGround && !onGround;
        boolean landed = onGround && !prevOnGround;
        prevOnGround = onGround;

        // 落地维持滑翔：落地那一 tick 起强行维持本地滑翔状态「维持tick」那么多 tick。
        // 只在水平速度够快时启用：速度慢了服务端预测本来就能对上，维持滑翔反而会被拉回
        boolean keepGlideFastEnough = horizontalSpeed() > KEEP_GLIDE_MIN_SPEED;

        if (landed && legal.get() && keepGlide.get() && keepGlideFastEnough) {
            keepGlideTicks = keepGlideTicksSetting.get();
        }
        if (keepGlideTicks > 0) {
            // 掉到阈值以下就立刻停掉，别把这段维持拖到低速里
            if (!keepGlideFastEnough) keepGlideTicks = 0;
            else {
                keepGlideTicks--;
                forceKeepGlide();
            }
        }

        if (legal.get()) tickLegal();

        // 固定相机：在 tick 前半段走合法转头API-严格模式（只改服务器看到的朝向，相机不动）。
        // 放在这里而不是 tick 末尾，本 tick 的移动运算与移动包就直接用这个锁定朝向
        if (fixedCamera.get() && checkConditions()) {
            LegalRotation.rotate(
                getYawDirection(),
                lockPitch.get() ? pitch.get().floatValue() : mc.player.getXRot(),
                LegalRotation.Mode.SEVERE
            );
            // 固定相机时相机是自由的，服务端记的朝向必须一直是这一份锁定朝向：
            // 让真实朝向每 tick 差一点点（0.001°），这一份朝向就一定会随移动包重发出去，
            // 服务端那边不会被相机视角（位置纠正包 / 别处补的移动包）盖成相机方向 ——
            // 盖掉的话本地走的是锁定方向、服务端预测的是相机方向，起跳就会被拉回
            LegalRotation.nudgeRealYaw(ROTATION_CONFIRM_EPSILON);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        boolean legalMode = legal.get();
        if (legalMode) tickLegalEnd();

        // 按着跳跃但没在滑翔：手动补一发起飞包（官方行为，客户端靠起飞包维持滑翔）。
        // 合法模式不这么补：那一发在 Grim 上会被看到「按着跳跃」直接驳回，
        // 起飞交给 tickLegal 按正常顺序发
        if (!legalMode && mc.options.keyJump.isDown() && !mc.player.isFallFlying() && !manualTakeoff.get()) {
            sendStartFlying();
        }

        if (!checkConditions()) return;

        if (!rubberbanded) {
            if (prevFov != 0 && !sprint.get()) mc.options.fovEffectScale().set(0.0);
            if (autoJump.get()) mc.options.keyJump.setDown(true);
            mc.options.keyUp.setDown(true);
        }

        // 朝向锁定跟回弹状态无关：被拉回/传送后照旧锁（原来这块被 !rubberbanded 挡住，
        // 位置纠正一来、「回弹重启」又没开，锁定就再也不生效，表现成「相机指哪飞哪」）。
        // 固定相机：朝向锁定在本 tick 前半段已经用合法转头API发过（见 onPreTick），这里不动视角；
        // 偏航锁定「智能」挑的就是相机角度更接近的那个 45° 锁定值
        if (!fixedCamera.get()) {
            mc.player.setYRot(getYawDirection());
            if (lockPitch.get()) mc.player.setXRot(pitch.get().floatValue());
        }

        if (!sprint.get()) {
            // 一直疾跑在部分反作弊上会被回弹，关掉后只在地面疾跑
            mc.player.setSprinting(mc.player.isFallFlying() ? mc.player.onGround() : true);
        }

        // 回弹：等「重启延迟」后收尾。「回弹重启」只决定要不要补这一发起飞包，
        // 状态本身一定要清掉（不清的话「按住前进/跳跃」和朝向锁定就一直被它挡住）
        if (rubberbanded) {
            if (tickDelay > 0) {
                tickDelay--;
            } else {
                if (restart.get()) {
                    if (legalMode) grimTakeoffPending = true;
                    else sendStartFlying();
                }
                rubberbanded = false;
                tickDelay = restartDelay.get();
            }
        }
    }

    /**
     * 合法模式：不每 tick 用起飞包维持滑翔，只在离地那一 tick 正常发一发起飞包
     * （回弹重启、手动起飞排队的请求也在这里发）
     *
     * <p>触发条件和原来一致：自动跳跃开着就是离地瞬间自动起飞（不用按空格），
     * 关着要按着空格，手动起飞等空中那次按下。
     *
     * <p>「兼容 grim 输入检测」：Grim 的起飞检测（ElytraB）要求收到起飞包那一刻输入里的
     * 跳跃键是<b>松开</b>的（否则报 {@code [no release]} 并把起飞包直接取消），起飞包之后
     * 第一个移动包又要看到跳跃键<b>按下</b>（否则报 {@code [no jump]}）。开着时起飞包改成
     * 隔一 tick 发：先空一个 tick 只发「松开」输入包，下一 tick 才发起飞包并让本 tick 的
     * 输入包带「按下跳跃」（由 {@link fish22.modernsupport.mixin.MixinElytraKeyboardInput} 模拟）
     *
     * <p>「落地维持滑翔」：落地后本地强行维持滑翔状态 4 tick（服务端广播的停滑数据在
     * {@link fish22.modernsupport.mixin.MixinSynchedEntityData} 里被改回滑翔），
     * 弹跳离地那一瞬间直接按滑翔运算走（地面这段靠 Grim 对停止滑翔的豁免）。
     *
     * <p>发起飞包那一 tick 本地先 {@code tryToStartFallFlying()} 进滑翔：一是滑翔物理立刻跟上，
     * 二是原版这一 tick 的自动起飞（按下跳跃那一瞬间）会因「已在滑翔」直接跳过，
     * 不会多补一个起飞包（Grim ElytraC 起飞过频）。
     */
    private void tickLegal() {
        grimPressTick = false;

        // 地面 / 条件不满足（水里、骑乘、爬梯、创造飞行…）：输入原样上报，不起飞
        if (mc.player.onGround() || !checkConditions()) return;

        // 已经本地滑翔、又不是刚离地 / 没排队的重启：不补包（合法模式不每 tick 维持滑翔）
        if (mc.player.isFallFlying() && !grimTakeoffPending && !justLeftGround) return;

        boolean keyTakeoff = autoJump.get() || mc.options.keyJump.isDown();
        boolean wantTakeoff = manualTakeoff.get() ? grimTakeoffPending : (keyTakeoff || justLeftGround);
        if (!wantTakeoff) return;

        // 兼容 grim 输入检测：还没轮到「松开」窗口就先排队，这一 tick 只让输入包带「松开」，
        // 下一 tick 窗口开了再发（落地维持滑翔时本地一直是滑翔，靠排队才补得上这一发）
        if (grimOn() && !grimJumpReleased) {
            grimTakeoffPending = true;
            return;
        }

        // 本地起不来（没穿鞘翅 / 水里）：这一 tick 先不起飞，下一 tick 再试
        if (!mc.player.isFallFlying() && !mc.player.tryToStartFallFlying()) return;

        sendStartFlying();
        grimTakeoffPending = false;
        if (grimOn()) grimPressTick = true;
    }

    /** 合法模式：本 tick 的输入包已经发出去，推进状态（TickEvent.Post） */
    private void tickLegalEnd() {
        // 服务端这一 tick 看到的跳跃键 = 本 tick 真正发出去的按键状态
        grimJumpReleased = !lastJumpInput;
        grimPressTick = false;

        // 落地维持滑翔：这一 tick 里被服务器同步清掉的话，收尾再补回来
        forceKeepGlide();

        // 手动起飞（自动跳跃关着）：空中按着跳跃 = 玩家想起飞，等下一个「松开」窗口补上起飞序列
        if (!manualTakeoff.get() || autoJump.get()) return;
        if (!mc.player.onGround() && mc.options.keyJump.isDown()) grimTakeoffPending = true;
    }

    /** 落地维持滑翔：本 tick 是否在强行维持本地滑翔状态 */
    private boolean keepGlideActive() {
        return keepGlide.get() && keepGlideTicks > 0;
    }

    /** 落地维持滑翔：把本地滑翔状态补回来（服务端广播的停滑这几 tick 不生效） */
    private void forceKeepGlide() {
        if (!keepGlideActive() || mc.player.isFallFlying()) return;
        if (!checkConditions()) return;
        mc.player.startFallFlying();
    }

    /** 落地维持滑翔是否生效（给 {@link fish22.modernsupport.mixin.MixinSynchedEntityData} 用） */
    public static boolean isKeepingGlide() {
        return active != null && active.isActive() && active.legal.get() && active.keepGlideActive();
    }

    /**
     * 落地维持滑翔：把服务端同步过来的「停滑」数据改回滑翔（由
     * {@link fish22.modernsupport.mixin.MixinSynchedEntityData} 在应用实体数据前调用）。
     *
     * <p>服务端落地会广播 FALL_FLYING 位清零 + 姿态变站立，光靠每 tick 补状态会有一 tick
     * 的滑翔运算断档（就是掉速度那一下）。这里直接把广播里的这两项改回滑翔，
     * 本地这几 tick 的滑翔状态和滑翔运算都不闪断。
     */
    public static SynchedEntityData.DataValue<?> keepGlideDataValue(SynchedEntityData.DataValue<?> item) {
        if (item.id() == ID_FLAGS) {
            byte data = (Byte) item.value();
            if ((data & (1 << FALL_FLYING_FLAG_INDEX)) == 0) {
                return withValue(item, (byte) (data | (1 << FALL_FLYING_FLAG_INDEX)));
            }
        }
        return item;
    }

    /**
     * 落地维持滑翔：本地姿势要不要按「没在滑翔」算（由
     * {@link fish22.modernsupport.mixin.MixinElytraPoseLock} 在 {@code getDesiredPose} 入口调用）
     *
     * <p>维持窗口里本地滑翔标志是强行留着的（滑翔运算照走），但服务端此刻已经把姿势同步成站立，
     * 本地再按滑翔姿势算就会：视角高度掉到 0.4、碰撞箱只剩 0.6 高，和服务器预测的碰撞箱对不上。
     * 这里把本地姿势钉成站立/潜行（<b>只改姿势</b>，不动滑翔标志、不影响滑翔运算），
     * 和服务器这一 tick 的结算保持一致。
     */
    public static boolean shouldLockPose(Player player) {
        if (player == null || player != MeteorClient.mc.player) return false;
        if (active == null || !active.isActive() || !active.keepGlideActive()) return false;
        return player.isFallFlying();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SynchedEntityData.DataValue<?> withValue(SynchedEntityData.DataValue<?> item, Object value) {
        return new SynchedEntityData.DataValue(item.id(), item.serializer(), value);
    }


    /** 兼容 grim 输入检测的状态清零 */
    private void resetGrimInput() {
        grimPressTick = false;
        grimJumpReleased = true;
        grimTakeoffPending = false;
    }

    /** 兼容 grim 输入检测是否生效：合法模式 + 选项打开 */
    private boolean grimOn() {
        return legal.get() && grimInput.get();
    }

    /** 兼容 grim 输入检测：本 tick 的输入包要按下跳跃（起飞 tick） */
    public static boolean shouldPressJumpInput() {
        return active != null && active.isActive() && active.grimOn() && active.grimPressTick;
    }

    /** 兼容 grim 输入检测：本 tick 的输入包要强制「松开」（其余空中 tick） */
    public static boolean shouldHideJumpInput() {
        if (active == null || !active.isActive() || !active.grimOn()) return false;
        if (active.grimPressTick) return false;
        if (MeteorClient.mc.player == null || MeteorClient.mc.player.onGround()) return false;
        return active.checkConditions();
    }

    /** 兼容 grim 输入检测：记录本 tick 真正会发出去的跳跃键状态 */
    public static void recordJumpInput(boolean jump) {
        lastJumpInput = jump;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            rubberbanded = true;
            mc.player.stopFallFlying();
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (event.packet instanceof ServerboundPlayerCommandPacket command
            && command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            && !sprint.get()) {
            mc.player.setSprinting(true);
        }
    }

    /** 松开自动按下的前进/跳跃键 */
    private void unpress() {
        mc.options.keyUp.setDown(false);
        if (autoJump.get()) mc.options.keyJump.setDown(false);
    }

    private void sendStartFlying() {
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    /** 当前是否满足弹跳飞行的条件（官方 checkConditions） */
    private boolean checkConditions() {
        if (mc.player == null) return false;
        BlockState blockState = mc.player.getInBlockState();
        boolean isClimbing = blockState.is(BlockTags.CLIMBABLE) && !blockState.is(BlockTags.CAN_GLIDE_THROUGH);
        return !mc.player.getAbilities().flying
            && !mc.player.isPassenger()
            && !isClimbing
            && !mc.player.isInWater()
            && !mc.player.hasEffect(MobEffects.LEVITATION);
    }

    private float getYawDirection() {
        return switch (yawLockMode.get()) {
            case None -> mc.player.getYRot();
            case Smart -> Math.round((mc.player.getYRot() + 1f) / 45f) * 45f;
            case Simple -> yaw.get().floatValue();
        };
    }

    /**
     * 本地水平速度（m/s，一 tick = 1/20 秒）
     */
    private double horizontalSpeed() {
        Vec3 velocity = mc.player.getDeltaMovement();
        return Math.hypot(velocity.x, velocity.z) * 20.0;
    }
}
