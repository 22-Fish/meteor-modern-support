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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家每日 token 用量：openai/玩家/{玩家名}.json → {"date":"2026-09-17","tokens":1234}
 *
 * <p>每日额度由「白名单用户」里的用量限制决定（-1 = 不限量），跨天自动从头算。
 */
public final class AiUsage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AiUsage() {}

    private static Path dir() {
        return AI.getConfigDir().resolve("玩家");
    }

    /** 某玩家今日已用 token（日期不是今天就当 0） */
    public static long getUsageToday(String playerName) {
        if (!isSafePlayerName(playerName) || AI.getConfigDir() == null) return 0;
        Path file = usageFile(playerName);
        if (!Files.exists(file)) return 0;
        try {
            String json = Files.readString(file).trim();
            if (json.isEmpty()) return 0;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = GSON.fromJson(json, Map.class);
            if (data == null) return 0;
            if (!LocalDate.now(AiConfig.getZoneId()).toString().equals(data.get("date"))) return 0;
            return data.get("tokens") instanceof Number number ? number.longValue() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 累加某玩家今日用量 */
    public static void addUsage(String playerName, long delta) {
        if (delta <= 0 || !isSafePlayerName(playerName) || AI.getConfigDir() == null) return;
        try {
            Files.createDirectories(dir());
            Path file = usageFile(playerName);
            String today = LocalDate.now(AiConfig.getZoneId()).toString();
            long used = getUsageToday(playerName) + delta;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("date", today);
            data.put("tokens", used);
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            AI.ERROR("写入玩家用量失败: " + e.getMessage());
        }
    }

    /** 每日上限（-1 → Long.MAX_VALUE 表示不限量） */
    public static long getMaxTokens(String playerName) {
        long limit = AiConfig.limitOf(playerName);
        return limit < 0 ? Long.MAX_VALUE : limit;
    }

    /** 格式化成 xx万token */
    public static String formatWan(long tokens) {
        if (tokens >= Long.MAX_VALUE / 2) return "不限量";
        double wan = tokens / 10000.0;
        if (wan == Math.floor(wan)) return String.format("%.0f万", wan);
        return String.format("%.1f万", wan);
    }

    /** 玩家名是否安全（防路径穿越） */
    public static boolean isSafePlayerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) return false;
        // 只挡路径穿越；名字里 | 这类字符（系统分配的名字就带，如 ||Huanran）
        // 在 Windows 文件名里不合法，交给 usageFile 转义，不用把人家的名字拒之门外
        if (name.contains("/") || name.contains("\\") || name.contains(":") || name.contains("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    /**
     * 用量文件：openai/玩家/&lt;名字&gt;.json
     *
     * <p>名字里 Windows 文件名不认的字符（{@code | * ? " < >} 等）转成 %XXXX，免得写不出去。
     * 字母/数字/中文/下划线/短横线保持原样，所以老文件还能接着用。
     */
    private static Path usageFile(String playerName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < playerName.length(); i++) {
            char c = playerName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') sb.append(c);
            else sb.append(String.format("%%%04X", (int) c));
        }
        return dir().resolve(sb + ".json");
    }
}
