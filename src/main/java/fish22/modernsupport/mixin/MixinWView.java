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

import fish22.modernsupport.utils.RenderSettings;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 滚动列表的裁剪是一次「换裁剪区就刷一次」。
 *
 * <p>meteor 界面每换一次裁剪区就把已画的东西刷出去（开一个渲染通道），
 * 一个可滚动的列表要刷两次，模块界面十几个窗口加起来一帧能开几十个通道，
 * 每开一次整屏颜色和深度都要读一遍写一遍，所以打开界面会明显掉帧。
 *
 * <p>这里不开裁剪，改成「只画完全落在列表可视区里的控件」（见 {@link MixinWContainer}）：
 * 原来被裁掉的内容现在直接不画，整帧界面只刷一次。
 * 代价是列表边缘只露一半的那个控件会整块出现/消失（原来是被裁掉一半）。
 */
@Mixin(value = WView.class, remap = false)
public abstract class MixinWView extends WVerticalList {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/renderer/GuiRenderer;scissorStart(DDDD)V"), require = 0)
    private void modernsupport$scissorStart(GuiRenderer renderer, double x, double y, double width, double height) {
        if (RenderSettings.singleGuiPassEnabled()) return;

        renderer.scissorStart(x, y, width, height);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/renderer/GuiRenderer;scissorEnd()V"), require = 0)
    private void modernsupport$scissorEnd(GuiRenderer renderer) {
        if (RenderSettings.singleGuiPassEnabled()) return;

        renderer.scissorEnd();
    }
}
