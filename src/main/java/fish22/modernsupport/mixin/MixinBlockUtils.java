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

package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.MovementCorrection;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * BlockUtils.place 的旋转重定向 mixin
 *
 * <p>{@link BlockUtils#place} 内部在 rotate 为 true 时调用
 * {@link Rotations#rotate(double, double, int, Runnable)} 静默转向后再放置方块。
 * 本 mixin 精确拦截这一个调用点，当存在「方块放置矫正上下文」（由 SpawnProofer 等
 * 模块在调用 place 前通过 {@link MovementCorrection#beginPlace} 设置）时，
 * 把这次旋转替换成移动矫正（真实旋转 + 客户端静默），放置回调在移动包发送后执行，
 * 服务器视角正确到位后再发出放置包。
 *
 * <p>没有上下文（默认）时原样回退 {@link Rotations#rotate}，其他模块行为完全不变。
 */
@Mixin(value = BlockUtils.class, remap = false)
public abstract class MixinBlockUtils {

    @Redirect(
        method = "place(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/InteractionHand;IZIZZZ)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V"
        )
    )
    private static void redirectRotate(double yaw, double pitch, int priority, Runnable callback) {
        MovementCorrection.Mode mode = MovementCorrection.getPlaceMode();
        if (mode == MovementCorrection.Mode.SEVERE || mode == MovementCorrection.Mode.QUIET) {
            // 用移动矫正替代原版静默旋转，放置动作（swap + interact + swapBack）延后到移动包发送后
            MovementCorrection.rotate(yaw, pitch, mode, callback);
        } else {
            // 关闭 / 未设置上下文：回退原版静默旋转
            Rotations.rotate(yaw, pitch, priority, callback);
        }
    }
}
