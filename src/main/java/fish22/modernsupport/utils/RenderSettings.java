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
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.nbt.CompoundTag;

/**
 * 顶部栏 Render 板块的设置（保存在 meteor-client/render.nbt）
 */
public class RenderSettings extends System<RenderSettings> {
    public final Settings settings = new Settings();
    private final SettingGroup sgModify = settings.createGroup("修改");
    private final SettingGroup sgOptimize = settings.createGroup("优化");

    // ===== 修改 =====

    public final Setting<Boolean> loadingAnimation = sgModify.add(new BoolSetting.Builder()
        .name("启用meteor资源包加载动画")
        .description("把原版加载画面的 Mojang logo 换成 meteor logo 的描边点亮动画")
        .defaultValue(true)
        .build()
    );

    public final Setting<SettingColor> loadingAnimationBackground = sgModify.add(new ColorSetting.Builder()
        .name("资源包加载动画背景颜色")
        .description("自定义加载画面的背景色，关掉上面的加载动画后无效")
        .defaultValue(new SettingColor(239, 50, 61, 255))
        .build()
    );

    // ===== 优化 =====

    public final Setting<Boolean> skipUnusedDepth = sgOptimize.add(new BoolSetting.Builder()
        .name("跳过无用深度读写")
        .description("绘制不需要深度的东西时不再读写整屏深度，降低开界面/多透视时的掉帧")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> singleGuiPass = sgOptimize.add(new BoolSetting.Builder()
        .name("界面只开一个渲染通道")
        .description("滚动列表不再开裁剪区，整帧界面合成一个渲染通道，减少开界面掉帧")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> cullInvisibleWidgets = sgOptimize.add(new BoolSetting.Builder()
        .name("不可见控件剔除")
        .description("滚动区里看不见的控件直接不画（含边缘只露一半的）；关掉后它们会画到列表外面去")
        .defaultValue(false)
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

    /** 加载动画背景色，只有 RGB（透明度用原版算好的淡入淡出值） */
    public static int loadingAnimationBackgroundRgb() {
        RenderSettings renderSettings = get();
        if (renderSettings == null) return 0xEF323D;

        SettingColor color = renderSettings.loadingAnimationBackground.get();
        return (color.r << 16) | (color.g << 8) | color.b;
    }

    /** 跳过无用深度读写开关（没注册时按关闭算） */
    public static boolean skipUnusedDepthEnabled() {
        RenderSettings renderSettings = get();
        return renderSettings != null && renderSettings.skipUnusedDepth.get();
    }

    /** 界面合并成单通道开关（没注册时按关闭算） */
    public static boolean singleGuiPassEnabled() {
        RenderSettings renderSettings = get();
        return renderSettings != null && renderSettings.singleGuiPass.get();
    }

    /** 不可见控件剔除开关（没注册时按关闭算） */
    public static boolean cullInvisibleWidgetsEnabled() {
        RenderSettings renderSettings = get();
        return renderSettings != null && renderSettings.cullInvisibleWidgets.get();
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
