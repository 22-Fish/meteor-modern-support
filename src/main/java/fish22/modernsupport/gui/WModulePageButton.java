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
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.tabs.builtin.ModulesTab;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 顶部栏页面按钮（主界面 / 自定义页面）。
 * 点击切换到对应页面并打开模块列表界面。
 */
public class WModulePageButton extends WPressable {
    private final int pageIdx;

    public WModulePageButton(int pageIdx) {
        this.pageIdx = pageIdx;
    }

    private String name() {
        ModulePages pages = ModulePages.get();
        // 删除页面后旧顶部栏销毁前还会渲染一帧，此时索引可能越界
        if (pages == null || pageIdx >= pages.getPages().size()) return "";
        return pages.getPage(pageIdx).name;
    }

    /** 当前打开的就是本页面（高亮） */
    private boolean isCurrent() {
        return mc.screen instanceof ModulesScreen && ModulePages.get().getCurrent() == pageIdx;
    }

    @Override
    protected void onCalculateSize() {
        double pad = pad();
        width = pad + theme.textWidth(name()) + pad;
        height = pad + theme.textHeight() + pad;
    }

    @Override
    protected void onPressed(int button) {
        if (isCurrent()) return;

        ModulePages pages = ModulePages.get();
        // 旧按钮（页面已删除）不响应
        if (pages == null || pageIdx >= pages.getPages().size()) return;

        pages.setCurrent(pageIdx);
        pages.setOpeningViaBar(true);
        Tabs.get(ModulesTab.class).openScreen(theme);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        double pad = pad();
        boolean pressedState = pressed || isCurrent();

        Color background;
        Color text;
        if (theme instanceof MeteorGuiTheme meteorTheme) {
            background = meteorTheme.backgroundColor.get(pressedState, mouseOver);
            text = meteorTheme.textColor.get();
        } else {
            // 非 Meteor 主题兜底
            background = new Color(pressedState ? 80 : 40, pressedState ? 80 : 40, pressedState ? 80 : 40, 200);
            text = theme.textColor();
        }

        renderer.quad(x, y, width, height, background);
        renderer.text(name(), x + pad, y + pad, text, false);
    }
}
