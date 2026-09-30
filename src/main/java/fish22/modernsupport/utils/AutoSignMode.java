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

/**
 * 自动签名的模式（{@link fish22.modernsupport.mixin.MixinAutoSign} 给 Meteor 官方「自动签名」加的设置）。
 *
 * <p>设置界面按 {@link #toString()} 显示，配置里也按 {@link #toString()} 存取，
 * 所以直接把中文写在 toString 里，界面就是「复制 / 预设」。
 */
public enum AutoSignMode {
    /** 复制：抄第一块手写告示牌的内容 */
    Copy,
    /** 预设：每块都写自定义的四行模板 */
    Preset;

    @Override
    public String toString() {
        return this == Copy ? "复制" : "预设";
    }
}
