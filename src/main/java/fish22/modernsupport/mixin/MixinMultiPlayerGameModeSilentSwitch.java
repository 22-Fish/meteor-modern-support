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

import fish22.modernsupport.utils.SilentSwitch;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 静默切换：只拦「原版每 tick 把本地选中槽位同步给服务端」这一下
 * <p>
 * 手动切物品（数字键、滚轮）只是改本地槽位，真正告诉服务端的是 {@code MultiPlayerGameMode.tick()} 里那一下
 * ensureHasSentCarriedItem —— 静默按住工具期间把它拦掉，手动切物品就只改本地槽位和画面，服务端继续拿着工具
 * <p>
 * 别的模块切物品不走这条路：Meteor 的 InvUtils 换完直接当场同步（meteor$syncSelected），
 * 一键珍珠那种切槽 + 用物品照常发给服务端，所以不受影响
 */
@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameModeSilentSwitch {
    @Redirect(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;ensureHasSentCarriedItem()V"))
    private static void modernsupport$silentTickSync(MultiPlayerGameMode gameMode) {
        if (SilentSwitch.blockClientSync()) return;
        ((MultiPlayerGameModeMiningAccessor) gameMode).meteorsupport$ensureHasSentCarriedItem();
    }
}
