/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「珍珠点」设置：用户名 → 交互方块坐标。
 *
 * <p>GUI 由自定义 factory 渲染（顶部「新增珍珠点」按钮 + 每行 [用户名][坐标][删除]），
 * 坐标框里填 {@code x y z}，解析不了就保留原值。
 */
public class PearlPointSetting extends Setting<Map<String, PearlPointSetting.Point>> {

    /** 一个珍珠点：交互方块的坐标（面在交互时自动挑） */
    public static class Point {
        public int x;
        public int y;
        public int z;

        public Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public BlockPos blockPos() {
            return new BlockPos(x, y, z);
        }
    }

    public PearlPointSetting(String name, String description, IVisible visible) {
        super(name, description, new LinkedHashMap<>(), null, null, visible);
    }

    @Override
    protected void resetImpl() {
        value = new LinkedHashMap<>(defaultValue);
    }

    /** 按用户名查找（不区分大小写），找不到返回 null */
    public Point find(String username) {
        if (username == null || username.isEmpty()) return null;
        Point exact = value.get(username);
        if (exact != null) return exact;
        for (Map.Entry<String, Point> entry : value.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(username)) return entry.getValue();
        }

        return null;
    }

    @Override
    protected Map<String, Point> parseImpl(String str) {
        return null;
    }

    @Override
    protected boolean isValueValid(Map<String, Point> value) {
        return true;
    }

    @Override
    protected CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Point> entry : value.entrySet()) {
            Point point = entry.getValue();
            if (point == null) continue;
            CompoundTag pointTag = new CompoundTag();
            pointTag.putString("name", entry.getKey());
            pointTag.putInt("x", point.x);
            pointTag.putInt("y", point.y);
            pointTag.putInt("z", point.z);
            list.add(pointTag);
        }
        tag.put("points", list);
        return tag;
    }

    @Override
    protected Map<String, Point> load(CompoundTag tag) {
        value.clear();
        for (Tag element : tag.getListOrEmpty("points")) {
            CompoundTag pointTag = (CompoundTag) element;
            String name = pointTag.getStringOr("name", "");
            if (name.isEmpty()) continue;
            value.put(name, new Point(
                pointTag.getIntOr("x", 0),
                pointTag.getIntOr("y", 0),
                pointTag.getIntOr("z", 0)
            ));
        }
        return value;
    }
}
