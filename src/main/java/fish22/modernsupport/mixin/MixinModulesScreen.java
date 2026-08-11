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

import fish22.modernsupport.utils.ModulePages;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * ModulesScreen 注入：
 * <ol>
 *   <li>打开时若不是通过顶部页面按钮进入，重置当前页为主界面（"开启菜单展示的就是主界面"）</li>
 *   <li>搜索结果显示模块所属页面标签（跨页面搜索，[页面xxx] 前缀）</li>
 *   <li>分类窗口 id 按页面区分，各页面板块窗口的位置/折叠状态独立保存</li>
 * </ol>
 */
@Mixin(value = ModulesScreen.class, remap = false)
public abstract class MixinModulesScreen {

    @Inject(method = "init", at = @At("HEAD"))
    private void onInitHead(CallbackInfo ci) {
        ModulePages pages = ModulePages.get();
        if (pages == null) return;

        if (!pages.isOpeningViaBar()) {
            pages.setCurrent(0);
        }
        pages.setOpeningViaBar(false);
    }

    @Redirect(method = "createSearchW", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;module(Lmeteordevelopment/meteorclient/systems/modules/Module;Ljava/lang/String;)Lmeteordevelopment/meteorclient/gui/widgets/WWidget;"))
    private WWidget redirectSearchModule(GuiTheme theme, Module module, String searchText) {
        ModulePages pages = ModulePages.get();
        List<String> pageNames = pages.pagesOf(module);
        // 本界面（当前页）已展示的模块：完全不加前缀（即使也在其他页面）
        String currentPageName = pages.getPage(pages.getCurrent()).name;
        if (pageNames.contains(currentPageName) || pageNames.isEmpty()) {
            return theme.module(module, searchText);
        }
        // 不在本界面的模块：只标注第一个所属页面
        WHorizontalList list = theme.horizontalList();
        WLabel label = list.add(theme.label("[" + pageNames.get(0) + "] ")).widget();
        if (theme instanceof MeteorGuiTheme meteorTheme) {
            label.color = meteorTheme.textSecondaryColor.get();
        }
        list.add(theme.module(module, searchText)).expandX();
        return list;
    }

    @Redirect(method = "createCategory", at = @At(value = "FIELD", target = "Lmeteordevelopment/meteorclient/gui/widgets/containers/WWindow;id:Ljava/lang/String;", opcode = Opcodes.PUTFIELD))
    private void redirectCategoryWindowId(WWindow window, String value) {
        // 窗口 id 按页面区分：各页面板块窗口的位置/折叠状态独立保存
        window.id = "modulepage_" + ModulePages.get().getCurrent() + "_" + value;
    }
}
