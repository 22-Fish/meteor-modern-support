/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.gui;

import fish22.modernsupport.aibot.AiChatHistory;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.input.MouseButtonEvent;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * 工具调用提示框：有边框的一行，右键弹出小窗看调用参数和返回结果。
 */
public class WToolCallBox extends WWidget {
    private final AiChatHistory.Entry entry;

    public WToolCallBox(AiChatHistory.Entry entry) {
        this.entry = entry;
        this.tooltip = "右键查看调用参数和结果";
    }

    private String header() {
        // 只显示工具名；参数和结果右键看
        String name = entry.toolName == null || entry.toolName.isEmpty() ? "未知" : entry.toolName;
        return "[工具] " + name;
    }

    @Override
    protected void onCalculateSize() {
        width = theme.textWidth(header(), header().length(), false) + pad() * 2;
        height = theme.textHeight() + pad() * 2;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        double border = Math.max(1, theme.scale(1));
        Color fill = new Color(60, 60, 60, mouseOver ? 200 : 150);
        Color outline = theme.textSecondaryColor();

        renderer.quad(x, y, width, height, fill);
        renderer.quad(x, y, width, border, outline);
        renderer.quad(x, y + height - border, width, border, outline);
        renderer.quad(x, y + border, border, height - border * 2, outline);
        renderer.quad(x + width - border, y + border, border, height - border * 2, outline);

        renderer.text(header(), x + pad(), y + pad(), theme.textColor(), false);
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubled) {
        if (mouseOver && click.button() == GLFW_MOUSE_BUTTON_RIGHT) {
            mc.setScreen(new ToolDetailScreen(theme, entry.toolName, entry.args, entry.result));
            return true;
        }
        return false;
    }
}
