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

import fish22.modernsupport.gui.ModuleVisibilityScreen;
import fish22.modernsupport.utils.ModuleVisibility;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.systems.modules.Category;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * 窗口标题栏右键：模块列表里的板块（分类窗口）本来是收起/展开，改成打开该板块的模块显隐界面。
 * 收起照样能左键标题栏或者点右边的三角，别的窗口（搜索/收藏）不变。
 */
@Mixin(targets = "meteordevelopment.meteorclient.gui.widgets.containers.WWindow$WHeader", remap = false)
public abstract class MixinWWindowHeader {
    @Inject(method = "onMouseClicked", at = @At("HEAD"), cancellable = true)
    private void modernsupport$openVisibilityScreen(MouseButtonEvent click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() != GLFW_MOUSE_BUTTON_RIGHT) return;
        if (!(mc.screen instanceof ModulesScreen)) return;

        // WHeader 就是 WContainer，parent 是它所属的窗口
        WContainer header = (WContainer) (Object) this;
        if (!header.mouseOver || doubled) return;
        if (!(header.parent instanceof WWindow window)) return;

        int pageIdx = ModuleVisibility.pageIndexOfWindow(window.id);
        Category category = ModuleVisibility.categoryOfWindow(window.id);
        if (pageIdx < 0 || category == null) return;

        cir.setReturnValue(true);
        mc.setScreen(new ModuleVisibilityScreen(header.theme, window, category, pageIdx));
    }
}
