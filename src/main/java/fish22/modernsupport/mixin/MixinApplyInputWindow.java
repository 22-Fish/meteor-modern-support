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

import fish22.modernsupport.utils.LegalRotation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code applyInput} 期间临时挂起合法转头的移动窗口
 *
 * <p>{@code LocalPlayer#applyInput} 除了算出 {@code xxa}/{@code zza}（这些和朝向无关），
 * 还会把「手部晃动」用的 {@code xBob}/{@code yBob} 朝玩家角度插值：
 * {@code yBob += (getYRot() - yBob) * 0.5}、{@code xBob += (getXRot() - xBob) * 0.5}；
 * 只有 {@code ItemInHandRenderer} 会读这两个值。
 *
 * <p>合法转头的窗口现在盖住整段 {@code aiStep}（起跳那一 tick 的 0.2 冲量必须用真实角度），
 * 所以这里要单独把 {@code applyInput} 这一段挂起：这一小段按视角角度算，
 * 第一人称的手就不会跟着真实朝向每 tick 转一点，其余移动运算照旧用真实角度。
 */
@Mixin(LocalPlayer.class)
public class MixinApplyInputWindow {

    @Inject(method = "applyInput", at = @At("HEAD"))
    private void onApplyInputHead(CallbackInfo ci) {
        LegalRotation.suspendWindow();
    }

    @Inject(method = "applyInput", at = @At("RETURN"))
    private void onApplyInputReturn(CallbackInfo ci) {
        LegalRotation.resumeWindow();
    }
}
