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
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import fish22.modernsupport.utils.RenderPassTweaks;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * meteor 每次画一批东西（界面方块、文字、透视框）都会开一个渲染通道。
 * 通道挂不挂深度附件是按管线的深度状态来的，很多管线其实根本用不到深度，
 * 却照样让显卡读一遍写一遍整屏深度（见 {@link RenderPassTweaks}）。
 *
 * <p>这里只改「开通道时挂不挂深度」这一件事，绘制内容、顺序、次数都不动。
 */
@Mixin(value = MeshRenderer.class, remap = false)
public abstract class MixinMeshRenderer {
    @Shadow @Nullable private RenderPipeline pipeline;

    /**
     * 原版逻辑是「管线要深度就带深度附件开通道」，这里在管线根本用不到深度时换成不带深度的开法。
     * （原版自己的三参数 createRenderPass 就是不带深度的那个版本）
     */
    @Redirect(
        method = "end",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;")
    )
    private RenderPass modernsupport$skipUnusedDepth(CommandEncoder encoder, Supplier<String> name, GpuTextureView color,
                                                    OptionalInt clearColor, GpuTextureView depth, OptionalDouble clearDepth) {
        if (RenderPassTweaks.unusedDepth(pipeline)) {
            return encoder.createRenderPass(name, color, clearColor);
        }

        return encoder.createRenderPass(name, color, clearColor, depth, clearDepth);
    }
}
