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

import fish22.modernsupport.utils.GrimCriticalsSupport;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.combat.Criticals;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.item.MaceItem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「刀刀暴击」（Criticals）模块的 Grim 模式接管
 *
 * <p>模式枚举由 {@link MixinCriticalsMode} 追加了「Grim」，这里只负责在选中它的时候
 * 把官方那套逻辑换成本模组的 grim 平地刀爆（见 {@link GrimCriticalsSupport}）：
 *
 * <ul>
 *   <li>{@code onSendPacket}：攻击包能接就扣下等回弹（grim 只在这时候拦包），
 *       接不了的照常发；挥动包与「扣包那一 tick 客户端自己的坐标包」按需拦掉；</li>
 *   <li>{@code onTick}：跑等回弹 / 补发的状态机；</li>
 *   <li>{@code onActivate}：清记账、开始跟服务端的位置纠正包。</li>
 * </ul>
 *
 * <p>官方那个 switch 是 {@code tableswitch}（只认官方那五个序号），Grim 落进 default 什么都不做，
 * 所以没接下的攻击包走官方也不会出问题；官方先按「手持锤子 + 大锤强击」分流那支同样交给官方，
 * 别的模式一个都不动
 */
@Mixin(value = Criticals.class, remap = false)
public abstract class MixinCriticals {

    /** 追加模式的名字（枚举是运行时追加的，编译期引用不到，只能按 name 认） */
    @Unique
    private static final String GRIM_MODE = "Grim";

    @Shadow
    @Final
    private Setting<Criticals.Mode> mode;

    @Shadow
    @Final
    private Setting<Boolean> mace;

    @Unique
    private boolean modernsupport$isGrim() {
        Criticals.Mode current = mode != null ? mode.get() : null;
        return current != null && GRIM_MODE.equals(current.name());
    }

    /** 攻击包：能接就扣下等回弹，扣不了（水里 / 空中 / 不是活体）就让它照常发 */
    @Inject(method = "onSendPacket", at = @At("HEAD"), cancellable = true)
    private void onSendPacketGrim(PacketEvent.Send event, CallbackInfo ci) {
        if (!modernsupport$isGrim() || mc.player == null) return;
        if (GrimCriticalsSupport.isSendingOwn()) return;
        // 锤子那支官方逻辑里没有 switch（大锤强击），Grim 之下照旧交给官方
        if (mace.get() && mc.player.getMainHandItem().getItem() instanceof MaceItem) return;

        if (event.packet instanceof ServerboundAttackPacket) {
            if (GrimCriticalsSupport.onAttackPacket(event)) ci.cancel();
        } else if (GrimCriticalsSupport.shouldCancel(event)) ci.cancel();
    }

    @Inject(method = "onTick", at = @At("HEAD"))
    private void onTickGrim(TickEvent.Pre event, CallbackInfo ci) {
        GrimCriticalsSupport.onTick();
    }

    @Inject(method = "onActivate", at = @At("HEAD"))
    private void onActivateGrim(CallbackInfo ci) {
        GrimCriticalsSupport.onActivate();
    }
}
