/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * AI 聊天窗口：仿 Meteor 的大窗口（带标题栏 + 半透明背景），窗口四周距离屏幕边缘 100px。
 *
 * <p>窗口里从上到下是：对话历史（可滚动）、状态提示、输入框。
 * 输入框左右各留 200px 边距。
 */
public class WChatLayout extends WContainer {
    /** 窗口距离屏幕边缘 */
    public static final double WINDOW_MARGIN = 100;
    /** 输入框距离窗口左右边缘 */
    public static final double INPUT_MARGIN = 200;
    /** 用户消息整体右对齐、文字再往左缩进的量 */
    public static final double USER_INDENT = 300;

    private static final double HEADER_PAD = 6;
    private static final double BODY_PAD = 10;
    private static final double BOTTOM_MARGIN = 16;

    public final WView view;
    public final WLabel status;
    public final WChatInput input;

    private double headerHeight;

    public WChatLayout(GuiTheme theme) {
        this.theme = theme;

        // 先加输入框：滚轮/按键先给输入框，输入框不需要时再传给上面的历史
        input = new WChatInput();
        add(input);

        status = theme.label("");
        status.color(theme.textSecondaryColor());
        add(status);

        view = theme.view();
        view.scrollOnlyWhenMouseOver = false;
        add(view);
    }

    /** 聊天记录能用的横向宽度（给折行用），和 view 实际宽度保持一致 */
    public double messageAreaWidth() {
        double panelWidth = Utils.getWindowWidth() - theme.scale(WINDOW_MARGIN) * 2;
        return Math.max(theme.scale(80), panelWidth - theme.scale(BODY_PAD) * 2 - theme.scale(16));
    }

    @Override
    protected void onCalculateSize() {
        double windowMargin = theme.scale(WINDOW_MARGIN);
        headerHeight = theme.textHeight(true) + theme.scale(HEADER_PAD) * 2;

        width = Math.max(theme.scale(200), Utils.getWindowWidth() - windowMargin * 2);
        height = Math.max(theme.scale(200), Utils.getWindowHeight() - windowMargin * 2);

        double inputMargin = theme.scale(INPUT_MARGIN);
        input.width = Math.max(theme.scale(80), width - inputMargin * 2);
        input.calculateSize();
        status.calculateSize();

        double availableHeight = Math.max(theme.scale(40),
            height - headerHeight - theme.scale(BODY_PAD) * 2 - BOTTOM_MARGIN
                - input.height - status.height - theme.scale(6) * 2);
        view.maxHeight = availableHeight;
        view.width = Math.max(theme.scale(80), width - theme.scale(BODY_PAD) * 2);
        view.calculateSize();
    }

    @Override
    protected void onCalculateWidgetPositions() {
        double inputMargin = theme.scale(INPUT_MARGIN);
        double bottomMargin = theme.scale(BOTTOM_MARGIN);
        double bodyPad = theme.scale(BODY_PAD);

        input.x = x + inputMargin;
        input.y = y + height - bottomMargin - input.height;
        input.width = Math.max(theme.scale(80), width - inputMargin * 2);

        status.x = x + inputMargin;
        status.y = input.y - theme.scale(6) - status.height;

        view.x = x + bodyPad;
        view.y = y + headerHeight + bodyPad;
        view.width = Math.max(theme.scale(80), width - bodyPad * 2);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MeteorGuiTheme meteor = theme instanceof MeteorGuiTheme m ? m : null;
        Color body = meteor != null ? meteor.backgroundColor.get() : new Color(20, 20, 20, 200);
        Color accent = meteor != null ? meteor.accentColor.get() : new Color(145, 61, 226);
        Color outline = meteor != null ? meteor.outlineColor.get() : new Color(0, 0, 0);

        // 窗口背景
        renderer.quad(x, y, width, height, body);
        // 标题栏
        renderer.quad(x, y, width, headerHeight, accent);
        // 边框
        double border = Math.max(1, theme.scale(2));
        renderer.quad(x, y, width, border, outline);
        renderer.quad(x, y + height - border, width, border, outline);
        renderer.quad(x, y + border, border, height - border * 2, outline);
        renderer.quad(x + width - border, y + border, border, height - border * 2, outline);

        // 标题
        double titleHeight = theme.textHeight(true);
        renderer.text("AI 聊天", x + theme.scale(8), y + (headerHeight - titleHeight) / 2, Color.WHITE, true);
    }
}
