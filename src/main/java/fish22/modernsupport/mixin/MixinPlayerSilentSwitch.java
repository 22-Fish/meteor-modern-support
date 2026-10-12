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

import fish22.modernsupport.modules.SilentSwitchConfig;
import fish22.modernsupport.utils.SilentSwitch;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 静默切换：不重置客户端攻击冷却
 * <p>
 * 原版每 tick 比一次「手上那件东西和上次记的一样不一样」，不一样（而且是换了一类东西）
 * 就把攻击冷却清零（{@code resetAttackStrengthTicker}，顺带把手部动画那个进度也清零），
 * 准星上于是冒出攻击蓄力条、第一视角手上那件也会被拽下去
 * <p>
 * 静默切换只换了服务端那边的手持物（见 {@link MixinInventorySilentSwitch}），画面上什么都没动，
 * 冷却被清零就很突兀
 * <p>
 * 开了「静默切换不渲染工具冷却」（默认关）时就跳过这一下清零（见
 * {@link SilentSwitch#holding}，放开那两 tick 也算接管，免得漏掉放开那一下）
 * <p>
 * 只跳过清零：原版读到的物品、记的账一个字都不改，静默切换该按哪格还是哪格，别的模块照常
 */
@Mixin(value = Player.class, remap = false)
public class MixinPlayerSilentSwitch {

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;resetAttackStrengthTicker()V"
        )
    )
    private void meteorsupport$keepAttackCooldown(Player self) {
        if (self == mc.player && SilentSwitch.holding() && SilentSwitchConfig.isHideToolCooldown()) return;
        self.resetAttackStrengthTicker();
    }
}
