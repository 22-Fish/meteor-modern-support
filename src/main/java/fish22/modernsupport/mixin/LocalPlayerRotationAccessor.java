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

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 原版「上次发给服务器的角度」访问器
 *
 * <p>原版 {@code sendPosition} 用 {@code getYRot() != yRotLast} 判断「角度变了没」，
 * 变了才在移动包里带旋转——它<b>只知道它自己发出去的包</b>。
 *
 * <p>合法转头在「立刻发包」模式下会自己发旋转包，原版并不知道；不同步的话原版会以为
 * 角度没变，永远不发恢复包 → 服务器一直保持我们的角度，之后走路会被拉回（回弹）。
 * 所以自己发完包后，把这两个值同步成刚发出去的角度：下一 tick 原版就会自己把视角角度
 * 发出去恢复正常。
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerRotationAccessor {

    @Accessor("yRotLast")
    void setLastSentYaw(float value);

    @Accessor("xRotLast")
    void setLastSentPitch(float value);
}
