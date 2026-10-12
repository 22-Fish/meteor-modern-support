/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.aibot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把上下文（{@link AiContext} 的 main.json）整理成聊天界面能直接用的显示条目。
 *
 * <p>用户消息、AI 回复都是普通文字；工具调用单独拆出来，带上参数和结果，
 * 界面上就能画成有边框的小方块、右键查看详情。
 */
public final class AiChatHistory {

    public enum Role { USER, ASSISTANT, TOOL }

    public static final class Entry {
        public final Role role;
        /** user / assistant 的正文 */
        public final String text;
        /** 工具名（TOOL 用） */
        public final String toolName;
        /** 工具参数 JSON（TOOL 用） */
        public final String args;
        /** 工具返回结果，null 表示还在跑 / 没等到结果 */
        public String result;

        private Entry(Role role, String text, String toolName, String args) {
            this.role = role;
            this.text = text;
            this.toolName = toolName;
            this.args = args;
        }

        public static Entry user(String text) { return new Entry(Role.USER, text, null, null); }
        public static Entry assistant(String text) { return new Entry(Role.ASSISTANT, text, null, null); }
        public static Entry tool(String name, String args) { return new Entry(Role.TOOL, null, name, args); }
    }

    private AiChatHistory() {}

    /** 从当前上下文拼一份显示列表（按时间从旧到新） */
    public static List<Entry> build() {
        List<AiApiClient.Message> messages = AiContext.snapshotMainConversation();
        List<Entry> entries = new ArrayList<>(messages.size());
        Map<String, Entry> toolEntries = new HashMap<>();

        for (AiApiClient.Message message : messages) {
            String role = message.getRole();
            String content = content(message);

            switch (role) {
                case "user" -> {
                    if (!content.isBlank()) entries.add(Entry.user(content));
                }
                case "assistant" -> {
                    // AI 的正文：无边框文字
                    if (!content.isBlank()) entries.add(Entry.assistant(content));

                    // 这一轮的工具调用：有边框的小方块
                    Object toolCalls = message.data.get("tool_calls");
                    if (toolCalls instanceof List<?> list) {
                        for (Object item : list) {
                            if (!(item instanceof Map<?, ?> call)) continue;

                            String id = stringOf(call.get("id"));
                            String name = "";
                            String args = "";
                            if (call.get("function") instanceof Map<?, ?> function) {
                                name = stringOf(function.get("name"));
                                args = stringOf(function.get("arguments"));
                            }

                            Entry entry = Entry.tool(name, args);
                            entries.add(entry);
                            if (!id.isEmpty()) toolEntries.put(id, entry);
                        }
                    }
                }
                case "tool" -> {
                    String id = stringOf(message.data.get("tool_call_id"));
                    Entry entry = id.isEmpty() ? null : toolEntries.get(id);
                    if (entry != null) {
                        entry.result = content;
                    } else if (!content.isBlank()) {
                        // 找不到对应的调用（上下文被删减过）：当一条孤立的工具结果显示
                        Entry orphan = Entry.tool("(孤立结果)", "");
                        orphan.result = content;
                        entries.add(orphan);
                    }
                }
                default -> {
                    // system（系统提示词）不显示
                }
            }
        }

        return entries;
    }

    private static String content(AiApiClient.Message message) {
        Object content = message.data.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
