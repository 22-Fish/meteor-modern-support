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

package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 挖脚 — 自动挖掉目标脚部/头部的方块（完全替代 meteor 原版挖脚）
 *
 * <p>本模块只挑位置，挖掘本身交给「发包挖掘」：挑出来的位置直接塞进它的两个挖掘位，
 * 挖掘延迟、切工具、收尾、双挖都由它那套逻辑走，所以模块必须和「发包挖掘」一起开。
 *
 * <p>挑位置的顺序（脚部水平 → 头部水平 → 顶部）：
 * <ol>
 *   <li>脚中（目标脚部碰撞箱中心那一格，也就是人卡在方块里的那格）最优先</li>
 *   <li>脚旁：脚部水平四面都有方块才算需要挖（这时他才算被围住），挖完在脚部水平留重挖框方便塞水晶</li>
 *   <li>脚部水平没得挖 → 头部：第一挖挖头里面、第二挖挖头旁边，重挖框留旁边</li>
 *   <li>都没得挖 → 顶部（头顶那一格）</li>
 *   <li>「挖脚底」开着时额外挖脚底下的方块</li>
 * </ol>
 *
 * <p>只有水平位置（脚旁 / 头旁）留重挖框；脚部水平和头部水平都有框、而且人没卡在这两层的方块里时，
 * 说明已经能塞水晶炸了，本模块就停下来不挖
 */
public class AutoCity extends Module {
    /** meteor 原版挖脚的名字（要屏蔽掉，它和本模块会互相抢挖掘） */
    private static final String METEOR_AUTO_CITY = "auto-city";

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final double EPSILON = 1.0E-3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public Setting<Double> targetRange = sgGeneral
        .add(
            new DoubleSetting.Builder()
                .name("目标距离")
                .description("多远范围内的玩家会被当成目标")
                .defaultValue(5.5)
                .min(0)
                .sliderRange(0, 8)
                .build()
        );

    public Setting<Boolean> mineBottom = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("挖脚底")
                .description("开启后才会挖目标脚底下的方块")
                .defaultValue(false)
                .build()
        );

    private Player target;

    public AutoCity() {
        super(Categories.Combat, "挖脚", "自动挖掉目标脚部/头部的方块（要和「发包挖掘」一起开）");
    }

    @Override
    public void onActivate() {
        target = null;
        if (!ghostMineOn()) {
            error("需要先开启「发包挖掘」");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        GhostMine ghostMine = GhostMine.getInstance();
        if (ghostMine == null || !ghostMine.isActive()) {
            error("「发包挖掘」关了，挖脚一起关");
            toggle();
            return;
        }

        // meteor 原版挖脚：本模块完全替代它，发现它开着就直接关掉（配置加载后重新激活也在这里兜住）
        Module oldAutoCity = Modules.get().get(METEOR_AUTO_CITY);
        if (oldAutoCity != null && oldAutoCity.isActive()) oldAutoCity.toggle();

        target = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.ClosestAngle);
        if (TargetUtils.isBadTarget(target, targetRange.get())) {
            target = null;
            return;
        }

        for (Wanted wanted : wantedPositions(target, ghostMine)) {
            queue(ghostMine, wanted);
        }
    }

    private boolean ghostMineOn() {
        GhostMine ghostMine = GhostMine.getInstance();
        return ghostMine != null && ghostMine.isActive();
    }

    // ==================== 挑位置 ====================

    /**
     * 这一 tick 想挖的位置，按优先级从前到后排
     * <p>位置一律按目标的碰撞箱算：脚部水平 = 脚那一层，头部水平 = 头顶那一层（趴着时和脚同一层）
     */
    private List<Wanted> wantedPositions(Player target, GhostMine ghostMine) {
        List<Wanted> wanted = new ArrayList<>(6);

        AABB box = target.getBoundingBox();
        BlockPos feet = target.blockPosition();
        BlockPos head = BlockPos.containing(target.getX(), box.maxY - EPSILON, target.getZ());
        boolean crawling = head.equals(feet);

        // 停止挖脚：重挖框落在脚部水平或头部水平，而且这一层目标碰撞箱中心没卡着方块 → 已经能塞水晶炸
        BlockPos rebreakPos = ghostMine.getRebreakPos();
        if (rebreakPos != null
            && (rebreakPos.getY() == feet.getY() || rebreakPos.getY() == head.getY())
            && !isCenterStuckAtLevel(target, rebreakPos.getY())) {
            return wanted;
        }

        // 脚部水平：脚中 = 目标脚部碰撞箱中心那格（卡身），脚旁 = 贴着脚的那四面
        boolean feetCenter = mineable(feet, ghostMine);
        List<BlockPos> feetSides = new ArrayList<>(4);
        boolean feetAllSides = true;
        for (Direction direction : HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            feetSides.add(side);
            if (!mineable(side, ghostMine)) feetAllSides = false;
        }
        feetSides.sort(Comparator.comparingDouble(PlayerUtils::distanceTo));

        if (feetCenter || feetAllSides) {
            // 1. 脚中最优先
            if (feetCenter) wanted.add(new Wanted(feet, false));
            // 2. 脚旁：四面都有方块才挖（离得近的先挖）
            if (feetAllSides) {
                for (BlockPos side : feetSides) wanted.add(new Wanted(side, true));
            }
        } else if (!crawling && mineable(head, ghostMine)) {
            // 3. 脚部水平挖不动（基岩/空气）→ 头部：第一挖挖头里面、第二挖挖头旁边，重挖框留旁边
            wanted.add(new Wanted(head, false));
            BlockPos headSide = nearestMineable(head, ghostMine);
            if (headSide != null) wanted.add(new Wanted(headSide, true));
        } else if (mineable(head.above(), ghostMine)) {
            // 4. 都没有 → 顶部
            wanted.add(new Wanted(head.above(), false));
        }

        // 挖脚底：开关开着才挖
        if (mineBottom.get() && mineable(feet.below(), ghostMine)) {
            wanted.add(new Wanted(feet.below(), false));
        }

        return wanted;
    }

    /** 中心那一圈离得最近、能挖的一格（没有返回 null） */
    private BlockPos nearestMineable(BlockPos center, GhostMine ghostMine) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : HORIZONTAL) {
            BlockPos pos = center.relative(direction);
            if (!mineable(pos, ghostMine)) continue;
            double distance = PlayerUtils.distanceTo(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    /** 这个位置能不能挖：不是空气/流体、不是基岩这种挖不动的、挖得动、而且在「发包挖掘」的挖掘范围内 */
    private boolean mineable(BlockPos pos, GhostMine ghostMine) {
        if (pos == null || mc.level == null) return false;

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (GhostMine.unbreakableBlocks.contains(state.getBlock())) return false;
        if (state.getDestroySpeed(mc.level, pos) < 0) return false;

        return PlayerUtils.distanceTo(pos) <= ghostMine.range.get();
    }

    /** 目标在这一层的碰撞箱中心有没有卡着方块（卡着就说明重挖框那格随时会被他重新填上） */
    private boolean isCenterStuckAtLevel(Player target, int y) {
        AABB box = target.getBoundingBox();
        if (box.minY > y + 1.0 || box.maxY < y) return false;

        BlockPos center = BlockPos.containing((box.minX + box.maxX) / 2.0, y + 0.5, (box.minZ + box.maxZ) / 2.0);
        BlockState state = mc.level.getBlockState(center);
        if (state.isAir()) return false;
        return !state.getCollisionShape(mc.level, center).isEmpty();
    }

    // ==================== 塞给发包挖掘 ====================

    /**
     * 把一个位置放进「发包挖掘」的挖掘位
     * <p>它只有两个位（主挖 + 副挖），挖掘延迟、什么时候发开始包、切工具收尾都是它自己在 tick 里管，
     * 所以这里只管占位：位满了（已经在挖两个）就不加，加过的位置不重复加
     */
    private void queue(GhostMine ghostMine, Wanted wanted) {
        BlockPos pos = wanted.pos();
        if (pos == null) return;
        if (isQueued(GhostMine.firstBlockDate, pos) || isQueued(GhostMine.secondBlockDate, pos)) return;
        // 这个位置已经有重挖框了：方块再出现由重挖框自己补结束包，不用再排一次
        if (ghostMine.hasRebreakFrame(pos)) return;

        if (GhostMine.firstBlockDate == null) {
            GhostMine.firstBlockDate = ghostMine.getBlockDate(pos, BlockUtils.getDirection(pos), wanted.rebreak());
            return;
        }

        if (ghostMine.doubleBreak.get() && GhostMine.secondBlockDate == null) {
            GhostMine.secondBlockDate = ghostMine.getBlockDate(pos, BlockUtils.getDirection(pos), wanted.rebreak());
        }
    }

    private boolean isQueued(GhostMine.BlockDate block, BlockPos pos) {
        return block != null && block.pos.equals(pos);
    }

    /** 一个想挖的位置：{@code rebreak} = 挖掉之后要不要在这个位置留重挖框 */
    private record Wanted(BlockPos pos, boolean rebreak) {}
}
