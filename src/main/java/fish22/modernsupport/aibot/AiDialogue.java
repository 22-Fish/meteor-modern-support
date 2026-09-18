/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来。
 */

package fish22.modernsupport.aibot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 对话循环：准备上下文（必要时先删减）→ 调用 API → 处理工具调用 → 返回最终回复。
 *
 * <p>子代理功能已阉割：工具定义里那一批子代理工具会被工具开关一律关掉。
 */
public final class AiDialogue {

    /** 工具调用轮数上限（防止模型死循环把客户端拖垮） */
    private static final int MAX_TOOL_ROUNDS = 50;

    private AiDialogue() {}

    /** 子代理功能已阉割，保留此方法是为了兼容工具定义代码 */
    public static String getCurrentSubAgentName() { return null; }

    /**
     * 主对话入口（玩家触发 / 定时任务共用）
     *
     * @param onFinalResponse 拿到最终回复时回调（发送消息由调用方处理）
     */
    public static void callAI(String userMessage, Consumer<String> onFinalResponse) {
        callAI(List.of(userMessage), onFinalResponse);
    }

    /**
     * 主对话入口（自动聊天聚合多条消息时用）。
     *
     * @param userMessages 依次作为多条 user 消息加入上下文
     * @param onFinalResponse 拿到最终回复时回调（发送消息由调用方处理）
     */
    public static void callAI(List<String> userMessages, Consumer<String> onFinalResponse) {
        // 上次调用时间要在记录本次之前取（跨天判断 + 缓存时间判断都靠它）
        long lastCallTime = AI.getLastApiCallTime();

        // 删减只在这里做（真正要调 API 之前）：投机删除 / 强制删除，删完顺带刷新系统提示词
        AiContext.trimBeforeSend();
        // 系统提示词固定在上下文最前面一条，没有（第一次用、老存档）就补上
        AiConfig.ensureSystemPrompt();

        List<AiApiClient.Message> messages = AiContext.getMainConversation();
        // 清掉系统提示词后面的孤儿消息（系统提示词那条不能动）
        AiContext.dropLeadingOrphans();
        for (String userMessage : userMessages) {
            messages.add(new AiApiClient.Message(AiApiClient.userMsg(userMessage)));
        }

        // 跨天提醒：隔天再聊时告诉 AI 今天几号
        if (lastCallTime > 0) {
            LocalDate lastCallDate = Instant.ofEpochMilli(lastCallTime).atZone(AiConfig.getZoneId()).toLocalDate();
            LocalDate today = LocalDate.now(AiConfig.getZoneId());
            if (!lastCallDate.equals(today)) {
                String dateMessage = String.format("现在是%d月%d日了", today.getMonthValue(), today.getDayOfMonth());
                messages.add(new AiApiClient.Message(AiApiClient.userMsg(dateMessage)));
            }
        }

        AI.recordApiCallTime();
        AiFiles.resetCurrentDbPath();

        doCallWithTools(messages, onFinalResponse, AiApiClient.buildMainTools(), new AiApiClient.TokenUsage(), 0);
    }

    /** 递归处理工具调用循环，accumulated 累积所有轮次的 token 用量 */
    private static void doCallWithTools(List<AiApiClient.Message> messages,
                                        Consumer<String> onFinalResponse,
                                        List<Map<String, Object>> tools,
                                        AiApiClient.TokenUsage accumulated,
                                        int round) {
        if (Thread.currentThread().isInterrupted()) {
            onFinalResponse.accept("[已中断]");
            return;
        }
        if (round >= MAX_TOOL_ROUNDS) {
            AI.ERROR("工具调用超过 " + MAX_TOOL_ROUNDS + " 轮，已中止这次对话");
            return;
        }

        AiApiClient.ChatResponse response = AiApiClient.sendApiRequest(messages, tools);
        if (response == null) {
            AI.ERROR("API 调用失败: " + AiApiClient.getLastError());
            return;
        }

        // 每次返回都更新上下文长度（覆盖式），token 用量按轮累加
        if (response.usage != null) {
            AiContext.setContextTokens(((Number) response.usage.getOrDefault("prompt_tokens", 0)).longValue());
        }
        long before = accumulated.totalTokens();
        accumulated.add(response.usage);
        long delta = accumulated.totalTokens() - before;
        String chatter = AiChatHandler.getCurrentChatter();
        if (delta > 0 && chatter != null) AiUsage.addUsage(chatter, delta);

        if (response.choices == null || response.choices.isEmpty()) {
            AI.ERROR("API 返回内容为空");
            return;
        }

        Map<String, Object> responseMessage = response.choices.get(0).message;
        String finishReason = response.choices.get(0).finish_reason;
        List<Map<String, Object>> toolCalls = AiApiClient.getToolCalls(responseMessage);

        if ("tool_calls".equals(finishReason) && toolCalls != null && !toolCalls.isEmpty()) {
            messages.add(new AiApiClient.Message(responseMessage));
            AiToolExecutor.broadcastToolCalls(toolCalls);

            for (Map<String, Object> toolCall : toolCalls) {
                if (Thread.currentThread().isInterrupted()) {
                    onFinalResponse.accept("[已中断]");
                    return;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                if (function == null) continue;

                String toolCallId = (String) toolCall.get("id");
                String name = (String) function.get("name");
                String arguments = (String) function.get("arguments");

                String result = AiToolExecutor.executeTool(name, arguments);
                AiToolExecutor.broadcastToolResult(name, result);
                messages.add(new AiApiClient.Message(AiApiClient.toolMsg(toolCallId, result)));
            }

            doCallWithTools(messages, onFinalResponse, tools, accumulated, round + 1);
            return;
        }

        String content = responseMessage.get("content") != null ? String.valueOf(responseMessage.get("content")) : "";
        // 整条回复存进上下文（含思考内容），要不要剔除思考交给「无工具调用思考剔除」开关
        messages.add(new AiApiClient.Message(responseMessage));
        AiContext.save();
        onFinalResponse.accept(content);

        // 下面这些提示只在本地聊天栏显示，不会发到服务器
        AiToolExecutor.broadcastTokenUsage(accumulated);
        if (chatter != null && AiUsage.isSafePlayerName(chatter)) AiToolExecutor.broadcastUserUsage(chatter);
    }
}
