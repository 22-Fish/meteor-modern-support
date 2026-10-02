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
 * 「可见旋转方向」用什么方式把真实朝向画到玩家模型上。
 *
 * <p>设置界面按 {@link #toString()} 显示，枚举名本身就是显示名。
 */
public enum RotationRenderMode {
    /** 旧模式：直接把模型（身体与头）设成真实角度，和 Meteor Rotations 一样瞬间转过去 */
    Set,
    /** 原版：按原版转头动画来（头直接跟着真实角度，身体 0.3/tick 追上去，头相对身体不超过原版限制） */
    Vanilla
}
