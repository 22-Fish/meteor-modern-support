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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 「一个角度同 tick 放两块」算出来的那一份角度（包围的双重高度用）。
 *
 * <p>两块落在同一条射线上，后一块点的是「前一块马上放好之后的顶面」，所以两下要按顺序发：
 * 先点 {@code firstClicked} 那一面放下第一块，再点它的顶面放下第二块（服务器是按顺序处理的，
 * 轮到第二下时第一块已经在了）。
 *
 * @param yaw          这一下要转到的偏航
 * @param pitch        这一下要转到的俯仰
 * @param firstHit     第一下的命中点（落在被点的支撑方块面上）
 * @param firstClicked 第一下去点的方块（支撑方块）
 * @param firstFace    第一下点的是它的哪一面
 * @param secondHit    第二下的命中点（落在第一块放好之后的顶面上）
 */
public record DoublePlaceAim(float yaw, float pitch, Vec3 firstHit, BlockPos firstClicked, Direction firstFace, Vec3 secondHit) {
}
