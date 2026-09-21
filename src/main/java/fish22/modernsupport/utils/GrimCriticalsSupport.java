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

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixininterface.IServerboundMovePlayerPacket;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「刀刀暴击」（Criticals）模块「Grim」模式的逻辑 —— 史莱姆（SlimefunHelper）
 * 的 Grim 平地刀爆（{@code GRIM_GROUND_SIMULATION} / 「Grim平地」）搬过来的那一套
 *
 * <p>流程（平地、脚下有地、站着不动时才做）：
 *
 * <ol>
 *   <li><b>攻击那一 tick</b>：先停疾跑（1.21.2 起疾跑打不出暴击），补一发「伪造地面」坐标包
 *       —— y 先对齐到 {@value #ALIGN_STEP} 格、再抬 {@value #FAKE_DELTA}，差值小到服务端
 *       不会当成重复包或异常偏移；</li>
 *   <li>再补一发抬高 {@value #TRIGGER_HEIGHT} 格的包触发服务端模拟：这一发服务端一定判违规，
 *       于是把我们拉回（就是我们要等的回弹）；</li>
 *   <li>这一击的攻击包和挥动包先扣下不发（同时不发客户端自己那一发坐标包，
 *       一 tick 只有我们这几发移动包）；</li>
 *   <li><b>等回弹</b>：服务端的位置纠正包一到（收到后下一 tick），补两发
 *       「在伪造地面上、仍然在空中」→「往下一小格」的包，服务端摔伤距离 &gt; 0 且不在地面，
 *       再把扣下的攻击包 / 挥动包发出去，这一击就是暴击，而且偏移小于 Grim 的容忍度；</li>
 *   <li>超时（{@value #SETBACK_TIMEOUT} tick 没等到回弹）就把这一击照常补发，不会把这一击卡没。</li>
 * </ol>
 *
 * <p>补包里带的朝向优先用 {@link LegalRotation#getServerYaw()}（合法转头开着时那是目标角度），
 * 免得这一 tick 的坐标包被拦掉之后服务端还拿着旧朝向打空
 */
public final class GrimCriticalsSupport {

    /** 伪造地面：y 对齐的格（史莱姆的 MIN_HEIGHT_THRESHOLD） */
    private static final double ALIGN_STEP = 1.0E-4;

    /** 伪造地面上抬量（史莱姆的 MIN_HEIGHT_DELTA） */
    private static final double FAKE_DELTA = 1.0E-5;

    /** 触发服务端模拟用的抬高量（史莱姆 GS 模式的 y + 1） */
    private static final double TRIGGER_HEIGHT = 1.0;

    /** 等回弹的最多 tick 数，超时照常补发 */
    private static final int SETBACK_TIMEOUT = 5;

    /** Meteor 给「暴击伪造包」打的标记（官方 NoFall 看到这个标记就不管，别互相打架） */
    private static final int SPOOF_TAG = 1337;

    /** 自己补包时打标记用，避免再进一遍拦包逻辑 */
    private static boolean sendingOwn;

    /** 这一 tick 客户端自己那一发坐标包不发（一 tick 只留我们的移动包） */
    private static boolean cancelMoveThisTick;

    /** 扣下等回弹的攻击包与挥动包 */
    private static ServerboundAttackPacket pendingAttack;
    private static ServerboundSwingPacket pendingSwing;

    /** 自己数的 tick、扣包那一 tick、收到回弹那一 tick */
    private static int tick;
    private static int markTick;
    private static int setbackTick;
    private static boolean gotSetback;

    private static boolean subscribed;

    private static final PositionListener POSITION_LISTENER = new PositionListener();

    private GrimCriticalsSupport() {
    }

    /** 模块激活（{@link fish22.modernsupport.mixin.MixinCriticals} 在 onActivate 里调） */
    public static void onActivate() {
        reset();
        if (!subscribed) {
            MeteorClient.EVENT_BUS.subscribe(POSITION_LISTENER);
            subscribed = true;
        }
    }

    /** 这一发包是不是我们自己在补的（是的话官方那套也别拦） */
    public static boolean isSendingOwn() {
        return sendingOwn;
    }

    /**
     * 攻击包：能接就接（返回 true，包扣下等回弹之后再发）
     *
     * <p>接不了（水里 / 空中 / 打的不是活体 / 上一击还在等回弹）就返回 false，这一击照常发出去
     */
    public static boolean onAttackPacket(PacketEvent.Send event) {
        if (sendingOwn || pendingAttack != null) return false;
        if (!(event.packet instanceof ServerboundAttackPacket attack)) return false;
        if (!canCrit(attack.entityId())) return false;

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        sendingOwn = true;
        try {
            // 1.21.2 起疾跑时服务端不给暴击，先停疾跑
            if (mc.player.isSprinting()) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player,
                    ServerboundPlayerCommandPacket.Action.STOP_SPRINTING
                ));
                mc.player.setSprinting(false);
            }

            // 伪造地面高度：差值小到不会被判成重复包 / 异常偏移
            sendPos(x, fakeGroundY(y), z, true);
            // 触发服务端模拟：这一发服务端会纠正我们，回弹就是下一步要等的东西
            sendPos(x, y + TRIGGER_HEIGHT, z, false);
        } finally {
            sendingOwn = false;
        }

        pendingAttack = attack;
        pendingSwing = null;
        markTick = tick;
        gotSetback = false;
        cancelMoveThisTick = true;
        return true;
    }

    /** 挥动包 / 客户端自己的坐标包：该拦的拦掉（返回 true 才拦） */
    public static boolean shouldCancel(PacketEvent.Send event) {
        if (sendingOwn) return false;

        if (event.packet instanceof ServerboundSwingPacket) return pendingAttack != null;

        if (cancelMoveThisTick
            && event.packet instanceof ServerboundMovePlayerPacket move
            && move.hasPosition()) return true;

        return false;
    }

    /** 模块的 onTick（TickEvent.Pre）里调：跑状态机 */
    public static void onTick() {
        tick++;
        cancelMoveThisTick = false;

        if (pendingAttack == null) return;
        if (mc.player == null || mc.getConnection() == null) {
            reset();
            return;
        }

        // 回弹到了就补发；一直没等到就超时补发
        if (gotSetback && tick > setbackTick) finishAttack();
        else if (tick > markTick + SETBACK_TIMEOUT) finishAttack();
    }

    /** 平地暴击的前提：站在地上、不在水 / 岩浆 / 梯子 / 蛛网 / 载具里，打的是活体 */
    private static boolean canCrit(int entityId) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return false;
        if (!mc.player.onGround()) return false;
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()) return false;
        if (mc.player.isPassenger() || mc.player.isFallFlying()) return false;
        if (EntityUtils.isInCobweb(mc.player)) return false;

        Entity entity = mc.level.getEntity(entityId);
        return entity instanceof LivingEntity && entity != mc.player;
    }

    /** 回弹到了（或等超时了）：补两发伪造包，再把扣下的攻击包 / 挥动包发出去 */
    private static void finishAttack() {
        ServerboundAttackPacket attack = pendingAttack;
        ServerboundSwingPacket swing = pendingSwing;

        pendingAttack = null;
        pendingSwing = null;
        gotSetback = false;

        if (attack == null) return;

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        // 这一 tick 的朝向：合法转头开着的时候服务端记的是它的目标角度
        float yaw = LegalRotation.getServerYaw();
        float pitch = LegalRotation.getServerPitch();

        sendingOwn = true;
        try {
            // 声称自己在伪造地面上、还在空中
            sendPosRot(x, y + FAKE_DELTA, z, yaw, pitch, false);
            // 再往下一小格：服务端那边摔伤距离 > 0 且不在地面，这一击才算暴击
            sendPos(x, y, z, false);
            mc.getConnection().send(attack);
            if (swing != null) mc.getConnection().send(swing);
        } finally {
            sendingOwn = false;
        }

        // 这一 tick 客户端自己的坐标包也不发，免得同一 tick 多出一发移动包
        cancelMoveThisTick = true;
    }

    /** 伪造地面高度：把 y 对齐到 {@value #ALIGN_STEP} 格再抬 {@value #FAKE_DELTA} */
    private static double fakeGroundY(double y) {
        return Math.floor(y / ALIGN_STEP) * ALIGN_STEP + FAKE_DELTA;
    }

    private static void sendPos(double x, double y, double z, boolean onGround) {
        mc.getConnection().send(tag(new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, false)));
    }

    private static void sendPosRot(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        mc.getConnection().send(tag(new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, pitch, onGround, false)));
    }

    private static ServerboundMovePlayerPacket tag(ServerboundMovePlayerPacket packet) {
        ((IServerboundMovePlayerPacket) packet).meteor$setTag(SPOOF_TAG);
        return packet;
    }

    private static void reset() {
        tick = 0;
        markTick = 0;
        setbackTick = 0;
        gotSetback = false;
        sendingOwn = false;
        cancelMoveThisTick = false;
        pendingAttack = null;
        pendingSwing = null;
    }

    /** 收服务端的包：位置纠正包（回弹）→ 记下这一 tick，下一步好补发攻击 */
    private static class PositionListener {
        @EventHandler
        private void onPacketReceive(PacketEvent.Receive event) {
            if (!(event.packet instanceof ClientboundPlayerPositionPacket)) return;
            if (pendingAttack == null) return;

            gotSetback = true;
            setbackTick = tick;
        }
    }
}
