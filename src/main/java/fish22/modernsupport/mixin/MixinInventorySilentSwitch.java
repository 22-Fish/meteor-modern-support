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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 静默切换：让「客户端自己」也认为手上拿的是 {@link SilentSwitch#slot()} 那一格（水影的做法）
 * <p>
 * 原版和别的模块判断「手上拿的是什么」都是走 {@code Inventory.getSelectedItem()}，
 * 这里把那几处读「本地选中槽位」的地方换成静默槽位，于是挖掘速度、能不能挖、手上的工具、
 * 抽物品这些客户端逻辑全和服务端看齐，少一堆对不上的地方
 * <p>
 * 只换「读的是哪一格」，本地选中槽位那个字段本身一概不动 —— 热栏高亮和背包界面照旧
 * <p>
 * tick / removeFromSelected 也一起换：物品的「拿在手上」每秒结算、以及 Q 键丢物品，
 * 丢的也是服务端认的那一格里的东西
 */
@Mixin(Inventory.class)
public abstract class MixinInventorySilentSwitch {

    @Shadow
    @Final
    public Player player;

    @ModifyExpressionValue(
        method = {"getSelectedItem", "tick", "removeFromSelected"},
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/entity/player/Inventory;selected:I",
            opcode = Opcodes.GETFIELD
        )
    )
    private int meteorsupport$silentSelected(int original) {
        // 别的玩家/别的 Inventory（比如假人）不碰
        if (player != mc.player) return original;

        int slot = SilentSwitch.slot();
        return slot < 0 ? original : slot;
    }
}
