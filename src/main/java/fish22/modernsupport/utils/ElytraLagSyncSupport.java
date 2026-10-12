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
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 甲飞的「起飞限频绕过」（史莱姆 {@code ElytraExtra} 的 max-delay-ticks / 飞行状态同步延后刻数）
 *
 * <p>照搬史莱姆 {@code onArmorGlideDelay} + {@code onPacketPing}：起飞包发出之后
 * {@code 绕过窗口刻数} tick 内，收到「服务端说我们不在滑翔」的自身同步包就把入站包整段扣住，
 * 并把这一轮 transaction 的 pong 压到窗口结束才补发
 *
 * <ol>
 *   <li>起飞包发出（记窗口锚点，史莱姆的 {@code lastStartGlidingTick}）</li>
 *   <li>窗口内收到「服务端说我们不在滑翔」的自身同步包 → 开始整段扣包
 *       （史莱姆的 {@code currentDelaying}），并记住最近那一发 transaction ping</li>
 *   <li>扣包期间客户端回出去的 pong 一律不发（史莱姆的 {@code onPacketPing}）
 *       Grim 的滑翔状态挂在这条 transaction 上（{@code PacketSelfMetadataListener} → {@code addRealTimeTask}），
 *       pong 不出去它就一直认我们在滑翔，起飞/停滑的节奏因此被压下来</li>
 *   <li>刻数一到：把压住的那一发 ping 补处理一次（客户端这时才回 ack，反作弊那边此刻才推进），
 *       再按原顺序放回扣下的包 —— 放包这一下就是换甲时机（{@link #consumeTakeoffRequest()}）</li>
 * </ol>
 *
 * <p>还有史莱姆 {@code handleEntityDataUpdate} 的另一半，缺了它这套是废的：
 * <b>本地也不认这个「停滑」位</b>（{@link #keepGlideDataValue()} 把它改回 1，
 * 史莱姆那段注释写着 may cause fake gliding !!! must be careful）。
 * 不挡的话窗口一结束本地滑翔位就掉，客户端改用普通空中运算，服务端那边却已经认滑翔 ——
 * 两边各算各的，就是被位置纠正的那个位移差
 *
 * <p>只有会话类包例外（keep-alive 直接放行、断开/复活/重配置/传送到来时把扣下的全放掉）
 */
public final class ElytraLagSyncSupport {

    // ====== 设置引用（鞘翅飞行模块创建设置后注入） ======

    /** 「起飞限频绕过」*/
    public static Setting<Boolean> enabled;

    /** 「绕过窗口刻数」：起飞包之后这么多 tick 内的入站包扣住*/
    public static Setting<Integer> syncDelayTicks;

    // ====== 常量 ======

    /** 实体同步数据里「共享标志位」的索引（原版 {@code Entity#DATA_SHARED_FLAGS_ID}） */
    private static final int ID_FLAGS = 0;

    /** 共享标志位里「滑翔」那一位的位号（原版 {@code Entity#FALL_FLYING_FLAG_INDEX}） */
    private static final int FALL_FLYING_FLAG_INDEX = 7;

    /** 「绕过窗口刻数」没注入时的兜底值 */
    private static final int DEFAULT_SYNC_DELAY_TICKS = 5;

    // ====== 状态 ======

    /** 扣下还没放行的入站包（按到达顺序） */
    private static final Queue<Held> heldPackets = new ConcurrentLinkedQueue<>();

    /** 扣下的包 + 它原来是从哪条连接来的（放包时跟着同一条连接处理） */
    private record Held(Packet<?> packet, Connection connection) {}

    /** 正在扣包（史莱姆的 {@code currentDelaying}） */
    private static volatile boolean delaying;

    /** 正在放包：自己放出来的包不再走扣包判断 */
    private static volatile boolean replaying;

    /** 自己数的 tick（主线程） */
    private static volatile int tick;

    /** 最近一发起飞包发出的 tick（窗口锚点，史莱姆的 {@code lastStartGlidingTick}） */
    private static volatile int lastStartGlidingTick = Integer.MIN_VALUE / 2;

    /** 最近一发收到的 transaction ping（史莱姆的 {@code lastTransactionRecv} 那一手） */
    private static volatile Packet<?> lastPingPacket;
    private static volatile Connection lastPingConnection;

    /** 本轮触发那一刻的那一发 ping（史莱姆注入同 id ping 用的就是它；放包时补的就是这一发） */
    private static volatile Packet<?> delayedPingPacket;
    private static volatile Connection delayedPingConnection;

    /** 压 pong 中：扣包窗口里客户端回出去的 transaction pong 一律不发（史莱姆的 {@code onPacketPing}） */
    private static volatile boolean suppressPong;

    /** 自己补发的包不参与压 pong 判断 */
    private static volatile boolean bypassPongSuppress;

    /** 服务端说我们停滑了 → 该换一次甲了（史莱姆在 {@code handleEntityDataUpdate} 里当场换） */
    private static volatile boolean takeoffRequested;

    private ElytraLagSyncSupport() {
    }

    // ====== 状态查询 ======

    /**
     * 「起飞限频绕过」现在生不生效：开关开着 + 甲飞模式是「Grim模式」+ 模块真的在跑
     *
     * <p>它接的就是 Grim模式 的节奏（服务端说停滑才换甲），所以只配 Grim模式 用
     */
    public static boolean isEnabled() {
        return enabled != null && enabled.get()
            && ElytraFlySupport.armorMode != null
            && ElytraFlySupport.armorMode.get() == ElytraFlySupport.ArmorMode.Lazy
            && ElytraFlySupport.isArmorFlyEnabled();
    }

    /**
     * 本地是不是「不认服务端停滑广播」的状态（{@code MixinSynchedEntityData} 用）
     *
     * <p>史莱姆 {@code handleEntityDataUpdate} 就是把同步数据里的滑翔位改回 1：客户端全程滑翔，
     * 换甲时机由那一包触发。照搬
     *
     * <p>落地（本地 onGround）不算：人已经站在地上了，服务端那边「停滑」是正常结算，
     * 再把这个位撑住就变成落地还挂着滑翔，接着就是位置纠正
     */
    public static boolean isKeepingGlide() {
        return isEnabled() && mc.player != null && mc.player.isFallFlying() && !mc.player.onGround();
    }

    /**
     * 渲染用：本地这个被撑住的假滑翔位要不要把人物摆成趴着的滑翔姿势
     * （由 {@link fish22.modernsupport.mixin.MixinElytraGlideAnimation} 在
     * {@code AvatarRenderer#extractFlightData} 入口调用）
     *
     * <p>只在「本地滑翔位是被我们撑着的」时候成立（{@link #isKeepingGlide()}）；
     * 真滑翔（本地标志来自服务器同步）不干预，趴着是对的
     */
    public static boolean shouldSuppressGlideAnimation(Entity entity) {
        return entity != null && entity == mc.player && isKeepingGlide();
    }

    /** 把同步数据里的滑翔位改回 1（史莱姆 {@code handleEntityDataUpdate} 那一手） */
    public static SynchedEntityData.DataValue<?> keepGlideDataValue(SynchedEntityData.DataValue<?> item) {
        if (item.id() != ID_FLAGS) return item;
        if (!(item.value() instanceof Byte data)) return item;
        if ((data & (1 << FALL_FLYING_FLAG_INDEX)) != 0) return item;
        return withValue(item, (byte) (data | (1 << FALL_FLYING_FLAG_INDEX)));
    }

    /**
     * 取走「服务端说停滑了，该换一次甲了」（内核在 Grim模式 那一支里消费）
     */
    public static boolean consumeTakeoffRequest() {
        if (!takeoffRequested) return false;
        takeoffRequested = false;
        return true;
    }

    // ====== 生命周期 ======

    /** 每 tick（主线程）：窗口一过就补 pong、把扣下的包按原顺序放回去；不在用就把状态清干净 */
    public static void onPreTick() {
        tick++;

        if (!isEnabled()) {
            if (delaying || !heldPackets.isEmpty() || suppressPong) reset();
            return;
        }
        if (mc.player == null) return;

        // 刻数满了：先停扣包，补一发压住的 pong（「停滑」在反作弊那边此刻才推进），
        // 再按原顺序把扣下的包补处理 —— 那一发停滑这时才被处理，补处理时把「该换甲了」
        // 记下来，内核在同一 tick 稍后的 armorTick 里换一次甲
        if (delaying && !inWindow()) {
            delaying = false;
            suppressPong = false;
            replyDelayedPing();
            flushHeld();
        }
    }

    /** 模块开启 / 关掉：清状态并把还扣着的包放掉（不能吞包，keep-alive 之类还等着处理） */
    public static void reset() {
        delaying = false;
        suppressPong = false;
        replyDelayedPing();
        flushHeld();
        takeoffRequested = false;
        lastStartGlidingTick = Integer.MIN_VALUE / 2;
        lastPingPacket = null;
        lastPingConnection = null;
        delayedPingPacket = null;
        delayedPingConnection = null;
    }

    /** 发出起飞包：记下窗口锚点（史莱姆 {@code onPlayerCommand} 记 START_FALL_FLYING 那一手） */
    public static void onStartFlying() {
        if (!isEnabled()) return;
        lastStartGlidingTick = tick;
    }

    // ====== 收包（扣包就发生在这一步） ======

    /**
     * 收包监听：在 {@link ElytraFlySupport#onPacketReceive} 最前面调用
     *
     * <p>窗口内看到「服务端说我们不在滑翔」的自身同步包就开始整段扣包，
     * 一直到窗口结束；窗口外才到的停滑同步不扣，内核当 tick 就地换甲（和没开这个功能一样）
     */
    public static void onReceive(PacketEvent.Receive event) {
        // 自己放出来的包（窗口结束时的补处理）：这一发就是「服务端说停滑」→ 换甲信号
        if (replaying) {
            if (isSelfStopGliding(event.packet)) takeoffRequested = true;
            return;
        }

        if (!isEnabled()) {
            if (delaying || !heldPackets.isEmpty() || suppressPong) reset();
            return;
        }
        if (mc.player == null) return;

        // 史莱姆 onArmorGlideDelay 的第一行：记下最近一发收到的 transaction ping
        //（「停滑」这一下就挂在它的 transaction 上，窗口结束时补的就是它）
        if (event.packet instanceof ClientboundPingPacket) {
            lastPingPacket = event.packet;
            lastPingConnection = event.connection;
        }

        // 本轮已经在扣包：整段扣下
        if (delaying) {
            // 断开 / 复活 / 重配置 / 传送：会话状态要变了，扣下的全放掉，这一包照常处理
            if (isSessionReset(event.packet)) {
                reset();
                return;
            }
            // keep-alive 不能等，直接放行（窗口继续，它不属于要被压状态的那条链）
            if (event.packet instanceof ClientboundKeepAlivePacket) return;

            hold(event);
            return;
        }

        // 窗口内收到「服务端说我们不在滑翔」→ 开始整段扣包（这一发也在里面），
        // 同时把这一轮的 pong 压住，到窗口结束才补发（史莱姆 currentDelaying）
        if (isArmorGliding() && inWindow() && isSelfStopGliding(event.packet)) {
            delaying = true;
            suppressPong = true;
            delayedPingPacket = lastPingPacket;
            delayedPingConnection = lastPingConnection;
            hold(event);
        }
    }

    /**
     * 发包监听：在 {@link ElytraFlySupport#onPacketSend} 最前面调用
     *
     * <p>两件事，都是史莱姆那套的前提：
     * <ul>
     *   <li>任何发出去的起飞包都刷窗口锚点（史莱姆在 {@code PlayerStateManager} 里监听 c2s 的
     *       {@code START_FALL_FLYING}，原版客户端自己补的那一发也算）</li>
     *   <li>扣包窗口里客户端回出去的 transaction pong 一律不发（史莱姆 {@code onPacketPing}
     *       在 pre-handle 点取消那一发 ping，这里改在出口拦同一发 pong，效果一样），
     *       窗口结束由 {@link #replyDelayedPing()} 补出去</li>
     * </ul>
     */
    public static void onSend(PacketEvent.Send event) {
        if (!isEnabled()) return;

        if (event.packet instanceof ServerboundPlayerCommandPacket cmd
            && cmd.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
            lastStartGlidingTick = tick;
            return;
        }

        if (suppressPong && !bypassPongSuppress && event.packet instanceof ServerboundPongPacket) {
            event.cancel();
        }
    }

    // ====== 内部 ======

    /** 窗口长度（tick）：起飞包之后这么多 tick 内的入站包扣住 */
    private static int delayTicks() {
        return syncDelayTicks == null ? DEFAULT_SYNC_DELAY_TICKS : Math.max(0, syncDelayTicks.get());
    }

    /** 还在「起飞包之后」的窗口里（史莱姆：{@code tick - lastStartGlidingTick < maxDelayTicks}） */
    private static boolean inWindow() {
        return tick - lastStartGlidingTick < delayTicks();
    }

    /**
     * 史莱姆 {@code onArmorGlideDelay} 的第一道门 {@code isCurrentArmorGliding()}：
     * 甲飞真的在维持滑翔（服务端此刻认我们滑翔）才配这一手
     *
     * <p>不这道门的话，锚点过期、人已落地之类的时候也会开始扣包，白白把客户端掐住
     */
    private static boolean isArmorGliding() {
        if (!ElytraFlySupport.isArmorFlyEnabled() || mc.player == null) return false;
        if (mc.player.onGround() || mc.player.isPassenger() || mc.player.isSpectator()) return false;
        return mc.player.isFallFlying() || ElytraFlySupport.serverSaysGliding();
    }

    /** 扣下这一包：取消本 tick 的处理，塞进队列等窗口过了再放 */
    private static void hold(PacketEvent.Receive event) {
        event.cancel();
        heldPackets.add(new Held(event.packet, event.connection));
    }

    /**
     * 放包：按到达顺序把扣下的包补处理一遍
     *
     * <p>先重发一次收包事件（别的模块照常看到这一包），没被取消就交给原版处理。
     * 必须在主线程调用：原版客户端的收包处理自己会检查线程（{@code PacketUtils#ensureRunningOnSameThread}），
     * 在这里调用就是「这一包刚到」
     */
    private static void flushHeld() {
        if (heldPackets.isEmpty()) return;

        // 原版客户端的收包处理要求主线程：还没到主线程就排到主线程去放
        if (!mc.isSameThread()) {
            mc.execute(ElytraLagSyncSupport::flushHeld);
            return;
        }

        List<Held> flush = new ArrayList<>();
        for (Held held = heldPackets.poll(); held != null; held = heldPackets.poll()) {
            flush.add(held);
        }

        for (Held held : flush) {
            Packet<?> packet = held.packet();
            Connection connection = held.connection();
            if (connection == null || !connection.isConnected()) continue;

            replaying = true;
            try {
                PacketEvent.Receive event = new PacketEvent.Receive(packet, connection);
                MeteorClient.EVENT_BUS.post(event);
                if (!event.isCancelled()) handleRaw(packet, connection);
            } catch (Throwable t) {
                ModernSupport.LOG.warn("[甲飞] 起飞限频绕过放包失败: {}",
                    packet.getClass().getSimpleName(), t);
            } finally {
                replaying = false;
            }
        }
    }

    /**
     * 把压住的那一发 ping 补处理一次：客户端这时才发出它对应的 pong
     *
     * <p>史莱姆的做法是往入站队列里注入一发同 id 的 {@code CommonPingS2CPacket}；这里直接
     * 再走一遍那一发原 ping，客户端同样会补出一发同 id 的 pong（反作弊那边「停滑」此刻才推进）
     *
     * <p>故意不走 {@code PacketEvent.Receive}：这是我们自己补的，别的模块不该再看到一次
     */
    private static void replyDelayedPing() {
        if (!mc.isSameThread()) {
            mc.execute(ElytraLagSyncSupport::replyDelayedPing);
            return;
        }

        Packet<?> packet = delayedPingPacket;
        Connection connection = delayedPingConnection;
        delayedPingPacket = null;
        delayedPingConnection = null;
        if (packet == null || connection == null || !connection.isConnected()) return;

        bypassPongSuppress = true;
        try {
            handleRaw(packet, connection);
        } catch (Throwable t) {
            ModernSupport.LOG.warn("[甲飞] 起飞限频绕过补 pong 失败", t);
        } finally {
            bypassPongSuppress = false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void handleRaw(Packet<?> packet, Connection connection) {
        PacketListener listener = connection.getPacketListener();
        if (listener == null) return;
        ((Packet) packet).handle(listener);
    }

    /**
     * 这一包是不是「服务端说我们不在滑翔」的自身实体同步
     *
     * <p>只认自身实体的共享标志位、滑翔位被清零。服务端会把这种同步包打在 bundle 里发，
     * 所以 bundle 也拆开看
     */
    private static boolean isSelfStopGliding(Packet<?> packet) {
        if (mc.player == null) return false;

        if (packet instanceof ClientboundSetEntityDataPacket update) {
            if (update.id() != mc.player.getId()) return false;
            for (SynchedEntityData.DataValue<?> value : update.packedItems()) {
                if (value.id() == ID_FLAGS && value.value() instanceof Byte data) {
                    return (data & (1 << FALL_FLYING_FLAG_INDEX)) == 0;
                }
            }
            return false;
        }

        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                if (isSelfStopGliding(sub)) return true;
            }
        }
        return false;
    }

    /** 会话状态要变的包：扣着它们会把客户端 / 连接卡死 */
    private static boolean isSessionReset(Packet<?> packet) {
        return packet instanceof ClientboundDisconnectPacket
            || packet instanceof ClientboundTransferPacket
            || packet instanceof ClientboundStartConfigurationPacket
            || packet instanceof ClientboundRespawnPacket;
    }

    /** 复制一份同步数据项、把值换掉（原版 {@code DataValue} 是 record，只能新建） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SynchedEntityData.DataValue<?> withValue(SynchedEntityData.DataValue<?> item, Object value) {
        return new SynchedEntityData.DataValue(item.id(), item.serializer(), value);
    }
}
