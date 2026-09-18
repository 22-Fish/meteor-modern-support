/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * APIKEY 输入框的渲染器：内容照常编辑，但界面上只显示一行点（不让旁边的人看到密钥）。
 */
public class ApiKeyRenderer implements WTextBox.Renderer {
    @Override
    public void render(GuiRenderer renderer, double x, double y, String text, Color color) {
        String shown = text == null || text.isEmpty() ? "" : "•".repeat(Math.min(text.length(), 48));
        renderer.text(shown, x, y, color, false);
    }
}
