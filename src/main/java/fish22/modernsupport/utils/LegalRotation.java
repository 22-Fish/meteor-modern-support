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
import fish22.modernsupport.mixin.EntityRotationAccessor;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 合法转头 API
 *
 * <p>rotate() 只记录目标旋转，不碰 player.yRot（摄像机不会转）。
 * 在 aiStep 前（onPlayerTickMovement）才临时设 yRot 供移动计算用，
 * aiStep 后立即恢复。move 包在发包前再次临时设 yRot，发出后恢复。
 *
 * <p>同 tick 多次调用 rotate()：最后一次的值生效。
 */
public class LegalRotation {

    public enum Mode {
        OFF("关闭"),
        NO_MOVE("停止移动"),
        SEVERE("严格"),
        QUIET("静默");

        private final String displayName;
        Mode(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

    // ====== 状态 ======

    private static Mode currentMode = Mode.OFF;
    private static float targetYaw;
    private static float targetPitch;
    private static float prevYaw;
    private static float prevPitch;
    private static boolean active = false;
    private static boolean originalCached = false;
    private static Runnable pendingCallback;
    private static int holdTicks = 0;
    private static final List<Runnable> postSendActions = new ArrayList<>();
    private static boolean rotatedThisTick = false;

    // ====== 初始化 ======

    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(LegalRotation.class);
    }

    // ====== API ======

    /** 记录目标旋转（不碰 player.yRot，摄像机不转） */
    public static void rotate(double yaw, double pitch) {
        recordTarget(yaw, pitch, Mode.SEVERE, null);
    }

    public static void rotate(double yaw, double pitch, Runnable callback) {
        recordTarget(yaw, pitch, Mode.SEVERE, callback);
    }

    public static void rotate(double yaw, double pitch, Mode mode) {
        recordTarget(yaw, pitch, mode, null);
    }

    public static void rotate(double yaw, double pitch, Mode mode, Runnable callback) {
        recordTarget(yaw, pitch, mode, callback);
    }

    public static void rotateWithMode(double yaw, double pitch, Mode mode) {
        rotate(yaw, pitch, mode);
    }

    public static void runAfterSend(Runnable action) {
        postSendActions.add(action);
    }

    public static void setHoldTicks(int ticks) {
        holdTicks = Math.max(0, ticks);
    }

    // ====== 内部 ======

    /** 只记录目标值，不碰 player.yRot */
    private static void recordTarget(double yaw, double pitch, Mode mode, Runnable callback) {
        targetYaw = (float) yaw;
        targetPitch = (float) pitch;
        currentMode = mode;
        active = true;
        rotatedThisTick = true;
        if (callback != null) pendingCallback = callback;

        if (mc.player != null) {
            if (!originalCached) {
                prevYaw = mc.player.getYRot();
                prevPitch = mc.player.getXRot();
                originalCached = true;
            }
            // 立即设 yRot + yRotO，渲染瞬间到位（无插值），摄像机不会看到旋转
            applyYawFull(mc.player, targetYaw, targetPitch);
        }
    }

    /** 临时设 yRot（不碰 yRotO，摄像机不受渲染插值影响） */
    private static void applyYawOnly(Entity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
    }

    /** 同步 yRot + yRotO（move 包发出前用，渲染瞬间到位） */
    private static void applyYawFull(Entity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        ((EntityRotationAccessor) entity).setYRotO(yaw);
        ((EntityRotationAccessor) entity).setXRotO(pitch);
    }

    // ====== 方块放置上下文 ======

    private static Mode placeMode = Mode.OFF;
    public static void beginPlace(Mode mode) { placeMode = mode; }
    public static void endPlace() { placeMode = Mode.OFF; }
    public static Mode getPlaceMode() { return placeMode; }

    // ====== 状态查询 ======

    public static boolean isActive() { return active && currentMode != Mode.OFF; }
    public static boolean wasActiveThisTick() { return rotatedThisTick; }
    public static Mode getMode() { return currentMode; }
    public static float getTargetYaw() { return targetYaw; }
    public static float getTargetPitch() { return targetPitch; }
    public static float getVisualYaw() { return prevYaw; }
    public static float getVisualPitch() { return prevPitch; }

    /** travel 前调用：确保 yRot 是目标朝向（防止 aiStep 内部改回去） */
    public static void forceRotationBeforeMove(Entity entity) {
        if (entity == mc.player && active) {
            applyYawOnly(entity, targetYaw, targetPitch);
        }
    }

    // ====== 事件 ======

    @EventHandler
    private static void onTickPost(TickEvent.Post event) {
        rotatedThisTick = false;
    }

    /**
     * aiStep 前：确保 yRot 是目标值（兜底，防止 aiStep 内部改回去）。
     * yRotO 已在 rotate() 里同步，这里只补 yRot。
     */
    @EventHandler
    private static void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (!active || mc.player == null) return;
        if (!originalCached) {
            prevYaw = mc.player.getYRot();
            prevPitch = mc.player.getXRot();
            originalCached = true;
        }
        applyYawOnly(mc.player, targetYaw, targetPitch);
    }

    /**
     * move 包发出前：确保 yRot+yRotO 都是目标值。
     * rotate() 已经同步过，这里再确认一次（aiStep 可能改了 yRot）。
     */
    @EventHandler
    private static void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (!active || mc.player == null) return;
        applyYawFull(mc.player, targetYaw, targetPitch);
    }

    /** move 包发出后：恢复 yRot + yRotO 为原朝向（摄像机回到原位） */
    @EventHandler
    private static void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (!postSendActions.isEmpty()) {
            List<Runnable> actions = new ArrayList<>(postSendActions);
            postSendActions.clear();
            for (Runnable action : actions) {
                try {
                    action.run();
                } catch (Exception e) {
                    ModernSupport.LOG.error("[合法转头] runAfterSend 回调执行异常", e);
                }
            }
        }

        if (!active || mc.player == null) return;

        if (holdTicks > 0) {
            holdTicks--;
            applyYawFull(mc.player, prevYaw, prevPitch);
            return;
        }

        applyYawFull(mc.player, prevYaw, prevPitch);

        if (pendingCallback != null) {
            pendingCallback.run();
            pendingCallback = null;
        }

        active = false;
        currentMode = Mode.OFF;
        originalCached = false;
    }
}
