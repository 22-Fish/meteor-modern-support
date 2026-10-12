/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 工具调用详情小窗：显示调用参数和返回结果，右键工具调用提示框时弹出。
 */
public class ToolDetailScreen extends WindowScreen {
    private final String toolName;
    private final String args;
    private final String result;

    public ToolDetailScreen(GuiTheme theme, String toolName, String args, String result) {
        super(theme, "工具调用");
        this.toolName = toolName;
        this.args = args;
        this.result = result;
    }

    @Override
    public void initWidgets() {
        add(theme.label("工具：" + (toolName == null || toolName.isEmpty() ? "未知" : toolName), true)).expandX();

        add(theme.horizontalSeparator("调用参数")).expandX();
        add(theme.label(WrappedText.wrap(theme, blankTo(args, "（无参数）"), wrapWidth()))).expandX();

        add(theme.horizontalSeparator("调用结果")).expandX();
        String resultText = result == null ? "（还在运行，暂无结果）" : blankTo(result, "（空）");
        add(theme.label(WrappedText.wrap(theme, resultText, wrapWidth()))).expandX();

        WButton copy = add(theme.button("复制参数和结果")).expandX().widget();
        copy.action = () -> mc.keyboardHandler.setClipboard(
            "工具: " + toolName + "\n\n参数:\n" + args + "\n\n结果:\n" + result
        );
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private double wrapWidth() {
        return theme.scale(320);
    }
}
