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

package fish22.modernsupport.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问原版客户端「现在在挖哪个方块」的状态（{@code MultiPlayerGameMode.isDestroying} / {@code destroyBlockPos}）——
 * 发包挖掘自己不发原版那套挖掘包，拿这两个字段就能知道原版那条路（手动点、别的模块）有没有在挖别的地方，
 * 免得同一个 tick 里两套挖掘包撞在一起
 *
 * <p>Meteor 自己的 {@code MultiPlayerGameModeAccessor} 只开放了进度和位置，没有「在不在挖」这个标记
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeMiningAccessor {
    /** 原版客户端现在是不是在挖方块 */
    @Accessor("isDestroying")
    boolean meteorsupport$isDestroying();

    /** 原版客户端现在挖的是哪个方块（没在挖的时候是上一次的位置） */
    @Accessor("destroyBlockPos")
    BlockPos meteorsupport$getDestroyBlockPos();
}
