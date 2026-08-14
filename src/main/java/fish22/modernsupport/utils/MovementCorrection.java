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
 * 移动矫正 API（参考 Baritone LookBehavior 与 LiquidBounce MovementCorrection）
 *
 * <p>与 Meteor 原版 {@link meteordevelopment.meteorclient.utils.player.Rotations} 不同：
 * 不再只改发包里的旋转，而是在移动计算前真实设置玩家朝向。
 * 这样走路、鞘翅飞行的移动方向自然跟随旋转，服务器收到的旋转也正确。
 *
 * <p>每 tick 时序：
 * <ol>
 *   <li>调用方（如 KillAura）在 tick 开头调用 {@link #rotate}，记录目标旋转</li>
 *   <li>移动计算前（aiStep）：把玩家朝向设为目标旋转，移动方向自然正确；
 *       同时保存原朝向（客户端视觉朝向），供静默模式按键映射使用</li>
 *   <li>移动包发送：包内旋转 = 目标旋转，服务器朝向正确</li>
 *   <li>发包后：恢复原朝向（客户端静默），并清除状态</li>
 * </ol>
 *
 * <p>状态由调用方每 tick 刷新：调用方停止调用后，下一个 tick 自动归位，
 * 不会残留锁定（解决旋转后不归位的问题）。
 *
 * <p>模式说明：
 * <ul>
 *   <li>{@link Mode#OFF} — 关闭，不进行移动矫正</li>
 *   <li>{@link Mode#SEVERE} — 严格：真实旋转，客户端静默（服务器朝向正确、移动方向正确，客户端视角不动）</li>
 *   <li>{@link Mode#QUIET} — 静默：严格的基础上，把 WASD 按键映射到服务器朝向坐标系，
 *       玩家移动方向与客户端视觉朝向一致（如服务器朝右、视觉朝前时，W 等效 A）</li>
 *   <li>{@link Mode#NO_MOVE} — 停止移动：暂未实现，无效</li>
 * </ul>
 */
public class MovementCorrection {

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


    /** 当前生效的矫正模式 */
    private static Mode currentMode = Mode.OFF;

    /** 目标旋转（调用 rotate 时保存） */
    private static float targetYaw;
    private static float targetPitch;

    /** 设置朝向前的原朝向（发包后恢复用，也是客户端视觉朝向） */
    private static float prevYaw;
    private static float prevPitch;

    /** 本 tick 是否有活跃的矫正请求 */
    private static boolean active = false;

    /** 旋转完成后的回调 */
    private static Runnable pendingCallback;

    /** 旋转保持剩余 tick 数（OnHit 模式：攻击后延迟转回） */
    private static int holdTicks = 0;

    /** 移动包发送完毕后执行的动作队列（多个调用方可同时注册，互不覆盖） */
    private static final List<Runnable> postSendActions = new ArrayList<>();

    /** 本 tick 是否调用过 rotate（烟花加速方向对齐用；TickEvent.Post 时重置） */
    private static boolean rotatedThisTick = false;

    /** 诊断：状态日志计数 */
    private static int debugTick = 0;

    /**
     * 注册一个在移动包发送完毕后执行的动作。
     * 多个调用方（如 KillAura 延迟攻击、鞘翅延迟烟花）可同时注册，互不覆盖，
     * Post 时按注册顺序全部执行。
     */
    public static void runAfterSend(Runnable action) {
        postSendActions.add(action);
    }

    /**
     * 初始化：注册事件到 Meteor 事件总线。
     * 在 {@code ModernSupport.onInitialize()} 中调用。
     */
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(MovementCorrection.class);
    }

    // ====== API 入口（类似 Rotations.rotate） ======

    /**
     * 带移动矫正模式的旋转。
     * 目前 {@link Mode#SEVERE}、{@link Mode#QUIET} 生效，{@link Mode#NO_MOVE} 暂为无效。
     */
    public static void rotate(double yaw, double pitch, Mode mode) {
        rotate(yaw, pitch, mode, null);
    }

    /**
     * 带移动矫正模式和回调的旋转。
     * 回调在本次旋转的移动包发送完毕后执行。
     * 目前 {@link Mode#SEVERE}、{@link Mode#QUIET} 生效，{@link Mode#NO_MOVE} 暂为无效。
     */
    public static void rotate(double yaw, double pitch, Mode mode, Runnable callback) {
        // 未实现的模式直接不激活，等同关闭
        if (mode != Mode.SEVERE && mode != Mode.QUIET) return;

        targetYaw = (float) yaw;
        targetPitch = (float) pitch;
        currentMode = mode;
        active = true;
        rotatedThisTick = true;
        pendingCallback = callback;
    }

    // ====== 状态查询 ======

    /**
     * 按模式旋转的统一入口（供各模块使用）。
     * {@link Mode#SEVERE}、{@link Mode#QUIET} 走移动矫正，
     * 其余模式（关闭、停止移动未实现）回退 Meteor 原版静默旋转。
     */
    public static void rotateWithMode(double yaw, double pitch, Mode mode) {
        if (mode == Mode.SEVERE || mode == Mode.QUIET) {
            rotate(yaw, pitch, mode);
        } else {
            Rotations.rotate(yaw, pitch);
        }
    }

    /** 设置旋转保持 tick 数（保持期间不归位，用于 OnHit 模式攻击后延迟转回） */
    public static void setHoldTicks(int ticks) {
        holdTicks = Math.max(0, ticks);
    }

    // ====== 方块放置矫正上下文 ======
    //
    // Meteor 的 BlockUtils.place 内部调用 Rotations.rotate 静默转向，
    // SpawnProofer（防止生成）等放置类模块要用移动矫正时，需要把这次旋转换成移动矫正。
    // 但放置的旋转发生在 BlockUtils.place 内部，模块拿不到旋转调用点，
    // 因此 MixinSpawnProofer 在调用 BlockUtils.place 前设置此上下文，
    // MixinBlockUtils 重定向 Rotations.rotate 时读取，其余模块不受影响（默认为关闭）。

    /** 方块放置矫正模式上下文（默认关闭） */
    private static Mode placeMode = Mode.OFF;

    /** 进入方块放置流程前设置矫正模式（MixinSpawnProofer 调用） */
    public static void beginPlace(Mode mode) {
        placeMode = mode;
    }

    /** 结束方块放置流程，清除矫正模式（MixinSpawnProofer 调用） */
    public static void endPlace() {
        placeMode = Mode.OFF;
    }

    /** 当前方块放置矫正模式（MixinBlockUtils 读取，判断是否用移动矫正替代 Rotations） */
    public static Mode getPlaceMode() {
        return placeMode;
    }

    /** 是否存在活跃的矫正（非 OFF 即活跃） */
    public static boolean isActive() {
        return active && currentMode != Mode.OFF;
    }

    /** 本 tick 是否调用过 rotate（移动矫正 API 旋转过；供烟花加速方向对齐判断） */
    public static boolean wasActiveThisTick() {
        return rotatedThisTick;
    }

    /** 当前矫正模式 */
    public static Mode getMode() {
        return currentMode;
    }

    /** 目标旋转 yaw（服务器朝向，aiStep 前设置给玩家） */
    public static float getTargetYaw() {
        return targetYaw;
    }

    /** 目标旋转 pitch（服务器朝向，放烟花等动作对齐用） */
    public static float getTargetPitch() {
        return targetPitch;
    }

    /** 客户端视觉朝向 yaw（设置朝向前的原朝向，静默模式按键映射用） */
    public static float getVisualYaw() {
        return prevYaw;
    }

    /** 客户端视觉朝向 pitch（applyInput bob 跟随目标用） */
    public static float getVisualPitch() {
        return prevPitch;
    }

    /**
     * 移动计算前强制应用目标朝向（travel 前调用）。
     * aiStep 内部可能有逻辑把朝向改回视觉值，导致移动方向与目标不一致；
     * 在 travel 入口再强制一次，保证本 tick 移动方向一定跟随目标旋转。
     */
    public static void forceRotationBeforeMove(Entity entity) {
        if (entity == mc.player && active) {
            applyRotation(entity, targetYaw, targetPitch);
        }
    }

    // ====== 事件处理器 ======

    /**
     * tick 结束：重置「本 tick 活跃」标记。
     * rotate 在 TickEvent.Pre（模块 onTick）里调用，标记持续到 tick 末尾
     * （实体 tick 在此之后），下一 tick 重新计数。
     */
    @EventHandler
    private static void onTickPost(TickEvent.Post event) {
        rotatedThisTick = false;
    }

    /**
     * 移动计算前：把玩家朝向设为目标旋转（Baritone PRE 逻辑）。
     * 真实设置朝向后，WASD 移动方向、鞘翅飞行方向都自然跟随旋转。
     */
    @EventHandler
    private static void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (!active || mc.player == null) return;

        if (++debugTick % 100 == 0) {
            ModernSupport.LOG.info("[移动矫正] aiStep前 设置朝向 目标yaw={} 目标pitch={} 原yaw={}",
                targetYaw, targetPitch, mc.player.getYRot());
        }

        LocalPlayer player = mc.player;
        prevYaw = player.getYRot();
        prevPitch = player.getXRot();
        applyRotation(player, targetYaw, targetPitch);
    }

    /**
     * 设置玩家朝向并同步渲染插值旧值（yRotO/xRotO）。
     * 渲染按 rotLerp(yRotO, yRot) 插值转向，不同步旧值会出现 1 tick 的平滑转头；
     * 同步后旋转瞬间到位，无插值动画。
     */
    private static void applyRotation(Entity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        ((EntityRotationAccessor) entity).setYRotO(yaw);
        ((EntityRotationAccessor) entity).setXRotO(pitch);
    }

    /**
     * 移动包发送前：再次强制设置目标朝向。
     * aiStep 内部（游泳、爬行、水流等转向逻辑）及发包前的其他代码可能修改玩家朝向，
     * 这里确保移动包里携带的旋转一定是目标旋转。
     */
    @EventHandler
    private static void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (!active || mc.player == null) return;
        // aiStep 内部（游泳、爬行、水流等转向逻辑）及发包前的其他代码可能修改玩家朝向，
        // 这里确保移动包里携带的旋转一定是目标旋转
        applyRotation(mc.player, targetYaw, targetPitch);
    }

    /**
     * 移动包发送完毕后（Baritone POST 逻辑）：
     * 恢复原朝向，客户端静默（服务器已收到正确旋转，移动方向也已正确）。
     * 最后清除状态，等待调用方下个 tick 刷新。
     */
    @EventHandler
    private static void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        // 延迟动作（如攻击包）不依赖矫正状态，始终执行：
        // 此刻移动包（含旋转）已发出，服务器视角已到位，攻击包此时发出才能命中。
        if (!postSendActions.isEmpty()) {
            List<Runnable> actions = new ArrayList<>(postSendActions);
            postSendActions.clear();
            for (Runnable action : actions) {
                try {
                    action.run();
                } catch (Exception e) {
                    ModernSupport.LOG.error("[移动矫正] runAfterSend 回调执行异常", e);
                }
            }
        }

        if (!active || mc.player == null) return;

        if (++debugTick % 100 == 0) {
            ModernSupport.LOG.info("[移动矫正] 发包后 恢复朝向 原yaw={} holdTicks={}",
                prevYaw, holdTicks);
        }
        // 服务器视角继续保持在目标方向，等待归零后归位
        if (holdTicks > 0) {
            holdTicks--;
            applyRotation(mc.player, prevYaw, prevPitch);
            return;
        }

        applyRotation(mc.player, prevYaw, prevPitch);

        if (pendingCallback != null) {
            pendingCallback.run();
            pendingCallback = null;
        }

        active = false;
        currentMode = Mode.OFF;
    }
}
