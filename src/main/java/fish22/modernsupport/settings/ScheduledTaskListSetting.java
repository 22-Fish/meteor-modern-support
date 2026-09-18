/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来。
 */

package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 「openAI」定时任务设置：每个条目 = 任务名称 + 提示词 + 触发时间（HH:mm，每天一次）。
 * GUI 由自定义 factory 渲染（顶部「新增」按钮 + 每行 [名称][时间][删除] + 提示词输入框）。
 *
 * <p>提示词里可以用 [[[time]]] 指代触发时间、[[[name]]] 指代任务名。
 */
public class ScheduledTaskListSetting extends Setting<List<ScheduledTaskListSetting.Entry>> {

    /** 一个定时任务 */
    public static class Entry {
        public String name;
        public String prompt;
        public String time;

        public Entry(String name, String prompt, String time) {
            this.name = name;
            this.prompt = prompt;
            this.time = time;
        }

        @Override
        public String toString() {
            return name + "@" + time;
        }
    }

    public ScheduledTaskListSetting(String name, String description, IVisible visible) {
        super(name, description, new ArrayList<>(), null, null, visible);
    }

    @Override
    protected void resetImpl() {
        if (value == null) value = new ArrayList<>();
        else value.clear();
    }

    /** 线程安全的快照（调度线程与 GUI 可能同时访问） */
    public List<Entry> snapshot() {
        return new ArrayList<>(value);
    }

    public Entry find(String name) {
        if (name == null) return null;
        for (Entry entry : value) {
            if (entry.name != null && entry.name.equals(name)) return entry;
        }
        return null;
    }

    public boolean remove(String name) {
        Entry entry = find(name);
        return entry != null && value.remove(entry);
    }

    /** 校验 HH:mm */
    public static boolean isValidTime(String time) {
        if (time == null) return false;
        String[] parts = time.trim().split(":");
        if (parts.length != 2) return false;
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 规范化成两位的 HH:mm */
    public static String normalizeTime(String time) {
        String[] parts = time.trim().split(":");
        return String.format("%02d:%02d", Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
    }

    @Override
    protected List<Entry> parseImpl(String str) {
        return null;
    }

    @Override
    protected boolean isValueValid(List<Entry> value) {
        return true;
    }

    @Override
    protected CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : value) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("name", entry.name == null ? "" : entry.name);
            entryTag.putString("prompt", entry.prompt == null ? "" : entry.prompt);
            entryTag.putString("time", entry.time == null ? "00:00" : entry.time);
            list.add(entryTag);
        }
        tag.put("value", list);
        return tag;
    }

    @Override
    protected List<Entry> load(CompoundTag tag) {
        value.clear();
        for (Tag element : tag.getListOrEmpty("value")) {
            CompoundTag entryTag = (CompoundTag) element;
            value.add(new Entry(
                entryTag.getStringOr("name", ""),
                entryTag.getStringOr("prompt", ""),
                entryTag.getStringOr("time", "00:00")
            ));
        }
        return value;
    }
}
