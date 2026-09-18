/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来。
 */

package fish22.modernsupport.aibot;

import fish22.modernsupport.settings.WhiteListSetting;
import meteordevelopment.meteorclient.mixin.ChatComponentAccessor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.multiplayer.chat.GuiMessage;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 聊天处理：解析服务器公屏/私聊消息 → 白名单与额度校验 → 启动一次 AI 对话。

 * <p>识别格式用设置里的模板（{name} / {text}），正则从消息开头匹配，
 * 保证别人伪造不出这个前缀；发给 AI 的消息也按同一模板拼回前缀（如 {@code <Steve>: 你好}）。
 * AI 的回复按触发来源发送：公屏触发的走公屏，私聊触发的走私聊命令。
 */
public final class AiChatHandler {
    private record ParsedChat(String name, String text) {}
    private record AutoChatMessage(String name, String text) {
        String toUserMessage() {
            return "<" + name + ">:" + text;
        }
    }

    /** 公屏聊天记录（check_chat 工具用，最多 100 条） */
    private static final List<String> recentChatMessages = new ArrayList<>();
    private static final int MAX_CHAT_HISTORY = 100;
    /** 同一玩家 1 秒内只处理一次触发 */
    private static final Map<String, Long> lastTriggerTime = new HashMap<>();
    private static final long MIN_TRIGGER_INTERVAL_MS = 1000;
    /** 「玩家名|消息内容」→ 上次触发时间：同一条消息的本地发送和服务器回显只算一次 */
    private static final Map<String, Long> lastTriggerContent = new HashMap<>();
    private static final long CONTENT_DEDUP_MS = 3000;
    /** 未识别提示的限流（万一再出现自我循环也不会刷屏卡死） */
    private static long lastUnrecognizedLog = 0;
    private static final long UNRECOGNIZED_LOG_INTERVAL_MS = 1000;
    /** 格式模板 → 正则缓存 */
    private static final Map<String, Pattern> patternCache = new HashMap<>();
    /** 本模块最近发出去的消息：用来忽略自己回复的服务器回显，避免 AI 自己跟自己聊起来 */
    private static final Deque<SentLine> sentLines = new ArrayDeque<>();
    private static final long SENT_LINE_TTL_MS = 60_000;

    /** AI 接口是否正在处理（这期间其他玩家的触发直接不回应） */
    private static volatile boolean apiBusy = false;
    private static volatile String currentChatter = null;
    private static Thread currentAiThread = null;

    /** 待发送的消息队列：AI 回复拆成的多行、工具发的公屏消息都排在这里，由发送线程按间隔一条条发出去 */
    private static final Deque<String> sendQueue = new ArrayDeque<>();
    /** 队列 + 发送线程的锁：队列空了发送线程就在这上面等 */
    private static final Object sendLock = new Object();
    private static Thread senderThread = null;
    /** 发送线程手上正拿着一条消息（还没真正发出去） */
    private static volatile boolean sendingNow = false;

    /** 消息间隔的随机浮动：±1-8 tick（1 tick = 50 毫秒） */
    private static final int JITTER_MIN_TICKS = 1;
    private static final int JITTER_MAX_TICKS = 8;
    private static final long TICK_MS = 50L;
    private static final long MIN_MESSAGE_DELAY_MS = 50L;
    private static final Random RANDOM = new Random();

    private record SentLine(String text, long time) {}

    /** 自动聊天待整合消息；第一条消息起算上限，最近一条消息起算空闲下限 */
    private static final List<AutoChatMessage> autoChatQueue = new ArrayList<>();
    private static final Object autoChatLock = new Object();
    private static long autoChatFirstMessageAt = 0L;
    private static long autoChatLastMessageAt = 0L;
    private static long autoChatMaxJitterMs = 0L;
    private static long autoChatMinJitterMs = 0L;
    /** 本次自动聊天已经写入「自动聊天开启」，关闭时才有必要补「自动聊天关闭」 */
    private static boolean autoChatSessionOpenMarked = false;
    /** AI 正忙时先记下来，等它空闲再把开关标记写进上下文 */
    private static boolean autoChatPendingOpenMarker = false;
    private static boolean autoChatPendingCloseMarker = false;
    /** 上一次拿到的设置值，避免配置重载时把正在聚合的消息清掉 */
    private static boolean autoChatSettingWasEnabled = false;

    private AiChatHandler() {}

    /** AI 在调用接口、或者还有消息没发完，都算忙（这期间其他玩家的触发不回应） */
    public static boolean isBusy() {
        if (apiBusy || sendingNow) return true;
        synchronized (sendLock) {
            return !sendQueue.isEmpty();
        }
    }

    /**
     * AI 接口是否正在处理一次对话（工具循环跑完才算结束）。
     *
     * <p>这期间对话上下文正被 AI 线程用着，界面上的按钮别去动它。
     */
    public static boolean isCallingApi() {
        return apiBusy;
    }

    public static String getCurrentChatter() { return currentChatter; }
    public static List<String> getRecentChatMessages() { return recentChatMessages; }

    /** agent 板块「强制触发」按钮：取公屏最近 10 条消息，强制触发一次公屏回复 */
    public static void forceTriggerFromRecentChat() {
        if (isBusy()) {
            AI.LOG("AI 正忙，无法强制触发");
            return;
        }

        List<String> messages = recentPublicMessagesFromChatHud(10);
        if (messages.isEmpty()) {
            // 兜底：聊天栏历史读不到时，用模块自己记录的公屏聊天。
            int from = Math.max(0, recentChatMessages.size() - 10);
            messages = new ArrayList<>(recentChatMessages.subList(from, recentChatMessages.size()));
        }
        if (messages.isEmpty()) {
            AI.LOG("公屏还没有聊天记录，无法强制触发");
            return;
        }

        if (!startAiCall(messages, null, false, null)) {
            AI.LOG("AI 正忙，无法强制触发");
            return;
        }
        AI.LOG("强制触发：已传入公屏最近 " + messages.size() + " 条消息");
    }

    /** 从客户端聊天栏取最近 N 条能识别成公屏的消息，按时间从旧到新返回 */
    private static List<String> recentPublicMessagesFromChatHud(int limit) {
        List<String> result = new ArrayList<>();
        try {
            if (mc == null || mc.gui == null || mc.gui.getChat() == null) return result;

            List<GuiMessage> allMessages = ((ChatComponentAccessor) mc.gui.getChat()).meteor$getAllMessages();
            if (allMessages == null || allMessages.isEmpty()) return result;

            // ChatComponent 的 allMessages 是新消息在前；先收集最近 N 条，再反转成时间顺序。
            for (GuiMessage guiMessage : allMessages) {
                if (guiMessage == null || guiMessage.content() == null) continue;
                String text = guiMessage.content().getString();
                if (AiConfig.isIgnoreFormatting()) text = AI.stripColor(text);

                ParseResult parsed = parseLine(text);
                if (parsed == null || parsed.isPrivate()) continue;

                ParsedChat chat = parsed.chat();
                if (chat.name().isEmpty() || chat.text().isEmpty()) continue;
                if (isRecentlySent(chat.text())) continue;   // 不要把AI自己发出去的回复再喂回去

                result.add(new AutoChatMessage(chat.name(), chat.text()).toUserMessage());
                if (result.size() >= limit) break;
            }
            Collections.reverse(result);
        } catch (Exception ignored) {
            // 聊天栏结构变化或读取失败时交给调用方走模块自己的公屏记录
        }
        return result;
    }

    // ==================== 自动聊天 ====================

    /** 模块开启时初始化一次会话状态，不主动写上下文标记 */
    public static void onModuleActivated() {
        synchronized (autoChatLock) {
            resetAutoChatBufferLocked();
            autoChatSessionOpenMarked = false;
            autoChatPendingOpenMarker = false;
            autoChatPendingCloseMarker = false;
            autoChatSettingWasEnabled = AiConfig.isAutoChatEnabled();
        }
    }

    /**
     * 自动聊天开关变化：
     * 开启时只清掉旧状态，等真的有聊天触发才写「自动聊天开启」；
     * 关闭时丢掉还没发的整批消息，如果本次写过开启标记，再补一条「自动聊天关闭」。
     */
    public static void onAutoChatSettingChanged(boolean enabled) {
        synchronized (autoChatLock) {
            boolean wasEnabled = autoChatSettingWasEnabled;
            autoChatSettingWasEnabled = enabled;

            // 配置重载也会触发 onChanged；值没变时不要动正在等待的那一批消息。
            if (enabled && wasEnabled) return;

            resetAutoChatBufferLocked();
            if (enabled) {
                autoChatSessionOpenMarked = false;
                autoChatPendingOpenMarker = false;
                autoChatPendingCloseMarker = false;
                return;
            }
            if (wasEnabled && (autoChatSessionOpenMarked || autoChatPendingOpenMarker)) {
                autoChatPendingCloseMarker = true;
            }
        }
        processAutoChatMarkers();
    }

    /** 每个 tick 检查自动聊天批次是否到了上限或空闲下限 */
    public static void tickAutoChat() {
        processAutoChatMarkers();
        if (!AiConfig.isAutoChatEnabled()) return;

        List<AutoChatMessage> batch;
        synchronized (autoChatLock) {
            if (autoChatQueue.isEmpty()) return;

            long now = System.currentTimeMillis();
            long maxDelay = Math.max(TICK_MS, AiConfig.getAutoChatMaxDelayMs() + autoChatMaxJitterMs);
            long minDelay = Math.max(TICK_MS, AiConfig.getAutoChatMinDelayMs() + autoChatMinJitterMs);
            boolean maxReached = now - autoChatFirstMessageAt >= maxDelay;
            boolean idleReached = now - autoChatLastMessageAt >= minDelay;
            if (!maxReached && !idleReached) return;
            batch = new ArrayList<>(autoChatQueue);
        }

        // AI 还在处理上一批：这批继续留着，下一个 tick 再试。
        if (isBusy()) return;

        List<String> userMessages = new ArrayList<>(batch.size());
        for (AutoChatMessage message : batch) userMessages.add(message.toUserMessage());

        // 自动聊天无论来源是公屏还是私聊，回复一律发到公屏。
        if (!startAiCall(userMessages, null, false, null)) return;

        synchronized (autoChatLock) {
            if (autoChatQueue.size() >= batch.size()) {
                autoChatQueue.subList(0, batch.size()).clear();
            } else {
                autoChatQueue.clear();
            }
            if (autoChatQueue.isEmpty()) {
                resetAutoChatBufferLocked();
            } else {
                // 极端情况下有新消息插进来，把新批次的起算时间从保留消息的第一条重新算。
                autoChatFirstMessageAt = System.currentTimeMillis();
                randomizeAutoChatJitterLocked();
            }
        }
        AI.LOG("自动聊天整合 " + batch.size() + " 条消息，触发AI");
    }

    private static void queueAutoChat(ParsedChat parsed) {
        long now = System.currentTimeMillis();
        synchronized (autoChatLock) {
            if (autoChatQueue.isEmpty()) {
                autoChatFirstMessageAt = now;
                randomizeAutoChatJitterLocked();
                AI.LOG("自动聊天开始计时");
            }
            autoChatQueue.add(new AutoChatMessage(parsed.name(), parsed.text()));
            autoChatLastMessageAt = now;
            // 只在自动聊天首次收到有效聊天时写一条，不调用 API。
            if (!autoChatSessionOpenMarked && !autoChatPendingOpenMarker) {
                if (isCallingApi()) {
                    autoChatPendingOpenMarker = true;
                } else {
                    autoChatSessionOpenMarked = AiContext.appendUserMessage("自动聊天开启");
                    if (!autoChatSessionOpenMarked) autoChatPendingOpenMarker = true;
                }
            }
        }
    }

    private static void resetAutoChatBufferLocked() {
        autoChatQueue.clear();
        autoChatFirstMessageAt = 0L;
        autoChatLastMessageAt = 0L;
        autoChatMaxJitterMs = 0L;
        autoChatMinJitterMs = 0L;
    }

    /** 每批消息开始聚合时抽一次，整批期间上下限实际取值保持不变 */
    private static void randomizeAutoChatJitterLocked() {
        if (!AiConfig.isAutoChatRandomDelay()) {
            autoChatMaxJitterMs = 0L;
            autoChatMinJitterMs = 0L;
            return;
        }

        int maxTicks = 1 + RANDOM.nextInt(10);
        int minTicks = 1 + RANDOM.nextInt(10);
        autoChatMaxJitterMs = (RANDOM.nextBoolean() ? 1L : -1L) * maxTicks * TICK_MS;
        autoChatMinJitterMs = (RANDOM.nextBoolean() ? 1L : -1L) * minTicks * TICK_MS;
    }

    /** AI 空闲时把排队的「自动聊天开启/关闭」标记写进上下文，不调用 API */
    private static void processAutoChatMarkers() {
        synchronized (autoChatLock) {
            if (isCallingApi()) return;

            if (autoChatPendingOpenMarker) {
                if (!AiContext.appendUserMessage("自动聊天开启")) return;
                autoChatPendingOpenMarker = false;
                autoChatSessionOpenMarked = true;
            }

            if (autoChatPendingCloseMarker && autoChatSessionOpenMarked) {
                if (AiContext.appendUserMessage("自动聊天关闭")) {
                    autoChatSessionOpenMarked = false;
                    autoChatPendingCloseMarker = false;
                }
            }
        }
    }

    // ==================== 消息入口 ====================

    /** 收到一条聊天栏消息（模块订阅 ReceiveMessageEvent 后调用） */
    public static void onMessage(String raw) {
        if (raw == null || raw.isEmpty()) return;
        // 本模块自己打在本地聊天栏的提示（工具调用/token/报错/未识别）不再当作聊天处理
        if (AI.isLocalMessage(raw)) return;

        // 忽略字体/颜色字符，方便玩家填自己服务器魔改过的格式
        String text = AiConfig.isIgnoreFormatting() ? AI.stripColor(raw) : raw;

        ParseResult result = parseLine(text);
        if (result == null) {
            // 认不出格式：带触发词（或开了调试）就把服务器实际显示的内容打在本地，方便对着改格式
            boolean hasTrigger = containsTrigger(text);
            if ((hasTrigger || AiConfig.isDebugChat()) && !isRecentlySent(text)) {
                long now = System.currentTimeMillis();
                if (now - lastUnrecognizedLog >= UNRECOGNIZED_LOG_INTERVAL_MS) {
                    lastUnrecognizedLog = now;
                    AI.LOG("[未识别] " + escapeInvisible(text));
                    AI.LOG("[未识别] 码点 " + describeCodePoints(text, 20));
                    if (hasTrigger) {
                        AI.LOG("[未识别] 当前 公屏格式=" + AiConfig.getPublicChatFormat()
                            + " | 私聊格式=" + AiConfig.getPrivateChatFormat());
                    }
                }
            }
            return;
        }
        ParsedChat parsed = result.chat();
        boolean isPrivate = result.isPrivate();

        // 本模块刚发出去的回复（服务器回显的那一份）不再触发一次，否则 AI 会自己跟自己聊起来
        if (isRecentlySent(parsed.text())) return;

        // 名字是自己的消息不再一律拦掉了：自己开小号私聊自己、或自己发公屏测试时，也要能触发。
        // 防止自问自答靠的是上面那句 isRecentlySent（自己发出去的内容会被记住）。

        handle(parsed, isPrivate);
    }

    /**
     * 自己在聊天框里发出去的公屏消息（模块订阅 SendMessageEvent 后调用）。
     *
     * <p>走这条路的目的是：自己说的话不依赖服务器回显也能触发，一个人也能测试和使用这个模块。
     * 记进"刚发过"表里之后，服务器回显同一条时就不会再触发第二次。
     */
    public static void onOutgoingMessage(String message) {
        if (message == null || message.isEmpty()) return;
        if (message.startsWith("/")) return;   // 命令不算聊天
        if (isRecentlySent(message)) return;   // 本模块自己发出去的回复

        String self = selfName();
        if (self == null || self.isEmpty()) return;

        handle(new ParsedChat(self, message), false);
    }

    /** 公屏/私聊消息的统一处理：聊天记录 → 触发词 → 白名单 → 额度 → 防抖 → 起一次 AI 对话 */
    private static void handle(ParsedChat parsed, boolean isPrivate) {
        if (parsed.name().isEmpty() || parsed.text().isEmpty()) return;
        boolean isSelf = isSelfName(parsed.name());
        long now = System.currentTimeMillis();

        // 自己发的同一条消息会同时走本地发送和服务器回显，历史只记第一条。
        if (isSelf && isDuplicateContent(parsed.name(), parsed.text(), now)) return;

        // 公屏聊天记录：自己的普通发言也算，AI 自己发出去的回复会在更早的 isRecentlySent 里排掉。
        if (!isPrivate) {
            recentChatMessages.add(AiConfig.getPublicChatFormat()
                .replace("{name}", parsed.name())
                .replace("{text}", parsed.text()));
            if (recentChatMessages.size() > MAX_CHAT_HISTORY) recentChatMessages.remove(0);
        }

        // 自动聊天：任何符合公屏/私聊格式的消息都进入聚合，不看触发词和白名单。
        if (AiConfig.isAutoChatEnabled()) {
            queueAutoChat(parsed);
            return;
        }

        // 触发词：私聊可以设置为不需要触发词
        boolean triggered = containsTrigger(parsed.text());
        if (!triggered && !(isPrivate && AiConfig.isPrivateNoTrigger())) return;

        // 白名单：只有名单里的玩家能用（服务器可能给名字加前缀，统一用白名单里的标准名）
        WhiteListSetting.Entry user = AiConfig.findUser(parsed.name());
        if (user == null) {
            // 不在白名单里的触发不提示（公屏上谁都不知道会冒出来一句，太刷屏），只写进日志文件
            AI.QUIET("忽略 " + parsed.name() + " 的触发（不在白名单里）");
            return;
        }
        // 回复命令和用量记录都用白名单里那个名字本身
        // （名字里的 || 之类字符是系统分配的，只有「名字前缀忽略」里写了的才剥）
        String name = AiConfig.stripNamePrefixes(user.name);
        if (name.isEmpty()) name = user.name;

        // 每日额度（-1 = 不限量）
        if (user.limit >= 0 && AiUsage.getUsageToday(name) >= user.limit) {
            AI.QUIET(name + " 今日额度已用完，忽略这次触发");
            return;
        }

        // 同一条内容短时间内只处理一次：自己在聊天框发的那条和服务器回显的那条是同一条消息，
        // 上面两条入口都会看到它，这里去重，避免 AI 回复两遍
        if (!isSelf && isDuplicateContent(name, parsed.text(), now)) return;

        // 防重复触发（同一玩家 1 秒内只处理一次）
        String key = name.toLowerCase();
        Long last = lastTriggerTime.get(key);
        if (last != null && now - last < MIN_TRIGGER_INTERVAL_MS) return;
        lastTriggerTime.put(key, now);

        if (isBusy()) {
            // API 在忙、或者上一条回复还没发完：这次触发直接不回应（只有开了调试才打在本地）
            if (AiConfig.isDebugChat()) AI.LOG("AI 正忙，忽略 " + name + " 的这次触发");
            return;
        }

        // 给 AI 的消息保留前缀（用识别格式拼回来）
        String format = isPrivate ? AiConfig.getPrivateChatFormat() : AiConfig.getPublicChatFormat();
        String prefixed = format.replace("{name}", name).replace("{text}", parsed.text()).trim();

        AI.LOG("触发 [" + name + (isPrivate ? " 私聊" : " 公屏") + "]: " + parsed.text());
        startAiCall(prefixed, name, isPrivate);
    }

    /** 「玩家名|消息内容」短时间内只处理一次：本地发送和服务器回显只算一条 */
    private static boolean isDuplicateContent(String name, String text, long now) {
        String fingerprint = name + "|" + text;
        Long lastContent = lastTriggerContent.get(fingerprint);
        if (lastContent != null && now - lastContent < CONTENT_DEDUP_MS) return true;
        lastTriggerContent.put(fingerprint, now);
        if (lastTriggerContent.size() > 100) {
            lastTriggerContent.entrySet().removeIf(entry -> now - entry.getValue() >= CONTENT_DEDUP_MS);
        }
        return false;
    }

    // ==================== 触发 ====================

    /**
     * 启动一次 AI 对话（玩家触发和定时任务共用）。
     *
     * @param chatterName 触发者名字（定时任务传 null，不记玩家用量）
     * @param isPrivate   是否私聊触发（决定回复走私聊命令还是公屏）
     */
    public static boolean startAiCall(String userMessage, String chatterName, boolean isPrivate) {
        return startAiCall(List.of(userMessage), chatterName, isPrivate, chatterName);
    }

    /**
     * 启动一次 AI 对话（自动聊天聚合多条 user 消息时用）。
     *
     * @param userMessages 依次加入上下文的多条 user 消息
     * @param replyTarget AI 回复的私聊目标（公屏回复传 null）
     * @param isPrivate   是否私聊回复
     * @param usageName   记入每日 token 用量的玩家名（自动聊天传 null，不占玩家额度）
     */
    public static boolean startAiCall(List<String> userMessages, String replyTarget, boolean isPrivate, String usageName) {
        if (userMessages == null || userMessages.isEmpty()) return false;
        if (isBusy()) return false;
        apiBusy = true;
        currentChatter = usageName;

        Thread thread = new Thread(() -> {
            try {
                AiDialogue.callAI(userMessages, response -> reply(replyTarget, isPrivate, response));
            } catch (Exception e) {
                AI.ERROR("AI 出错了: " + e.getMessage());
            } finally {
                apiBusy = false;
                currentChatter = null;
                currentAiThread = null;
            }
        }, "openai-ai");
        thread.setDaemon(true);
        currentAiThread = thread;
        thread.start();
        return true;
    }

    /** 中断当前 AI 处理（模块关闭时调用） */
    public static void stop() {
        Thread thread = currentAiThread;
        if (thread != null && thread.isAlive()) thread.interrupt();

        synchronized (autoChatLock) {
            resetAutoChatBufferLocked();
            if (autoChatSessionOpenMarked || autoChatPendingOpenMarker) {
                autoChatPendingCloseMarker = true;
            }
        }
        processAutoChatMarkers();

        synchronized (sendLock) {
            sendQueue.clear();
            Thread sender = senderThread;
            senderThread = null;
            if (sender != null) sender.interrupt();
        }
    }

    /** AI 回复的每一行都排进发送队列：公屏触发走公屏，私聊触发走私聊命令 */
    private static void reply(String target, boolean isPrivate, String response) {
        if (response == null || response.isEmpty()) return;
        for (String rawLine : response.split("\n", -1)) {
            String line = AI.formatMarkdown(rawLine.trim());
            if (line.isEmpty()) continue;

            if (isPrivate && target != null) {
                rememberSent(line);   // 私聊回显里只剩正文，正文也记一份，免得 AI 认成新消息
                enqueue(buildPrivateCommand(target, line));
            } else {
                enqueue(line);
            }
        }
    }

    /**
     * 拼私聊命令：命令里 {name} 是玩家名、{text} 是消息内容（默认 {@code /tell {name} {text}}）。
     * 老配置只写了 {name}（如 {@code /tell {name}}）时，消息自动接在命令后面，保证老配置还能用。
     */
    private static String buildPrivateCommand(String target, String line) {
        String command = AiConfig.getPrivateCommand();
        if (command.contains("{text}")) {
            return command.replace("{name}", target).replace("{text}", line);
        }
        return command.replace("{name}", target) + " " + line;
    }

    /** 往公屏发一条消息（send_message 工具用）：排队，由发送线程按间隔发出去 */
    public static void sendToPublicChat(String message) {
        enqueue(message);
    }

    // ==================== 发送队列 ====================

    /**
     * 把一条要发到服务器的消息排进队列。
     *
     * <p>只有这个队列空了（并且 AI 也没在调接口）才算"不忙"，所以上一条回复还没发完时，
     * 其他玩家的触发直接不回应。
     */
    public static void enqueue(String message) {
        if (message == null || message.isEmpty()) return;
        synchronized (sendLock) {
            sendQueue.addLast(message);
            if (senderThread == null || !senderThread.isAlive()) {
                Thread thread = new Thread(AiChatHandler::sendLoop, "openai-sender");
                thread.setDaemon(true);
                senderThread = thread;
                thread.start();
            }
            sendLock.notifyAll();
        }
    }

    /** 发送线程：队列空了就等着，有消息就发一条、再按间隔歇一下 */
    private static void sendLoop() {
        while (true) {
            String message;
            synchronized (sendLock) {
                while (sendQueue.isEmpty()) {
                    try {
                        sendLock.wait();
                    } catch (InterruptedException e) {
                        senderThread = null;
                        return;
                    }
                }
                message = sendQueue.pollFirst();
                sendingNow = true;
            }

            try {
                if (!isModuleActive()) {
                    synchronized (sendLock) {
                        sendQueue.clear();
                    }
                    continue;
                }
                send(message);
            } finally {
                sendingNow = false;
            }

            sleep(nextMessageDelayMs());
        }
    }

    /** 下一条消息的间隔：设置里的值；开了「随机消息间隔」就在它上下随机浮动 1-8 tick */
    private static long nextMessageDelayMs() {
        long base = AiConfig.getMessageDelayMs();
        if (!AiConfig.isRandomMessageDelay()) return base;

        int ticks = JITTER_MIN_TICKS + RANDOM.nextInt(JITTER_MAX_TICKS - JITTER_MIN_TICKS + 1);
        long jitter = ticks * TICK_MS;
        long delay = RANDOM.nextBoolean() ? base + jitter : base - jitter;
        return Math.max(MIN_MESSAGE_DELAY_MS, delay);
    }

    private static void send(String message) {
        if (mc == null || mc.player == null) return;
        rememberSent(message);
        mc.execute(() -> ChatUtils.sendPlayerMsg(message));
    }

    private static boolean isModuleActive() {
        try {
            return fish22.modernsupport.modules.OpenAI.get() != null
                && fish22.modernsupport.modules.OpenAI.get().isActive()
                && !Thread.currentThread().isInterrupted();
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 解析 ====================

    /** 一行聊天匹配的结果 */
    private record ParseResult(ParsedChat chat, boolean isPrivate) {}

    /**
     * 依次用公屏、私聊格式匹配一行聊天。
     *
     * <p>没有"宽容匹配"：格式怎么写就怎么匹配（只有你填的格式首尾空白会被去掉，
     * 因为从聊天栏复制时很容易多带一个空格）。
     * 先公屏后私聊，且行首带装饰（头像字符/称号）时会去掉装饰再试一次。
     */
    private static ParseResult parseLine(String text) {
        ParseResult result = parseBoth(text);
        if (result != null) return result;

        // 行首带装饰（头像字符 / 称号）时，去掉装饰再试一次
        String stripped = stripLeadingDecoration(text);
        if (!stripped.equals(text)) return parseBoth(stripped);
        return null;
    }

    private static ParseResult parseBoth(String text) {
        ParsedChat chat = parse(AiConfig.getPublicChatFormat(), text);
        if (chat != null) return new ParseResult(chat, false);

        chat = parse(AiConfig.getPrivateChatFormat(), text);
        if (chat != null) return new ParseResult(chat, true);
        return null;
    }

    /**
     * 把模板转成正则：{name} → 玩家名，{text} → 消息内容。
     * 整条消息必须从开头就匹配（^），所以别人伪造不出这个前缀。
     */
    private static ParsedChat parse(String format, String text) {
        String template = cleanFormat(format);
        if (!template.contains("{name}") || !template.contains("{text}")) return null;
        Pattern pattern = patternCache.computeIfAbsent(template, AiChatHandler::buildPattern);
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) return null;
        return new ParsedChat(cleanName(matcher.group(1)), matcher.group(2).trim());
    }

    /**
     * 你填的格式模板：只去掉首尾空白（从聊天栏复制时最容易多带一个空格），
     * 里面的字符一个都不动，消息更是原样参与匹配。
     */
    private static String cleanFormat(String format) {
        return format == null ? "" : format.trim();
    }

    // ==================== 名字/行首装饰 ====================

    /**
     * 去掉玩家名前面的装饰，一共两类，都可控：
     * <ul>
     *   <li>「名字前缀忽略」里你自己填的前缀（如 {@code ||}、{@code ★}），按整段相等剥，可叠多层；</li>
     *   <li>开了「忽略名字前的[]」时，再剥掉头像模组塞的私有区/控制字符和 {@code [xxx]} 内容
     *       （如 {@code [unknown player head]}、{@code [VIP]}）。</li>
     * </ul>
     * 只作用于解析出来的名字，消息内容一个字都不动。
     */
    private static String cleanName(String name) {
        String result = name == null ? "" : name.trim();
        for (int i = 0; i < 5; i++) {
            boolean changed = false;

            for (String prefix : AiConfig.getNamePrefixIgnores()) {
                while (result.startsWith(prefix)) {
                    result = result.substring(prefix.length()).trim();
                    changed = true;
                }
            }

            if (AiConfig.isIgnoreNamePrefix()) {
                while (!result.isEmpty() && isDecorationChar(result.charAt(0))) {
                    result = result.substring(1).trim();
                    changed = true;
                }
                if (result.startsWith("[")) {
                    int end = result.indexOf(']');
                    if (end > 0) {
                        result = result.substring(end + 1).trim();
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        return result;
    }

    /** 去掉整行最前面的装饰（自定义前缀 / 头像字符 / [xxx]），用于格式没匹配上时的兜底 */
    private static String stripLeadingDecoration(String text) {
        String result = text;
        for (int i = 0; i < 5; i++) {
            boolean changed = false;

            for (String prefix : AiConfig.getNamePrefixIgnores()) {
                while (result.startsWith(prefix)) {
                    result = result.substring(prefix.length());
                    changed = true;
                }
            }

            if (AiConfig.isIgnoreNamePrefix()) {
                while (!result.isEmpty() && isDecorationChar(result.charAt(0))) {
                    result = result.substring(1);
                    changed = true;
                }
                if (result.startsWith("[")) {
                    int end = result.indexOf(']');
                    if (end > 0) {
                        result = result.substring(end + 1);
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        return result;
    }

    /** 头像模组会用私有区字符，服务器可能塞控制字符 */
    private static boolean isDecorationChar(char c) {
        return c < 0x20 || (c >= 0xE000 && c <= 0xF8FF);
    }

    /** 把不可见字符转成 U+XXXX 形式，方便在聊天栏里看清服务器到底塞了什么 */
    private static String escapeInvisible(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (isDecorationChar(c) || c == 0x7F) sb.append(String.format("\\u%04X", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 把一整行的字符列成 U+XXXX 形式。这些字符全是 ASCII，
     * 所以就算日志用 GBK 编码保存（游戏自带日志就是），也不会变成乱码。
     */
    private static String describeCodePoints(String text, int max) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (count++ >= max) {
                sb.append(" ...");
                break;
            }
            sb.append(String.format("U+%04X ", cp));
        }
        return sb.toString().trim();
    }

    /**
     * 把模板转成正则：{name} → 玩家名，{text} → 消息内容。
     * 整条消息必须从开头就匹配（^），所以别人伪造不出这个前缀。
     * 格式里的每个字符（包括空格）都按原样匹配，不做任何替换或放宽。
     */
    private static Pattern buildPattern(String format) {
        StringBuilder regex = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            if (format.startsWith("{name}", i)) {
                appendLiteral(regex, literal);
                regex.append("(.+?)");
                i += 6;
            } else if (format.startsWith("{text}", i)) {
                appendLiteral(regex, literal);
                regex.append("(.*)");
                i += 6;
            } else {
                literal.append(format.charAt(i));
                i++;
            }
        }
        appendLiteral(regex, literal);
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * 把一段字面量整段引用进正则（引用完就清空，等下一段字面量）。
     *
     * <p>必须整段引用，不能一个 char 一个 char 引用：📨 这类 emoji 在 Java 字符串里是两个 char
     * （代理对），拆开引用后正则里就变成两个孤零零的半截字符，消息里那个 emoji 永远匹配不上。
     */
    private static void appendLiteral(StringBuilder regex, StringBuilder literal) {
        if (literal.length() == 0) return;
        regex.append(Pattern.quote(literal.toString()));
        literal.setLength(0);
    }

    private static boolean containsTrigger(String content) {
        String lower = content.toLowerCase();
        for (String trigger : AiConfig.getTriggers()) {
            if (!trigger.isEmpty() && lower.contains(trigger.toLowerCase())) return true;
        }
        return false;
    }

    private static String selfName() {
        try {
            return mc.getUser().getName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 这条消息是不是自己发的。服务器可能给名字加前缀（如 {@code [VIP] 22_Fish}），
     * 所以除了完全相等，也接受"以前缀修饰过的自己"。
     */
    private static boolean isSelfName(String name) {
        String self = selfName();
        if (self == null || self.isEmpty() || name == null || name.isEmpty()) return false;
        if (name.equalsIgnoreCase(self)) return true;
        return name.length() > self.length() && name.toLowerCase().endsWith(self.toLowerCase());
    }

    // ==================== 自己发出去的消息 ====================

    private static void rememberSent(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (sentLines) {
            long now = System.currentTimeMillis();
            sentLines.addLast(new SentLine(text, now));
            while (!sentLines.isEmpty() && now - sentLines.peekFirst().time() > SENT_LINE_TTL_MS) sentLines.removeFirst();
        }
    }

    /** 这条聊天内容是不是本模块刚发出去的那条（服务器回显） */
    private static boolean isRecentlySent(String text) {
        if (text == null || text.isEmpty()) return false;
        String plain = AI.stripColor(text);
        long now = System.currentTimeMillis();
        synchronized (sentLines) {
            for (SentLine line : sentLines) {
                if (now - line.time() > SENT_LINE_TTL_MS) continue;
                if (line.text().equals(text)) return true;
                // 自己回复里可能带 § 字体代码，比较时统一去掉
                if (AI.stripColor(line.text()).equals(plain)) return true;
            }
        }
        return false;
    }
}
