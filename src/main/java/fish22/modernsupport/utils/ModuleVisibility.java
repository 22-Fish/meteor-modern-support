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

package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * 板块显隐（模块列表右键板块标题栏打开的那个界面）：
 * 勾选 = 该模块展示，取消勾选 = 隐藏。
 * 隐藏记在 {@link ModulePages} 里，按"页 + 分类"分开存，
 * 同一个分类出现在多个页面时，各页面板块互不影响。
 * Meteor 自带的隐藏模块名单（Config.hiddenModules）照样尊重：它在哪都不显示。
 */
public final class ModuleVisibility {
    private ModuleVisibility() {}

    /** 模块在这个板块里是否展示 */
    public static boolean isVisible(int pageIdx, Category category, Module module) {
        if (Config.get().hiddenModules.get().contains(module)) return false;
        return !ModulePages.get().isModuleHidden(pageIdx, category.name, module.name);
    }

    /** 设置模块在这个板块里显不显示（只影响这一页的这个板块） */
    public static void setVisible(int pageIdx, Category category, Module module, boolean visible) {
        ModulePages.get().setModuleHidden(pageIdx, category.name, module.name, !visible);

        // 勾上了却不显示，是 Meteor 自带隐藏名单里还留着它，一并去掉
        if (visible && Config.get().hiddenModules.get().remove(module)) Config.get().save();
    }

    /** 显隐变化后重建板块内容，顺序与 ModulesScreen.createCategory 一致 */
    public static void rebuild(GuiTheme theme, WWindow window, Category category, int pageIdx) {
        if (window == null || category == null) return;

        window.clear();
        for (Module module : Modules.get().getGroup(category)) {
            if (isVisible(pageIdx, category, module)) window.add(theme.module(module)).expandX();
        }
        window.invalidate();
    }

    /** 分类窗口 id 形如 modulepage_<页索引>_<分类名>，取页索引；不是分类窗口返回 -1 */
    public static int pageIndexOfWindow(String windowId) {
        String rest = restOfWindowId(windowId);
        if (rest == null) return -1;

        int separator = rest.indexOf('_');
        if (separator < 0) return -1;

        try {
            return Integer.parseInt(rest.substring(0, separator));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 分类窗口 id 形如 modulepage_<页索引>_<分类名>，取回对应分类；不是分类窗口返回 null */
    public static Category categoryOfWindow(String windowId) {
        String rest = restOfWindowId(windowId);
        if (rest == null) return null;

        int separator = rest.indexOf('_');
        if (separator < 0) return null;

        String name = rest.substring(separator + 1);
        for (Category category : Modules.loopCategories()) {
            if (category.name.equals(name)) return category;
        }
        return null;
    }

    /** 分类窗口 id 去掉前缀后的部分（页索引_分类名），不是分类窗口返回 null */
    private static String restOfWindowId(String windowId) {
        if (windowId == null || !windowId.startsWith(ModulePages.CATEGORY_WINDOW_ID_PREFIX)) return null;
        return windowId.substring(ModulePages.CATEGORY_WINDOW_ID_PREFIX.length());
    }
}
