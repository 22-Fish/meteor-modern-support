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

import fish22.modernsupport.settings.ComputedBoolSetting;
import fish22.modernsupport.settings.ScaffoldRangeMode;
import fish22.modernsupport.utils.LegalPlace;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IVisible;
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
import org.objectweb.asm.Opcodes;
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
 * Meteor 官方「脚手架」的放置：目标位置照旧由原版挑（其他设置照旧生效）。
 *
 * <p>官方「air-place」一个开关背了三件事，这里拆成两个设置：
 * <ul>
 *   <li><b>空中放置</b>：只管「没有支撑面时能不能点空气空放」，别的什么都不影响；</li>
 *   <li><b>范围模式</b>：单个（只放目标一块，提前量 / 最近可放搜索照旧）还是范围（按 radius
 *       一圈、每 tick 至多 blocks-per-tick 块）。ahead-distance / closest-block-range 跟着
 *       「单个」显隐，radius / blocks-per-tick 跟着「范围」显隐。</li>
 * </ul>
 * 官方 air-place 因此从设置列表撤掉，它在 onTick 里被读的三处换成本类算出来的值
 * （见下面三个 {@code redirectAirPlace...}）。
 *
 * <p>开了「仅合法朝向」后改走合法放置：{@link LegalPlace} 算出这个位置的合法放置角度
 * （算不出来就不放），{@link LegalRotation} 转向那个方向，放置包排在「带着这份朝向的
 * 移动包」之后（同一 tick）。六个面都没支撑时：开着空中放置就改用空中放置的角度点空气自己，
 * 关着就不放。
 */
@Mixin(value = Scaffold.class, remap = false)
public abstract class MixinScaffold {

    @Shadow
    @Final
    private Setting<Boolean> rotate;

    @Shadow
    @Final
    private Setting<Double> aheadDistance;

    @Shadow
    @Final
    private Setting<Double> placeRange;

    @Shadow
    @Final
    private Setting<Double> radius;

    @Shadow
    @Final
    private Setting<Integer> blocksPerTick;

    @Unique
    private Setting<Boolean> onlyLegalAim;

    @Unique
    private Setting<LegalRotation.Mode> legalRotation;

    @Unique
    private Setting<Integer> legalRotationPriority;

    @Unique
    private Setting<ScaffoldRangeMode> rangeMode;

    @Unique
    private Setting<Boolean> allowAirPlace;

    /** onTick 里读 air-place 的替身：那几处要的是「范围模式」而不是设置值 */
    @Unique
    private Setting<Boolean> stubRange;

    /** 同上，最近可放搜索那处取反后要的是「单个且不空放」，所以这里给「范围或空放」 */
    @Unique
    private Setting<Boolean> stubRangeOrAir;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        Scaffold self = (Scaffold) (Object) this;

        rangeMode = new EnumSetting.Builder<ScaffoldRangeMode>()
            .name("范围模式")
            .description("单个:只放目标一块。范围:按半径一圈每 tick 放多块")
            .defaultValue(ScaffoldRangeMode.SINGLE)
            .build();

        allowAirPlace = new BoolSetting.Builder()
            .name("空中放置")
            .description("没有支撑面时允许点空气空放。关闭:必须有支撑面才放")
            .defaultValue(false)
            .build();

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

        stubRange = new ComputedBoolSetting("范围模式", () -> rangeMode.get() == ScaffoldRangeMode.RANGE);
        stubRangeOrAir = new ComputedBoolSetting("范围模式或空放",
            () -> rangeMode.get() == ScaffoldRangeMode.RANGE || allowAirPlace.get());

        // 撤掉官方 air-place：一个开关背了「空放」和「范围」两件事，拆成上面两个设置
        List<Setting<?>> general = ((SettingGroupAccessor) (Object) self.settings.getDefaultGroup()).getSettings();
        general.removeIf(setting -> setting.name.equals("air-place"));

        insertAfter(self.settings.getDefaultGroup(), "rotate", rangeMode);
        insertAfter(self.settings.getDefaultGroup(), "范围模式", allowAirPlace);
        insertAfter(self.settings.getDefaultGroup(), "空中放置", onlyLegalAim);
        insertAfter(self.settings.getDefaultGroup(), "仅合法朝向", legalRotation);
        insertAfter(self.settings.getDefaultGroup(), "合法转头", legalRotationPriority);

        // 提前量 / 最近可放只给「单个」用，半径 / 每 tick 块数只给「范围」用（官方按 air-place 显隐）
        setVisible(aheadDistance, () -> rangeMode.get() == ScaffoldRangeMode.SINGLE);
        setVisible(placeRange, () -> rangeMode.get() == ScaffoldRangeMode.SINGLE);
        setVisible(radius, () -> rangeMode.get() == ScaffoldRangeMode.RANGE);
        setVisible(blocksPerTick, () -> rangeMode.get() == ScaffoldRangeMode.RANGE);
    }

    /**
     * onTick 里第一处读 air-place（挑目标位置那里的分支）：这里要的是「范围模式」。
     *
     * <p>范围：目标就是脚下那一块（半径循环围着它展开）。单个：照旧按位置 + 提前量挑
     */
    @Redirect(
        method = "onTick(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V",
        at = @At(
            value = "FIELD",
            target = "Lmeteordevelopment/meteorclient/systems/modules/movement/Scaffold;airPlace:Lmeteordevelopment/meteorclient/settings/Setting;",
            opcode = Opcodes.GETFIELD,
            ordinal = 0
        )
    )
    private Setting<Boolean> redirectAirPlaceTarget(Scaffold self) {
        return stubRange;
    }

    /**
     * 第二处（「最近可放」搜索那里的取反条件）：取反后要的是「单个且不空放」——
     * 范围模式不搜（半径循环自己会跳过放不了的格子），空放开着也不搜（没支撑就点空气）
     */
    @Redirect(
        method = "onTick(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V",
        at = @At(
            value = "FIELD",
            target = "Lmeteordevelopment/meteorclient/systems/modules/movement/Scaffold;airPlace:Lmeteordevelopment/meteorclient/settings/Setting;",
            opcode = Opcodes.GETFIELD,
            ordinal = 1
        )
    )
    private Setting<Boolean> redirectAirPlaceFallbackScan(Scaffold self) {
        return stubRangeOrAir;
    }

    /** 第三处（半径循环还是只放一块的分支）：和第一处一样，要「范围模式」 */
    @Redirect(
        method = "onTick(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V",
        at = @At(
            value = "FIELD",
            target = "Lmeteordevelopment/meteorclient/systems/modules/movement/Scaffold;airPlace:Lmeteordevelopment/meteorclient/settings/Setting;",
            opcode = Opcodes.GETFIELD,
            ordinal = 2
        )
    )
    private Setting<Boolean> redirectAirPlaceRadiusLoop(Scaffold self) {
        return stubRange;
    }

    @Redirect(
        method = "place(Lnet/minecraft/core/BlockPos;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;place(Lnet/minecraft/core/BlockPos;Lmeteordevelopment/meteorclient/utils/player/FindItemResult;ZIZZ)Z"
        )
    )
    private boolean redirectPlace(BlockPos blockPos, FindItemResult item, boolean rotate, int rotationPriority, boolean swingHand, boolean checkEntities) {
        boolean legal = rotate && onlyLegalAim.get();

        // 空中放置关着时不空放：没支撑面的格子不放（原版会直接点空气），范围模式下换下一格
        if (!legal && !allowAirPlace.get() && BlockUtils.getPlaceSide(blockPos) == null) return false;

        // 没开「旋转」或没开「仅合法朝向」：完全交给原版（meteor 的放置 + 发包旋转）
        if (!legal) {
            return BlockUtils.place(blockPos, item, rotate, rotationPriority, swingHand, checkEntities);
        }

        // 这一格放不下就不放（人站在地上时原版每 tick 都会走到这里）
        ItemStack stack = mc.player.getInventory().getItem(item.slot());
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        if (!BlockUtils.canPlaceBlock(blockPos, checkEntities, blockItem.getBlock())) return false;

        LegalPlace.Aim aim = LegalPlace.compute(blockPos);
        if (aim == null) {
            // 六个面都没有支撑：开着空中放置就点空气自己，关着就不放
            return allowAirPlace.get() && airPlaceLegally(blockPos, item, swingHand);
        }

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

    /** 空放（合法角度版）：算得出角度才放，转头规则和支撑面那套一样 */
    @Unique
    private boolean airPlaceLegally(BlockPos blockPos, FindItemResult item, boolean swingHand) {
        if (mc.player == null || mc.level == null) return false;

        LegalPlace.AirAim aim = LegalPlace.computeAir(blockPos);
        if (aim == null) return false;

        if (aim.rotated()) {
            if (legalRotation.get() == LegalRotation.Mode.OFF) return false;
            if (!LegalRotation.rotate(aim.yaw(), aim.pitch(), legalRotation.get(), legalRotationPriority.get())) return false;
        }

        LegalRotation.runAfterSend(() -> placeAirLegit(item, blockPos, aim, swingHand));
        return true;
    }

    @Unique
    private void placeLegit(FindItemResult item, LegalPlace.Aim aim, boolean swingHand) {
        // 点算好的那个面：角度是照着它算的
        interactLegit(item, new BlockHitResult(
            aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock(), false
        ), swingHand);
    }

    @Unique
    private void placeAirLegit(FindItemResult item, BlockPos blockPos, LegalPlace.AirAim aim, boolean swingHand) {
        // 空放：点目标方块自己朝眼睛的那一面
        interactLegit(item, new BlockHitResult(aim.hitPos(), aim.face(), blockPos, false), swingHand);
    }

    /** 切到方块那格点一下，再切回去 */
    @Unique
    private void interactLegit(FindItemResult item, BlockHitResult hit, boolean swingHand) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        boolean offhand = item.isOffhand();
        boolean swap = !offhand && !item.isMainHand();
        if (swap) InvUtils.swap(item.slot(), true);

        BlockUtils.interact(hit, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, swingHand);

        if (swap) InvUtils.swapBack();
    }

    /** 插到指定设置下面（Meteor 只能末尾追加，得自己按位置插） */
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

    /** 换官方设置的显示条件（字段是 final 的，官方没给事后换的入口） */
    @Unique
    private static void setVisible(Setting<?> setting, IVisible visible) {
        ((SettingAccessor) (Object) setting).setVisible(visible);
    }
}
