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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 移动窗口内读朝向统一返回真实角度
 *
 * <p>合法转头只在「本 tick 的移动运算」这一段窗口里用真实角度（见
 * {@link LegalRotation#pushMoveWindow()} / {@link LegalRotation#isInMoveWindow()}）。
 * 窗口里除了我们主动包住的方法，还有别的会读朝向的原版代码，比如
 * {@code LivingEntity#jumpFromGround} 里疾跑起跳那个 0.2 冲量、
 * {@code Entity#move} 里算 {@code minorHorizontalCollision} 用的
 * {@code LocalPlayer#isHorizontalCollisionMinor}。
 *
 * <p>只要有一处读到的是视角角度，客户端这一 tick 算出来的速度方向就和服务端预测的差
 * 一个「目标偏航 − 视角偏航」；走路时地面摩擦把差异吃掉不明显，疾跑起跳那一 tick 会直接
 * 分叉被拉回（误差 ∝ sin(差角)，所以目标偏航和视角偏航一致时看不出问题）。
 *
 * <p>这里在窗口开着时直接把读取结果换成真实角度，窗口里不管哪段代码读都拿到服务端那一份。
 * 窗口平时是关的（渲染、相机、鼠标读的还是视角角度），相机和画面完全不受影响。
 */
@Mixin(Entity.class)
public class MixinEntityRotationWindow {

    @Inject(method = "getYRot()F", at = @At("HEAD"), cancellable = true)
    private void windowYaw(CallbackInfoReturnable<Float> cir) {
        if ((Object) this == mc.player && LegalRotation.isInMoveWindow()) {
            cir.setReturnValue(LegalRotation.getRealYaw());
        }
    }

    @Inject(method = "getXRot()F", at = @At("HEAD"), cancellable = true)
    private void windowPitch(CallbackInfoReturnable<Float> cir) {
        if ((Object) this == mc.player && LegalRotation.isInMoveWindow()) {
            cir.setReturnValue(LegalRotation.getRealPitch());
        }
    }
}
