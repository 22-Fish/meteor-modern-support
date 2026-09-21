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

import fish22.modernsupport.ModernSupport;
import fish22.modernsupport.utils.GrimNoFallSupport;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.movement.NoFall;
import net.minecraft.world.item.MaceItem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「无摔伤」（NoFall）模块的 Grim 模式接管
 *
 * <p>模式枚举由 {@link MixinNoFallMode} 追加了「Grim」，这里只负责在选中它的时候
 * 把官方 Packet / AirPlace / Place 那三套逻辑换成本模组的 grim 逻辑（见 {@link GrimNoFallSupport}）：
 * 每 tick 记一次摔伤距离，落地那一 tick 的补包 / 拦包在官方那两处事件方法上动手
 *
 * <ul>
 *   <li>{@code onTick}（TickEvent.Pre）：记下这一 tick 移动之前的摔伤距离，救完补那一跳；</li>
 *   <li>{@code onSendPacket}（PacketEvent.Send）：落地那一 tick 补重置包 / 落地状态包并拦下落地包；</li>
 *   <li>{@code onActivate} / {@code onDeactivate}：起停记账与服务端位置纠正的监听。</li>
 * </ul>
 *
 * <p>官方的三个模式判断都是 {@code mode.get() == Mode.X}，Grim 之下它们一个都不成立，
 * 官方逻辑自然不跑，不需要额外拦
 */
@Mixin(value = NoFall.class, remap = false)
public abstract class MixinNoFall {

    /** 追加模式的名字（枚举是运行时追加的，编译期引用不到，只能按 name 认） */
    @Unique
    private static final String GRIM_MODE = "Grim";

    @Shadow
    @Final
    private Setting<NoFall.Mode> mode;

    @Shadow
    @Final
    private Setting<Boolean> anchor;

    @Shadow
    @Final
    private Setting<Boolean> pauseOnMace;

    @Unique
    private boolean modernsupport$isGrim() {
        NoFall.Mode current = mode != null ? mode.get() : null;
        return current != null && GRIM_MODE.equals(current.name());
    }

    /** 官方「锚点」只属于 空放 / 放水 两个模式，Grim 下不该露出来 */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        hideForeignSettings();
    }

    /**
     * 官方的可见条件是 {@code mode.get() != Mode.Packet}，Grim 也被算进去了，
     * 设置界面里就会混进「锚点」这一项。这里把它的可见条件包一层：Grim 模式不显示，官方模式照旧
     *
     * <p>{@code Setting.visible} 是 private final 字段，Java 17+ 反射改不动，用 Unsafe 直接写
     */
    @Unique
    private void hideForeignSettings() {
        try {
            Field field = Setting.class.getDeclaredField("visible");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = getUnsafe();
            long offset = unsafe.objectFieldOffset(field);

            IVisible original = (IVisible) unsafe.getObject(anchor, offset);
            unsafe.putObject(anchor, offset,
                (IVisible) () -> !modernsupport$isGrim() && (original == null || original.isVisible()));
        } catch (Exception e) {
            ModernSupport.LOG.warn("隐藏 NoFall 锚点设置失败", e);
        }
    }

    @Unique
    private static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            return sun.misc.Unsafe.getUnsafe();
        }
    }

    /** 每 tick 记下移动之前的摔伤距离（落地包发出时客户端已经清零，只能提前记） */
    @Inject(method = "onTick", at = @At("HEAD"))
    private void onTick(TickEvent.Pre event, CallbackInfo ci) {
        GrimNoFallSupport.onTick();
    }

    /** 落地那一 tick 处理这一摔（和官方「暂停锤子」一个规矩） */
    @Inject(method = "onSendPacket", at = @At("HEAD"))
    private void onSendPacket(PacketEvent.Send event, CallbackInfo ci) {
        if (!modernsupport$isGrim()) return;
        if (mc.player == null) return;
        if (pauseOnMace.get() && mc.player.getMainHandItem().getItem() instanceof MaceItem) return;

        GrimNoFallSupport.onPacketSend(event);
    }

    @Inject(method = "onActivate", at = @At("HEAD"))
    private void onActivate(CallbackInfo ci) {
        GrimNoFallSupport.onActivate();
        if (modernsupport$isGrim()) ModernSupport.LOG.info("[GrimNoFall] 模块已激活（模式=Grim）");
    }

    @Inject(method = "onDeactivate", at = @At("HEAD"))
    private void onDeactivate(CallbackInfo ci) {
        GrimNoFallSupport.onDeactivate();
    }
}
