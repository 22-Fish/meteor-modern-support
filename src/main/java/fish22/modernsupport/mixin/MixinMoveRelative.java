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
 * <p>同一个窗口也包住 {@code move(MoverType, Vec3)}：它里面算
 * {@code minorHorizontalCollision = isHorizontalCollisionMinor(...)} 同样读 {@code getYRot()}
 * （原版「这次贴墙算不算正面撞」的判定），不包的话客户端按视角角度分类、服务端按移动包里的
 * 朝向分类，撞墙那几 tick 的动量处理和疾跑状态就会分叉（{@code SprintE} / 位置预测拉回）。
 *
 * <p>窗口里所有读朝向的地方都由 {@link MixinEntityRotationWindow} 统一返回真实角度，
 * 所以窗口只需要「开」对地方，不需要逐个方法去改。窗口只覆盖移动运算，不覆盖输入处理
 * （{@code applyInput} 里手部晃动的 {@code xBob}/{@code yBob} 读的还是视角角度）和渲染。
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

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void onMoveHead(CallbackInfo ci) {
        if ((Object) this == mc.player) LegalRotation.pushMoveWindow();
    }

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("RETURN"))
    private void onMoveReturn(CallbackInfo ci) {
        if ((Object) this == mc.player) LegalRotation.popMoveWindow();
    }
}
