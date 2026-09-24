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

import meteordevelopment.meteorclient.utils.player.ChatUtils;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 水晶光环（官方 CrystalAura）合法化改造用的公共部分。
 *
 * <h3>待用角度（先转头、后交互）</h3>
 * 官方 CrystalAura 的旋转走的是 Meteor 的 {@code Rotations.rotate(..., Runnable)}：
 * 自己发旋转包，回调里再发交互包。本类存一份「这一下应该转到的合法角度」，
 * 由 {@link fish22.modernsupport.mixin.MixinRotations} 在官方那次调用上截住，
 * 换成 {@link LegalRotation}（合法转头：朝向跟着移动包走，回调排在移动包之后）。
 */
public final class LegalCrystal {

    /**
     * 官方 {@code Rotations.rotate} 里「水晶光环那一次」用的优先级：
     * 官方放置与破坏都传 50（偏航步骤检查内部那次是 -100，别的模块各有各的）。
     *
     * <p>{@link fish22.modernsupport.mixin.MixinRotations} 只认这个数：认错了就会把别人的
     * 转向换成水晶这份角度。锚光环也用 50，所以它自己错开 1（见 MixinAnchorAura#officialRotationPriority）。
     */
    public static final int CRYSTAL_ROTATION_PRIORITY = 50;

    /**
     * 一份「还没随移动包发出去的合法旋转」。
     *
     * @param yaw      偏航（已经按鼠标灵敏度量化过，见 {@link LegalPlace}）
     * @param pitch    俯仰（同上）
     * @param mode     合法转头模式
     * @param priority 优先级（同一 tick 里和其它模块抢转向用）
     */
    public record Pending(double yaw, double pitch, LegalRotation.Mode mode, int priority) {
    }

    private static Pending pending;

    /** 已经取用过（官方那次旋转调用只算一次，不重复用同一份角度） */
    private static boolean consumed;

    /** 取用之后真的换成合法转头了（抢不过更高优先级的那份旋转时不算） */
    private static boolean applied;

    /** 存进来时的游戏刻：换 tick 就作废，防止漏到别的模块的旋转调用上 */
    private static long tick;

    private LegalCrystal() {
    }

    /** 水晶光环的「调试输出」开关（排查用，默认关） */
    public static boolean debug;

    /** 调试输出：开启时往聊天栏打一行（不带坐标） */
    public static void log(String format, Object... args) {
        if (!debug) return;
        ChatUtils.info("[水晶光环] " + format, args);
    }

    /** 排一份待用的合法角度（模式为关闭时等于清掉） */
    public static void setPending(double yaw, double pitch, LegalRotation.Mode mode, int priority) {
        if (mode == null || mode == LegalRotation.Mode.OFF) {
            clear();
            return;
        }

        pending = new Pending(yaw, pitch, mode, priority);
        consumed = false;
        applied = false;
        tick = currentTick();
    }

    /** 取用这份角度（同一 tick 只取一次，取不到返回 null） */
    public static Pending peek() {
        if (pending == null || consumed || currentTick() != tick) return null;
        return pending;
    }

    /** 取用这份角度（同一 tick 只取一次，取不到返回 null） */
    public static Pending take() {
        Pending p = peek();
        if (p == null) return null;
        consumed = true;
        return p;
    }

    /** 本 tick 这份角度真的被用来转头了吗（没被用上时交互包不能改成这份角度的命中点） */
    public static boolean appliedThisTick() {
        return applied && currentTick() == tick;
    }

    /** 合法转头确实排上了（{@link LegalRotation#rotate} 返回 true 时调） */
    public static void markApplied() {
        if (pending != null && consumed) applied = true;
    }

    public static void clear() {
        pending = null;
        consumed = false;
        applied = false;
        tick = Long.MIN_VALUE;
    }

    /** 当前游戏刻（拿不到世界时用 -1，等值比较同样能作废） */
    private static long currentTick() {
        return mc == null || mc.level == null ? -1L : mc.level.getGameTime();
    }
}
