/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来。
 */

package fish22.modernsupport.aibot;

import fish22.modernsupport.modules.OpenAI;
import fish22.modernsupport.settings.WhiteListSetting;
import meteordevelopment.meteorclient.settings.Setting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 配置读取层：所有值都取自 openAI 模块的设置界面（不再读写独立配置文件），
 * 另外负责「提示词」文件夹的创建与系统提示词拼接。
 */
public final class AiConfig {
    private AiConfig() {}

    private static OpenAI m() { return OpenAI.get(); }

    private static <T> T value(Setting<T> setting, T fallback) {
        return setting == null ? fallback : setting.get();
    }

    // ==================== API ====================

    /** 接口地址：只填域名也行，自动补 /chat/completions */
    public static String getApiUrl() {
        String url = value(m() == null ? null : m().apiUrl, "https://api.deepseek.com").trim();
        if (url.isEmpty()) url = "https://api.deepseek.com";
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.contains("/chat/completions")) url = url + "/chat/completions";
        return url;
    }

    public static String getApiKey() {
        return value(m() == null ? null : m().apiKey, "").trim();
    }

    public static String getModelName() {
        String model = value(m() == null ? null : m().modelName, "").trim();
        return model.isEmpty() ? "deepseek-v4-pro" : model;
    }

    /** 思考模式开关 */
    public static boolean isThinkingEnabled() {
        return m() == null || m().thinkingMode.get();
    }

    /** 兼容旧调用：enabled / disabled */
    public static String getThinkingMode() {
        return isThinkingEnabled() ? "enabled" : "disabled";
    }

    public static String getReasoningEffort() {
        String effort = value(m() == null ? null : m().reasoningEffort, "low").trim();
        return effort.isEmpty() ? "low" : effort;
    }

    /** true = 不把「无工具调用的思考」发给服务器 */
    public static boolean isStripThinking() {
        return m() == null || m().stripThinking.get();
    }

    // ==================== 聊天识别 ====================

    public static String getPublicChatFormat() {
        return value(m() == null ? null : m().publicChatFormat, "<{name}> {text}");
    }

    public static String getPrivateChatFormat() {
        return value(m() == null ? null : m().privateChatFormat, "{name} 悄悄对你说：{text}");
    }

    /** 忽略聊天消息里的字体/颜色字符（§x）后再做匹配 */
    public static boolean isIgnoreFormatting() {
        return m() == null || m().ignoreFormatting.get();
    }

    /** 忽略玩家名前面的 [] 内容（称号前缀 / 头像模组标记） */
    public static boolean isIgnoreNamePrefix() {
        return m() == null || m().ignoreNamePrefix.get();
    }

    /** 名字前缀忽略列表（设置里用逗号分隔，如 {@code ||}、{@code ★}） */
    public static List<String> getNamePrefixIgnores() {
        String raw = value(m() == null ? null : m().namePrefixIgnores, "");
        List<String> prefixes = new ArrayList<>();
        if (raw == null) return prefixes;
        for (String part : raw.split("[,，]")) {
            String prefix = part.trim();
            if (!prefix.isEmpty()) prefixes.add(prefix);
        }
        return prefixes;
    }

    /** 按上面的列表把名字开头的前缀剥掉（同一项可以连剥多次，最多 5 轮） */
    public static String stripNamePrefixes(String name) {
        if (name == null) return "";
        List<String> prefixes = getNamePrefixIgnores();
        if (prefixes.isEmpty()) return name;
        String result = name;
        for (int round = 0; round < 5; round++) {
            boolean changed = false;
            for (String prefix : prefixes) {
                while (result.startsWith(prefix)) {
                    result = result.substring(prefix.length()).trim();
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return result;
    }

    /** 调试：把没匹配上格式的消息原样打到本地 */
    public static boolean isDebugChat() {
        return m() != null && m().debugChat.get();
    }

    /** 私聊命令：{name} 替换成玩家名，{text} 替换成消息内容（没写 {text} 时消息接在命令后面） */
    public static String getPrivateCommand() {
        return value(m() == null ? null : m().privateCommand, "/tell {name} {text}");
    }

    /** 每条消息之间的间隔（毫秒） */
    public static long getMessageDelayMs() {
        return Math.max(1, value(m() == null ? null : m().messageDelay, 20)) * 10L;
    }

    /** 消息间隔是否加随机浮动（±1-8 tick） */
    public static boolean isRandomMessageDelay() {
        return m() == null || m().randomMessageDelay.get();
    }

    /** 触发词列表（设置里用逗号/空格分隔多个） */
    public static List<String> getTriggers() {
        String raw = value(m() == null ? null : m().trigger, "@ai");
        List<String> triggers = new ArrayList<>();
        for (String part : raw.split("[,，\\s]+")) {
            if (!part.isBlank()) triggers.add(part.trim());
        }
        if (triggers.isEmpty()) triggers.add("@ai");
        return triggers;
    }

    /** 私聊里不写触发词也回复 AI */
    public static boolean isPrivateNoTrigger() {
        return m() == null || m().privateNoTrigger.get();
    }

    // ==================== 自动聊天 ====================

    /** 自动聊天模式：不看触发词和白名单，任何符合公屏/私聊格式的消息都进入聚合 */
    public static boolean isAutoChatEnabled() {
        return m() != null && m().autoChat.get();
    }

    /** 自动聊天单批最长等待时间（秒） */
    public static long getAutoChatMaxDelayMs() {
        int seconds = value(m() == null ? null : m().autoChatMaxDelay, 30);
        return Math.max(1, Math.min(100, seconds)) * 1000L;
    }

    /** 自动聊天空闲多久后立即发送（秒） */
    public static long getAutoChatMinDelayMs() {
        int seconds = value(m() == null ? null : m().autoChatMinDelay, 10);
        return Math.max(1, Math.min(100, seconds)) * 1000L;
    }

    /** 自动聊天的上下限是否随机浮动 1-10 tick */
    public static boolean isAutoChatRandomDelay() {
        return m() == null || m().autoChatRandomDelay.get();
    }

    // ==================== agent：上下文删减 ====================

    /** 上下文自动删除总开关 */
    public static boolean isAutoTrim() {
        return m() == null || m().autoTrim.get();
    }

    /** 投机删除阈值（token）：超过它且距上次调用超过「忽略缓存时间」→ 下次调用前先删减 */
    public static long getSpeculativeTrimThreshold() {
        return value(m() == null ? null : m().speculativeTrim, 40000);
    }

    /** 忽略缓存时间（分钟） */
    public static long getCacheIgnoreMs() {
        return Math.max(1, value(m() == null ? null : m().cacheIgnoreMinutes, 240)) * 60_000L;
    }

    /** 删减后想保留的上下文大小（token，按这个原值删） */
    public static long getTrimKeepTarget() {
        return value(m() == null ? null : m().trimKeep, 10000);
    }

    /** 强制删除阈值（token）：超过它就无条件先删减再发送 */
    public static long getForceTrimThreshold() {
        return value(m() == null ? null : m().forceTrim, 80000);
    }

    // ==================== 白名单 ====================

    public static boolean isWhiteListed(String playerName) {
        OpenAI mod = m();
        return mod != null && mod.whiteList.contains(playerName);
    }

    /** 白名单条目（用量限制等都在里面），不在名单里返回 null */
    public static WhiteListSetting.Entry findUser(String playerName) {
        OpenAI mod = m();
        if (mod == null) return null;
        WhiteListSetting.Entry entry = mod.whiteList.find(playerName);
        if (entry != null) return entry;

        // 名字两侧都按「名字前缀忽略」处理过再比一次：白名单里填 ||Huanran 或 Huanran 都能对上。
        // 注意：名字里的 || 之类字符可能是系统真正分配的名字的一部分，只有「名字前缀忽略」里
        // 明确写了才会剥，别自作主张去掉。
        String stripped = stripNamePrefixes(playerName);
        if (stripped.isEmpty()) return null;
        for (WhiteListSetting.Entry candidate : mod.whiteList.get()) {
            if (candidate.name == null) continue;
            if (stripNamePrefixes(candidate.name).equalsIgnoreCase(stripped)) return candidate;
        }
        return null;
    }

    /** 玩家每日 token 限制，-1 = 不限量 */
    public static long limitOf(String playerName) {
        OpenAI mod = m();
        return mod == null ? 0 : mod.whiteList.limitOf(playerName);
    }

    public static List<WhiteListSetting.Entry> whiteListSnapshot() {
        OpenAI mod = m();
        return mod == null ? new ArrayList<>() : new ArrayList<>(mod.whiteList.get());
    }

    // ==================== 工具开关 ====================

    /**
     * 工具是否开启。用开关分组里对应的复选框控制；
     * 没列出来的工具（子代理那一批）一律关闭 —— 子代理功能已阉割。
     */
    public static boolean isToolEnabled(String tool) {
        OpenAI mod = m();
        if (mod == null || tool == null) return false;
        return switch (tool) {
            case "get_time" -> mod.toolGetTime.get();
            case "check_chat" -> mod.toolCheckChat.get();
            case "list_players" -> mod.toolListPlayers.get();
            case "send_message" -> mod.toolSendMessage.get();
            case "create_scheduled_task", "delete_scheduled_task" -> mod.toolScheduledTask.get();
            case "list_roots" -> mod.toolListRoots.get();
            case "cd", "ls", "read" -> mod.toolFileRead.get();
            case "write", "edit", "mkdir", "move" -> mod.toolFileWrite.get();
            case "delete_file" -> mod.toolFileDelete.get();
            case "search_name", "search_content", "search_file" -> mod.toolSearch.get();
            default -> false;
        };
    }

    // ==================== 时区 ====================

    /** 时间相关计算统一用客户端所在时区 */
    public static ZoneId getZoneId() {
        return ZoneId.systemDefault();
    }

    // ==================== 系统提示词 ====================

    public static Path getPromptDir() {
        return AI.getConfigDir().resolve("提示词");
    }

    /** 创建提示词文件夹与默认文件 */
    public static void loadPrompts() {
        try {
            Path promptDir = getPromptDir();
            Files.createDirectories(promptDir);

            Path promptFile = promptDir.resolve("系统提示词.md");
            if (!Files.exists(promptFile)) {
                String defaultPrompt = """
                    你是Minecraft服务器聊天里的一个AI玩家，名字叫[[[name]]]。
                    玩家在公屏或私聊里和你说话，你的回复会直接发到服务器聊天栏。
                    要求：
                    - 说话简洁、口语化，像玩家聊天，不要长篇大论（聊天栏一行的长度就够）
                    - 直接说内容，不要输出"我说："这类前缀，也不要解释自己是AI
                    - 需要让大家看到就用 send_message 工具；能用工具查清楚的事就别瞎猜
                    - 不确定的事可以问玩家，或者用工具查

                    {{人物设定.md}}

                    【记忆】
                    {{记忆.md}}
                    """;
                Files.writeString(promptFile, defaultPrompt);
                AI.LOG("已创建默认系统提示词: " + promptFile);
            }

            Path charFile = promptDir.resolve("人物设定.md");
            if (!Files.exists(charFile)) {
                Files.writeString(charFile, "性格：友善、活泼、喜欢聊天\n喜欢：建筑、红石、和玩家聊天\n说话风格：简洁、偶尔带点幽默");
                AI.LOG("已创建默认人物设定: " + charFile);
            }

            Path memoryFile = promptDir.resolve("记忆.md");
            if (!Files.exists(memoryFile)) {
                Files.writeString(memoryFile, "（暂无记忆）");
            }
        } catch (IOException e) {
            AI.ERROR("加载提示词出错: " + e.getMessage());
        }
    }

    /**
     * 读提示词文件夹、拼出完整的系统提示词。返回 null 表示失败。
     */
    private static String buildSystemPrompt() {
        try {
            Path promptFile = getPromptDir().resolve("系统提示词.md");
            if (!Files.exists(promptFile)) {
                AI.ERROR("系统提示词.md 不存在，无法刷新");
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(promptFile)) {
                sb.append(processPromptLine(line, getPromptDir())).append("\n");
            }
            String result = sb.toString().trim();
            result = result.replace("[[[name]]]", playerName());
            return result;
        } catch (IOException e) {
            AI.ERROR("刷新系统提示词失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 重新拼接系统提示词，并替换上下文最前面那一条。
     *
     * <p>只有两种情况会调它：删减完上下文之后、以及手动点「刷新系统提示词」按钮。
     * 平时系统提示词固定在上下文最前面不动，这样最前面这一段稳定，服务商的上下文缓存才命中。
     */
    public static String refreshSystemPrompt() {
        if (AI.getConfigDir() == null) {
            AI.ERROR("openAI 模块还没开启，读不到「提示词」文件夹");
            return null;
        }
        String prompt = buildSystemPrompt();
        if (prompt == null || prompt.isEmpty()) {
            AI.ERROR("系统提示词是空的，没有刷新");
            return null;
        }
        AiContext.setSystemPrompt(prompt);
        AiContext.save();
        AI.LOG("系统提示词已刷新（" + prompt.length() + " 个字符）");
        return prompt;
    }

    /** 上下文最前面还没有系统提示词时（第一次用、老存档）补一条，已有的不动 */
    public static void ensureSystemPrompt() {
        if (AI.getConfigDir() == null) return;
        if (!AiContext.getSystemPrompt().isEmpty()) return;
        String prompt = buildSystemPrompt();
        if (prompt == null || prompt.isEmpty()) return;
        AiContext.setSystemPrompt(prompt);
        AiContext.save();
        AI.LOG("已把系统提示词写进上下文最前面（" + prompt.length() + " 个字符）");
    }

    /** GUI 按钮用：手动刷新系统提示词（AI 正在跑就不刷新，免得两个线程一起动上下文） */
    public static void refreshSystemPromptFromGui() {
        if (AiChatHandler.isCallingApi()) {
            AI.LOG("AI 正在处理一次对话，等它说完再刷新系统提示词");
            return;
        }
        refreshSystemPrompt();
    }

    /** 当前客户端玩家名（提示词里的 [[[name]]]） */
    public static String playerName() {
        try {
            return mc.getUser().getName();
        } catch (Exception e) {
            return "AI";
        }
    }

    /**
     * 处理提示词里的一行：{{文件名}} 会替换成同目录下文件的内容。
     * 只允许引用纯文件名（禁止 / \ .. : 和点开头），避免越权读取到别的文件。
     */
    private static String processPromptLine(String line, Path baseDir) {
        StringBuilder result = new StringBuilder();
        int currentPos = 0;
        while (true) {
            int startIdx = line.indexOf("{{", currentPos);
            if (startIdx == -1) {
                result.append(line.substring(currentPos));
                break;
            }
            int endIdx = line.indexOf("}}", startIdx + 2);
            if (endIdx == -1) {
                result.append(line.substring(currentPos));
                break;
            }
            result.append(line, currentPos, startIdx);
            String fileName = line.substring(startIdx + 2, endIdx).trim();

            if (fileName.isEmpty()
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..") || fileName.contains(":")
                || fileName.startsWith(".")) {
                currentPos = endIdx + 2;
                continue;
            }

            try {
                Path resolved = baseDir.resolve(fileName).normalize();
                if (resolved.startsWith(baseDir) && Files.isRegularFile(resolved)) {
                    result.append(Files.readString(resolved).trim());
                }
            } catch (IOException ignored) {
                // 读取失败就当空
            }
            currentPos = endIdx + 2;
        }
        return result.toString();
    }
}
