/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 22_Fish
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.CompoundTag;

/**
 * 顶部栏 Render 板块的设置（保存在 meteor-client/render.nbt）
 */
public class RenderSettings extends System<RenderSettings> {
    public final Settings settings = new Settings();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> loadingAnimation = sgGeneral.add(new BoolSetting.Builder()
        .name("启用meteor加载动画")
        .description("把原版加载画面的 Mojang logo 换成 meteor logo 的描边点亮动画")
        .defaultValue(true)
        .build()
    );

    public RenderSettings() {
        super("render");
    }

    public static RenderSettings get() {
        return Systems.get(RenderSettings.class);
    }

    /** 加载动画开关（没注册时按开启算） */
    public static boolean loadingAnimationEnabled() {
        RenderSettings renderSettings = get();
        return renderSettings == null || renderSettings.loadingAnimation.get();
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("settings", settings.toTag());
        return tag;
    }

    @Override
    public RenderSettings fromTag(CompoundTag tag) {
        settings.fromTag(tag.getCompoundOrEmpty("settings"));
        return this;
    }
}
