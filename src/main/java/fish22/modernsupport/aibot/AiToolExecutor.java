/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来。
 */

package fish22.modernsupport.aibot;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 工具调度：Function Calling 分发 + 通用工具（时间/聊天记录/在线玩家/发消息/文件/搜索/定时任务）。
 *
 * <p>工具调用提示、执行结果、token 消耗都只在本地聊天栏显示（{@link AI#LOG}），
 * 只有 send_message 和最终回复才会真的发到服务器。
 */
public final class AiToolExecutor {

    private AiToolExecutor() {}

    @SuppressWarnings("unchecked")
    public static String executeTool(String name, String argsJson) {
        Map<String, Object> args;
        try {
            args = AiApiClient.GSON.fromJson(argsJson, Map.class);
        } catch (Exception e) {
            args = new HashMap<>();
        }
        if (args == null) args = new HashMap<>();

        if (!AiConfig.isToolEnabled(name)) return "工具「" + name + "」没有开启（可在模块设置的「工具开关」里打开）";

        return switch (name) {
            case "get_time" -> executeGetTime();
            case "check_chat" -> executeCheckChat(args);
            case "list_players" -> executeListPlayers();
            case "send_message" -> executeSendMessage(args);
            case "list_roots" -> AiFiles.getRootsDesc();
            case "create_scheduled_task" -> AiTasks.createTask(args);
            case "delete_scheduled_task" -> AiTasks.deleteTask(args);
            case "cd" -> AiFiles.executeCd(args);
            case "ls" -> AiFiles.executeLs(args);
            case "read" -> AiFiles.executeRead(args);
            case "write" -> AiFiles.executeWrite(args);
            case "edit" -> AiFiles.executeEdit(args);
            case "delete_file" -> AiFiles.executeDeleteFile(args);
            case "mkdir" -> AiFiles.executeMkdir(args);
            case "move" -> AiFiles.executeMove(args);
            case "search_name" -> AiSearch.executeSearchName(args);
            case "search_content" -> AiSearch.executeSearchContent(args);
            case "search_file" -> AiSearch.executeSearchFile(args);
            default -> "未知工具: " + name;
        };
    }

    // ==================== 通用工具 ====================

    private static String executeGetTime() {
        return LocalDateTime.now(AiConfig.getZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String executeCheckChat(Map<String, Object> args) {
        List<String> recent = AiChatHandler.getRecentChatMessages();
        if (recent.isEmpty()) return "没有聊天记录";

        // a、b 是"距离"：0 = 当前这条，1 = 前一条。返回 (a, b] 范围，不含 a 自身
        int a = args.get("a") instanceof Number number ? number.intValue() : 0;
        int b = args.get("b") instanceof Number number ? number.intValue() : 5;
        if (a >= b) return "参数错误: a必须小于b";
        if (a < 0) return "参数错误: a不能为负数";

        int total = recent.size();
        int fromIdx = Math.max(0, total - 1 - b);
        int toIdx = Math.max(0, total - 1 - a);
        if (fromIdx >= total) return "查询范围超出聊天记录总数(" + total + "条)";

        StringBuilder sb = new StringBuilder();
        sb.append("【聊天记录 前").append(b).append("条到前").append(a + 1).append("条】\n");
        for (int i = fromIdx; i < toIdx && i < total; i++) {
            sb.append(recent.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private static String executeListPlayers() {
        if (mc.getConnection() == null) return "无人在线";
        String names = mc.getConnection().getOnlinePlayers().stream()
            .map(player -> player.getProfile().name())
            .collect(Collectors.joining(", "));
        return names.isEmpty() ? "无人在线" : names;
    }

    private static String executeSendMessage(Map<String, Object> args) {
        String message = args.get("message") != null ? args.get("message").toString() : "";
        if (message.isBlank()) return "send_message: 消息不能为空";

        for (String line : message.split("\n")) {
            if (line.isBlank()) continue;
            AiChatHandler.sendToPublicChat(line.trim());
        }
        return "已在公屏发送";
    }

    // ==================== 本地提示（不发到服务器） ====================

    public static void broadcastToolCalls(List<Map<String, Object>> toolCalls) {
        StringBuilder sb = new StringBuilder("[工具调用]");
        for (Map<String, Object> toolCall : toolCalls) {
            Object function = toolCall.get("function");
            if (!(function instanceof Map<?, ?> map)) continue;
            sb.append(" ").append(map.get("name")).append("(");
            Object arguments = map.get("arguments");
            if (arguments != null && !"{}".equals(arguments)) sb.append(arguments);
            sb.append(")");
        }
        AI.LOG(sb.toString());
    }

    public static void broadcastToolResult(String toolName, String result) {
        String display = result == null ? "" : (result.length() > 500 ? result.substring(0, 500) + "..." : result);
        AI.LOG("[" + toolName + "]调用返回: " + display);
    }

    public static void broadcastTokenUsage(AiApiClient.TokenUsage usage) {
        AI.LOG(usage.format() + " | 上下文: " + AiContext.getContextTokens() + " tokens");
    }

    public static void broadcastUserUsage(String playerName) {
        long used = AiUsage.getUsageToday(playerName);
        AI.LOG("玩家 " + playerName + " 今日已用 " + AiUsage.formatWan(used) + "token / 上限 " + AiUsage.formatWan(AiUsage.getMaxTokens(playerName)));
    }
}
