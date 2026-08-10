/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 fish22
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

import fish22.modernsupport.gui.WModulePageButton;
import fish22.modernsupport.utils.ModulePages;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.tabs.builtin.ModulesTab;
import meteordevelopment.meteorclient.gui.widgets.WTopBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶部 Tab 栏注入：
 * 在原有标签前插入页面按钮（主界面 + 自定义页），
 * 并移除原 Modules 标签（其位置被"主界面"按钮替代）。
 */
@Mixin(value = WTopBar.class, remap = false)
public abstract class MixinWTopBar {

    @Inject(method = "init", at = @At("HEAD"))
    private void onInitHead(CallbackInfo ci) {
        if (ModulePages.get() == null) return;

        WTopBar self = (WTopBar) (Object) this;
        // 主界面按钮（始终第一）
        self.add(new WModulePageButton(0));
        // 自定义页面按钮
        for (int i = 1; i < ModulePages.get().getPages().size(); i++) {
            self.add(new WModulePageButton(i));
        }
    }

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/tabs/Tabs;get()Ljava/util/List;"))
    private List<Tab> redirectTabsGet() {
        // 原 Modules 标签由"主界面"按钮替代，不再单独渲染
        List<Tab> filtered = new ArrayList<>();
        for (Tab tab : Tabs.get()) {
            if (!(tab instanceof ModulesTab)) {
                filtered.add(tab);
            }
        }
        return filtered;
    }
}
