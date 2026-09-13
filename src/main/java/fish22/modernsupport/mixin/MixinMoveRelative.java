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
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 移动方向运算 mixin（对应 Baritone 的 Entity#moveRelative 处理）
 *
 * <p>原版把 WASD 输入换算成世界方向时会读玩家朝向（{@code moveRelative} 内部的
 * {@code getYRot()}）。这里在这一次调用期间临时把玩家朝向换成合法转头的真实角度，
 * 算完立刻换回视角角度：
 *
 * <ul>
 *   <li>移动方向永远和发给服务器的朝向一致（服务器预测不会分叉 → 不回弹）；</li>
 *   <li>玩家自己的视角角度全程不变（不闪视角、鼠标可以自由转）。</li>
 * </ul>
 *
 * <p>合法转头没有激活时 {@link LegalRotation#pushMoveWindow()} 什么都不做，
 * 完全等价原版。
 */
@Mixin(Entity.class)
public class MixinMoveRelative {

    @Inject(method = "moveRelative", at = @At("HEAD"))
    private void onMoveRelativeHead(CallbackInfo ci) {
        if ((Object) this == mc.player) LegalRotation.pushMoveWindow();
    }

    @Inject(method = "moveRelative", at = @At("RETURN"))
    private void onMoveRelativeReturn(CallbackInfo ci) {
        if ((Object) this == mc.player) LegalRotation.popMoveWindow();
    }
}
