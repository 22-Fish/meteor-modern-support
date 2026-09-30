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
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 配合 {@link MixinWView}：滚动列表不再开裁剪区之后，
 * 露一半的控件直接不画（原来是被裁剪区切掉一半），否则它会画到列表外面去。
 *
 * <p>三种控件不跳过：
 * <ul>
 *   <li>完全在可视区里的（正常画）</li>
 *   <li>直接挂在滚动区上、而且比可视区还高的内容容器（比如 Config/GUI 页那一整块设置列表）。
 *       它的背景本来就铺满可视区，露在外面的子控件会各自被跳过，跳过它反而会整页空白</li>
 *   <li>关掉「不可见控件剔除」开关时</li>
 * </ul>
 *
 * <p>meteor 自己已经会跳过「完全在可视区外」的控件，这里只补「露一半」这种情况。
 */
@Mixin(value = WContainer.class, remap = false)
public abstract class MixinWContainer {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void modernsupport$skipOutsideView(WWidget widget, GuiRenderer renderer, double mouseX, double mouseY, double delta, CallbackInfo ci) {
        if (!RenderSettings.singleGuiPassEnabled()) return;
        if (!RenderSettings.cullInvisibleWidgetsEnabled()) return;

        WView view = widget.getView();
        if (view == null) return;

        // 完整可见
        if (widget.y >= view.y && widget.y + widget.height <= view.y + view.height) return;

        // 装内容的大容器（比可视区高，直接挂在滚动区上），交给它里面的子控件去判断
        if (widget.parent == view && widget.height >= view.height) return;

        ci.cancel();
    }
}
