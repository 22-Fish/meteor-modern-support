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

package fish22.modernsupport.modules;

import fish22.modernsupport.utils.SilentSwitch;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * 静默切换配置 — 杂项模块
 *
 * <p>静默切换（{@link SilentSwitch}）的显示相关配置。本模块<b>只提供设置项</b>，
 * 开关状态不参与任何逻辑（里面没有 tick、没有事件），功能只由设置项决定：
 *
 * <ul>
 *   <li><b>静默切换固定手部物品渲染</b>（默认关闭）：开启后，静默切换期间的画面
 *       固定显示本地选中槽位那件物品，不跟着静默切换换（实现见
 *       {@link fish22.modernsupport.mixin.MixinItemInHandRendererSilentSwitch}）。</li>
 *   <li><b>静默切换不渲染工具冷却</b>（默认关闭）：开启后，静默切换不重置客户端攻击冷却，
 *       屏幕上不会出现攻击蓄力条（实现见
 *       {@link fish22.modernsupport.mixin.MixinPlayerSilentSwitch}）。</li>
 * </ul>
 */
public class SilentSwitchConfig extends Module {

    /** 模块实例，供 {@link SilentSwitch} 读设置（每次用时读，配置加载顺序无关） */
    private static SilentSwitchConfig instance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> fixedHandItemRender = sgGeneral.add(new BoolSetting.Builder()
        .name("静默切换固定手部物品渲染")
        .description("开启后，静默切换期间手部渲染的物品固定成本地选中槽位那件，不跟着静默切换换")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hideToolCooldown = sgGeneral.add(new BoolSetting.Builder()
        .name("静默切换不渲染工具冷却")
        .description("开启后，静默切换不重置客户端攻击冷却，屏幕上不会出现攻击蓄力条")
        .defaultValue(false)
        .build()
    );

    public SilentSwitchConfig() {
        super(Categories.Misc, "静默切换配置",
            "静默切换的显示配置");
        instance = this;
    }

    /** 「静默切换固定手部物品渲染」是否开启（默认关闭；模块还没创建时按默认值算） */
    public static boolean isFixedHandItemRender() {
        return instance != null && instance.fixedHandItemRender.get();
    }

    /** 「静默切换不渲染工具冷却」是否开启（默认关闭；模块还没创建时按默认值算） */
    public static boolean isHideToolCooldown() {
        return instance != null && instance.hideToolCooldown.get();
    }
}
