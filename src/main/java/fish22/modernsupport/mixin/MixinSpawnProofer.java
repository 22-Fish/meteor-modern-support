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
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.world.SpawnProofer;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * SpawnProofer（防止生成）增强 mixin：
 * <ul>
 *   <li>「合法转头」：放置前直接调 LegalRotation.rotate()，不走 MixinBlockUtils 间接拦截</li>
 *   <li>「背包放置」：方块在背包（主背包）也能放置（走 BackpackUse 4 包 PICKUP）</li>
 *   <li>「瞄准点与范围优化」：范围按眼睛距离算，瞄准点用支撑方块表面中心（而非放置方块中心）</li>
 * </ul>
 */
@Mixin(value = SpawnProofer.class, remap = false)
public abstract class MixinSpawnProofer {

    @Shadow
    private Setting<Boolean> rotate;

    @Shadow
    private Setting<Double> placeRange;

    @Shadow
    private Setting<Double> wallsRange;

    @Shadow
    private Setting<List<Block>> blocks;

    @Unique
    private Setting<LegalRotation.Mode> legalRotationMode;

    @Unique
    private Setting<Boolean> backpackPlace;

    @Unique
    private Setting<fish22.modernsupport.utils.BackpackUse.Mode> backpackMode;

    @Unique
    private Setting<Boolean> aimOptimization;

    @Unique
    private Setting<Boolean> debugLog;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        SpawnProofer self = (SpawnProofer) (Object) this;

        SettingGroup sg = self.settings.createGroup("合法转头");
        legalRotationMode = sg.add(new EnumSetting.Builder<LegalRotation.Mode>()
            .name("合法转头")
            .description("合法转头模式。严格：移动方向为真实旋转。静默：在严格基础上映射 WASD 按键,尝试让移动方向与视觉朝向一致。")
            .defaultValue(LegalRotation.Mode.OFF)
            .visible(() -> rotate.get())
            .build()
        );
        debugLog = sg.add(new BoolSetting.Builder()
            .name("调试日志")
            .description("聊天栏打印旋转和放置时序")
            .defaultValue(false)
            .visible(() -> rotate.get() && (legalRotationMode.get() == LegalRotation.Mode.SEVERE || legalRotationMode.get() == LegalRotation.Mode.QUIET))
            .build()
        );

        SettingGroup sgGeneral = self.settings.getDefaultGroup();
        backpackPlace = sgGeneral.add(new BoolSetting.Builder()
            .name("背包放置")
            .description("方块在背包时也能放置。")
            .defaultValue(false)
            .build()
        );
        backpackMode = sgGeneral.add(new EnumSetting.Builder<fish22.modernsupport.utils.BackpackUse.Mode>()
            .name("背包使用模式")
            .description("背包放置的发包模式。1p：SWAP 2包;2p：PICKUP 4 包。除特殊原因，请使用2p更稳定")
            .defaultValue(fish22.modernsupport.utils.BackpackUse.Mode.PICKUP)
            .visible(backpackPlace::get)
            .build()
        );
        aimOptimization = sgGeneral.add(new BoolSetting.Builder()
            .name("瞄准点与范围优化")
            .description("范围按眼睛距离算，瞄准点瞄准支撑方块上表面中心而非放置方块中心")
            .defaultValue(true)
            .build()
        );
    }

    // ====== 没有方块时不报错不关闭，直接不动 ======

    @Redirect(
        method = {"onTickPre", "onTickPost"},
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/systems/modules/world/SpawnProofer;error(Ljava/lang/String;[Ljava/lang/Object;)V"
        )
    )
    private void redirectError(SpawnProofer self, String message, Object[] args) {
    }

    @Redirect(
        method = {"onTickPre", "onTickPost"},
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/systems/modules/world/SpawnProofer;toggle()V"
        )
    )
    private void redirectToggle(SpawnProofer self) {
    }

    // ====== 背包放置：onTickPre 检查方块 ======

    @Redirect(
        method = "onTickPre",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/InvUtils;testInHotbar(Ljava/util/function/Predicate;)Z"
        )
    )
    private boolean redirectTestInHotbar(Predicate<ItemStack> predicate) {
        if (backpackPlace.get()) {
            return InvUtils.find(predicate).found();
        }
        return InvUtils.testInHotbar(predicate);
    }

    // ====== 背包放置：onTickPost 找方块 ======

    @Redirect(
        method = "onTickPost",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/InvUtils;findInHotbar(Ljava/util/function/Predicate;)Lmeteordevelopment/meteorclient/utils/player/FindItemResult;"
        )
    )
    private FindItemResult redirectFindInHotbar(Predicate<ItemStack> predicate) {
        FindItemResult hotbar = InvUtils.findInHotbar(predicate);
        if (hotbar.found() || !backpackPlace.get()) return hotbar;
        return InvUtils.find(predicate);
    }

    // ====== 放置：直接调合法转头 + 以 rotate=false 调 BlockUtils.place ======

    @Redirect(
        method = "onTickPost",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;place(Lnet/minecraft/core/BlockPos;Lmeteordevelopment/meteorclient/utils/player/FindItemResult;ZIZ)Z"
        )
    )
    private boolean redirectPlace(BlockPos blockPos, FindItemResult block, boolean rotate, int rotationPriority, boolean checkEntities) {
        LegalRotation.Mode mode = legalRotationMode.get();
        boolean useBackpack = backpackPlace.get() && block.isMain();

        boolean useCorrection = rotate && (mode == LegalRotation.Mode.SEVERE || mode == LegalRotation.Mode.QUIET);

        if (useBackpack) {
            return placeFromBackpack(blockPos, useCorrection, mode);
        }

        if (!useCorrection) {
            return BlockUtils.place(blockPos, block, rotate, rotationPriority, checkEntities);
        }

        // 直接算旋转 + 调合法转头（不走 MixinBlockUtils 间接拦截）
        BlockHitResult hitResult = calcPlaceHitResult(blockPos);
        double yaw = Rotations.getYaw(hitResult.getLocation());
        double pitch = Rotations.getPitch(hitResult.getLocation());

        if (debugLog.get()) logToChat("旋转");
        LegalRotation.rotate(yaw, pitch, mode);

        // 以 rotate=false 调 BlockUtils.place，放置立即执行（不经过 Rotations.rotate）
        boolean result = BlockUtils.place(blockPos, block, false, rotationPriority, checkEntities);
        if (debugLog.get() && result) logToChat("放置");
        return result;
    }

    @Unique
    private boolean placeFromBackpack(BlockPos blockPos, boolean useCorrection, LegalRotation.Mode mode) {
        BlockHitResult hitResult = calcPlaceHitResult(blockPos);
        Predicate<ItemStack> pred = stack -> blocks.get().contains(Block.byItem(stack.getItem()));

        if (useCorrection) {
            double yaw = Rotations.getYaw(hitResult.getLocation());
            double pitch = Rotations.getPitch(hitResult.getLocation());
            if (debugLog.get()) logToChat("旋转");
            LegalRotation.rotate(yaw, pitch, mode);
        }

        boolean result = fish22.modernsupport.utils.BackpackUse.place(pred, hitResult, backpackMode.get());
        if (debugLog.get() && result) logToChat("放置");
        return result;
    }

    // ====== 瞄准点与范围优化 ======

    @Inject(method = "isOutOfRange", at = @At("HEAD"), cancellable = true)
    private void onIsOutOfRange(BlockPos blockPos, CallbackInfoReturnable<Boolean> cir) {
        if (!aimOptimization.get()) return;

        BlockHitResult hit = calcPlaceHitResult(blockPos);
        Vec3 aimPos = hit.getLocation();
        BlockPos supportBlock = hit.getBlockPos();

        double eyeDistSq = mc.player.getEyePosition().distanceToSqr(aimPos);
        if (eyeDistSq > placeRange.get() * placeRange.get()) {
            cir.setReturnValue(true);
            return;
        }

        ClipContext raycast = new ClipContext(mc.player.getEyePosition(), aimPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult result = mc.level.clip(raycast);
        if (result == null || !result.getBlockPos().equals(supportBlock)) {
            cir.setReturnValue(eyeDistSq > wallsRange.get() * wallsRange.get());
            return;
        }
        cir.setReturnValue(false);
    }

    @Unique
    private static BlockHitResult calcPlaceHitResult(BlockPos blockPos) {
        Vec3 hitPos = Vec3.atCenterOf(blockPos);
        Direction side = BlockUtils.getPlaceSide(blockPos);
        BlockPos neighbour;
        if (side == null) {
            side = Direction.UP;
            neighbour = blockPos;
        } else {
            neighbour = blockPos.relative(side);
            hitPos = hitPos.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        }
        return new BlockHitResult(hitPos, side.getOpposite(), neighbour, false);
    }

    @Unique
    private static void logToChat(String msg) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
        }
    }
}
