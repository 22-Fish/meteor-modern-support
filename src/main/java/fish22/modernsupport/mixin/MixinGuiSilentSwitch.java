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

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fish22.modernsupport.utils.SilentSwitch;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 静默切换：热栏那圈高亮画在哪一格
 * <p>
 * 手动切槽现在不拦（照原版切本地槽位），接管期间 {@link SilentSwitch#displaySlot()} 就是
 * 本地选中槽位，这里读到的和原版一致，留着是给「接管期间画面单独走一套高亮」备用
 */
@Mixin(Gui.class)
public class MixinGuiSilentSwitch {

    @ModifyExpressionValue(
        method = "extractItemHotbar",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedSlot()I")
    )
    private int modernsupport$visualHotbarHighlight(int original) {
        return SilentSwitch.active() ? SilentSwitch.displaySlot() : original;
    }
}
