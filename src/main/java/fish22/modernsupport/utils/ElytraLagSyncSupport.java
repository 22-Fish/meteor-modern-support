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
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 「Grim Lag」甲飞的频率限制层（史莱姆 {@code ElytraExtra} 的 max-delay-ticks / 延后同步）
 *
 * <p><b>内核不是这里，是 Grim 模式本身</b>：{@link ElytraFlySupport} 的 Grim Lag 分支照抄
 * Grim 模式（本地还在滑翔就什么都不做，服务端说停滑了才做一次「换鞘翅 → 起飞 → 换回胸甲」）
 *
 * <p>还有一件同样是史莱姆那套的前提：<b>本地全程认自己在滑翔</b>
 * （史莱姆 {@code handleEntityDataUpdate} 把同步数据里的滑翔位改回 1，注释里写着
 * 「may cause fake gliding !!! must be careful」）。本地滑翔位一旦掉假，客户端就退回普通空中
 * 运算，而服务端 / 反作弊那边（被我们扣住的 transaction）还认滑翔 —— 两端每 tick 位移都对不上，
 * 表现就是「每 tick 被拉回、位置钉在原地、最后被原版当悬浮踢掉」。
 * 起飞时本地置一次滑翔（{@link #onStartFlying()}），此后停滑同步包一律改回 1（{@link #keepGlideDataValue}）
 *
 * <p>这里只负责把「服务端说停滑」这个信号延后几 tick 再交给内核：
 *
 * <ol>
 *   <li><b>扣住停滑同步包</b>：服务端说我们不在滑翔，是随自身实体同步包来的（滑翔位清零）。
 *       扣下它，本地 {@code isFallFlying()} 就还是真，内核（Grim 模式）自然不换甲；</li>
 *   <li><b>扣住 transaction ping</b>：Grim 把这条状态挂在 transaction 上
 *       （{@code PacketSelfMetadataListener} → {@code addRealTimeTask}），
 *       要等这一发 ping 的 pong 从我们这边绕回去才真正应用。扣下 ping，pong 就发不出去，
 *       反作弊那边这几 tick 也一直认我们在滑翔 —— 我们本地照旧按滑翔运算移动，
 *       两边不会分叉；</li>
 *   <li><b>窗口到了按原顺序放包</b>：放包这一下就是史莱姆的换甲时机
 *       （{@code thisTickTickStartFallFly} 那一手），内核在下一 tick 换一次甲，
 *       起飞包重新支起下一个窗口。本地滑翔位全程由 {@link #keepGlideDataValue} 撑着，不掉假。</li>
 * </ol>
 *
 * <p>效果就是新反作弊抓不到的东西：服务端看到的「起飞 → 停滑」节奏从每 tick 一次
 * 降到每「延后同步刻数」tick 一次（旧版那套「换甲太高频会被回弹」正是栽在频率上）
 *
 * <p>只扣这两类包，其它入站包照常处理，所以不会有史莱姆整段扣包那种卡顿 / 回拉
 */
public final class ElytraLagSyncSupport {

    // ====== 设置引用（鞘翅飞行模块创建设置后注入） ======

    /**
     * 「启用延后同步」：史莱姆那个 max-delay-ticks 是「可选项」（默认不启用，值是 5）
     *
     * <p>对应史莱姆 {@code armorGlideMaxDelayTicks} 的 {@code present} 位：关掉就完全不动入站包，
     * 换甲节奏按 Grim 模式本身走
     */
    public static Setting<Boolean> enabled;

    /** 「延后同步刻数」：起飞包之后这么多 tick 内，停滑同步包与 transaction ping 一律扣下（史莱姆的 max-delay-ticks） */
    public static Setting<Integer> syncDelayTicks;

    /** 「反踢出」：发过起飞包的那一 tick 强制发一发带完整坐标的移动包（史莱姆的 anti-kick / resyncPos） */
    public static Setting<Boolean> antiKick;

    /** 「补 pong」：起飞包后补一发哨兵 pong，放包时再补一发被扣 ping 的同 id pong（史莱姆的 GrimBadPacket 修复） */
    public static Setting<Boolean> pongFix;

    // ====== 常量 ======

    /** 实体同步数据里「共享标志位」的索引（原版 {@code Entity#DATA_SHARED_FLAGS_ID}） */
    private static final int ID_FLAGS = 0;

    /** 共享标志位里「滑翔」那一位的位号（原版 {@code Entity#FALL_FLYING_FLAG_INDEX}） */
    private static final int FALL_FLYING_FLAG_INDEX = 7;

    /** 起飞包后补的那发 pong 用的哨兵 id（史莱姆的 {@code Integer.MIN_VALUE}） */
    private static final int BAD_PACKET_PONG_ID = Integer.MIN_VALUE;

    /** 「延后同步刻数」没注入时的兜底值（史莱姆默认 5） */
    private static final int DEFAULT_SYNC_DELAY_TICKS = 5;

    /** 兜底重试间隔（tick）：起飞包之后这么久都没等到「服务端说停滑」，就按「起飞包没被认」重试 */
    private static final int STALL_RETRY_TICKS = 20;

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

    /** 最近一发收到的 transaction ping（「补 pong」用，史莱姆的 {@code lastTransactionRecv} 那一手） */
    private static volatile Packet<?> lastPingPacket;
    private static volatile Connection lastPingConnection;

    /** 反踢出：本 tick 的移动包要带完整坐标（史莱姆的 {@code resyncPos}） */
    private static volatile boolean forcePositionPacket;

    /** 服务端说我们停滑了 → 该换一次甲了（史莱姆在同步包处理里当场换甲） */
    private static volatile boolean takeoffRequested;

    /** 这一轮起飞之后有没有收到过「服务端说停滑」（一直收不到 = 起飞包没被服务端认，得重试） */
    private static volatile boolean stopGlideSinceStart = true;

    private ElytraLagSyncSupport() {
    }

    // ====== 状态查询 ======

    /** 甲飞模式是不是「Grim Lag」且模块真的在跑 */
    public static boolean isGrimLag() {
        return ElytraFlySupport.armorMode != null
            && ElytraFlySupport.armorMode.get() == ElytraFlySupport.ArmorMode.GrimLag
            && ElytraFlySupport.isArmorFlyEnabled();
    }

    /** 现在是不是「延后同步」窗口内（Grim Lag 的换甲间隔就是它，内核靠它压频率） */
    public static boolean isDelayWindow() {
        return isGrimLag() && delayTicks() > 0 && inWindow();
    }

    /** 反踢出：本 tick 的移动包要不要强制带完整坐标 */
    public static boolean shouldForcePositionPacket() {
        return forcePositionPacket;
    }

    /** 兜底：起飞包发出去之后一直没等到「服务端说停滑」，说明这一发没被服务端认（换装落空 / 包丢了），补一次 */
    public static boolean shouldRetryTakeoff() {
        return isGrimLag() && !stopGlideSinceStart && tick - lastStartGlidingTick >= STALL_RETRY_TICKS;
    }

    /**
     * 本地是不是「不认服务端停滑广播」的状态（{@code MixinSynchedEntityData} 用）
     *
     * <p>史莱姆 {@code handleEntityDataUpdate} 在 TICK 模式下就是把同步数据里的滑翔位改回 1：
     * 客户端全程滑翔，换甲时机另由这一包触发。照搬
     */
    public static boolean isKeepingGlide() {
        return isGrimLag() && mc.player != null && mc.player.isFallFlying();
    }

    /** 把同步数据里的滑翔位改回 1（史莱姆 {@code handleEntityDataUpdate} 那一手） */
    public static SynchedEntityData.DataValue<?> keepGlideDataValue(SynchedEntityData.DataValue<?> item) {
        if (item.id() != ID_FLAGS) return item;
        if (!(item.value() instanceof Byte data)) return item;
        if ((data & (1 << FALL_FLYING_FLAG_INDEX)) != 0) return item;
        return withValue(item, (byte) (data | (1 << FALL_FLYING_FLAG_INDEX)));
    }

    /**
     * 取走「该换甲了」的请求（换甲成功 / 换不了都会清掉，下一次停滑同步再请求）
     */
    public static boolean consumeTakeoffRequest() {
        if (!takeoffRequested) return false;
        takeoffRequested = false;
        return true;
    }

    // ====== 生命周期 ======

    /** 每 tick（主线程）：窗口一过就把扣下的包放回去；不在 Grim Lag 时把状态清干净 */
    public static void onPreTick() {
        tick++;
        forcePositionPacket = false;

        if (!isGrimLag()) {
            if (delaying || !heldPackets.isEmpty()) reset();
            return;
        }
        if (mc.player == null) return;

        // 窗口到期：先停扣包，再按原顺序把扣下的包补处理。放包这一下就是「服务端说停滑」，
        // 内核在紧接着的 grimLagTick 里换一次甲（本地滑翔位由 keepGlideDataValue 撑着，不掉假）
        if (delaying && !inWindow()) {
            delaying = false;
            flushHeld();
        }
    }

    /** 模块开启 / 关掉：清状态并把还扣着的包放掉（不能吞包，keep-alive 之类还等着处理） */
    public static void reset() {
        delaying = false;
        forcePositionPacket = false;
        takeoffRequested = false;
        lastStartGlidingTick = Integer.MIN_VALUE / 2;
        stopGlideSinceStart = true;
        lastPingPacket = null;
        lastPingConnection = null;
        flushHeld();
    }

    /** 发出起飞包：窗口锚点 + 反踢出 + 补 pong（史莱姆 {@code onPlayerCommand} 记 START_FALL_FLYING 那一手） */
    public static void onStartFlying() {
        if (!isGrimLag()) return;
        // 每发一发起飞包都重新计时，窗口跟着换甲节奏走
        lastStartGlidingTick = tick;
        stopGlideSinceStart = false;
        // 反踢出：这一 tick 的移动包带完整坐标
        if (antiKickOn()) forcePositionPacket = true;
        // GrimBadPacket 修复：起飞包后补一发哨兵 pong
        if (pongFixOn()) sendPong(BAD_PACKET_PONG_ID);
        // 本地全程认自己在滑翔（史莱姆的 fake gliding）：起飞这一下本地也置成滑翔，
        // 之后服务端发来的停滑同步包由 keepGlideDataValue 改回 1，本地滑翔位就不会再掉
        if (mc.player != null && !mc.player.isFallFlying()) mc.player.startFallFlying();
    }

    // ====== 收包（延后同步就发生在这一步） ======

    /**
     * 收包监听：在 {@link ElytraFlySupport#onPacketReceive} 最前面调用
     *
     * <p>只在窗口内动手，而且只扣两类包：服务端说我们不在滑翔的自身实体同步包
     * （一出现本轮就开始扣）、以及紧接着那一发 transaction ping。
     * 其余入站包（世界、别人、位置纠正）一律照常，不卡客户端。
     */
    public static void onReceive(PacketEvent.Receive event) {
        if (!isGrimLag()) {
            if (delaying || !heldPackets.isEmpty()) reset();
            return;
        }
        if (mc.player == null) return;

        // 自己放出来的包（延迟窗口结束时的补处理）：这一发就是「服务端说停滑」→ 换甲信号
        if (replaying) {
            if (isSelfStopGliding(event.packet)) {
                stopGlideSinceStart = true;
                takeoffRequested = true;
            }
            return;
        }

        // 本轮已经在扣包：服务端紧接着发来的 transaction ping 一起扣下（pong 发不出去，
        // Grim 的 addRealTimeTask 就一直不执行）；「补 pong」也要用这一发
        if (delaying && event.packet instanceof ClientboundPingPacket) {
            lastPingPacket = event.packet;
            lastPingConnection = event.connection;
            hold(event);
            return;
        }

        if (!isSelfStopGliding(event.packet)) return;

        stopGlideSinceStart = true;

        // 服务端说我们停滑了：
        // 窗口内 → 这一包扣下（本地滑翔位不变假，反作弊那边也停在滑翔），窗口结束放回来时才换甲
        // 窗口外（没启用延后同步 / 网络比窗口还慢）→ 当场记一次换甲请求，本 tick 就换
        if (delayTicks() > 0 && inWindow()) {
            delaying = true;
            hold(event);
            return;
        }
        takeoffRequested = true;
    }

    // ====== 内部 ======

    /** 延后同步是否启用（史莱姆 max-delay-ticks 的 present 位） */
    private static boolean delayOn() {
        return enabled != null && enabled.get();
    }

    /** 窗口长度（tick）：起飞包之后这么多 tick 内的包扣下（没启用就是 0，等于不延迟） */
    private static int delayTicks() {
        if (!delayOn()) return 0;
        return syncDelayTicks == null ? DEFAULT_SYNC_DELAY_TICKS : Math.max(0, syncDelayTicks.get());
    }

    private static boolean antiKickOn() {
        return antiKick == null || antiKick.get();
    }

    private static boolean pongFixOn() {
        return pongFix == null || pongFix.get();
    }

    /** 还在「起飞包之后」的窗口里（史莱姆：{@code tick - lastStartGlidingTick < maxDelayTicks}） */
    private static boolean inWindow() {
        return tick - lastStartGlidingTick < delayTicks();
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
     * 在这里调用就是「这一包刚到」。
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
                ModernSupport.LOG.warn("[甲飞] Grim Lag 放包失败: {}",
                    packet.getClass().getSimpleName(), t);
            } finally {
                replaying = false;
            }
        }

        // 「补 pong」：扣下的那一发 ping 再处理一次，客户端就再补一发同 id 的 pong
        // （史莱姆往队列里注入重复 ping 的效果），把 transaction 计数往前顶一格
        if (pongFixOn()) replyLastPing();
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
     * 所以 bundle 也拆开看。
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

    /** 「补 pong」：再处理一次被扣下的那一发 ping（客户端因此再回一发同 id 的 pong） */
    private static void replyLastPing() {
        Packet<?> packet = lastPingPacket;
        Connection connection = lastPingConnection;
        lastPingPacket = null;
        lastPingConnection = null;
        if (packet == null || connection == null || !connection.isConnected()) return;

        replaying = true;
        try {
            handleRaw(packet, connection);
        } catch (Throwable t) {
            ModernSupport.LOG.warn("[甲飞] Grim Lag 补 pong 失败", t);
        } finally {
            replaying = false;
        }
    }

    /** 补一发哨兵 pong（史莱姆的 GrimBadPacket 修复） */
    private static void sendPong(int id) {
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundPongPacket(id));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SynchedEntityData.DataValue<?> withValue(SynchedEntityData.DataValue<?> item, Object value) {
        return new SynchedEntityData.DataValue(item.id(), item.serializer(), value);
    }

}
