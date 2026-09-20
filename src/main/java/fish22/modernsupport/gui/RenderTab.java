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

import fish22.modernsupport.utils.RenderSettings;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import net.minecraft.client.gui.screens.Screen;

/**
 * 顶部栏 Render 板块（排在 GUI 和 HUD 中间）
 */
public class RenderTab extends Tab {
    public static final RenderTab INSTANCE = new RenderTab();

    public RenderTab() {
        super("Render");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new RenderScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof RenderScreen;
    }

    private static class RenderScreen extends WindowTabScreen {
        public RenderScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);

            RenderSettings.get().settings.onActivated();
        }

        @Override
        public void initWidgets() {
            add(theme.settings(RenderSettings.get().settings)).expandX();
        }

        @Override
        public boolean toClipboard() {
            return NbtUtils.toClipboard(RenderSettings.get());
        }

        @Override
        public boolean fromClipboard() {
            return NbtUtils.fromClipboard(RenderSettings.get());
        }
    }
}
