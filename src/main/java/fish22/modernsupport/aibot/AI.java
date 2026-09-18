/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来。
 */

package fish22.modernsupport.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * openAI 模块的公共工具：数据目录、本地聊天提示、Markdown 格式化、API 调用时间记录、主线程执行。
 *
 * <p>「本地聊天提示」只显示在自己聊天栏（工具调用提示 / token 消耗 / API 报错都走这里），
 * 不会发到服务器；只有 AI 真正要发出去的消息才通过 {@link ChatUtils#sendPlayerMsg} 发送。
 */
public final class AI {
    /** 数据目录名：Meteor 配置目录下的 openai/ */
    public static final String FOLDER_NAME = "openai";
    /** 本地提示的统一标记：带上它之后，自己的提示就不会被当成聊天消息再处理一遍 */
    public static final String LOCAL_TAG = "[openAI]";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path dataDir;
    private static Path contextDir;

    /**
     * 还没来得及发到聊天栏的本地提示。
     *
     * <p>提示不能「就地」往聊天栏里塞：聊天消息事件的处理器里插一条消息，Meteor 的
     * ReceiveMessageEvent 是同一个对象，重入会让正在显示的那条聊天（比如别人的私聊）
     * 被提示顶掉，提示本身也会变成两条。所以统一先攒着，等这个 tick 过去再发。
     */
    private static final Deque<Component> pendingChatMessages = new ArrayDeque<>();

    private AI() {}

    /** 初始化数据目录（模块开启时调用） */
    public static void init(Path dir) {
        dataDir = dir;
        contextDir = dir.resolve("上下文");
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(contextDir);
        } catch (IOException e) {
            MeteorClient.LOG.error("[openAI] 创建数据目录失败", e);
        }
    }

    /** 数据目录（同时也是 AI 文件工具的根，权限.json 放在这里） */
    public static Path getConfigDir() { return dataDir; }

    /** 对话上下文目录 */
    public static Path getContextDir() { return contextDir; }

    // ==================== 本地聊天提示 ====================

    /** 本地聊天栏提示（灰色，不会发到服务器） */
    public static void LOG(String message) {
        local(ChatFormatting.GRAY, message);
    }

    /** 本地聊天栏错误提示（红色，不会发到服务器） */
    public static void ERROR(String message) {
        local(ChatFormatting.RED, message);
    }

    /** 只写进日志文件、不进聊天栏的提示（忽略的触发之类，免得刷屏） */
    public static void QUIET(String message) {
        if (message == null || message.isEmpty()) return;
        MeteorClient.LOG.info(stripColor(message));
    }

    private static void local(ChatFormatting color, String message) {
        if (message == null || message.isEmpty()) return;
        String plain = stripColor(message);
        MeteorClient.LOG.info(plain);
        if (mc == null || mc.level == null) return;

        MutableComponent component = Component.empty();
        component.append(Component.literal(LOCAL_TAG + " ").withStyle(ChatFormatting.AQUA));
        component.append(Component.literal(plain).withStyle(color));
        synchronized (pendingChatMessages) {
            pendingChatMessages.addLast(component);
        }
    }

    /** 把攒下的本地提示发到聊天栏（每个 tick 末调一次，见 ModernSupport#onTickPost） */
    public static void flushChatMessages() {
        List<Component> batch;
        synchronized (pendingChatMessages) {
            if (pendingChatMessages.isEmpty()) return;
            batch = new ArrayList<>(pendingChatMessages);
            pendingChatMessages.clear();
        }
        for (Component component : batch) ChatUtils.sendMsg(component);
    }

    /**
     * 这条聊天栏消息是不是本模块自己打的本地提示。
     *
     * <p>Meteor 往本地聊天栏加的消息同样会走聊天事件（{@code ReceiveMessageEvent}），
     * 如果不排掉，自己的提示会被再解析一遍 —— 调试打印会自己喂自己，直接卡死客户端。
     */
    public static boolean isLocalMessage(String text) {
        return text != null && text.contains(LOCAL_TAG);
    }

    /** 去掉 Minecraft 颜色/格式代码（§x） */
    public static String stripColor(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    // ==================== Markdown ====================

    /** 把模型回复里的 Markdown 标记换成 Minecraft 格式代码 */
    public static String formatMarkdown(String text) {
        if (text == null) return "";
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "§l$1§r");
        text = text.replaceAll("\\*(.+?)\\*", "§o$1§r");
        text = text.replaceAll("`([^`]+)`", "§n$1§r");
        return text;
    }

    // ==================== 主线程执行 ====================

    /**
     * 在客户端主线程执行动作并等结果（AI 线程里调工具时用，保证线程安全）
     */
    public static <T> T runOnClientThread(Supplier<T> action) {
        if (mc == null) return action.get();
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== API 调用时间（缓存.json） ====================

    /** 记录本次 API 调用时间 */
    public static void recordApiCallTime() {
        try {
            Path cacheFile = dataDir.resolve("缓存.json");
            Files.createDirectories(cacheFile.getParent());
            Map<String, Object> cache = new LinkedHashMap<>();
            if (Files.exists(cacheFile)) {
                String existing = Files.readString(cacheFile).trim();
                if (!existing.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> old = GSON.fromJson(existing, Map.class);
                    if (old != null) cache = old;
                }
            }
            cache.put("lastApiCallTime", System.currentTimeMillis());
            Files.writeString(cacheFile, GSON.toJson(cache));
        } catch (IOException e) {
            ERROR("写入缓存失败: " + e.getMessage());
        }
    }

    /** 上次 API 调用时间（毫秒），没记录过返回 0 */
    public static long getLastApiCallTime() {
        try {
            Path cacheFile = dataDir.resolve("缓存.json");
            if (Files.exists(cacheFile)) {
                String json = Files.readString(cacheFile).trim();
                if (!json.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cache = GSON.fromJson(json, Map.class);
                    if (cache != null && cache.get("lastApiCallTime") instanceof Number number) {
                        return number.longValue();
                    }
                }
            }
        } catch (IOException ignored) {
            // 读不到就当没记录
        }
        return 0;
    }
}
