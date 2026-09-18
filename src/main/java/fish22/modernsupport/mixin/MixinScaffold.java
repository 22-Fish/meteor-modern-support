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

import fish22.modernsupport.utils.LegalPlace;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
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
 * Meteor 官方「脚手架」的放置：目标位置照旧由原版挑（其他设置照旧生效）。
 *
 * <p>开了「仅合法朝向」后改走合法放置：{@link LegalPlace} 算出这个位置的合法放置角度
 * （算不出来就不放），{@link LegalRotation} 转向那个方向，放置包排在「带着这份朝向的
 * 移动包」之后（同一 tick）。
 */
@Mixin(value = Scaffold.class, remap = false)
public abstract class MixinScaffold {

    @Shadow
    private Setting<Boolean> rotate;

    @Unique
    private Setting<Boolean> onlyLegalAim;

    @Unique
    private Setting<LegalRotation.Mode> legalRotation;

    @Unique
    private Setting<Integer> legalRotationPriority;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        Scaffold self = (Scaffold) (Object) this;

        onlyLegalAim = new BoolSetting.Builder()
            .name("仅合法朝向")
            .description("只在算得出合法放置角度时放（走 LegalPlace 找角度）。关闭：原版 meteor 的放置与旋转。")
            .defaultValue(false)
            .visible(() -> rotate.get())
            .build();

        legalRotation = new EnumSetting.Builder<LegalRotation.Mode>()
            .name("合法转头")
            .description("算出来的角度用哪种方式转过去。关闭：不转，只放当前视角本来就合法的那种。")
            .defaultValue(LegalRotation.Mode.SEVERE)
            .visible(() -> rotate.get())
            .build();

        legalRotationPriority = new IntSetting.Builder()
            .name("合法转头优先级")
            .description("合法转头的优先级")
            .defaultValue(0)
            .sliderRange(-20, 20)
            .visible(() -> rotate.get())
            .build();

        insertAfter(self.settings.getDefaultGroup(), "rotate", onlyLegalAim);
        insertAfter(self.settings.getDefaultGroup(), "仅合法朝向", legalRotation);
        insertAfter(self.settings.getDefaultGroup(), "合法转头", legalRotationPriority);
    }

    @Redirect(
        method = "place(Lnet/minecraft/core/BlockPos;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;place(Lnet/minecraft/core/BlockPos;Lmeteordevelopment/meteorclient/utils/player/FindItemResult;ZIZZ)Z"
        )
    )
    private boolean redirectPlace(BlockPos blockPos, FindItemResult item, boolean rotate, int rotationPriority, boolean swingHand, boolean checkEntities) {
        // 没开「旋转」或没开「仅合法朝向」：完全交给原版（meteor 的放置 + 发包旋转）
        if (!rotate || !onlyLegalAim.get()) {
            return BlockUtils.place(blockPos, item, rotate, rotationPriority, swingHand, checkEntities);
        }

        // 这一格放不下就不放（人站在地上时原版每 tick 都会走到这里）
        ItemStack stack = mc.player.getInventory().getItem(item.slot());
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        if (!BlockUtils.canPlaceBlock(blockPos, checkEntities, blockItem.getBlock())) return false;

        LegalPlace.Aim aim = LegalPlace.compute(blockPos);
        if (aim == null) return false;

        if (aim.rotated()) {
            // 需要转头却没开合法转头：不转就发是个不合法的包，那就不放
            if (legalRotation.get() == LegalRotation.Mode.OFF) return false;
            // 被更高优先级的旋转顶掉：同上，这一格不放
            if (!LegalRotation.rotate(aim.yaw(), aim.pitch(), legalRotation.get(), legalRotationPriority.get())) return false;
        }

        // 放包排在移动包之后：服务器那时看到的才是这份朝向
        LegalRotation.runAfterSend(() -> placeLegit(item, aim, swingHand));
        return true;
    }

    @Unique
    private void placeLegit(FindItemResult item, LegalPlace.Aim aim, boolean swingHand) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        boolean offhand = item.isOffhand();
        boolean swap = !offhand && !item.isMainHand();
        if (swap) InvUtils.swap(item.slot(), true);

        // 点算好的那个面：角度是照着它算的
        BlockUtils.interact(new BlockHitResult(
                aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock(), false
            ),
            offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
            swingHand
        );

        if (swap) InvUtils.swapBack();
    }

    /** 插到原版「旋转」设置下面（Meteor 只能末尾追加，得自己按位置插） */
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
