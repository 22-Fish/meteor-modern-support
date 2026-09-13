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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 起跳 / 滑翔方向的合法转头 mixin（对应 Baritone 的 jumpFromGround 与 travel 处理）
 *
 * <ul>
 *   <li>{@code jumpFromGround}：起跳时水平冲量的方向由朝向决定，用真实角度算；</li>
 *   <li>{@code updateFallFlyingMovement}：滑翔速度运算读 {@code getLookAngle()} 与
 *       {@code getXRot()}（滑翔抬升、俯冲加速、视线对齐），用真实角度算。
 *       甲飞（穿胸甲假飞）也是直接调用这个方法，所以同样被覆盖。</li>
 * </ul>
 *
 * <p>两处都是「进入时临时换成真实角度、返回前立刻换回视角角度」，窗口只在这一次
 * 运算内部，渲染与鼠标处理永远在窗口之外。合法转头没激活时不做任何事。
 */
@Mixin(LivingEntity.class)
public class MixinLivingEntityMovement {

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void onJumpHead(CallbackInfo ci) {
        if ((Object) this == mc.player) LegalRotation.pushMoveWindow();
    }

    @Inject(method = "jumpFromGround", at = @At("RETURN"))
    private void onJumpReturn(CallbackInfo ci) {
        if ((Object) this == mc.player) LegalRotation.popMoveWindow();
    }

    @Inject(method = "updateFallFlyingMovement", at = @At("HEAD"))
    private void onGlideHead(CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this == mc.player) LegalRotation.pushMoveWindow();
    }

    @Inject(method = "updateFallFlyingMovement", at = @At("RETURN"))
    private void onGlideReturn(CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this == mc.player) LegalRotation.popMoveWindow();
    }
}
