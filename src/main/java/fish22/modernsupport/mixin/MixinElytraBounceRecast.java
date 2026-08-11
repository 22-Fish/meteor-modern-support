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

import fish22.modernsupport.modules.ElytraBounce;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅弹跳 meteor模式 —— 落地自动重飞（recast）
 *
 * <p>移植 Meteor 官方 LivingEntityMixin.recastOnLand：滑翔状态从 true 变 false
 * （落地/退出滑翔）的瞬间，如果「鞘翅弹跳」模块处于 meteor 模式，
 * 立即恢复本地滑翔并重发起飞包（落地弹起继续飞）。
 * 官方原版判断的是 ElytraFly 的 Bounce 模式，此处改为本模块的 meteor 模式。
 */
@Mixin(LivingEntity.class)
public abstract class MixinElytraBounceRecast {

    /** 上一次查询 isFallFlying 时的滑翔状态 */
    @Unique
    private boolean previousElytra = false;

    @Inject(method = "isFallFlying", at = @At("TAIL"), cancellable = true)
    private void recastOnLand(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this != mc.player) return;

        boolean elytra = cir.getReturnValue();
        ElytraBounce bounce = Modules.get().get(ElytraBounce.class);
        if (previousElytra && !elytra && bounce.isActive() && bounce.isMeteorMode()) {
            cir.setReturnValue(ElytraBounce.recastElytra(mc.player));
        }
        previousElytra = elytra;
    }
}
