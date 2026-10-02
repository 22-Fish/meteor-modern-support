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

import fish22.modernsupport.utils.LegalRotation;
import fish22.modernsupport.utils.RotationRenderMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * 合法转头API配置 — 杂项模块
 *
 * <p>合法转头 API（{@link LegalRotation}）的显示相关配置。本模块<b>只提供设置项</b>，
 * 开关状态不参与任何逻辑（里面没有 tick、没有事件），功能只由设置项决定：
 *
 * <ul>
 *   <li><b>可见旋转方向</b>（默认开启）：合法转头转动方向后，第三视角里玩家模型
 *       （身体与头）的显示朝向也转到真实角度，方便判断服务器看到的真实朝向。
 *       只改渲染显示：相机、鼠标输入、发包全都不动（实现见
 *       {@link fish22.modernsupport.mixin.MixinVisibleRotation}）。</li>
 *   <li><b>旋转朝向渲染模式</b>：<b>Vanilla</b>（默认）= 按原版转头动画来
 *       （头直接跟着真实角度，身体 0.3/tick 追上去，头相对身体不超过原版限制）；
 *       <b>Set</b> = 旧模式，直接把模型设成真实角度，瞬间转过去。
 *       只影响显示动画，真实角度本身两种模式一样。</li>
 *   <li><b>默认优先级</b>（默认 0）：调用合法转头 API 时没写优先级的那些功能（鞘翅飞行等）
 *       用的优先级。同一 tick 里多个模块都要转视角时，优先级高的那一份生效，低的整个忽略
 *       （见 {@link LegalRotation}）。</li>
 * </ul>
 *
 * <p>原来还有「每次调用都设置朝向」设置项，现在隐藏并硬编码为关闭：重复朝向包会被
 * Grim 的 {@code AimDuplicateLook} 每 tick 告警，退回原版逻辑（朝向不一样才带朝向发包）。
 */
public class LegalRotationConfig extends Module {

    /** 模块实例，供 {@link LegalRotation} 读设置（渲染时每次读，配置加载顺序无关） */
    private static LegalRotationConfig instance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> visibleRotation = sgGeneral.add(new BoolSetting.Builder()
        .name("可见旋转方向")
        .description("开启后，合法转头转动方向时，第三视角里玩家模型的朝向也会转到那个方向，方便判断服务器看到的真实朝向。")
        .defaultValue(true)
        .build()
    );

    private final Setting<RotationRenderMode> rotationRenderMode = sgGeneral.add(new EnumSetting.Builder<RotationRenderMode>()
        .name("旋转朝向渲染模式")
        .description("Set 是旧模式：强行把模型设成真实角度。Vanilla 用原版转头动画平滑转过去。")
        .defaultValue(RotationRenderMode.Vanilla)
        .visible(visibleRotation::get)
        .build()
    );

    private final Setting<Integer> defaultPriority = sgGeneral.add(new IntSetting.Builder()
        .name("默认优先级")
        .description("合法转头的优先级")
        .defaultValue(0)
        .sliderRange(-20, 20)
        .build()
    );

    public LegalRotationConfig() {
        super(Categories.Misc, "合法转头API配置",
            "合法转头 API 的显示配置");
        instance = this;
    }

    /** 「可见旋转方向」是否开启（默认开启；模块还没创建时按默认值算） */
    public static boolean isVisibleRotation() {
        return instance == null || instance.visibleRotation.get();
    }

    /** 「旋转朝向渲染模式」（默认 Vanilla；模块还没创建时按默认值算） */
    public static RotationRenderMode getRotationRenderMode() {
        return instance == null ? RotationRenderMode.Vanilla : instance.rotationRenderMode.get();
    }

    /** 「每次调用都设置朝向」：设置项已隐藏，硬编码关闭（重复朝向包会被 Grim 的 AimDuplicateLook 每 tick 告警） */
    public static boolean isAlwaysSetRotation() {
        return false;
    }

    /** 「默认优先级」（默认 0；模块还没创建时按默认值算） */
    public static int getDefaultPriority() {
        return instance == null ? 0 : instance.defaultPriority.get();
    }
}
