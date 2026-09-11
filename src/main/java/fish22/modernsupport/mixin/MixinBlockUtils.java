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

import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * BlockUtils.place 的旋转重定向 mixin
 *
 * <p>当存在「方块放置转头上下文」时，用合法转头替代原版 Rotations.rotate。
 * 合法转头会立即同步 yRot+yRotO（渲染瞬间到位，摄像机不卡），
 * 放置回调立即执行（不等移动包）。
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
        LegalRotation.Mode mode = LegalRotation.getPlaceMode();
        if (mode == LegalRotation.Mode.SEVERE || mode == LegalRotation.Mode.QUIET) {
            // 合法转头：立即设 yRot+yRotO + 立即执行放置回调
            LegalRotation.rotate(yaw, pitch, mode);
            callback.run();
        } else {
            Rotations.rotate(yaw, pitch, priority, callback);
        }
    }
}
