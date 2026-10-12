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

import fish22.modernsupport.utils.ElytraLagSyncSupport;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 甲飞「起飞限频绕过」的滑翔动作屏蔽 mixin —— 被撑住的假滑翔不要再把人摆成趴着的滑翔姿势
 *
 * <p>「起飞限频绕过」为了和服务器（反作弊那边也还认滑翔）对上，会把服务端发回来的「停滑」
 * 同步改回滑翔（见 {@link MixinSynchedEntityData}），本地滑翔位一直掉不下来。而渲染整段都是照
 * 这个位摆的：{@code AvatarRenderer#setupRotations} 按 {@code AvatarRenderState#fallFlyingScale()}
 * 把人转成趴着（-90° 俯仰），{@code HumanoidModel} 再把头顶起 45°、手脚摆动幅度按
 * {@code AvatarRenderState#speedValue}（滑翔时按速度平方算成几百，等于把四肢锁死）除一遍。
 *
 * <p>这里在渲染状态提取完之后（{@code AvatarRenderer#extractRenderState} 末尾）把这一帧的
 * 滑翔位和那个摆动除数一起还原成「没滑翔」：姿态旋转 0°、头按真实俯仰、手脚照常摆，
 * 看上去就是正常站立的样子。
 *
 * <p><b>只抹滑翔位是不够的</b>：{@code speedValue} 是在滑翔位还是真的那一步（同一次提取里更早）
 * 就算好的，只改滑翔位的话四肢仍然被那个大除数锁着，人物会变成直挺挺的僵尸，所以两个都要还原。
 *
 * <p><b>只改这一帧的渲染状态</b>：不动本地滑翔位、不动滑翔运算（{@link MixinElytraTravel}）、
 * 不动换装时序，绕过本身的时序完全不受影响；判断条件见
 * {@link ElytraLagSyncSupport#shouldSuppressGlideAnimation(net.minecraft.world.entity.Entity)}。
 */
@Mixin(AvatarRenderer.class)
public abstract class MixinElytraGlideAnimation {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL"))
    private void meteor$suppressGlideAnimation(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (!ElytraLagSyncSupport.shouldSuppressGlideAnimation(entity)) return;
        // 这一帧不摆滑翔：身体不转、头不仰
        state.isFallFlying = false;
        // 手脚摆动幅度是拿它当除数的（滑翔时几百，四肢就不动了），还原成站立时的 1
        state.speedValue = 1.0F;
    }
}
