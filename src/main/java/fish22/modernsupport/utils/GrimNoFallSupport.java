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
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「无摔伤」（NoFall）模块「Grim」模式的逻辑
 *
 * <p>这就是史莱姆（SlimefunHelper）里 LAZY_GRIM_PLUS 那一套（NoFallGrimLazyPlus / 作者标注在
 * loyisa 4.9 能跑的那版 V2），按 Meteor 的事件重写了一遍：
 *
 * <ol>
 *   <li><b>落地那一 tick</b>：客户端已经踩在地上、服务端还以为你在空中 ——
 *       拦下客户端自己那一发落地位置包，只补一发「只报落地状态、不带坐标」的包。
 *       这一发会被 Grim 的 NoFall 检查认成「空中报落地」，Grim 会把它改成 onGround=false
 *       再回一发位置纠正包（就是我们等的回弹），所以服务端那边不会真的结算这次落地；</li>
 *   <li>客户端 X/Z 换成服务端记的那一份（史莱姆那套），本地按落地记，进入等回弹状态；</li>
 *   <li><b>等回弹期间</b>：客户端自己的坐标包一律不发，改发「抬高 {@value #DELTA_Y}、声称还在空中」
 *       的包 —— 26.1 服务端在处理带坐标的包时，只要这个包的 Y 比它记的高（movedUpwards）
 *       就把摔伤距离清零（{@code Entity#resetFallDistance}），所以这段等待里服务端那条摔伤距离一直是 0。
 *       回弹没落地之前发别的坐标包没用：服务端在等传送确认时会直接吞掉移动包；</li>
 *   <li><b>等 Grim 的回弹</b>：Grim 会把位置纠正包发回来（它认为你该在别的位置）。
 *       回弹位置离刚才的落点不远就收下，把客户端挪到回弹位置、按落地站稳，然后补跳一下 ——
 *       这一跳走输入包（{@link fish22.modernsupport.mixin.MixinElytraKeyboardInput} 把本地输入的跳跃键按下），
 *       服务端看到的是「按了跳跃键的跳」，不会像硬抬坐标那样被判回弹；</li>
 *   <li>超时（{@value #LATENCY} × 2 tick 没等到）就回普通状态，不影响走路。</li>
 * </ol>
 *
 * <p>落地包被拦掉之后，服务端那边记住的坐标停在落地前的高度。清摔伤距离靠的是等回弹期间
 * 那几发抬高 {@value #DELTA_Y} 的坐标包（必须在服务端没在等传送确认的时候发才有用），
 * 落地那一 tick 只发状态包是为了让 Grim 回弹
 */
public final class GrimNoFallSupport {
    private enum Step {
        COMMON,
        WAIT_FOR_RESYNC,
        APPLY_JUMP
    }

    /** 抬高量（史莱姆那边的 DELTA_Y） */
    private static final double DELTA_Y = 9.0E-8;

    /** 等回弹的窗口（史莱姆那边的 latency） */
    private static final int LATENCY = 5;

    /** 收到回弹之后补跳几 tick（这几 tick 的输入包带跳跃键） */
    private static final int JUMP_TICKS = 2;

    /** 服务端此刻记住的坐标（= 最后一发带坐标移动包里的坐标） */
    private static double lastServerX;
    private static double lastServerY;
    private static double lastServerZ;

    /** 这一摔是从多高开始掉的（史莱姆的 lastOnGroundHeight：踩地 / 上升时跟着抬） */
    private static double groundHeight;

    /** 这一 tick 开始时客户端在不在地上（用来认「这一 tick 刚落地」） */
    private static boolean onGroundAtTickStart = true;

    private static Step step = Step.COMMON;

    /** 进入等回弹状态时的「落点」与 tick */
    private static int markTick;
    private static double markX;
    private static double markY;
    private static double markZ;

    /** 收到的那一发回弹位置 */
    private static boolean hasAccept;
    private static int acceptTick;
    private static double acceptX;
    private static double acceptY;
    private static double acceptZ;

    /** 还差几 tick 要补跳 */
    private static int jumpTicks;

    /** 这一 tick 的输入包要不要带跳跃键 */
    private static boolean jumpThisTick;

    /** 自己数的 tick */
    private static int tick;

    /** 正在补自己的包（避免再进一遍发包监听） */
    private static boolean sendingPacket;

    private static final PositionListener POSITION_LISTENER = new PositionListener();

    private GrimNoFallSupport() {
    }

    /** 模块激活：清记账 + 开始跟服务端的位置纠正 */
    public static void onActivate() {
        reset();
        MeteorClient.EVENT_BUS.subscribe(POSITION_LISTENER);
    }

    /** 模块关闭：停跟位置纠正 */
    public static void onDeactivate() {
        MeteorClient.EVENT_BUS.unsubscribe(POSITION_LISTENER);
        reset();
    }

    private static void reset() {
        if (mc.player != null) {
            lastServerX = mc.player.getX();
            lastServerY = mc.player.getY();
            lastServerZ = mc.player.getZ();
            groundHeight = mc.player.getY();
        }
        onGroundAtTickStart = true;
        step = Step.COMMON;
        jumpTicks = 0;
        jumpThisTick = false;
        hasAccept = false;
        tick = 0;
    }

    /** 每 tick 开始（模块自己的 onTick）：记账 + 跑「等回弹 / 补跳」状态机 */
    public static void onTick() {
        tick++;
        if (mc.player == null) return;

        // 这一摔是从多高掉的：踩地 / 水里就按当前高度，往上飞就跟着抬
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) {
            groundHeight = mc.player.getY();
        } else if (mc.player.getY() > groundHeight) {
            groundHeight = mc.player.getY();
        }
        onGroundAtTickStart = mc.player.onGround();

        if (step == Step.WAIT_FOR_RESYNC) {
            if (markTick + LATENCY * 2 < tick) {
                // 等太久了，回普通状态
                step = Step.COMMON;
            } else if (hasAccept) {
                if (tick <= acceptTick + 1) {
                    step = Step.APPLY_JUMP;
                    // 用回弹给的坐标站稳，然后再跳一下
                    mc.player.setPos(acceptX, acceptY, acceptZ);
                    mc.player.setOnGround(true);
                    // 回弹落地前再补一发抬高包：把回弹这段可能又攒起来的摔伤距离清掉
                    sendHoverPacket();
                    markTick = tick;
                    hasAccept = false;
                    jumpTicks = JUMP_TICKS;
                    ModernSupport.LOG.info("[GrimNoFall] 收到回弹，按回弹坐标站稳并补跳");
                }
            } else if (markTick + 2 >= tick) {
                // 还没等到回弹：先按原输入补跳，别卡着不动
                jumpTicks = JUMP_TICKS;
            }
        } else if (step == Step.APPLY_JUMP) {
            step = Step.COMMON;
        }

        // 补跳：这一 tick 的输入包带上跳跃键（由键盘输入混入负责按下）。
        // 放在状态机之后：这一 tick 刚排上的跳就用这一 tick 的输入包
        jumpThisTick = jumpTicks > 0;
        if (jumpTicks > 0) jumpTicks--;
    }

    /** 键盘输入那一步用：这一 tick 是否把跳跃键按下（见 {@link fish22.modernsupport.mixin.MixinElytraKeyboardInput}） */
    public static boolean shouldPressJumpInput() {
        return jumpThisTick;
    }

    /** 发包监听：落地那一 tick 拦包 + 补包，平时只记服务端那边的坐标 */
    public static void onPacketSend(PacketEvent.Send event) {
        if (sendingPacket || mc.player == null || mc.getConnection() == null) return;
        if (!(event.packet instanceof ServerboundMovePlayerPacket move)) return;

        // 等回弹期间：客户端自己的坐标包不发，改发抬高包把服务端那条摔伤距离一直清零
        if (step == Step.WAIT_FOR_RESYNC && !hasAccept && move.hasPosition()) {
            event.cancel();
            sendHoverPacket();
            return;
        }

        if (move.hasPosition() && shouldHandleLanding()) {
            handleLanding();
            // 客户端自己的落地位置包不发了
            event.cancel();
            return;
        }

        // 只有带坐标的移动包才会改服务端那边的位置记录
        if (move.hasPosition()) {
            double x = move.getX(lastServerX);
            double y = move.getY(lastServerY);
            double z = move.getZ(lastServerZ);
            if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
                lastServerX = x;
                lastServerY = y;
                lastServerZ = z;
            }
        }
    }

    /** 这一 tick 是不是「刚落地、而且这一摔会摔掉血」 */
    private static boolean shouldHandleLanding() {
        if (step != Step.COMMON) return false;
        if (tick <= markTick + LATENCY) return false;
        if (onGroundAtTickStart || !mc.player.onGround()) return false;
        if (!canSave()) return false;

        double safeDistance = mc.player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        return mc.player.getY() <= groundHeight - safeDistance
            && lastServerY > mc.player.getY();
    }

    /** 这一摔管不管：创造 / 旁观、骑乘、滑翔、水里 / 岩浆里都不管 */
    private static boolean canSave() {
        if (mc.player.getAbilities().instabuild || mc.player.isSpectator()) return false;
        if (mc.player.isPassenger() || mc.player.isFallFlying()) return false;
        if (mc.player.isInWater() || mc.player.isInLava()) return false;
        return true;
    }

    /**
     * 落地那一 tick 的处理（史莱姆那套）：
     *
     * <ol>
     *   <li>补「只报落地状态、不带坐标」的包 → Grim 认出来会给回弹（服务端不结算这次落地）；</li>
     *   <li>客户端 X/Z 换成服务端记的那一份，本地按落地记，进入等回弹状态。</li>
     * </ol>
     *
     * <p>这一发必须排在客户端自己那一发落地包之前（Meteor 的发包事件发生在真正写包之前）。
     * 清摔伤距离的抬高包放到「等回弹」那一段去补 —— 这边发也没用：Grim 判违规时会先让服务端
     * 等传送确认，那段时间服务端会把我们的移动包直接吞掉
     */
    private static void handleLanding() {
        step = Step.WAIT_FOR_RESYNC;
        hasAccept = false;
        markX = mc.player.getX();
        markY = mc.player.getY();
        markZ = mc.player.getZ();
        markTick = tick;

        sendingPacket = true;
        try {
            mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(
                true,
                mc.player.horizontalCollision
            ));
        } finally {
            sendingPacket = false;
        }

        // 客户端 X/Z 用服务端记的那一份（史莱姆那套），Y 不动、本地按落地站稳
        mc.player.setPos(lastServerX, markY, lastServerZ);
        mc.player.setOnGround(true);

        ModernSupport.LOG.info("[GrimNoFall] 落地：已拦下落地包、补了落地状态包，等回弹");
    }

    /** 补「抬高 {@value #DELTA_Y}、声称还在空中」的包：服务端按 movedUpwards 把摔伤距离清零 */
    private static void sendHoverPacket() {
        sendingPacket = true;
        try {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
                lastServerX,
                lastServerY + DELTA_Y,
                lastServerZ,
                false,
                mc.player.horizontalCollision
            ));
        } finally {
            sendingPacket = false;
        }
        // 服务端那边记的坐标跟着这一发走
        lastServerY += DELTA_Y;
    }

    /** 服务端位置纠正（回弹 / 传送）：位置不远就按史莱姆那套收下 */
    private static void onPositionCorrection(Vec3 absolute) {
        if (mc.player == null) return;

        if (step == Step.WAIT_FOR_RESYNC
            && tick < markTick + LATENCY
            && squaredDistance(absolute.x, absolute.y, absolute.z, markX, markY, markZ) < 1.0) {
            acceptX = absolute.x;
            acceptY = absolute.y;
            acceptZ = absolute.z;
            acceptTick = tick;
            hasAccept = true;
            return;
        }

        lastServerX = absolute.x;
        lastServerY = absolute.y;
        lastServerZ = absolute.z;
    }

    private static double squaredDistance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 收服务端的包：位置纠正包 → 按史莱姆那套处理（收下 / 记账） */
    private static class PositionListener {
        @EventHandler
        private void onPacketReceive(PacketEvent.Receive event) {
            if (!(event.packet instanceof ClientboundPlayerPositionPacket position)) return;
            if (mc.player == null) return;

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

            onPositionCorrection(absolute);
        }
    }
}
