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
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 可见旋转方向 mixin —— 让玩家模型在第三视角显示服务器看到的朝向
 *
 * <p>合法转头全程只动「这一 tick 的移动运算与移动包」，视角角度、相机、鼠标输入都不变；
 * 所以第三视角里看到的一直是玩家自己的视角朝向，看不出服务器认为你在看哪。
 * 模块「合法转头API配置」的「可见旋转方向」（默认开启）就是补上这块显示。
 *
 * <p>做法是渲染状态上的覆盖：原版 {@code LivingEntityRenderer#extractRenderState}
 * 把实体角度抄进 {@code LivingEntityRenderState}（模型只认这份数据，相机不认），
 * 这里在它抄完之后、对<b>本地玩家</b>把三个字段改成合法转头的真实角度：
 *
 * <ul>
 *   <li>{@code bodyRot}：身体的绝对偏航；</li>
 *   <li>{@code yRot}：头相对身体的角度（正是它相对身体，所以写 0 让头和身体同向）；</li>
 *   <li>{@code xRot}：头的俯仰；</li>
 *   <li>{@code shouldApplyFlyingYRot} / {@code flyingYRot}（滑翔专用）：滑翔时原版会再加一个
 *       「视角 → 移动方向」的偏航偏移，而合法转头的移动方向是按真实角度算的，这个偏移会
 *       把显示方向带偏，所以一并归零。</li>
 * </ul>
 *
 * <p>相机、鼠标、发包、移动运算一概不碰；第一人称不渲染玩家模型，自然看不到。
 * 没有合法转头（或设置关闭）时这里什么都不做，完全等价原版。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinVisibleRotation {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
    private void meteor$visibleRealRotation(LivingEntity entity, LivingEntityRenderState state, float tickDelta, CallbackInfo ci) {
        // 只改本地玩家：别人的朝向由服务器同步，改了就错了
        if (entity != mc.player) return;
        if (!LegalRotation.isDisplayingRealRotation()) return;

        state.bodyRot = LegalRotation.getDisplayYaw();
        state.yRot = 0.0f;   // 头相对身体的角度：头和身体同向
        state.xRot = LegalRotation.getDisplayPitch();

        // 滑翔姿势的额外偏航偏移（视角 → 移动方向）按真实角度算就不对了，归零
        if (state instanceof AvatarRenderState avatarState) {
            avatarState.shouldApplyFlyingYRot = false;
            avatarState.flyingYRot = 0.0f;
        }
    }
}
