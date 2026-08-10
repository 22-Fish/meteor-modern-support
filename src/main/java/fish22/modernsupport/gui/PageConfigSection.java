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

package fish22.modernsupport.gui;

import fish22.modernsupport.ModernSupport;
import fish22.modernsupport.utils.ModulePages;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 页面配置区块（模块分页管理）：
 * 新增页面（上限 {@link ModulePages#MAX_PAGES} 个）+ 每页一行
 * [模块列表按钮][重命名][删除]（主界面行无删除，始终排第一）。
 * <p>
 * 通过 {@link #addToSettings} 作为设置分组（WSection）挂到 Config 设置列表的
 * 末尾，与设置分组一起滚动、一起被重建，不受列表裁剪影响。
 * 继承 {@link WVerticalList}（垂直排列），不能直接继承 WContainer
 * （WContainer 默认把所有子控件堆叠在同一位置）。
 */
public class PageConfigSection extends WVerticalList {

    /**
     * 在设置列表（WSettings）末尾追加"页面配置"设置分组。
     * 由 MixinConfigScreen / MixinSettings 在创建设置列表时调用。
     */
    public static void addToSettings(WContainer settingsContainer, GuiTheme theme) {
        ModernSupport.LOG.info("[模块分页] 向设置列表追加页面配置分组");
        WSection section = settingsContainer.add(theme.section("页面配置", true)).expandX().widget();
        section.add(new PageConfigSection()).expandX();
    }

    @Override
    public void init() {
        rebuild();
    }

    private void rebuild() {
        clear();

        // 新增页面按钮（满上限时提示）
        WHorizontalList top = add(theme.horizontalList()).expandX().widget();
        if (ModulePages.get().pageCount() < ModulePages.MAX_PAGES) {
            WButton addBtn = top.add(theme.button("新增页面")).widget();
            addBtn.action = this::addPage;
        } else {
            top.add(theme.label("已达页面上限 (" + ModulePages.MAX_PAGES + " 个)"));
        }

        // 页面行：页名按钮(撑满) + 重命名 + 删除(右对齐)
        WTable table = add(theme.table()).expandX().widget();
        List<ModulePages.Page> pages = ModulePages.get().getPages();
        for (int i = 0; i < pages.size(); i++) {
            int idx = i;

            WButton listBtn = table.add(theme.button(pages.get(i).name)).expandCellX().widget();
            listBtn.action = () -> mc.setScreen(new ModuleSelectScreen(theme, idx));

            WButton renameBtn = table.add(theme.button("重命名")).widget();
            renameBtn.action = () -> mc.setScreen(new RenamePageScreen(theme, idx));

            if (idx > 0) {
                WButton delBtn = table.add(theme.button("删除")).widget();
                delBtn.action = () -> confirmDelete(idx);
            }

            table.row();
        }
    }

    private void addPage() {
        ModulePages.get().addPage();
        refreshGui();
    }

    private void confirmDelete(int idx) {
        YesNoPrompt.create(theme, mc.screen)
            .title("删除页面")
            .message("确定删除页面 \"" + ModulePages.get().getPage(idx).name + "\" 吗？")
            .message("该页面勾选的分类将从本页面移除，其他页面不受影响。")
            .dontShowAgainCheckboxVisible(false)
            .onYes(() -> {
                ModulePages.get().deletePage(idx);
                refreshGui();
            })
            .show();
    }

    /**
     * 页面增删改后重建当前 Tab 界面（顶部栏 + 内容一起重建，按钮全部更新）。
     * 当前可能在确认弹窗/子界面上，沿 WidgetScreen.parent 链找 TabScreen。
     */
    public static void refreshTabScreen() {
        Screen screen = mc.screen;
        while (screen != null) {
            if (screen instanceof TabScreen tabScreen) {
                tabScreen.tab.openScreen(GuiThemes.get());
                return;
            }
            if (!(screen instanceof WidgetScreen widgetScreen)) return;
            screen = widgetScreen.parent;
        }
    }

    private void refreshGui() {
        refreshTabScreen();
    }
}
