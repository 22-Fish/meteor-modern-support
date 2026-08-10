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
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 静默模式按键映射 mixin
 *
 * <p>静默模式（QUIET）下，服务器朝向与客户端视觉朝向不一致：
 * 服务器朝目标旋转方向，但客户端视角保持原样。此时把 WASD 按键输入
 * 旋转到服务器朝向坐标系，使移动方向与客户端视觉朝向一致
 * （参考 LiquidBounce MovementCorrection.SILENT）。
 *
 * <p>例：服务器朝向正右（90°）、客户端视觉朝正前（0°）时，
 * W 键映射为 A 键的效果，人物仍朝视觉正前方移动；
 * 斜向时按角度映射出 W+D 这类组合键效果。
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickTail(CallbackInfo ci) {
        // 仅静默模式生效
        if (!MovementCorrection.isActive() || MovementCorrection.getMode() != MovementCorrection.Mode.QUIET) {
            return;
        }

        // 当前按键输入：z = 前正后负，x = 左正右负
        float z = (keyPresses.forward() ? 1 : 0) - (keyPresses.backward() ? 1 : 0);
        float x = (keyPresses.left() ? 1 : 0) - (keyPresses.right() ? 1 : 0);
        if (z == 0 && x == 0) {
            // 没按移动键，无需映射
            return;
        }

        // 客户端视觉朝向与服务器朝向的差值
        float deltaYaw = MovementCorrection.getVisualYaw() - MovementCorrection.getTargetYaw();
        double rad = Math.toRadians(deltaYaw);

        // 输入向量旋转（LiquidBounce 同款公式）
        float newX = (float) (x * Math.cos(rad) - z * Math.sin(rad));
        float newZ = (float) (z * Math.cos(rad) + x * Math.sin(rad));

        // 量化到按键组合（支持 WD 这类斜向组合键），用于输入包与冲刺判断
        int moveForward = Math.round(newZ);
        int moveSideways = Math.round(newX);

        keyPresses = new Input(
            moveForward > 0,
            moveForward < 0,
            moveSideways > 0,
            moveSideways < 0,
            keyPresses.jump(),
            keyPresses.shift(),
            keyPresses.sprint()
        );

        // 移动向量按映射后的按键重新计算，与输入包完全一致（LiquidBounce 同款）：
        // 避免浮点方向与输入包不一致，被服务器移动模拟判定异常回弹
        float f = keyPresses.forward() == keyPresses.backward() ? 0.0F : (keyPresses.forward() ? 1.0F : -1.0F);
        float g = keyPresses.left() == keyPresses.right() ? 0.0F : (keyPresses.left() ? 1.0F : -1.0F);
        moveVector = new Vec2(g, f).normalized();
    }
}
