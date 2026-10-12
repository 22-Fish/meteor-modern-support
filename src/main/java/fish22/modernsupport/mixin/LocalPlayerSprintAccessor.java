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
 * 原版「上一次发给服务端的疾跑状态」访问器
 *
 * <p>原版发不发疾跑包只看两个值（{@code LocalPlayer#sendIsSprintingIfNeeded}）：
 * 当前的 {@code isSprinting()} 和它自己记的 {@code wasSprinting}（上一次发出去的那份），
 * 两个不一样就补一发 START / STOP_SPRINTING。
 *
 * <p>我们（甲飞拦截疾跑包 / 换甲前停疾跑 / 暴击前停疾跑）会自己发或者拦掉疾跑包，
 * 原版的记账就跟不上了：比如拦下 START 之后只把 {@code isSprinting()} 置回 false，
 * {@code wasSprinting} 还是 true，下一 tick 原版就自己补一发 STOP_SPRINTING ——
 * 可那发 START 根本没发出去，服务端看来这发 STOP 就是「重复的停止疾跑」
 * （Grim {@code BadPacketsF}, {@code state=false}）。
 *
 * <p>所以改疾跑包的同时要把它一起同步成「服务端实际收到的状态」。
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerSprintAccessor {

    @Accessor("wasSprinting")
    boolean getWasSprinting();

    @Accessor("wasSprinting")
    void setWasSprinting(boolean value);
}
