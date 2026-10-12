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
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixin.LivingEntityAccessor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「无摔伤」（NoFall）模块「Grim」模式的逻辑
 *
 * <p>搬的是史莱姆（SlimefunHelper）里作者标了「works on loyisa」的那一版
 * {@code NoFallGrimLazyPlus}（配置里的 {@code LAZY_GRIM_PLUS}），不是后面的 V2
 *
 * <p>V2 那套（假装落地 → 等 Grim 回弹 → 挪到回弹坐标起跳）用不了：回弹点在半空，
 * 从半空起跳在服务端预测里就是「空中起跳」，每 tick 被判一次回弹，
 * 回弹没确认完之前移动包全被 Grim 的 SetbackBlocker 吞掉，
 * 服务端那条摔伤距离一次都没清，最后落回地面照样结算（落得越高越是必死）
 *
 * <p>史莱姆这版只在落地那一 tick 做一件事：<b>把客户端自己那发落地包压掉，
 * 改发一发「Y 抬高 {@value #DELTA_Y}、声称还在空中」的坐标包</b>
 *
 * <ul>
 *   <li>26.1 服务端 {@code ServerGamePacketListenerImpl#handleMovePlayer} 里
 *       {@code if (movedUpwards) this.player.resetFallDistance()}：
 *       只要这一发的 Y 高过它记的 lastGoodY，摔伤距离当场清零；</li>
 *   <li>{@code onGround=false} 不会踩到 Grim 的 NoFall 检查，所以这一发不会招来回弹，
 *       也不会被 SetbackBlocker 卡住 —— 清零这一下是真的送到了；</li>
 *   <li>客户端本身不动（还站在地面上），下一 tick 的移动包照常发，
 *       服务端看到的是「上一 tick 微微往上、然后正常下落」，摔伤距离从 0 重新算</li>
 * </ul>
 *
 * <p>落地之后按住空格 {@value #HOLD_JUMP_TICKS} tick：客户端还踩在地上，
 * 这一跳是原版合法起跳、服务端认（不会再被回弹），跳起来的那几发坐标又各清一次摔伤距离。
 * 这就是「落地马上就跳起来」的由来 —— 跳要在地面上跳，不是在回弹点跳
 */
public final class GrimNoFallSupport {
    private enum Step {
        COMMON,
        /** 落地救完的窗口：按住空格，让客户端自己从地面跳起来 */
        HOLD_JUMP
    }

    /** 抬高量（史莱姆的 DELTA_Y）：上报的 Y 比服务端记的 lastGoodY 高这么一点，就把摔伤距离清零 */
    private static final double DELTA_Y = 9.0E-8;

    /** 史莱姆的 latency：两次救之间至少隔这么久，按住空格的窗口是它的两倍 */
    private static final int LATENCY = 3;
    private static final int HOLD_JUMP_TICKS = LATENCY * 2;

    /** 摔伤高度阈值的默认值（设置里 1-64，改的是 MixinNoFall 挂到模块上的那一项） */
    public static final int DEFAULT_FALL_THRESHOLD = 10;

    /** 排查用：关键分支在聊天栏打一行（问题定位完就关掉） */
    private static final boolean DEBUG_CHAT = false;

    private static Step step = Step.COMMON;

    /** 自己数的 tick */
    private static int tick;

    /** 上一次救（落地改包）的 tick */
    private static int lastResyncTick;

    /** 这一 tick 的输入：按下真实跳跃键 */
    private static boolean pressJumpThisTick;

    /** 这一 tick 压掉了原版位置包，等原版那一发过去之后再补「抬高 DELTA_Y 的空中包」 */
    private static boolean pendingUpPacket;

    /** 正在补自己的包：放行，别被自己的拦截吃掉 */
    private static boolean sendingPacket;

    /** 本 tick 是不是在「原版位置包发送窗口」内（LocalPlayer#sendPosition 的 Pre~Post） */
    private static boolean inSendPosition;

    /** 这一 tick 开始时客户端的坐标（史莱姆 EntityMovementStatus 里的 preProgress） */
    private static double tickStartX;
    private static double tickStartY;
    private static double tickStartZ;

    /** 这一 tick 开始时客户端在不在地上（用来认「这一 tick 刚落地」） */
    private static boolean onGroundAtTickStart = true;

    /** 这一摔是从多高开始掉的（史莱姆的 lastOnGroundHeight：踩地 / 上升时跟着抬） */
    private static double groundHeight;

    /** 现在是不是选中了 Grim 模式：没选中就整段不干活（别的模式照旧） */
    private static boolean grimMode;

    /** 只有摔下高度超过这个值才救（模块设置里读，1-64） */
    private static int fallThreshold = DEFAULT_FALL_THRESHOLD;

    /** 排查用：剩几 tick 要往聊天栏打每 tick 的状态 */
    private static int debugTicks;

    private static final Listener LISTENER = new Listener();

    private GrimNoFallSupport() {
    }

    /** 模块每 tick 告诉我们当前是不是 Grim 模式（设置里可以随时切走） */
    public static void setGrimMode(boolean value) {
        if (grimMode == value) return;
        grimMode = value;
        reset();
    }

    /** 模块激活：清记账 + 开始跟原版移动包窗口 */
    public static void onActivate() {
        reset();
        MeteorClient.EVENT_BUS.subscribe(LISTENER);
    }

    /** 模块关闭：停跟移动包窗口 */
    public static void onDeactivate() {
        MeteorClient.EVENT_BUS.unsubscribe(LISTENER);
        reset();
    }

    /** 模块每 tick 把设置里的高度阈值告诉我们 */
    public static void setFallThreshold(int value) {
        fallThreshold = value;
    }

    private static void reset() {
        if (mc.player != null) groundHeight = mc.player.getY();
        step = Step.COMMON;
        lastResyncTick = 0;
        pressJumpThisTick = false;
        pendingUpPacket = false;
        sendingPacket = false;
        inSendPosition = false;
        onGroundAtTickStart = true;
        debugTicks = 0;
        tick = 0;
    }

    /** 每 tick 开始（模块自己的 onTick）：记账 + 跑「按住空格」窗口 */
    public static void onTick() {
        if (!grimMode) return;

        tick++;
        if (mc.player == null) return;

        // 上一 tick 留下的标记一律复位（发包窗口由 SendMovementPacketsEvent 重新开）
        inSendPosition = false;
        pendingUpPacket = false;
        pressJumpThisTick = false;

        tickStartX = mc.player.getX();
        tickStartY = mc.player.getY();
        tickStartZ = mc.player.getZ();

        // 这一摔是从多高掉的：踩地 / 水里就按当前高度，往上飞就跟着抬
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) {
            groundHeight = mc.player.getY();
        } else if (mc.player.getY() > groundHeight) {
            groundHeight = mc.player.getY();
        }
        onGroundAtTickStart = mc.player.onGround();

        if (step == Step.HOLD_JUMP) {
            if (lastResyncTick + HOLD_JUMP_TICKS < tick) {
                step = Step.COMMON;
            } else {
                // 还踩在地上：按住空格，原版自己起跳（起跳冷却清掉，免得原版把这一跳丢掉）
                pressJumpThisTick = true;
                clearJumpCooldown();
            }
        }

        if (debugTicks > 0) {
            debugTicks--;
            debugChat(String.format("t%d dy=%.3f onGround=%s", tick - lastResyncTick,
                mc.player.getDeltaMovement().y, mc.player.onGround()));
        }
    }

    /** 键盘输入那一步用：这一 tick 是否把跳跃键按下（{@link fish22.modernsupport.mixin.MixinElytraKeyboardInput}） */
    public static boolean shouldPressJumpInput() {
        return pressJumpThisTick;
    }

    /**
     * 发包监听：只动「原版位置包」那一发
     *
     * <p>{@link SendMovementPacketsEvent} 的窗口盖的是原版 {@code LocalPlayer#sendPosition}
     */
    public static void onPacketSend(PacketEvent.Send event) {
        if (sendingPacket || mc.player == null) return;
        if (!(event.packet instanceof ServerboundMovePlayerPacket move)) return;
        if (!inSendPosition) return;
        if (step != Step.COMMON || !move.hasPosition() || !shouldHandleLanding()) return;

        handleLanding();
        // 客户端自己那一发落地位置包不发了，原版移动包发完后再补「抬头一点点」的空中包
        event.cancel();
        pendingUpPacket = true;
    }

    /** 这一 tick 是不是「刚落地、而且这一摔会摔掉血」 */
    private static boolean shouldHandleLanding() {
        if (step != Step.COMMON) return false;
        if (tick <= lastResyncTick + LATENCY) return false;
        if (onGroundAtTickStart || !mc.player.onGround()) return false;
        if (!canSave()) return false;

        // 设置里的高度阈值：只有摔得比它高才动手
        return groundHeight - mc.player.getY() > fallThreshold;
    }

    /** 这一摔管不管：创造 / 旁观、骑乘、滑翔、水里 / 岩浆里都不管 */
    private static boolean canSave() {
        if (mc.player.getAbilities().instabuild || mc.player.isSpectator()) return false;
        if (mc.player.isPassenger() || mc.player.isFallFlying()) return false;
        if (mc.player.isInWater() || mc.player.isInLava()) return false;
        return true;
    }

    /**
     * 落地那一 tick 的处理（史莱姆 applyBeforeMovementPacketModify 里 COMMON 那一段）：
     *
     * <ol>
     *   <li>原版那一发落地位置包由调用方压掉，原版移动包发完后再补一发
     *       「X/Z 回到这一 tick 开始的位置、Y 抬高 {@value #DELTA_Y}、onGround=false」的包；</li>
     *   <li>本地那份摔伤距离清掉，免得本地先算一次伤害；</li>
     *   <li>进入「按住空格」窗口，让客户端这一跳从地面上起。</li>
     * </ol>
     */
    private static void handleLanding() {
        step = Step.HOLD_JUMP;
        lastResyncTick = tick;

        // 本地那份摔伤距离清掉：这一 tick 的落地由我们自己处理，不让本地先算一次伤害
        mc.player.resetFallDistance();
        debugTicks = 6;

        debugChat("落地，清摔伤距离");
    }

    /** 排查用：聊天栏打一行（{@value #DEBUG_CHAT} 关掉就不打了） */
    private static void debugChat(String message) {
        if (!DEBUG_CHAT) return;
        ChatUtils.info("(无摔伤) " + message);
    }

    /**
     * 补那一发「抬高 {@value #DELTA_Y}、声称还在空中」的坐标包
     *
     * <p>X/Z 用这一 tick 开始时的坐标（把这一 tick 的位移从服务端眼里抹掉），
     * Y 用这一 tick 开始的 Y 再抬 {@value #DELTA_Y} —— 服务端算出来就是 movedUpwards，
     * 于是 {@code resetFallDistance()}；onGround 必须是 false，
     * 不然 Grim 会按「空中报落地」回弹我们
     */
    private static void sendUpPacket() {
        sendingPacket = true;
        try {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
                tickStartX,
                tickStartY + DELTA_Y,
                tickStartZ,
                false,
                mc.player.horizontalCollision
            ));
        } finally {
            sendingPacket = false;
        }
    }

    /** 原版跳跃冷却（noJumpDelay）清零，史莱姆的 onJump 钩子干的就是这件事 */
    private static void clearJumpCooldown() {
        if (mc.player instanceof LivingEntityAccessor accessor) accessor.meteor$setJumpCooldown(0);
    }

    /** 事件监听：原版位置包窗口 */
    private static class Listener {
        @EventHandler
        private void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
            inSendPosition = true;
        }

        @EventHandler
        private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
            inSendPosition = false;
            // 原版那一发位置包已经过去了：这时候补自己的空中包，一 tick 里就只有这一发移动包
            if (pendingUpPacket && mc.player != null && mc.getConnection() != null) sendUpPacket();
            pendingUpPacket = false;
        }
    }
}
