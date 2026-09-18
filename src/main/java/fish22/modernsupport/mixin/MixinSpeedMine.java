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

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.player.SpeedMine;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「速度开采」的「即时开采」（瞬间破坏）增强：瞬间破坏保留延迟。
 *
 * <p>原版瞬间破坏（{@code MultiPlayerGameModeMixin.onStartDestroyBlock}）：开始挖方块时只要这个方块的
 * 单 tick 挖掘进度 &gt; 0.5，就立刻本地破坏 + 同一 tick 发 START/STOP；而且它不吃原版「破坏方块后的
 * 延迟」（{@code destroyDelay} 只有原版自己挖完一个方块时才会被设成 5）—— 所以按住左键扫过去就是
 * 每 tick 秒破一个，服务端收到的是一串零间隔的破坏包。
 *
 * <p>开启「瞬间破坏保留延迟」后，方块**还是秒破**，但两次挖掘之间保留原版那个延迟：
 * <ul>
 *   <li>秒破一个方块 → 它的 STOP 包发出去之后，把原版延迟设成 5（跟原版挖完一个方块一样）；</li>
 *   <li>延迟没走完（{@code destroyDelay > 0}）→ 不再秒破：{@link SpeedMine#instamine()} 返回 false，
 *       交给原版这条路（这段延迟里原版自己的挖掘进度也是冻着的，行为跟原版一致）；</li>
 *   <li>延迟走完 → 下一个方块照旧秒破（按住扫过去、一下一下点，都是这个节奏）。</li>
 * </ul>
 *
 * <p>延迟只在模块自己秒破时补（原版、别的模块的破坏不动它）；没按住左键时原版不会帮我们扣延迟
 * （只有 {@code continueDestroyBlock} 会扣），所以这种时候自己扣，免得延迟永远不降、之后就再也秒破不了。
 */
@Mixin(value = SpeedMine.class, remap = false)
public abstract class MixinSpeedMine {

    /** 原版破坏方块后的延迟（tick）：两次挖掘之间保留的就是它 */
    @Unique
    private static final int BREAK_DELAY = 5;

    @Shadow
    @Final
    private Setting<Boolean> instamine;

    @Unique
    private Setting<Boolean> keepDelay;

    /** 这一下要被秒破的方块（等它的 STOP 包发出去就把破坏延迟补上） */
    @Unique
    private BlockPos queuedPos;

    /** tick 开始时的延迟值（用来判断原版这一 tick 有没有帮我扣掉 1） */
    @Unique
    private int delayAtTickStart;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        SpeedMine self = (SpeedMine) (Object) this;

        keepDelay = new BoolSetting.Builder()
            .name("瞬间破坏保留延迟")
            .description("方块照旧秒破，但两次挖掘之间保留原版的破坏延迟（5 tick）：延迟没走完的时候不秒破，交给原版挖。")
            .defaultValue(false)
            .visible(() -> self.mode.get() == SpeedMine.Mode.Damage && instamine.get())
            .build();

        insertAfter(self.settings.getDefaultGroup(), "instamine", keepDelay);
    }

    /** 两次挖掘之间的延迟还没走完 → 不秒破（走原版挖掘，进度本来就被这个延迟冻着） */
    @Inject(method = "instamine", at = @At("RETURN"), cancellable = true)
    private void onInstamine(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (keepDelay != null && keepDelay.get() && delay() > 0) cir.setReturnValue(false);
    }

    /** 记下这一下会被秒破的方块（条件跟原版瞬间破坏一致：会被秒破 + 过方块过滤器 + 不在延迟里） */
    @Unique
    @EventHandler
    private void modernsupport$onStartBreaking(StartBreakingBlockEvent event) {
        queuedPos = null;

        if (!settingOn()) return;

        BlockPos pos = event.blockPos;
        if (pos == null || event.direction == null) return;
        if (delay() > 0) return;

        BlockState state = mc.level.getBlockState(pos);
        if (state.getDestroyProgress(mc.player, mc.level, pos) <= 0.5f) return;
        if (!((SpeedMine) (Object) this).filter(state.getBlock())) return;

        queuedPos = pos.immutable();
    }

    /** 秒破的 STOP 包发出去了 → 从现在开始算「两次挖掘之间的延迟」（同一 tick 里后面的方块就不会再秒破了） */
    @Unique
    @EventHandler
    private void modernsupport$onSend(PacketEvent.Send event) {
        if (queuedPos == null || !settingOn()) return;
        if (!(event.packet instanceof ServerboundPlayerActionPacket packet)) return;
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) return;
        if (!queuedPos.equals(packet.getPos())) return;

        queuedPos = null;
        setDelay(BREAK_DELAY);
    }

    @Unique
    @EventHandler
    private void modernsupport$onTickPre(TickEvent.Pre event) {
        delayAtTickStart = delay();
    }

    /**
     * 原版只在 {@code continueDestroyBlock}（按住左键挖）里扣这个延迟；没按住左键挖的时候
     * （例如一下一下点着秒破）它不会扣，那就自己扣，免得延迟一直挂着、之后就再也秒破不了。
     */
    @Unique
    @EventHandler
    private void modernsupport$onTickPost(TickEvent.Post event) {
        if (!settingOn() || delayAtTickStart <= 0) return;
        if (delay() != delayAtTickStart) return;
        if (mc.options.keyAttack.isDown()) return;

        setDelay(delayAtTickStart - 1);
    }

    @Inject(method = "onDeactivate", at = @At("HEAD"))
    private void onDeactivate(CallbackInfo ci) {
        queuedPos = null;
    }

    /** 设置开着、模块在生效（模式/即时开采都满足）并且在游戏里 */
    @Unique
    private boolean settingOn() {
        if (keepDelay == null || !keepDelay.get() || !instamine.get()) return false;
        return Utils.canUpdate() && mc.player != null && mc.level != null && mc.gameMode != null;
    }

    @Unique
    private static int delay() {
        if (mc.gameMode == null) return 0;
        return ((MultiPlayerGameModeDelayAccessor) mc.gameMode).meteorsupport$getDestroyDelay();
    }

    @Unique
    private static void setDelay(int ticks) {
        if (mc.gameMode == null) return;
        ((MultiPlayerGameModeDelayAccessor) mc.gameMode).meteorsupport$setDestroyDelay(ticks);
    }

    /** 插到「即时开采」设置下面（Meteor 只能末尾追加，得自己按位置插） */
    @Unique
    private static void insertAfter(SettingGroup group, String afterName, Setting<?> setting) {
        List<Setting<?>> settings = ((SettingGroupAccessor) (Object) group).getSettings();
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).name.equals(afterName)) {
                settings.add(i + 1, setting);
                return;
            }
        }
        group.add(setting);
    }
}
