/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来。
 */

package fish22.modernsupport.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话上下文：openai/上下文/main.json 持久化 + 上下文长度跟踪 + 发送前自动删减。
 *
 * <p>文件格式与 AEBot 一致（头部 last_context_tokens + 各会话的消息数组），
 * 想接着用以前的对话记录，把老的 main.json 拷到 openai/上下文/ 下即可。
 */
public final class AiContext {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MAIN_KEY = "default";

    private static final Map<String, List<AiApiClient.Message>> conversationHistory = new HashMap<>();
    /** 最近一次 API 返回的输入 token 数（覆盖式更新，不累计） */
    private static long contextTokens = 0;
    /** 上次序列化后的字符数（用来估算 token） */
    private static int lastChars = 0;

    private AiContext() {}

    // ==================== 读写 ====================

    public static void load() {
        conversationHistory.clear();
        contextTokens = 0;
        lastChars = 0;
        if (AI.getContextDir() == null) return;

        Path file = AI.getContextDir().resolve("main.json");
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file).trim();
            if (json.isEmpty()) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> loaded = GSON.fromJson(json, Map.class);
            if (loaded == null) return;

            if (loaded.get("last_context_tokens") instanceof Number number) contextTokens = number.longValue();
            for (Map.Entry<String, Object> entry : loaded.entrySet()) {
                if ("last_context_tokens".equals(entry.getKey())) continue;
                if (!(entry.getValue() instanceof List<?> list)) continue;

                List<AiApiClient.Message> messages = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) map;
                        messages.add(new AiApiClient.Message(data));
                    }
                }
                conversationHistory.put(entry.getKey(), messages);
            }
            lastChars = json.length();
            AI.LOG("已恢复对话上下文（" + contextTokens + " tokens / " + getMainConversation().size() + " 条消息）");
        } catch (IOException e) {
            AI.ERROR("加载对话上下文失败: " + e.getMessage());
        }
    }

    public static void save() {
        if (AI.getContextDir() == null) return;
        try {
            String json = buildJson();
            Path file = AI.getContextDir().resolve("main.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
            lastChars = json.length();
        } catch (IOException e) {
            AI.ERROR("保存对话上下文失败: " + e.getMessage());
        }
    }

    /**
     * 只在上下文末尾补一条 user 消息并立即保存，不调用 API。
     * 自动聊天开关状态就是靠这个记录给下次 AI 调用看的。
     */
    public static boolean appendUserMessage(String content) {
        if (AI.getContextDir() == null) return false;
        getMainConversation().add(new AiApiClient.Message(AiApiClient.userMsg(content)));
        save();
        return true;
    }

    private static String buildJson() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("last_context_tokens", contextTokens);
        for (Map.Entry<String, List<AiApiClient.Message>> entry : conversationHistory.entrySet()) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (AiApiClient.Message message : entry.getValue()) messages.add(message.data);
            data.put(entry.getKey(), messages);
        }
        return GSON.toJson(data);
    }

    /** 主对话的消息列表（最前面固定是系统提示词，发送前会清理它后面的孤儿消息） */
    public static List<AiApiClient.Message> getMainConversation() {
        return conversationHistory.computeIfAbsent(MAIN_KEY, key -> new ArrayList<>());
    }

    // ==================== 系统提示词（上下文最前面那一条） ====================

    /**
     * 上下文最前面固定放着的那条 system（系统提示词）。
     *
     * <p>它跟着上下文一起持久化，而且只在「上下文删减」和「手动点刷新系统提示词」时重写，
     * 别的时候一直不动：上下文最前面这一段稳定，服务商的上下文缓存才命中，省钱也省时间。
     */
    public static String getSystemPrompt() {
        List<AiApiClient.Message> messages = getMainConversation();
        if (headSize(messages) == 0) return "";
        Object content = messages.get(0).data.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    /** 替换（没有就插入）上下文最前面的系统提示词，顺带清掉后面多出来的 system 消息 */
    public static void setSystemPrompt(String prompt) {
        List<AiApiClient.Message> messages = getMainConversation();
        AiApiClient.Message message = new AiApiClient.Message(AiApiClient.systemMsg(prompt == null ? "" : prompt));
        if (headSize(messages) == 1) messages.set(0, message);
        else messages.add(0, message);

        for (int i = messages.size() - 1; i >= 1; i--) {
            if ("system".equals(messages.get(i).getRole())) messages.remove(i);
        }
    }

    /** 清掉系统提示词后面开头的孤儿消息（工具结果等），系统提示词那条不动 */
    public static void dropLeadingOrphans() {
        List<AiApiClient.Message> messages = getMainConversation();
        int head = headSize(messages);
        while (messages.size() > head && !"user".equals(messages.get(head).getRole())) messages.remove(head);
    }

    /**
     * 开头连续 system 消息的条数（0 或 1）。
     *
     * <p>系统提示词永远在上下文最前面，删减上下文时不能动它，所以凡是「从头删东西」的地方
     * 都要先跳过这里算出来的条数。
     */
    private static int headSize(List<AiApiClient.Message> messages) {
        return (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) ? 1 : 0;
    }

    public static long getContextTokens() { return contextTokens; }

    public static void setContextTokens(long tokens) { contextTokens = tokens; }

    // ==================== 自动删减 ====================

    /**
     * 发送 API 前的自动删减（上下文自动删除开启时）：
     * <ul>
     *   <li>强制删除：上下文到达「强制删除阈值」→ 无条件先删减再发送</li>
     *   <li>投机删除：上下文超过「投机删除阈值」，且距上次调用超过「忽略缓存时间」
     *       （缓存已经失效，删了不亏）→ 先删减再发送</li>
     * </ul>
     */
    public static void trimBeforeSend() {
        if (!AiConfig.isAutoTrim()) return;
        if (contextTokens <= 0) return;

        long force = AiConfig.getForceTrimThreshold();
        long speculative = AiConfig.getSpeculativeTrimThreshold();
        // 「忽略缓存时间」要从「上次调用时间」往后算：没记录过上次调用（第一次用、刚开游戏、
        // 缓存文件被删）时按 0 算，投机删除不出手，免得一启动就把上下文删掉。
        long lastCall = AI.getLastApiCallTime();
        long elapsed = lastCall > 0 ? System.currentTimeMillis() - lastCall : 0;

        boolean forceTrim = force > 0 && contextTokens >= force;
        boolean speculativeTrim = speculative > 0 && contextTokens >= speculative
            && lastCall > 0 && elapsed >= AiConfig.getCacheIgnoreMs();
        if (!forceTrim && !speculativeTrim) return;

        // 按「上下文删除保留值」原值删
        long keep = Math.max(1000, AiConfig.getTrimKeepTarget());
        long before = contextTokens;
        int rounds = trimToTarget(keep);
        if (rounds > 0) {
            AI.LOG(String.format("%s: 上下文 %d tokens，删除 %d 轮对话（保留目标 %d）",
                forceTrim ? "强制删除" : "投机删除", before, rounds, keep));
            AiConfig.refreshSystemPrompt();
        }
    }

    /**
     * 手动删减一次：当前上下文没有超过「上下文删除保留值」就什么都不做。
     *
     * <p>按保留值原值删（不乘 0.7），删完重新拼一次系统提示词。GUI 上的「立即删减上下文」按钮用。
     */
    public static void trimNow() {
        if (AI.getConfigDir() == null) {
            AI.ERROR("上下文未初始化，无法删减");
            return;
        }
        // 按钮在界面线程上按，AI 正在跑工具循环时上下文归它用，别两边一起动
        if (AiChatHandler.isCallingApi()) {
            AI.LOG("AI 正在处理一次对话，等它说完再删减");
            return;
        }

        long keep = Math.max(1000, AiConfig.getTrimKeepTarget());
        long before = contextTokens > 0 ? contextTokens : estimateTokens(buildJson());
        if (before <= keep) {
            AI.LOG("当前上下文约 " + before + " tokens，没超过保留值 " + keep + "，无需删减");
            return;
        }

        int rounds = trimToTarget(keep);
        save();
        AiConfig.refreshSystemPrompt();
        AI.LOG("手动删减: 上下文 " + before + " → " + estimateTokens(buildJson())
            + " tokens，删除 " + rounds + " 轮对话");
    }

    /**
     * 从最长的会话开始删整轮对话（至少保留最近一轮），直到估算 token 数不超过 keepTarget。
     *
     * @return 删掉的轮数
     */
    private static int trimToTarget(long keepTarget) {
        int rounds = 0;
        String json = buildJson();
        while (estimateTokens(json) > keepTarget) {
            List<AiApiClient.Message> biggest = null;
            for (List<AiApiClient.Message> messages : conversationHistory.values()) {
                // 至少得有两轮对话才删得动（最前面的系统提示词不算轮）
                if (messages.size() > headSize(messages) + 1
                    && (biggest == null || messages.size() > biggest.size())) {
                    biggest = messages;
                }
            }
            if (biggest == null) break;

            // 系统提示词一直待在上下文最前面，删减只能从它后面开始
            int head = headSize(biggest);

            // 清理开头的孤儿消息（tool 结果等）
            while (biggest.size() > head && !"user".equals(biggest.get(head).getRole())) biggest.remove(head);
            if (biggest.size() <= head + 1) break;

            // 找下一轮的 user 头，把这一整轮删掉；找不到说明只剩最后一轮，不删
            int roundEnd = -1;
            for (int i = head + 1; i < biggest.size(); i++) {
                if ("user".equals(biggest.get(i).getRole())) {
                    roundEnd = i;
                    break;
                }
            }
            if (roundEnd < 0) break;

            biggest.subList(head, roundEnd).clear();
            rounds++;
            json = buildJson();
        }
        lastChars = json.length();
        return rounds;
    }

    /**
     * 按字符数估算 token：优先用「上次 API 返回的 prompt_tokens / 上次字符数」的比例，
     * 没有数据时按 0.6（中文为主时的经验值）。
     */
    private static long estimateTokens(String json) {
        double ratio = 0.6;
        if (lastChars > 0 && contextTokens > 0) {
            ratio = Math.max(0.15, Math.min(1.5, (double) contextTokens / lastChars));
        }
        return (long) (json.length() * ratio);
    }
}
