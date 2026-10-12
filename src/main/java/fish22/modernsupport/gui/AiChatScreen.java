/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.gui;

import fish22.modernsupport.aibot.AI;
import fish22.modernsupport.aibot.AiChatHandler;
import fish22.modernsupport.aibot.AiChatHistory;
import fish22.modernsupport.aibot.AiContext;
import fish22.modernsupport.modules.OpenAI;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.GuiKeyEvents;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * AI 聊天界面：仿 Meteor 的大窗口，窗口四周离屏幕边缘 100px。
 * 中间是对话历史（AI 的文字靠左、用户文字整块靠右、工具调用是有边框的小方块，
 * 右键查看参数和结果），底部是输入框（回车发送、Shift+回车换行）。
 */
public class AiChatScreen extends WidgetScreen {
    private static final Color USER_COLOR = new Color(120, 190, 255);
    private static final Color ERROR_COLOR = new Color(255, 120, 120);

    private WChatLayout layout;
    private int lastVersion = -1;
    private boolean closingSelf = false;

    public AiChatScreen(GuiTheme theme) {
        super(theme, "AI 聊天");
    }

    /** 快捷键 / 按钮统一走这里打开界面 */
    public static void open() {
        OpenAI module = OpenAI.get();
        if (module == null || !module.isActive()) {
            AI.LOG("先开启 openAI 模块，再打开聊天界面");
            return;
        }
        try {
            mc.setScreen(new AiChatScreen(GuiThemes.get()));
        } catch (Throwable t) {
            AI.ERROR("打开聊天界面失败: " + t);
        }
    }

    @Override
    protected void init() {
        super.init();
        closingSelf = false;
        if (layout != null) layout.input.setFocused(true);
    }

    @Override
    public void onClose() {
        // 记下正在关闭：关闭的最后一帧不要再把输入框抢回焦点，
        // 否则焦点输入框会在这一帧把 GuiKeyEvents.canUseKeys 又置 false，关掉后所有快捷键都失效
        closingSelf = true;
        super.onClose();
    }

    @Override
    protected void onClosed() {
        // 关界面时输入框失焦（输入法由 IMBlocker 之类的 mod 按 Meteor 文本框规则处理）
        if (layout != null) layout.input.setFocused(false);
    }

    @Override
    public void initWidgets() {
        layout = add(new WChatLayout(theme)).center().widget();
        layout.input.setOnSend(this::send);
        rebuild();
    }

    private void send() {
        String text = layout.input.getText().trim();
        if (text.isEmpty()) return;

        if (AiChatHandler.startGuiChat(text)) {
            layout.input.clearText();
        }
    }

    @Override
    protected void onRenderBefore(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 聊天输入框一直保持焦点（点历史记录滚动后也能继续打字）
        if (!closingSelf && layout != null && !layout.input.isFocused()) layout.input.setFocused(true);

        if (AiContext.getVersion() != lastVersion) rebuild();

        if (AiChatHandler.isCallingApi()) {
            layout.status.color(theme.textSecondaryColor());
            layout.status.set("AI 正在回复…");
        } else {
            String error = AiChatHandler.getLastError();
            if (error != null) {
                if (error.length() > 120) error = error.substring(0, 120) + "…";
                layout.status.color(ERROR_COLOR);
                layout.status.set(error);
            } else {
                layout.status.color(theme.textSecondaryColor());
                layout.status.set("");
            }
        }
    }

    @Override
    public void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.renderCustom(graphics, mouseX, mouseY, delta);

        // 关闭的那一帧兜底：焦点输入框会把 canUseKeys 置 false，这里在渲染后强制恢复，
        // 免得关掉界面后所有快捷键失灵
        if (closingSelf) GuiKeyEvents.canUseKeys = true;
    }

    /** 按当前上下文重建对话历史（只在上下文变化时调用） */
    private void rebuild() {
        lastVersion = AiContext.getVersion();
        layout.view.clear();

        double areaWidth = layout.messageAreaWidth();
        double userWidth = Math.max(theme.scale(80), areaWidth - theme.scale(WChatLayout.USER_INDENT));
        List<AiChatHistory.Entry> entries = AiChatHistory.build();

        for (AiChatHistory.Entry entry : entries) {
            switch (entry.role) {
                // 用户消息：整块靠右；块本身按文字宽度，右边有空间就贴到右边去，
                // 最长不超过 areaWidth - USER_INDENT，这样块的左边不会越过 USER_INDENT
                case USER -> layout.view.add(
                    theme.label(WrappedText.wrap(theme, entry.text, userWidth)).color(USER_COLOR)
                ).right();
                // AI 消息：靠左
                case ASSISTANT -> layout.view.add(
                    theme.label(WrappedText.wrap(theme, entry.text, areaWidth)).color(theme.textColor())
                ).expandX();
                // 工具调用：只显示工具名的小方块
                case TOOL -> layout.view.add(new WToolCallBox(entry));
            }
        }

        // 有新内容时自动滚到底部
        taskAfterRender = () -> layout.view.onMouseScrolled(-1_000_000);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        // 窗口大小变了，按新宽度重新折行
        lastVersion = -1;
    }
}
