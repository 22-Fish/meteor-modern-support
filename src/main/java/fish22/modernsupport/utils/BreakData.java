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

package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 原版那一套挖掘进度公式（26.1 {@code BlockState.getDestroyProgress} + {@code Player.getDestroySpeed}）
 *
 * <p>服务端判定用的是这套公式，本类是把它单独拎出来，好让客户端也能「按服务端的方式」算：
 *
 * <ul>
 *   <li>{@link #perTick}：单 tick 进度 = 工具挖掘速度 ÷ 方块硬度 ÷ 30|100</li>
 *   <li>{@link #stopProgress}：服务端收到结束包时算的进度 = 单 tick 进度 × (开始包之后过了多少 tick + 1)</li>
 *   <li>{@link #ticksToBreak}：挖穿要多少 tick</li>
 * </ul>
 *
 * <p>工具是直接传 {@link ItemStack} 的（不是槽位号），所以能拿「还没换到手上、但马上会换上去」的那把去算，
 * 收尾时机会准很多；硬度带位置参数，少数按位置改硬度的方块也算得对
 */
public final class BreakData {
    private BreakData() {
    }

    /** 服务端收到结束包时「进度到这么多就当场破坏」，不到就退回延迟破坏槽位 */
    public static final double STOP_INSTANT = 0.7;

    /** 单 tick 进度：工具挖掘速度 ÷ 硬度 ÷ (能收获 30 | 不能收获 100) */
    public static double perTick(BlockState state, BlockPos pos, ItemStack tool, boolean onGround) {
        if (state == null || tool == null) return 0.0;

        double hardness = hardness(state, pos);
        if (hardness <= 0.0) return 0.0;

        double divisor = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state) ? 30.0 : 100.0;
        return speed(tool, state, onGround) / hardness / divisor;
    }

    /** 服务端收到结束包时算的进度：单 tick 进度 × (开始包之后过了多少 tick + 1) */
    public static double stopProgress(BlockState state, BlockPos pos, ItemStack tool, int elapsedTicks, boolean onGround) {
        return perTick(state, pos, tool, onGround) * (Math.max(0, elapsedTicks) + 1);
    }

    /** 用这把工具挖这个方块要多少 tick（<= 0 或无限 = 挖不动） */
    public static double ticksToBreak(BlockState state, BlockPos pos, ItemStack tool, boolean onGround) {
        double delta = perTick(state, pos, tool, onGround);
        return delta <= 0.0 ? Double.MAX_VALUE : 1.0 / delta;
    }

    /** 方块硬度（-1 = 挖不动，0 = 秒挖） */
    public static double hardness(BlockState state, BlockPos pos) {
        if (state == null) return -1.0;

        BlockGetter level = mc.level;
        return level != null && pos != null ? state.getDestroySpeed(level, pos) : state.getDestroySpeed(null, null);
    }

    /**
     * 工具挖掘速度：物品自己的速度 + 效率附魔，再乘急迫 / 挖掘疲劳 / 水里 / 空中这些玩家状态
     * <p>
     * 对照原版 {@code Player.getDestroySpeed}，和 Meteor 的 {@code BlockUtils} 用的是同一套
     */
    public static double speed(ItemStack tool, BlockState state, boolean onGround) {
        double speed = tool.getDestroySpeed(state);

        if (speed > 1.0) {
            int efficiency = Utils.getEnchantmentLevel(tool, Enchantments.EFFICIENCY);
            if (efficiency > 0 && !tool.isEmpty()) speed += efficiency * efficiency + 1;
        }

        if (mc.player == null) return speed;

        if (MobEffectUtil.hasDigSpeed(mc.player)) {
            speed *= 1 + (MobEffectUtil.getDigSpeedAmplification(mc.player) + 1) * 0.2F;
        }

        if (mc.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            float k = switch (mc.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };

            speed *= k;
        }

        if (mc.player.isEyeInFluid(FluidTags.WATER)) {
            speed *= mc.player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
        }

        if (!onGround) speed /= 5.0;
        return speed;
    }
}
