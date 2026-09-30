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
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.world.EndermanLook;
import meteordevelopment.meteorclient.utils.player.Rotations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Meteor「末影人观察」的合法转头选项
 *
 * <p>模块原来用 {@link Rotations} 转朝向：转完还会在之后几 tick 里继续把这份朝向塞进移动包，
 * 一 tick 也可能多一个旋转包。开了「合法转头」改走 {@link LegalRotation}：朝向跟着本 tick 的
 * 移动包一起发出去，一 tick 只有一个移动类包，视角、相机、鼠标输入全程不动。
 *
 * <p>「合法转头」关闭（或选了还没实现的「停止移动」）时走原来的 {@link Rotations}，行为和以前一样。
 */
@Mixin(value = EndermanLook.class, remap = false)
public abstract class MixinEndermanLook {

    /** 合法转头模式（关闭＝用 Meteor 原版旋转） */
    @Unique
    private Setting<LegalRotation.Mode> legalRotation;

    /** 合法转头优先级 */
    @Unique
    private Setting<Integer> legalRotationPriority;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        EndermanLook self = (EndermanLook) (Object) this;

        SettingGroup sgLegit = self.settings.createGroup("合法转头");

        legalRotation = sgLegit.add(new EnumSetting.Builder<LegalRotation.Mode>()
            .name("合法转头")
            .description("转头走合法转头（朝向跟着移动包发出，视角不动）。关闭：用 Meteor 原版旋转")
            .defaultValue(LegalRotation.Mode.OFF)
            .build()
        );

        legalRotationPriority = sgLegit.add(new IntSetting.Builder()
            .name("合法转头优先级")
            .description("同一 tick 里和其它模块抢转向时的优先级，大的赢。默认 -75 和 Meteor 原版一样，让其它模块先转")
            .defaultValue(-75)
            .sliderRange(-100, 20)
            .visible(this::isLegalRotationOn)
            .build()
        );
    }

    /** onTick 里那几处旋转：开了合法转头就不走 Meteor 那套（关闭时原样透传） */
    @Redirect(
        method = "onTick",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V"
        )
    )
    private void redirectRotate(double yaw, double pitch, int priority, Runnable callback) {
        LegalRotation.Mode mode = legalRotation.get();

        if (mode == LegalRotation.Mode.SEVERE || mode == LegalRotation.Mode.QUIET) {
            LegalRotation.rotate(yaw, pitch, mode, legalRotationPriority.get(), callback);
        } else {
            // 关闭 / 停止移动（未实现）：回退原版静默旋转
            Rotations.rotate(yaw, pitch, priority, callback);
        }
    }

    /** 合法转头开没开 */
    @Unique
    private boolean isLegalRotationOn() {
        return legalRotation != null && legalRotation.get() != LegalRotation.Mode.OFF;
    }
}
