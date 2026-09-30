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

import fish22.modernsupport.modules.GhostMine;
import fish22.modernsupport.utils.BreakFace;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.world.Nuker;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 给 Meteor 官方「核爆」（Nuker）补两块：
 *
 * <p><b>1. 挖掘面选择</b>：新设置「挖掘面」——
 * 射线面 = 眼睛到方块中心的射线进入面（眼睛真能看到的那一面，Grim 这类反作弊的
 * 「不可能的角度挖这一面」检查能过，算法和「自动收甘蔗」一致，见 {@link BreakFace}）；
 * 原版面 = Meteor 原来按碰撞箱猜面的那套（没有碰撞箱的方块会猜错面）。
 * 原版挖掘、发包挖掘两条路都按这个设置报面（交互模式不动，还是原版那套）
 *
 * <p><b>2. 接到「发包挖掘」</b>：「发包挖掘」开着时核爆不再自己发挖掘包，
 * 把扫出来的方块塞进它的两个挖掘位（主挖 + 副挖），挖掘延迟、切工具、收尾都由它那套逻辑走。
 * 「发包挖掘」开着双挖时两个位都占（一次排两个方块）—— 核爆就是用双挖在挖；
 * 双挖没开就只排主挖位。这样核爆挖出来的包序列和手动挖掘一致，能适配更多反作弊
 */
@Mixin(value = Nuker.class, remap = false)
public abstract class MixinNuker {

    @Shadow
    @Final
    private Setting<Boolean> interact;

    @Shadow
    @Final
    private List<BlockPos> blocks;

    /** 挖掘面怎么选（插进核爆自己的设置里） */
    @Unique
    private Setting<BreakFace> faceMode;

    /** 原版挖掘这条流程的收尾状态（照「自动收甘蔗」那套：停下来时补一个停止挖掘） */
    @Unique
    private boolean breaking;
    @Unique
    private boolean breakingThisTick;

    // ==================== 设置 ====================

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        Nuker self = (Nuker) (Object) this;

        faceMode = new EnumSetting.Builder<BreakFace>()
            .name("挖掘面")
            .description("挖掘时报给服务端的面：射线面 = 眼睛看得到的那一面，能过 Grim 这类反作弊的角度检查；原版面 = 按碰撞箱猜的面")
            .defaultValue(BreakFace.RAY)
            .build();

        insertAfter(self.settings.getDefaultGroup(), "rotate", faceMode);
    }

    /** 插到「旋转」设置下面（Meteor 只能末尾追加，得自己按位置插） */
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

    // ==================== 接到「发包挖掘」 ====================

    /**
     * 「发包挖掘」开着时这一步不自己挖，把方块塞进它的挖掘位
     * <p>
     * 它只有两个位（主挖 + 副挖），这里一次把还空着的位排满：双挖开着时一次排两个方块，
     * 没开就只排主挖。挖掘延迟、切工具、收尾、双挖都由它那套逻辑走
     */
    @Inject(method = "breakBlock", at = @At("HEAD"), cancellable = true)
    private void onBreakBlock(BlockPos blockPos, CallbackInfo ci) {
        if (interact.get()) return;

        GhostMine ghostMine = GhostMine.getInstance();
        if (ghostMine == null || !ghostMine.isActive()) return;

        ci.cancel();
        queueToGhostMine(ghostMine, blockPos);
    }

    /**
     * 把这个方块和扫描列表里剩下的方块塞进「发包挖掘」还空着的挖掘位
     * <p>
     * 核爆一个 tick 可能只挑一个方块（每tick破坏数量 = 1），但双挖需要两个位都有方块，
     * 所以第一个塞不进（已经在挖了）或者位还没满时，继续从扫描列表里往后补
     */
    @Unique
    private void queueToGhostMine(GhostMine ghostMine, BlockPos first) {
        queueOne(ghostMine, first);
        for (BlockPos pos : blocks) {
            if (slotsFull(ghostMine)) return;
            queueOne(ghostMine, pos);
        }
    }

    /** 挖掘位是不是都占满了：双挖没开时只有主挖一个位 */
    @Unique
    private static boolean slotsFull(GhostMine ghostMine) {
        return GhostMine.firstBlockDate != null
            && (!ghostMine.doubleBreak.get() || GhostMine.secondBlockDate != null);
    }

    /** 塞一个方块进「发包挖掘」的挖掘位（位满了 / 已经在里面 / 超出它的挖掘范围就不动） */
    @Unique
    private void queueOne(GhostMine ghostMine, BlockPos pos) {
        if (pos == null || slotsFull(ghostMine)) return;
        if (sameAsQueued(GhostMine.firstBlockDate, pos) || sameAsQueued(GhostMine.secondBlockDate, pos)) return;
        if (ghostMine.hasRebreakFrame(pos)) return;
        if (GhostMine.unbreakableBlocks.contains(mc.level.getBlockState(pos).getBlock())) return;
        if (!ghostMine.ignoreRangeWhileMining.get() && PlayerUtils.distanceTo(pos) > ghostMine.range.get()) return;

        // 方向只是兜底：「发包挖掘」发包时还会按当时的眼睛位置重算面
        Direction face = faceMode.get().pick(pos);

        if (GhostMine.firstBlockDate == null) {
            GhostMine.firstBlockDate = ghostMine.getBlockDate(pos, face, false);
            return;
        }

        if (ghostMine.doubleBreak.get() && GhostMine.secondBlockDate == null) {
            GhostMine.secondBlockDate = ghostMine.getBlockDate(pos, face, false);
        }
    }

    @Unique
    private static boolean sameAsQueued(GhostMine.BlockDate block, BlockPos pos) {
        return block != null && block.pos.equals(pos);
    }

    // ==================== 挖掘面 ====================

    /**
     * 发包挖掘那条路（开始包 / 结束包）：包体还是原来那个 lambda 造的，只把里面的挖掘面换掉
     * <p>
     * 面是 lambda 里算的（{@code BlockUtils.getDirection}），lambda 是静态方法拦不到，
     * 所以拦在造包这一步：拿它造好的包，把 face 换成算出来的射线面再发出去
     */
    @Redirect(
        method = "breakBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V"
        )
    )
    private void onSendMiningPacket(MultiPlayerGameMode gameMode, ClientLevel level, PredictiveAction action) {
        if (faceMode == null || interact.get() || faceMode.get() == BreakFace.VANILLA) {
            gameMode.startPrediction(level, action);
            return;
        }

        gameMode.startPrediction(level, sequence -> {
            Packet<ServerGamePacketListener> packet = action.predict(sequence);
            if (packet instanceof ServerboundPlayerActionPacket playerAction) {
                return new ServerboundPlayerActionPacket(
                    playerAction.getAction(), playerAction.getPos(),
                    BreakFace.ray(playerAction.getPos()), sequence);
            }
            return packet;
        });
    }

    /** 原版挖掘那条路：按「挖掘面」设置报面（原版面时原样走 Meteor 的 BlockUtils.breakBlock） */
    @Redirect(
        method = "breakBlock",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;breakBlock(Lnet/minecraft/core/BlockPos;Z)Z"
        )
    )
    private boolean onLegitBreak(BlockPos blockPos, boolean doSwing) {
        if (faceMode == null || faceMode.get() == BreakFace.VANILLA) {
            return BlockUtils.breakBlock(blockPos, doSwing);
        }

        return mineWithFace(blockPos, doSwing);
    }

    /**
     * 走原版挖掘流程（和 Meteor 的 {@link BlockUtils#breakBlock} 同一套调用），区别只在挖掘面用算出来的射线面
     * <p>
     * 返回值和它一致：没挖（方块挖不动）返回 false，动手挖了返回 true
     */
    @Unique
    private boolean mineWithFace(BlockPos blockPos, boolean doSwing) {
        if (!BlockUtils.canBreak(blockPos, mc.level.getBlockState(blockPos))) return false;

        Direction face = faceMode.get().pick(blockPos);

        if (mc.gameMode.isDestroying()) mc.gameMode.continueDestroyBlock(blockPos, face);
        else mc.gameMode.startDestroyBlock(blockPos, face);

        if (doSwing) mc.player.swing(InteractionHand.MAIN_HAND);
        else mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        breaking = true;
        breakingThisTick = true;
        return true;
    }

    // ==================== 原版挖掘的收尾 ====================

    @Unique
    @EventHandler
    private void modernsupport$onTickPre(TickEvent.Pre event) {
        breakingThisTick = false;
    }

    /** 这一 tick 没挖方块、上一 tick 在挖 → 收尾（对应原版停止挖掘的时机） */
    @Unique
    @EventHandler
    private void modernsupport$onTickPost(TickEvent.Post event) {
        if (breaking && !breakingThisTick) {
            breaking = false;
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        }
    }

    @Inject(method = "onActivate", at = @At("HEAD"))
    private void onActivate(CallbackInfo ci) {
        // 上次开着挖到一半被关掉：把还挂着的「正在挖掘」收掉，不然原版以为还在挖
        if (breaking && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        breaking = false;
        breakingThisTick = false;
    }
}
