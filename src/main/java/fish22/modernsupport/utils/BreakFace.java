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

import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 挖掘面（挖掘包里报给服务端的 face）怎么选
 *
 * <p>射线面：从眼睛朝方块中心打一条射线，取射线「进入方块」的那一面，也就是眼睛真能看到的那一面。
 * Grim 的 PositionBreakA（"Tried to break a block face from an impossible eye position"）只放行这种面，
 * 报一个眼睛看不到的面会把包直接取消，表现就是「方块挖不动」
 *
 * <p>原版面：Meteor 的 {@link BlockUtils#getDirection}，按方块的**碰撞箱**猜面。
 * 甘蔗/竹子这类没有碰撞箱的方块会猜出一个眼睛根本看不到的面 —— 站在平地上挖自己头顶那一格时
 * 会被反作弊直接取消，表现就是「平地站着挖不动，跳一下才好用」（跳起来时眼睛进到方块里，
 * 反作弊对「人已经在方块里」的情况直接放行）
 */
public enum BreakFace {
    RAY("射线面"),
    VANILLA("原版面");

    private final String displayName;

    BreakFace(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /** 按这个模式算出该报给服务端的挖掘面 */
    public Direction pick(BlockPos pos) {
        return this == RAY ? ray(pos) : BlockUtils.getDirection(pos);
    }

    /**
     * 射线面：从眼睛朝方块中心打一条射线，取射线「进入方块」的那一面
     * <p>
     * 这个面必须是眼睛真正能看到的面（比如挖头顶上方的方块要报底面），否则反作弊会判定
     * 玩家不可能从那个角度挖这一面，直接把包取消掉
     */
    public static Direction ray(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();

        double dx = pos.getX() + 0.5 - eye.x();
        double dy = pos.getY() + 0.5 - eye.y();
        double dz = pos.getZ() + 0.5 - eye.z();

        // 每个轴各算一个「射线进入方块」的参数，参数最大的那个轴就是真正的进入面
        double tx = entryT(eye.x(), dx, pos.getX());
        double ty = entryT(eye.y(), dy, pos.getY());
        double tz = entryT(eye.z(), dz, pos.getZ());

        if (tx >= ty && tx >= tz) return dx > 0 ? Direction.WEST : Direction.EAST;
        if (ty >= tx && ty >= tz) return dy > 0 ? Direction.DOWN : Direction.UP;
        return dz > 0 ? Direction.NORTH : Direction.SOUTH;
    }

    /**
     * 射线在某个轴上进入方块的参数 t
     * <p>
     * 眼睛已经落在方块这个轴的范围内（t &lt; 0）或者射线和这个轴平行时返回 -∞，
     * 表示这个轴不决定进入面，由别的轴决定
     */
    private static double entryT(double eyeCoord, double dir, int blockMin) {
        if (dir == 0) return Double.NEGATIVE_INFINITY;

        double t = ((dir > 0 ? blockMin : blockMin + 1) - eyeCoord) / dir;
        return t < 0 ? Double.NEGATIVE_INFINITY : t;
    }
}
