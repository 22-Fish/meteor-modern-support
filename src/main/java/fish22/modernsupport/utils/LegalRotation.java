/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 22_Fish
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fish22.modernsupport.utils;

import fish22.modernsupport.ModernSupport;
import fish22.modernsupport.mixin.LocalPlayerRotationAccessor;
import fish22.modernsupport.modules.LegalRotationConfig;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 合法转头 API（参考 Baritone freeLook 的 serverRotation 思路）
 *
 * <h3>发包方式（硬编码开关 {@link #SEND_PACKET_IMMEDIATELY}）</h3>
 * <ul>
 *   <li><b>false（默认）</b>：不自己发包，旋转跟着原版这一 tick 的移动包一起发出去，
 *       一 tick 一个移动类包，和原版玩家节奏完全一致。</li>
 *   <li><b>true</b>：rotate() 时立刻发一个只带旋转的包（时序更精确：先旋转、后交互），
 *       但这一 tick 会有两个移动类包（我们的旋转包 + 原版的位置包）。注意 Grim 的
 *       {@code TickTimer} 是带 setback 的检查：一 tick 内超过一个移动类包
 *       （POSITION / POSITION_AND_ROTATION / ROTATION / FLYING 都算）会判定
 *       「发包过快」并拉回，所以这个模式适合在别的服务端上试验。</li>
 * </ul>
 *
 * <h3>玩家身上只有一个朝向值</h3>
 * 它同时被相机渲染、移动运算、发包使用。要「服务器认为你在看别处、相机不动」，
 * 就必须把这两个用途分开：
 *
 * <ul>
 *   <li><b>视角角度</b>：玩家自己的角度。鼠标改它、渲染用它，本 API 全程不碰。</li>
 *   <li><b>真实角度</b>：我们维护的、要让服务器看到的角度。rotate() 设置它，
 *       这一 tick 的移动包就会带着它发出去。</li>
 * </ul>
 *
 * <h3>只在真正读角度的瞬间替换</h3>
 * <ol>
 *   <li><b>移动运算</b>（走 / 跳 / 游泳 / 滑翔的方向）：{@link #pushMoveWindow()}，
 *       由 moveRelative / jumpFromGround / updateFallFlyingMovement 三处调用
 *       （和 Baritone 用的是同一批位置）；</li>
 *   <li><b>移动包构造</b>：sendPosition 前换成「本 tick 移动运算用过的角度」，
 *       发完立刻换回视角角度。</li>
 * </ol>
 *
 * <p>替换窗口都在玩家 tick 内部、且一定在渲染与鼠标处理之前关闭，所以本 API 在 tick 内
 * 任意时刻调用都不会影响视角（不闪、不卡、可自由转）。
 *
 * <h3>优先级（同一 tick 多个模块抢转向）</h3>
 * rotate() 可以多带一个 {@code priority} 参数：<b>数字越大越优先</b>，不写就是「合法转头API配置」
 * 里的「默认优先级」（默认 0）。同一 tick 里第二次调用优先级<b>更低</b> → 整个调用被忽略
 * （返回 false，连回调也不排）；优先级<b>更高</b> → 覆盖上一份；<b>一样</b> → 照旧最后一次生效。
 *
 * <p>优先级跟着「这一份还没随移动包发出去的旋转」走：tick 末尾设置的旋转顺延到下一 tick 的
 * 移动包发出去，这期间它的优先级一直有效（下一 tick 里优先级更低的调用同样会被顶掉）。
 * 旋转随移动包发完（或模式关闭）之后优先级记录清空，下一次调用重新开始仲裁。
 *
 * <h3>时序</h3>
 * <ul>
 *   <li>rotate() 在 tick 前半段（模块的 TickEvent.Pre）：本 tick 的移动运算与移动包
 *       都用这个角度；下一 tick 原版自己会把视角角度发出去，服务器自动恢复。</li>
 *   <li>rotate() 在 tick 末尾（移动包已经发完，例如放置方块）：这次旋转留给
 *       <b>下一 tick 的移动包</b>带出去。需要「先旋转、后交互」的模块把自己的交互包
 *       排到 {@link #runAfterSend(Runnable)}（移动包发出之后），顺序就和原版一样合法。</li>
 * </ul>
 *
 * <h3>可见旋转方向（显示，可选）</h3>
 * 模块「合法转头API配置」里的「可见旋转方向」（默认开启）打开时，渲染时把玩家模型
 * （身体与头）的显示朝向换成真实角度，第三视角下就能看出服务器看到的朝向。
 * 这只是渲染状态上的覆盖（{@link #isDisplayingRealRotation()} /
 * {@link #getDisplayYaw()} / {@link #getDisplayPitch()}），
 * 玩家角度、相机、鼠标输入、发包全部不变。
 */
public class LegalRotation {

    public enum Mode {
        OFF("关闭"),
        NO_MOVE("停止移动"),
        SEVERE("严格"),
        QUIET("静默");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    // ====== 状态 ======

    /**
     * 【硬编码开关】rotate() 时是否立刻自己发一个旋转包。
     *
     * <p>false（默认）：不发，旋转跟着本 tick 的移动包一起发。一 tick 一个移动类包，
     * Grim 的 TickTimer（带 setback）不会报；交互包只要排到 {@link #runAfterSend(Runnable)}
     * 之后，服务器看到的就已经是目标角度，时序一样正确。
     *
     * <p>true：立刻发。时序更精确（旋转包严格早于交互包），但这一 tick 会有两个移动类包
     * （我们的旋转包 + 原版的位置包），Grim 的 TickTimer 会判定发包过快并 setback。
     */
    private static final boolean SEND_PACKET_IMMEDIATELY = false;

    /**
     * 让原版认为「角度变了」用的偏移量（只写进客户端自己的记账字段，不会发到服务器）。
     *
     * <p>原版 {@code LocalPlayer#sendPosition} 只在「当前角度 != 上次发出去的角度」时才在
     * 移动包里带朝向（{@code ServerboundMovePlayerPacket} 的 {@code hasRot}），而
     * 「上次发出去的角度」正是我们上一 tick 写进去的真实角度 —— 真实角度没变化时
     * （同方向输入、相机不动）原版会算出 {@code rot=false}，只发不带朝向的 Pos 包，
     * 服务端那边（{@code packet.getYRot(fallback)}）就沿用上一次收到的朝向。
     * 而服务端记的朝向随时可能被别处覆盖（收到位置纠正包时原版会自动回一发带
     * <b>相机视角</b>的 PosRot；甲飞/冻结自己补的移动包、其它模块单独发的移动包同理），
     * 覆盖之后我们又永远不再发朝向 → 服务端一直按相机视角预测，客户端按真实角度滑翔
     * → 每 tick 偏移被拉回（回弹方向就是相机方向）。把记账字段写成不同值，
     * 原版就会算出 {@code rot=true}，让真实角度每 tick 都重新确认一次（包数不变）。
     */
    private static final float ROTATION_CONFIRM_EPSILON = 1.0E-4f;

    /** 是否有一份「还没随移动包发出去」的真实角度（移动包发出后清零） */
    private static boolean rotating;

    /**
     * 当前这一份旋转的优先级（{@link #priorityActive} 为真时有效）。
     *
     * <p>用来仲裁同一 tick 里多个模块的 rotate()：优先级更低的调用整个忽略，更高的覆盖上一份。
     * 跟「还没发出去的这一份旋转」同生共死（见 {@link #onSendMovementPacketsPost}）。
     */
    private static int activePriority;
    private static boolean priorityActive;

    /** 本 tick 是否旋转过（TickEvent.Post 清空；烟花等 tick 之后才跑的代码要用） */
    private static boolean rotatedThisTick;

    /**
     * 渲染显示是否生效：最近一次 rotate() 的目标角度是不是「此刻的真实朝向」。
     *
     * <p>rotate() 时置真；之后某个 tick 没有再 rotate()，就在那个 tick 末尾清掉
     * （和服务器角度归位的时机一致：下一 tick 的移动包已经把视角角度发回去了）。
     * 只影响渲染显示，见 {@link #isDisplayingRealRotation()}。
     */
    private static boolean displayActive;

    /** 我们维护的真实角度：移动运算与移动包都用它 */
    private static float realYaw;
    private static float realPitch;

    /** 当前模式（静默模式的按键映射要用） */
    private static Mode currentMode = Mode.OFF;

    /** 本 tick 的视角角度（tick 开始时记录，替换窗口复位用） */
    private static float viewYaw;
    private static float viewPitch;

    /** 玩家角度替换窗口（嵌套计数，>0 表示玩家角度已被换成真实角度） */
    private static int windowDepth;

    /** 本 tick 移动运算用到的角度（移动包必须和它一致，否则服务器预测方向对不上） */
    private static boolean movementCaptured;
    private static float moveYaw;
    private static float movePitch;

    /** 本 tick 的移动包是否已经带着一份旋转发出去了（sendPosition 前设、发完清） */
    private static boolean packetApplied;
    private static float packetYaw;
    private static float packetPitch;

    /** 当前 tick 序号（TickEvent.Pre 里自增，用来判断「上一次移动包」是哪一 tick 发的） */
    private static int tickCounter;

    /**
     * 服务器此刻记录的角度：我们上一次随移动包发出去的真实角度。
     *
     * <p>本 tick / 上一 tick 没发过真实角度，说明这一份旋转早就结束了，
     * 服务器那边已经被原版发回去的视角角度覆盖（{@link #getServerYaw()} 会回退到视角角度）。
     */
    private static boolean serverRotationValid;
    private static int serverRotationTick;
    private static float serverYaw;
    private static float serverPitch;

    /** 移动包发出后要执行的动作 */
    private static final List<Runnable> postSendActions = new ArrayList<>();

    // ====== 初始化 ======

    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(LegalRotation.class);
    }

    // ====== API ======

    /** 记录目标旋转（严格模式，默认优先级） */
    public static boolean rotate(double yaw, double pitch) {
        return rotate(yaw, pitch, Mode.SEVERE, defaultPriority(), null);
    }

    /** 记录目标旋转（严格模式，指定优先级） */
    public static boolean rotate(double yaw, double pitch, int priority) {
        return rotate(yaw, pitch, Mode.SEVERE, priority, null);
    }

    /**
     * 记录目标旋转（严格模式，默认优先级），并在这一份旋转随着移动包发出之后执行回调
     * （等价 Meteor Rotations 的回调语义：回调里发交互包时服务器看到的已经是目标角度）。
     */
    public static boolean rotate(double yaw, double pitch, Runnable callback) {
        return rotate(yaw, pitch, Mode.SEVERE, defaultPriority(), callback);
    }

    /** 记录目标旋转（严格模式，指定优先级），并把回调排到这一份旋转发出之后 */
    public static boolean rotate(double yaw, double pitch, int priority, Runnable callback) {
        return rotate(yaw, pitch, Mode.SEVERE, priority, callback);
    }

    /** 记录目标旋转（指定模式，默认优先级） */
    public static boolean rotate(double yaw, double pitch, Mode mode) {
        return rotate(yaw, pitch, mode, defaultPriority(), null);
    }

    /** 记录目标旋转（指定模式 + 优先级） */
    public static boolean rotate(double yaw, double pitch, Mode mode, int priority) {
        return rotate(yaw, pitch, mode, priority, null);
    }

    /** 记录目标旋转（指定模式，默认优先级），并把回调排到这一份旋转发出之后 */
    public static boolean rotate(double yaw, double pitch, Mode mode, Runnable callback) {
        return rotate(yaw, pitch, mode, defaultPriority(), callback);
    }

    /**
     * 记录真实角度。这一 tick 的移动运算与移动包随后都会用这个角度；
     * 若调用发生在移动包之后（tick 末尾），则顺延到下一 tick 的移动包。
     *
     * <p><b>优先级</b>：同一 tick 里可能有多个模块都要转视角。这次调用的优先级比「当前这一份
     * 还没随移动包发出去的旋转」低 → 整个调用被忽略；更高 → 覆盖上一份；一样 → 照旧最后一次生效。
     * 被忽略的调用连回调一起不算数（调用方可以看返回值决定后面那件事还做不做）。
     *
     * @return true = 这份旋转被采纳；false = 被优先级更高的那一份顶掉，本次调用没有生效
     */
    public static boolean rotate(double yaw, double pitch, Mode mode, int priority, Runnable callback) {
        if (mode == Mode.OFF || mc.player == null) return false;

        // 优先级仲裁：本 tick（或顺延到本 tick）已经有一份优先级更高的旋转等着发出去 → 整份忽略
        if (priorityActive && priority < activePriority) return false;

        activePriority = priority;
        priorityActive = true;

        realYaw = (float) yaw;
        realPitch = (float) pitch;
        currentMode = mode;
        rotating = true;
        rotatedThisTick = true;
        displayActive = true;

        // 立即发包模式：先把这一份角度推给服务器（时序精确，但一 tick 两个移动类包）
        if (SEND_PACKET_IMMEDIATELY) {
            sendRotationPacket(realYaw, realPitch);
        }

        // 正在移动窗口内：立刻生效，后面还没跑的移动运算用新角度
        if (windowDepth > 0) applyReal();

        // 回调排到「移动包发出之后」：那时服务器看到的已经是这一份角度
        if (callback != null) runAfterSend(callback);

        return true;
    }

    public static boolean rotateWithMode(double yaw, double pitch, Mode mode) {
        return rotate(yaw, pitch, mode);
    }

    /**
     * 没有单独设优先级的调用用的优先级：「合法转头API配置」里的「默认优先级」（默认 0）。
     *
     * <p>鞘翅飞行这些借用本 API、但自己没有优先级设置项的功能走的就是这个值。
     */
    public static int defaultPriority() {
        return LegalRotationConfig.getDefaultPriority();
    }

    /**
     * 微调真实角度（不改视角）。用于「反 tick 跳过」这类需要让服务器看到
     * 角度有变化的场景；视角角度本身永远不动。
     */
    public static void nudgeRealYaw(float delta) {
        if (!rotating) return;
        realYaw += delta;
        if (windowDepth > 0) applyReal();
    }

    /** 本 tick 是否在伪造朝向 */
    public static boolean isRotating() {
        return rotating && currentMode != Mode.OFF;
    }

    /** 我们维护的真实角度（静默模式按键映射用） */
    public static float getRealYaw() {
        return realYaw;
    }

    public static float getRealPitch() {
        return realPitch;
    }

    /**
     * 服务器此刻记录的角度（偏航）。
     *
     * <p>本 tick 或上一 tick 发过真实角度，就是那一份真实角度（服务器只知道我们发出去的包）；
     * 否则是玩家自己的视角角度（原版自己把视角角度发回去了）。
     *
     * <p>用途：合法角度量化（相对「服务器当前角度」差整数格鼠标灵敏度增量）、
     * 避免发出和上一次完全相同的朝向。见 {@link LegalPlace#compute}。
     */
    public static float getServerYaw() {
        if (mc.player == null) return 0.0f;
        if (!serverRotationValid || tickCounter - serverRotationTick > 1) return mc.player.getYRot();
        return serverYaw;
    }

    /** 服务器此刻记录的角度（俯仰），语义同 {@link #getServerYaw()} */
    public static float getServerPitch() {
        if (mc.player == null) return 0.0f;
        if (!serverRotationValid || tickCounter - serverRotationTick > 1) return mc.player.getXRot();
        return serverPitch;
    }

    public static Mode getMode() {
        return currentMode;
    }

    /** 服务器此刻认为我们看的方向；本 tick 没旋转时就是玩家自己的视角 */
    public static Vec3 getServerLook(Entity entity) {
        if (entity == null) return Vec3.ZERO;
        if (!isRotating() && !rotatedThisTick) return entity.getLookAngle();
        return entity.calculateViewVector(realPitch, realYaw);
    }

    /** 移动包发出后执行的动作（队列化，一 tick 内的全部执行） */
    public static void runAfterSend(Runnable action) {
        if (action != null) postSendActions.add(action);
    }

    // ====== 可见旋转方向（渲染显示） ======

    /**
     * 渲染用：现在要不要把玩家模型的显示朝向改成真实角度。
     *
     * <p>条件：模块「合法转头API配置」里的「可见旋转方向」开启（默认开启），
     * 且这一 tick（或刚过去的那一 tick）真的旋转过。
     *
     * <p>调用方只改渲染状态（{@link fish22.modernsupport.mixin.MixinVisibleRotation}），
     * 玩家角度、相机、鼠标输入、发包全都不受影响；第一人称本来就不渲染玩家模型。
     */
    public static boolean isDisplayingRealRotation() {
        return displayActive && LegalRotationConfig.isVisibleRotation();
    }

    /** 渲染显示用的偏航（真实角度） */
    public static float getDisplayYaw() {
        return realYaw;
    }

    /** 渲染显示用的俯仰（真实角度） */
    public static float getDisplayPitch() {
        return realPitch;
    }

    // ====== 移动运算窗口（供 mixin 调用） ======

    /**
     * 移动运算开始：把玩家角度临时换成真实角度，并记录本 tick 移动运算用到的角度。
     * 本 tick 没有旋转时什么都不做（完全原版，零干扰）。
     */
    public static void pushMoveWindow() {
        if (!isRotating() || mc.player == null) return;

        movementCaptured = true;
        moveYaw = realYaw;
        movePitch = realPitch;

        windowDepth++;
        applyReal();
    }

    /** 移动运算结束：立刻换回视角角度 */
    public static void popMoveWindow() {
        if (windowDepth == 0) return;
        windowDepth--;
        if (windowDepth == 0 && mc.player != null) {
            mc.player.setYRot(viewYaw);
            mc.player.setXRot(viewPitch);
        }
    }

    // ====== 内部 ======

    private static void applyReal() {
        if (mc.player == null) return;
        mc.player.setYRot(realYaw);
        mc.player.setXRot(realPitch);
    }

    /** 发出一个只带旋转的移动包（和 Meteor Rotations 用的是同一种包） */
    private static void sendRotationPacket(float yaw, float pitch) {
        if (mc.player == null || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
            yaw,
            pitch,
            mc.player.onGround(),
            mc.player.horizontalCollision
        ));
        // 告诉原版「这个角度已经发出去了」：下一 tick 它才会自己把视角角度发出去恢复正常
        ((LocalPlayerRotationAccessor) mc.player).setLastSentYaw(yaw);
        ((LocalPlayerRotationAccessor) mc.player).setLastSentPitch(pitch);
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            ModernSupport.LOG.error("[合法转头] 回调执行异常", e);
        }
    }

    private static void runPostSendActions() {
        if (postSendActions.isEmpty()) return;

        List<Runnable> actions = new ArrayList<>(postSendActions);
        postSendActions.clear();
        for (Runnable action : actions) {
            runSafely(action);
        }
    }

    /** 清空全部状态（换世界 / 玩家为空时调用） */
    public static void reset() {
        rotating = false;
        priorityActive = false;
        activePriority = 0;
        rotatedThisTick = false;
        displayActive = false;
        movementCaptured = false;
        packetApplied = false;
        serverRotationValid = false;
        currentMode = Mode.OFF;
        postSendActions.clear();

        if (windowDepth > 0 && mc.player != null) {
            mc.player.setYRot(viewYaw);
            mc.player.setXRot(viewPitch);
        }
        windowDepth = 0;
    }

    // ====== 事件 ======

    /**
     * tick 开始：记下本 tick 的视角角度，并清空「本 tick 移动运算用到的角度」。
     * 优先级高于所有模块，保证模块在 TickEvent.Pre 里 rotate() 时环境是干净的。
     *
     * <p>注意这里<b>不</b>清空 rotating：tick 末尾（移动包之后）设置的旋转要留给
     * 下一 tick 的移动包带出去；rotating 在移动包发出后由 Post 清空。
     */
    @EventHandler(priority = EventPriority.HIGHEST + 100)
    private static void onTickPre(TickEvent.Pre event) {
        movementCaptured = false;
        tickCounter++;

        if (mc.player == null) {
            reset();
            return;
        }

        // 上一 tick 若还有没关掉的替换窗口，先强制关掉（保险）
        while (windowDepth > 0) popMoveWindow();

        viewYaw = mc.player.getYRot();
        viewPitch = mc.player.getXRot();
    }

    /**
     * 移动包发出前：换成「本 tick 移动运算用过的角度」，让服务器收到的朝向
     * 和客户端算出来的移动方向一致（这一份旋转就跟着本 tick 的移动包发出去）。
     */
    @EventHandler
    private static void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (mc.player == null || !isRotating()) return;

        // 移动窗口必须已经关闭（正常情况一定关了，这里兜底）
        while (windowDepth > 0) popMoveWindow();

        packetApplied = true;
        packetYaw = movementCaptured ? moveYaw : realYaw;
        packetPitch = movementCaptured ? movePitch : realPitch;
        mc.player.setYRot(packetYaw);
        mc.player.setXRot(packetPitch);

        // 记下「服务器接下来会收到的角度」，供 getServerYaw/getServerPitch 查询
        serverYaw = packetYaw;
        serverPitch = packetPitch;
        serverRotationTick = tickCounter;
        serverRotationValid = true;

        // 让这一份真实角度真的随本 tick 的移动包发出去（hasRot = true），
        // 理由见 ROTATION_CONFIRM_EPSILON：服务端那边记的朝向可能已经被相机视角覆盖，
        // 只靠「角度变了才发」的话就永远补不回来。
        // 「每次调用都设置朝向」关掉时不动记账字段：退回原版逻辑，朝向不一样才带朝向发包。
        LocalPlayerRotationAccessor sent = (LocalPlayerRotationAccessor) mc.player;
        if (LegalRotationConfig.isAlwaysSetRotation()) {
            sent.setLastSentYaw(packetYaw + ROTATION_CONFIRM_EPSILON);
            sent.setLastSentPitch(packetPitch + ROTATION_CONFIRM_EPSILON);
        }
    }

    /**
     * 移动包发出后：先跑队列动作（它们的交互包要排在移动包之后），
     * 再把玩家角度换回视角角度，最后清掉这一份旋转。
     */
    @EventHandler
    private static void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        runPostSendActions();

        boolean applied = packetApplied;
        packetApplied = false;

        if (applied && mc.player != null) {
            mc.player.setYRot(viewYaw);
            mc.player.setXRot(viewPitch);
        }

        // 这一份旋转有没有随着移动包发出去？没有（例如刚在回调里又 rotate 过、或这一 tick
        // 根本没走到发包）就留给下一 tick；发出去了就清掉，下一 tick 恢复视角角度。
        // 优先级记录跟这一份旋转同生共死：旋转结束了，下一次 rotate() 重新开始仲裁。
        boolean sent = applied && realYaw == packetYaw && realPitch == packetPitch;
        if (sent || currentMode == Mode.OFF) {
            rotating = false;
            priorityActive = false;
        }
    }

    /** tick 结束：清掉「本 tick 旋转过」标记（早于它的实体 tick 已经用完了） */
    @EventHandler(priority = EventPriority.LOWEST)
    private static void onTickPost(TickEvent.Post event) {
        // 这一 tick 没有再 rotate()：显示角度随之失效
        // （服务器也是这一 tick 之后回到视角角度，两边时机一致）
        if (!rotatedThisTick) displayActive = false;
        rotatedThisTick = false;
    }
}
