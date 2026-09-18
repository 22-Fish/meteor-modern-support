/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来。
 */

package fish22.modernsupport.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fish22.modernsupport.modules.OpenAI;
import fish22.modernsupport.settings.ScheduledTaskListSetting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时任务：任务列表来自模块设置（「工具调用」分组里的定时任务），到点自动让 AI 说一次话。

 * <p>触发状态写在 openai/定时任务状态.json（同一天不重复触发）；
 * 只有到点前后 1 分钟内才会触发，错过太久的当天直接跳过（不会一开游戏就补一串）。
 */
public final class AiTasks {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    /** 任务名 -> 已触发的日期（yyyy-MM-dd） */
    private static final Map<String, String> firedDates = new ConcurrentHashMap<>();

    private static Thread scheduler;

    private AiTasks() {}

    // ==================== 调度 ====================

    public static void start() {
        loadState();
        stop();
        scheduler = new Thread(AiTasks::loop, "openai-scheduler");
        scheduler.setDaemon(true);
        scheduler.start();
    }

    public static void stop() {
        if (scheduler != null) {
            scheduler.interrupt();
            scheduler = null;
        }
    }

    private static void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                checkOnce();
            } catch (Exception e) {
                AI.ERROR("定时任务检查出错: " + e.getMessage());
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void checkOnce() {
        OpenAI module = OpenAI.get();
        if (module == null || !module.isActive()) return;

        List<ScheduledTaskListSetting.Entry> tasks;
        try {
            tasks = module.scheduledTasks.snapshot();
        } catch (Exception e) {
            return;
        }

        String today = LocalDate.now(AiConfig.getZoneId()).toString();
        String now = LocalTime.now(AiConfig.getZoneId()).format(HHMM);

        for (ScheduledTaskListSetting.Entry task : tasks) {
            if (task.name == null || task.name.isBlank()) continue;
            if (!ScheduledTaskListSetting.isValidTime(task.time)) continue;
            if (today.equals(firedDates.get(task.name))) continue;
            if (now.compareTo(task.time) < 0) continue;      // 还没到点

            if (!isNearTaskTime(now, task.time)) {
                // 已经过点太久（当时没开游戏）：今天不触发，等明天
                firedDates.put(task.name, today);
                saveState();
                continue;
            }
            if (AiChatHandler.isBusy()) continue;            // AI 忙，10 秒后再看

            firedDates.put(task.name, today);
            saveState();
            fire(task, now);
        }
    }

    private static void fire(ScheduledTaskListSetting.Entry task, String timeHHmm) {
        String prompt = String.valueOf(task.prompt)
            .replace("[[[time]]]", timeHHmm)
            .replace("[[[name]]]", task.name);
        AI.LOG("定时任务「" + task.name + "」触发: " + prompt);
        // 触发者传 null：不记某个玩家的用量；回复走公屏
        AiChatHandler.startAiCall(prompt, null, false);
    }

    /** 与任务时间相差不超过 1 分钟算准点 */
    private static boolean isNearTaskTime(String nowHHmm, String taskTime) {
        try {
            String[] now = nowHHmm.split(":");
            String[] task = taskTime.split(":");
            int nowMinutes = Integer.parseInt(now[0]) * 60 + Integer.parseInt(now[1]);
            int taskMinutes = Integer.parseInt(task[0]) * 60 + Integer.parseInt(task[1]);
            return Math.abs(nowMinutes - taskMinutes) <= 1;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 触发状态持久化 ====================

    private static Path stateFile() {
        return AI.getConfigDir().resolve("定时任务状态.json");
    }

    @SuppressWarnings("unchecked")
    private static void loadState() {
        firedDates.clear();
        if (AI.getConfigDir() == null) return;
        try {
            Path file = stateFile();
            if (!Files.exists(file)) return;
            String json = Files.readString(file).trim();
            if (json.isEmpty()) return;

            Map<String, Object> data = GSON.fromJson(json, Map.class);
            if (data == null) return;
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (entry.getValue() instanceof String date) {
                    firedDates.put(entry.getKey(), date);
                } else if (entry.getValue() instanceof Map<?, ?> record && record.get("date") != null) {
                    firedDates.put(entry.getKey(), String.valueOf(record.get("date")));
                }
            }
        } catch (IOException e) {
            AI.ERROR("读取定时任务状态失败: " + e.getMessage());
        }
    }

    private static void saveState() {
        if (AI.getConfigDir() == null) return;
        try {
            Files.createDirectories(AI.getConfigDir());
            Files.writeString(stateFile(), GSON.toJson(new LinkedHashMap<>(firedDates)));
        } catch (IOException e) {
            AI.ERROR("保存定时任务状态失败: " + e.getMessage());
        }
    }

    // ==================== AI 工具接口 ====================

    /** 新增定时任务（AI 工具 create_scheduled_task） */
    public static String createTask(Map<String, Object> args) {
        String name = args.get("name") != null ? args.get("name").toString().trim() : "";
        String prompt = args.get("prompt") != null ? args.get("prompt").toString().trim() : "";
        String time = args.get("time") != null ? args.get("time").toString().trim() : "";
        if (name.isEmpty()) return "create_scheduled_task: 名称不能为空";
        if (prompt.isEmpty()) return "create_scheduled_task: 提示词不能为空";
        if (!ScheduledTaskListSetting.isValidTime(time)) return "create_scheduled_task: 时间格式无效（应为 HH:mm，如 08:00）";

        return AI.runOnClientThread(() -> {
            OpenAI module = OpenAI.get();
            if (module == null) return "模块未开启";
            if (module.scheduledTasks.find(name) != null) return "定时任务「" + name + "」已存在";

            String normalized = ScheduledTaskListSetting.normalizeTime(time);
            module.scheduledTasks.get().add(new ScheduledTaskListSetting.Entry(name, prompt, normalized));
            AI.LOG("AI 新增定时任务: " + name + " | 每天 " + normalized);
            return "定时任务「" + name + "」已创建，每天 " + normalized + " 触发";
        });
    }

    /** 删除定时任务（AI 工具 delete_scheduled_task） */
    public static String deleteTask(Map<String, Object> args) {
        String name = args.get("name") != null ? args.get("name").toString().trim() : "";
        if (name.isEmpty()) return "delete_scheduled_task: 名称不能为空";

        return AI.runOnClientThread(() -> {
            OpenAI module = OpenAI.get();
            if (module == null) return "模块未开启";
            if (!module.scheduledTasks.remove(name)) return "定时任务「" + name + "」不存在";

            firedDates.remove(name);
            saveState();
            AI.LOG("已删除定时任务: " + name);
            return "定时任务「" + name + "」已删除";
        });
    }
}
