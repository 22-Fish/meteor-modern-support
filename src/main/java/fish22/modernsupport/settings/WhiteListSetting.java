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
 * 「openAI」白名单用户设置：每个条目 = 玩家名 + 每日 token 用量限制。
 * GUI 由自定义 factory 渲染（顶部「新增用户」按钮 + 每行 [名称][用量限制][删除]）。
 *
 * <p>用量限制为 -1 表示不限量。
 */
public class WhiteListSetting extends Setting<List<WhiteListSetting.Entry>> {

    /** 白名单里的一个用户 */
    public static class Entry {
        public String name;
        public long limit;

        public Entry(String name, long limit) {
            this.name = name;
            this.limit = limit;
        }

        @Override
        public String toString() {
            return name + "=" + limit;
        }
    }

    public WhiteListSetting(String name, String description, IVisible visible) {
        super(name, description, new ArrayList<>(), null, null, visible);
    }

    /** value 与 defaultValue 分开，直接改列表才能被正确保存 */
    @Override
    protected void resetImpl() {
        if (value == null) value = new ArrayList<>();
        else value.clear();
    }

    /**
     * 按玩家名查找（不区分大小写），找不到返回 null。
     *
     * <p>服务器可能给名字加前缀（如 {@code [VIP] 22_Fish}），所以完全匹配失败时
     * 再用"最后一个词"匹配一次，这样白名单里填 22_Fish 也能认出来。
     * 名字前面那种自定义前缀（如 {@code ||}）由「名字前缀忽略」设置负责剥掉。
     */
    public Entry find(String playerName) {
        if (playerName == null) return null;
        String name = playerName.trim();
        for (Entry entry : value) {
            if (entry.name != null && entry.name.equalsIgnoreCase(name)) return entry;
        }

        int space = name.lastIndexOf(' ');
        if (space >= 0 && space + 1 < name.length()) {
            String lastToken = name.substring(space + 1).trim();
            for (Entry entry : value) {
                if (entry.name != null && entry.name.equalsIgnoreCase(lastToken)) return entry;
            }
        }
        return null;
    }

    public boolean contains(String playerName) {
        return find(playerName) != null;
    }

    /** 该玩家的用量限制，-1 = 不限量 */
    public long limitOf(String playerName) {
        Entry entry = find(playerName);
        return entry == null ? 0 : entry.limit;
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
            entryTag.putLong("limit", entry.limit);
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
            value.add(new Entry(entryTag.getStringOr("name", ""), entryTag.getLongOr("limit", -1)));
        }
        return value;
    }
}
