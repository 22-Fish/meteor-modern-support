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

import fish22.modernsupport.utils.ModulePages;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 页面分类勾选界面：列出所有分类（分组），勾选 = 该页面展示此分类下的所有模块。
 * 每个页面是一份独立名单，一个分类可同时勾选到多个页面。
 */
public class ModuleSelectScreen extends WindowScreen {
    private final int pageIdx;
    private WTable table;
    private String filterText = "";

    public ModuleSelectScreen(GuiTheme theme, int pageIdx) {
        super(theme, "分类列表 - " + ModulePages.get().getPage(pageIdx).name);
        this.pageIdx = pageIdx;
    }

    @Override
    public void initWidgets() {
        WTextBox filter = add(theme.textBox("")).minWidth(300).expandX().widget();
        filter.setFocused(true);
        filter.action = () -> {
            filterText = filter.get().trim().toLowerCase();
            table.clear();
            initTable();
        };

        table = add(theme.table()).expandX().widget();
        initTable();
    }

    private void initTable() {
        List<Category> categories = new ArrayList<>();
        for (Category category : Modules.loopCategories()) {
            categories.add(category);
        }
        categories.sort(Comparator.comparing(category -> category.name));

        ModulePages.Page page = ModulePages.get().getPage(pageIdx);

        for (Category category : categories) {
            if (!filterText.isEmpty() && !category.name.toLowerCase().contains(filterText)) {
                continue;
            }

            table.add(theme.label(category.name));

            WCheckbox checkbox = table.add(theme.checkbox(page.categories.contains(category.name))).expandCellX().right().widget();
            checkbox.action = () -> ModulePages.get().toggleCategory(pageIdx, category.name, checkbox.checked);

            table.row();
        }
    }
}
