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

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fish22.modernsupport.modules.SilentSwitchConfig;
import fish22.modernsupport.utils.SilentSwitch;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 静默切换：第一视角手上那件物品画哪一格（只换要画的那件，动画、姿势一概不动）
 * <p>
 * 原版手部渲染读的是「手上拿的是什么」，接管期间 {@code Inventory} 那边读的是静默槽位
 * （见 {@link MixinInventorySilentSwitch}），所以画面上的手会跟着静默切换换成工具那一格
 * <p>
 * 开了「静默切换固定手部物品渲染」（默认关）时，接管期间换成「画面选中那一格」的东西
 * （见 {@link SilentSwitch#handRenderItem}）：手上是玩家自己看着选中的那件，
 * 不会跟着静默切换跑，玩家自己切槽手也跟着换
 * <p>
 * 除了「画哪件」和下面那条「别播换手动画」，举多高、怎么挥、进食姿势这些全是原版自己那套
 * <p>
 * tick 是原版定下「手部渲染用哪件」的地方，{@code evaluateWhichHandsToRender} 是决定画几只手的
 * 地方，两处用同一件物品，画面和判断不会一个显示本地一个显示服务端
 * <p>
 * tick 末尾还把主手的高度钉在「完全举起」（见 {@link SilentSwitch#freezeHandRender}）：
 * 服务端手持物每换一次，原版就把换手动画进度清零，动画从头播 —— 画面固定成本地那件之后
 * 画面上没换东西却一直往下沉，就是这个来的
 */
@Mixin(value = ItemInHandRenderer.class, remap = false)
public class MixinItemInHandRendererSilentSwitch {

    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float oMainHandHeight;

    @ModifyExpressionValue(
        method = {"tick", "evaluateWhichHandsToRender"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"
        )
    )
    private static ItemStack meteorsupport$fixedHandItem(ItemStack original) {
        return SilentSwitch.handRenderItem(original);
    }

    /**
     * 静默切换：固定手部物品渲染时不播「换手动画」
     * <p>
     * 接管期间（以及放开后画面还没换东西那几 tick）把主手高度钉在「完全举起」，换手偏移为 0，
     * 物品不会往下沉；原版记账的那件也跟着画面走，免得画的和判断的对不上
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void meteorsupport$freezeHandSwitch(CallbackInfo ci) {
        if (!SilentSwitchConfig.isFixedHandItemRender()) return;

        ItemStack shown = SilentSwitch.fixedHandItem();
        if (!SilentSwitch.freezeHandRender(shown)) return;

        mainHandItem = shown;
        mainHandHeight = 1.0F;
        oMainHandHeight = 1.0F;
    }
}
