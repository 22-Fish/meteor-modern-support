/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * 聊天界面的输入框：多行（Shift+回车换行，回车发送），最高 7 行，超出内部滚动。
 *
 * <p>特意继承 Meteor 的 {@link WTextBox}：输入法修复类 mod（如 IMBlocker）
 * 只认 Meteor 自己的文本框，继承它才能正常调用输入法打中文。
 */
public class WChatInput extends WTextBox {
    private static final int MAX_LINES = 7;
    private static final int MAX_LENGTH = 4000;

    private static final CharFilter FILTER = (text, c) ->
        text.length() < MAX_LENGTH && (c >= 32 || c == '\n');

    private int scrollLine = 0;
    private Runnable onSend;

    private final List<Line> lines = new ArrayList<>();

    /** 一行可视文本：start/end 是在整段文字里的下标，text 不含换行符 */
    private record Line(int start, int end, String text) {}

    public WChatInput() {
        super("", "输入消息，回车发送，Shift+回车换行", FILTER, null);
        this.action = this::invalidate;
    }

    public void setOnSend(Runnable onSend) {
        this.onSend = onSend;
    }

    public String getText() {
        return get();
    }

    public void clearText() {
        set("");
        scrollLine = 0;
        invalidate();
    }

    @Override
    protected WContainer createCompletionsRootWidget() {
        return null;
    }

    @Override
    protected <T extends WWidget & ICompletionItem> T createCompletionsValueWidth(String completion, boolean selected) {
        return null;
    }

    // ==================== 尺寸 / 换行 ====================

    private double lineHeight() {
        return theme.textHeight() + theme.scale(2);
    }

    private double innerWidth() {
        return Math.max(theme.scale(24), width - pad() * 2);
    }

    @Override
    protected void onCalculateSize() {
        if (theme == null) return;
        rebuildLines();

        int visible = Math.min(Math.max(lines.size(), 1), MAX_LINES);
        height = pad() * 2 + visible * lineHeight();
        updateScroll();
    }

    /** 按当前宽度把整段文字拆成可视行（含换行和自动折行） */
    private void rebuildLines() {
        if (theme == null) return;

        lines.clear();
        double maxWidth = innerWidth();

        int i = 0;
        while (i <= text.length()) {
            int newline = text.indexOf('\n', i);
            int lineEnd = newline < 0 ? text.length() : newline;

            int start = i;
            while (start < lineEnd) {
                int end = start;
                double w = 0;
                while (end < lineEnd) {
                    double cw = theme.textWidth(String.valueOf(text.charAt(end)), 1, false);
                    if (end > start && w + cw > maxWidth) break;
                    w += cw;
                    end++;
                }
                if (end == start) end = Math.min(start + 1, lineEnd);
                lines.add(new Line(start, end, text.substring(start, end)));
                start = end;
            }
            if (start == lineEnd) lines.add(new Line(lineEnd, lineEnd, ""));

            if (newline < 0) break;
            i = newline + 1;
        }

        if (lines.isEmpty()) lines.add(new Line(0, 0, ""));
    }

    private int lineIndexFor(int pos) {
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (pos >= line.start && pos <= line.end) return i;
        }
        return lines.size() - 1;
    }

    private void updateScroll() {
        int maxScroll = Math.max(0, lines.size() - MAX_LINES);
        int idx = Math.max(0, lineIndexFor(cursor));

        if (idx < scrollLine) scrollLine = idx;
        if (idx >= scrollLine + MAX_LINES) scrollLine = idx - MAX_LINES + 1;
        scrollLine = Math.max(0, Math.min(scrollLine, maxScroll));
    }

    /** IMBlocker 用它算输入法候选框/预编辑的位置 */
    @Override
    protected double getTextWidth(int pos) {
        if (theme == null) return 0;
        rebuildLines();

        int idx = lineIndexFor(pos);
        if (idx < 0) return 0;

        Line line = lines.get(idx);
        int col = Math.max(0, Math.min(pos - line.start, line.text.length()));
        return theme.textWidth(line.text, col, false);
    }

    @Override
    protected void onCursorChanged() {
        if (theme != null) {
            rebuildLines();
            updateScroll();
        }
        invalidate();
    }

    // ==================== 输入事件 ====================

    @Override
    public boolean onKeyPressed(KeyEvent input) {
        int key = input.key();
        boolean shift = (input.modifiers() & GLFW_MOD_SHIFT) != 0;

        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
            if (shift) insertNewline();
            else if (onSend != null) onSend.run();
            return true;
        }

        switch (key) {
            case GLFW_KEY_UP -> {
                moveVertical(-1);
                return true;
            }
            case GLFW_KEY_DOWN -> {
                moveVertical(1);
                return true;
            }
            case GLFW_KEY_HOME -> {
                cursor = lineStart(cursor);
                onCursorChanged();
                return true;
            }
            case GLFW_KEY_END -> {
                cursor = lineEnd(cursor);
                onCursorChanged();
                return true;
            }
            default -> {
                return super.onKeyPressed(input);
            }
        }
    }

    private void insertNewline() {
        if (text.length() >= MAX_LENGTH) return;

        text = text.substring(0, cursor) + "\n" + text.substring(cursor);
        cursor++;
        if (action != null) action.run();
    }

    private int lineStart(int pos) {
        int idx = text.lastIndexOf('\n', Math.max(0, pos - 1));
        return idx < 0 ? 0 : idx + 1;
    }

    private int lineEnd(int pos) {
        int idx = text.indexOf('\n', pos);
        return idx < 0 ? text.length() : idx;
    }

    private void moveVertical(int direction) {
        rebuildLines();
        int idx = lineIndexFor(cursor);
        int target = idx + direction;
        if (target < 0 || target >= lines.size()) return;

        Line current = lines.get(idx);
        Line next = lines.get(target);
        int column = cursor - current.start;
        cursor = next.start + Math.min(column, next.end - next.start);
        onCursorChanged();
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubled) {
        if (mouseOver) {
            setFocused(true);
            cursor = text.length();
            onCursorChanged();
            return true;
        }

        if (isFocused()) setFocused(false);
        return false;
    }

    @Override
    public boolean onMouseScrolled(double amount) {
        if (!mouseOver) return false;

        refreshLinesIfNeeded();
        int maxScroll = Math.max(0, lines.size() - MAX_LINES);
        if (maxScroll == 0) return false;

        int next = scrollLine + (amount > 0 ? -1 : 1);
        next = Math.max(0, Math.min(next, maxScroll));
        if (next == scrollLine) return false;

        scrollLine = next;
        return true;
    }

    private void refreshLinesIfNeeded() {
        if (theme != null && lines.isEmpty()) rebuildLines();
    }

    // ==================== 渲染 ====================

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        double pad = pad();
        double border = Math.max(1, theme.scale(1));
        Color fill = new Color(0, 0, 0, 150);
        Color outline = theme.textSecondaryColor();

        renderer.quad(x, y, width, height, fill);
        renderer.quad(x, y, width, border, outline);
        renderer.quad(x, y + height - border, width, border, outline);
        renderer.quad(x, y + border, border, height - border * 2, outline);
        renderer.quad(x + width - border, y + border, border, height - border * 2, outline);

        if (lines.isEmpty()) rebuildLines();

        double textX = x + pad;
        double textY = y + pad;
        double lh = lineHeight();

        if (text.isEmpty()) {
            renderer.text(placeholder == null ? "" : placeholder, textX, textY, theme.textSecondaryColor(), false);
        }

        int from = scrollLine;
        int to = Math.min(lines.size(), scrollLine + MAX_LINES);
        for (int i = from; i < to; i++) {
            Line line = lines.get(i);
            if (!line.text.isEmpty()) {
                renderer.text(line.text, textX, textY + (i - from) * lh, theme.textColor(), false);
            }
        }

        if (isFocused()) {
            int idx = lineIndexFor(cursor);
            if (idx >= from && idx < to) {
                Line line = lines.get(idx);
                int col = Math.max(0, Math.min(cursor - line.start, line.text.length()));
                double caretX = textX + theme.textWidth(line.text, col, false);
                double caretY = textY + (idx - from) * lh;
                renderer.quad(caretX, caretY, Math.max(1, theme.scale(1)), theme.textHeight(), theme.textColor());
            }
        }
    }
}
