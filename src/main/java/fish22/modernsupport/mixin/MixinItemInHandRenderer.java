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

import com.mojang.blaze3d.vertex.PoseStack;
import fish22.modernsupport.utils.HandViewSupport;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.ArmRenderEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 手部视图「渲染副手」：副手空着时也把副手那条手臂画出来。
 *
 * <p>原版 {@code renderArmWithItem} 里「空手就画手臂」那一段只剩主手（它以「是不是主手」当条件，
 * 副手直接跳过），所以副手空着时画面里根本没有副手的手臂，只有拿东西时才有。
 *
 * <p>这里挂在原版已经决定要画副手的那次调用（renderHandsWithItems 里第二次 renderArmWithItem）后面：
 * 拿弓蓄力、举着上膛的弩这些「原版只画一只手」的场合，原版自己就不会走到这一句，我们也就不补，
 * 判断逻辑不用再抄一遍。真走到这一步就照原版空主手的画法把副手补上，手臂左右由原版按
 * getMainArm().getOpposite() 选，皮肤/袖子自然是副手那只的（主手换到左手时自动对调）。
 */
@Mixin(value = ItemInHandRenderer.class, remap = false)
public abstract class MixinItemInHandRenderer {

    @Shadow
    @Final
    private ItemStack offHandItem;

    @Shadow
    private float offHandHeight;

    @Shadow
    private float oOffHandHeight;

    @Shadow
    @Final
    private ItemModelResolver itemModelResolver;

    @Shadow
    private void renderPlayerArm(PoseStack poseStack, SubmitNodeCollector collector, int light, float equipProgress, float swing, HumanoidArm arm) {
        throw new AssertionError();
    }

    @Inject(
        method = "renderHandsWithItems",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
            ordinal = 1,
            shift = At.Shift.AFTER
        )
    )
    private void meteorsupport$renderEmptyOffhand(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
        if (!HandViewSupport.renderOffhand()) return;
        if (!offHandItem.isEmpty()) return;

        // 原版空手画手臂的两条前提（renderArmWithItem 开头那条和空手分支里那条）
        if (player.isScoping() || player.isInvisible()) return;

        // 主手举着地图时原版走的是「双手地图」，那条路本来就不画手臂，别多塞一条
        if (player.getMainHandItem().has(DataComponents.MAP_ID)) return;

        // 参数照原版传给副手的那份算：挥的是副手才带挥动进度，换手进度用副手自己的两个值插值
        float swing = player.swingingArm == InteractionHand.OFF_HAND ? player.getAttackAnim(frameInterp) : 0f;
        float equip = itemModelResolver.swapAnimationScale(offHandItem) * (1f - Mth.lerp(frameInterp, oOffHandHeight, offHandHeight));

        poseStack.pushPose();
        // meteor「手臂」那组设置靠这个事件改姿势，原版空手那条路不发，这里替它发一次
        MeteorClient.EVENT_BUS.post(ArmRenderEvent.get(InteractionHand.OFF_HAND, poseStack));
        renderPlayerArm(poseStack, collector, light, equip, swing, player.getMainArm().getOpposite());
        poseStack.popPose();
    }
}
