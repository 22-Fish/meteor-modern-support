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

package fish22.modernsupport.gui;

import fish22.modernsupport.utils.I18n;
import fish22.modernsupport.utils.ModuleVisibility;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * 板块显隐界面：右键模块列表里的板块标题栏打开。
 * 只有一个列表，勾选 = 此模块在本板块显示，取消勾选 = 关闭（隐藏）。
 */
public class ModuleVisibilityScreen extends WindowScreen {
    /** 被设置的那个板块窗口（点一下立刻重建，关掉界面就能看到效果） */
    private final WWindow panel;
    private final Category category;
    private final int pageIdx;

    public ModuleVisibilityScreen(GuiTheme theme, WWindow panel, Category category, int pageIdx) {
        super(theme, I18n.t("Text.module-visibility-format", "模块显隐 - %s").formatted(category.name));
        this.panel = panel;
        this.category = category;
        this.pageIdx = pageIdx;
    }

    @Override
    public void initWidgets() {
        WTable table = add(theme.table()).expandX().widget();

        for (Module module : Modules.get().getGroup(category)) {
            table.add(theme.label(module.title));

            WCheckbox checkbox = table.add(theme.checkbox(ModuleVisibility.isVisible(pageIdx, category, module))).expandCellX().right().widget();
            checkbox.action = () -> {
                ModuleVisibility.setVisible(pageIdx, category, module, checkbox.checked);
                ModuleVisibility.rebuild(theme, panel, category, pageIdx);
            };

            table.row();
        }
    }
}
