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

import com.mojang.blaze3d.pipeline.RenderPipeline;
import fish22.modernsupport.ModernSupport;
import fish22.modernsupport.utils.MeteorLogoPainter;
import fish22.modernsupport.utils.MeteorLogoRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 加载画面（资源包重载 / 游戏启动）：把 Mojang logo 换成 meteor logo 动画。
 *
 * <p>原版在这个方法里先铺淡紫背景，再把 mojangstudios.png 分两半贴出来，
 * 最后画进度条。这里把那两次贴图顶掉，换成自己画的动画，背景和进度条保持不变。
 * 动画没播完之前拦住淡出，保证「加载完成」正好是点亮那一下。
 */
@Mixin(value = LoadingOverlay.class, remap = false)
public abstract class MixinLoadingOverlay {
    @Unique
    private long meteorsupport$animStart = -1L;
    @Unique
    private long meteorsupport$lightStart = -1L;
    /** 自己画失败过就退回原版 logo，别再每帧重试 */
    @Unique
    private boolean meteorsupport$failed;

    /** 原版 logo 上半部分：改成画我们的动画（位置按原版条带算） */
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
        ordinal = 0))
    private void meteorsupport$drawLogo(GuiGraphicsExtractor gfx, RenderPipeline pipeline, Identifier id,
                                        int x, int y, float u, float v, int width, int height,
                                        int texWidth, int texHeight, int uWidth, int vHeight, int color) {
        if (!MeteorLogoRenderer.enabled() || meteorsupport$failed) {
            meteorsupport$vanilla(gfx, pipeline, id, x, y, u, v, width, height, texWidth, texHeight, uWidth, vHeight, color);
            return;
        }

        long now = Util.getMillis();
        if (meteorsupport$animStart < 0L) meteorsupport$animStart = now;

        double drawMs = now - meteorsupport$animStart;
        double lightMs = meteorsupport$lightStart < 0L ? -1.0 : now - meteorsupport$lightStart;
        try {
            // 原版两次贴图拼起来是 4d 宽，这里按整条画
            MeteorLogoRenderer.draw(gfx, x, y, width * 2, height, color, drawMs, lightMs);
        } catch (Throwable t) {
            meteorsupport$failed = true;
            ModernSupport.LOG.error("Meteor 加载动画绘制失败，已退回原版 logo", t);
            meteorsupport$vanilla(gfx, pipeline, id, x, y, u, v, width, height, texWidth, texHeight, uWidth, vHeight, color);
        }
    }

    /** 原版 logo 下半部分：我们只画一张完整的，这一半不画 */
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
        ordinal = 1))
    private void meteorsupport$skipSecondHalf(GuiGraphicsExtractor gfx, RenderPipeline pipeline, Identifier id,
                                              int x, int y, float u, float v, int width, int height,
                                              int texWidth, int texHeight, int uWidth, int vHeight, int color) {
        if (!MeteorLogoRenderer.enabled() || meteorsupport$failed) {
            meteorsupport$vanilla(gfx, pipeline, id, x, y, u, v, width, height, texWidth, texHeight, uWidth, vHeight, color);
        }
    }

    /** 加载完了也别急着淡出：先把点亮动画放完 */
    @Inject(method = "isReadyToFadeOut", at = @At("RETURN"), cancellable = true)
    private void meteorsupport$holdForAnimation(CallbackInfoReturnable<Boolean> cir) {
        if (!MeteorLogoRenderer.enabled() || meteorsupport$failed || meteorsupport$animStart < 0L) return;
        if (!cir.getReturnValueZ()) return;

        long now = Util.getMillis();
        if (meteorsupport$lightStart < 0L) {
            meteorsupport$lightStart = Math.max(now, meteorsupport$animStart + (long) MeteorLogoPainter.LIGHT_DELAY);
        }

        if (now < meteorsupport$lightStart + (long) MeteorLogoPainter.LIGHT_DURATION) cir.setReturnValue(false);
    }

    /** 原版那两下贴图 */
    @Unique
    private void meteorsupport$vanilla(GuiGraphicsExtractor gfx, RenderPipeline pipeline, Identifier id,
                                       int x, int y, float u, float v, int width, int height,
                                       int texWidth, int texHeight, int uWidth, int vHeight, int color) {
        gfx.blit(pipeline, id, x, y, u, v, width, height, texWidth, texHeight, uWidth, vHeight, color);
    }
}
