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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fish22.modernsupport.utils.SilentSwitch;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 静默切换：滚轮换热栏槽位那一下
 * <p>
 * 不拦：本地槽位照原版一格格滚，滚完立刻让静默切换让位（{@link SilentSwitch#onManualSelect()}），
 * 服务端跟着玩家滚到那一格
 */
@Mixin(MouseHandler.class)
public class MixinMouseHandlerSilentSwitch {

    @WrapOperation(
        method = "onScroll",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V")
    )
    private void modernsupport$manualScroll(Inventory inventory, int slot, Operation<Void> original) {
        original.call(inventory, slot);
        SilentSwitch.onManualSelect();
    }
}
