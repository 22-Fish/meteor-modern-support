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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问原版「破坏方块后的延迟」（{@code MultiPlayerGameMode.destroyDelay}）——
 * 「瞬间破坏保留延迟」用的就是原版这个延迟：秒破完一个方块后把它设成 5，
 * 延迟没走完之前不再秒破（这段时间原版自己的挖掘进度也是冻着的）。
 *
 * <p>Meteor 自己的 {@code MultiPlayerGameModeAccessor} 只开放了进度和位置，没开放这个字段。
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeDelayAccessor {
    /** 剩余破坏延迟（tick），0 = 没有延迟 */
    @Accessor("destroyDelay")
    int meteorsupport$getDestroyDelay();

    @Accessor("destroyDelay")
    void meteorsupport$setDestroyDelay(int delay);
}
