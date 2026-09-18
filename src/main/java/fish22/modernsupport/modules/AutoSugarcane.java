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

import meteordevelopment.meteorclient.events.entity.player.BlockBreakingCooldownEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 自动收甘蔗（AutoSugarcane）— 由 Meteor 官方「核爆」(Nuker) 简化的自动收割模块
 *
 * <p>只保留核爆的核心流程：
 * 1. 每 tick 用 {@link BlockIterator} 扫描玩家周围一定范围内的方块（球体范围，等同核爆的 Sphere 形状）
 * 2. 按「最近的优先」挑出目标方块，用 {@link BlockUtils#breakBlock} 正常挖掘（等同核爆的 Legit mine 模式）
 * 3. 延迟为 0 时一 tick 内可以破坏多个方块（秒破的甘蔗/竹子一次性全收，上限由「每tick破坏数量」决定）；
 *    延迟大于 0 时按「延迟」设置等待若干个 tick 再挖下一个
 *
 * <p>与原版核爆的区别（按需求砍掉的功能）：
 * 形状 / 模式（全部、平地、秒破）、方块黑名单白名单与方块选择快捷键、穿墙距离、
 * 排序模式、发包挖掘、仅合适工具、交互模式、包围盒渲染等全部去掉。
 * 方块选择简化为「甘蔗 / 竹子」两个复选框（可同时选中），并且只收**下方还是同一种方块**的那部分，
 * 也就是甘蔗/竹子最下面那一节留着不动，让它继续往上长。
 *
 * <p>挖掘面（发给服务端的 face）是自己算的，不沿用 Meteor 的 {@link BlockUtils#getDirection}：
 * 那个方法是按方块的**碰撞箱**猜面的，甘蔗/竹子没有碰撞箱，站在平地上挖自己头顶那一格时会猜出一个
 * 从眼睛位置根本看不到的面。GrimAC 的 PositionBreakA（"Tried to break a block face from an
 * impossible eye position"）遇到这种包会直接取消，表现就是「平地站着挖不动，跳一下才好用」
 * （跳起来时眼睛进到方块里，Grim 对「人已经在方块里」的情况直接放行）。
 */
public class AutoSugarcane extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("渲染");

    // ==================== 收割 ====================

    public final Setting<Boolean> sugarcane = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("甘蔗")
                .description("自动收割甘蔗")
                .defaultValue(true)
                .build()
        );

    public final Setting<Boolean> bamboo = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("竹子")
                .description("自动收割竹子")
                .defaultValue(true)
                .build()
        );

    public final Setting<Double> range = sgGeneral
        .add(
            new DoubleSetting.Builder()
                .name("范围")
                .description("以玩家为中心，最多收割多远的方块")
                .defaultValue(4)
                .min(1)
                .sliderRange(1, 6)
                .build()
        );

    public final Setting<Integer> delay = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("延迟")
                .description("每挖掉一个方块后等待多少 tick 再挖下一个（0 = 不等，一 tick 挖多个）")
                .defaultValue(0)
                .min(0)
                .sliderRange(0, 10)
                .build()
        );

    public final Setting<Integer> maxBlocksPerTick = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("每tick破坏数量")
                .description("延迟为 0 时，一个 tick 内最多破坏多少个方块（甘蔗/竹子是秒破方块，可以一次收很多）")
                .defaultValue(10)
                .range(1, 100)
                .sliderRange(1, 100)
                .visible(() -> delay.get() == 0)
                .build()
        );

    public final Setting<Boolean> rotate = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("旋转")
                .description("挖掘时把视角转向目标方块（发包旋转）")
                .defaultValue(true)
                .build()
        );

    public final Setting<Boolean> swing = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("挥手")
                .description("挖掘时挥手，其他玩家能看到动作")
                .defaultValue(true)
                .build()
        );

    // ==================== 渲染 ====================

    private final Setting<Boolean> render = sgRender
        .add(
            new BoolSetting.Builder()
                .name("显示渲染")
                .description("是否给正在收割的方块画框")
                .defaultValue(true)
                .build()
        );
    private final Setting<ShapeMode> shapeMode = sgRender
        .add(
            new EnumSetting.Builder<ShapeMode>()
                .name("形状")
                .description("渲染形状的显示方式")
                .defaultValue(ShapeMode.Both)
                .visible(render::get)
                .build()
        );
    private final Setting<SettingColor> sideColor = sgRender
        .add(
            new ColorSetting.Builder()
                .name("侧面颜色")
                .description("正在收割方块侧面的颜色")
                .defaultValue(new SettingColor(255, 0, 0, 80))
                .visible(render::get)
                .build()
        );
    private final Setting<SettingColor> lineColor = sgRender
        .add(
            new ColorSetting.Builder()
                .name("边框颜色")
                .description("正在收割方块边框的颜色")
                .defaultValue(new SettingColor(255, 0, 0, 255))
                .visible(render::get)
                .build()
        );

    // ==================== 内部状态 ====================

    /** 本 tick 扫描出来的目标方块（每 tick 重建） */
    private final List<BlockPos> blocks = new ArrayList<>();
    /** 收割冷却剩余 tick（>0 时本 tick 不动手） */
    private int timer;
    /** 上一 tick 是否在挖（用来在停下来时收尾，和 Meteor 的 BlockUtils 一样） */
    private boolean breaking;
    /** 本 tick 是否挖了方块 */
    private boolean breakingThisTick;

    public AutoSugarcane() {
        super(Categories.World, "自动收甘蔗", "自动收割身边的甘蔗/竹子，只收下面还是同一种方块的那部分（最下面一节留着继续长）");
    }

    @Override
    public void onActivate() {
        blocks.clear();
        timer = 0;
        breaking = false;
        breakingThisTick = false;
    }

    @Override
    public void onDeactivate() {
        blocks.clear();
        timer = 0;

        // 停下来时把「正在挖掘」的状态收尾，不然原版会以为还在挖
        if (breaking && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        breaking = false;
        breakingThisTick = false;
    }

    // ==================== 核心 Tick 处理 ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        breakingThisTick = false;

        // 收割冷却：还没到下一个方块的收割时间
        if (timer > 0) {
            timer--;
            return;
        }

        double pX = mc.player.getX(), pY = mc.player.getY(), pZ = mc.player.getZ();
        double rangeSq = range.get() * range.get();
        int radius = (int) Math.ceil(range.get());
        blocks.clear();

        // 找方块：BlockIterator 会在本 tick 稍后遍历，回调里判断方块是否符合条件
        BlockIterator.register(radius, radius, (blockPos, blockState) -> {
            // 只找勾选了的方块（甘蔗 / 竹子可以同时选）
            Block block = blockState.getBlock();
            boolean isSugarcane = block == Blocks.SUGAR_CANE && sugarcane.get();
            boolean isBamboo = block == Blocks.BAMBOO && bamboo.get();
            if (!isSugarcane && !isBamboo) return;

            // 只收下面还是同一种方块的那部分：最下面那一节留着继续长
            BlockPos pos = blockPos.immutable();
            if (mc.level.getBlockState(pos.below()).getBlock() != block) return;

            // 球体范围判定（和核爆的 Sphere 形状一致）
            if (Utils.squaredDistance(pX, pY, pZ, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > rangeSq) return;

            // 方块本身要能挖（硬度 -1 等挖不动的方块排除）
            if (!BlockUtils.canBreak(pos, blockState)) return;

            blocks.add(pos);
        });

        // 破坏方块：BlockIterator 遍历完之后回到这里
        BlockIterator.after(() -> {
            if (blocks.isEmpty()) return;

            // 最近的优先
            blocks.sort(Comparator.comparingDouble(
                pos -> Utils.squaredDistance(pX, pY, pZ, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));

            // 延迟大于 0：一次只挖一个，然后等延迟
            if (delay.get() > 0) {
                breakBlock(blocks.getFirst());
                timer = delay.get();
                return;
            }

            // 延迟为 0：一 tick 内破坏多个方块
            int count = 0;

            for (BlockPos block : blocks) {
                if (count >= maxBlocksPerTick.get()) break;

                boolean canInstaMine = BlockUtils.canInstaBreak(block);

                breakBlock(block);
                count++;

                // 不能秒破的方块一次只能碰一个：挖它要连续几个 tick 在同一个方块上累积进度，
                // 一 tick 内换方块会让服务端重新开始算（和核爆的非发包模式一样）
                if (!canInstaMine) break;
            }
        });
    }

    /** 本 tick 没挖方块、上一 tick 在挖 → 收尾（对应原版停止挖掘的时机） */
    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (breaking && !breakingThisTick) {
            breaking = false;
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        }
    }

    /**
     * 取消原版「挖掉一个方块后要等 5 tick 才能挖下一个」的限制（核爆也是这么干的）。
     * <p>
     * 不取消的话，「每tick破坏数量」设多少都没用：原版 {@code MultiPlayerGameMode} 里那个
     * destroyDelay 会把后续的挖掘全部挡掉，实际只能 ~6 tick 一个方块。
     */
    @EventHandler
    private void onBlockBreakingCooldown(BlockBreakingCooldownEvent event) {
        event.cooldown = 0;
    }

    /** 挖掉一个方块（等同核爆的非发包模式：正常挖掘 + 可选转头 + 可选挥手） */
    private void breakBlock(BlockPos blockPos) {
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(blockPos), Rotations.getPitch(blockPos), () -> mine(blockPos));
        } else {
            mine(blockPos);
        }

        if (render.get()) {
            RenderUtils.renderTickingBlock(blockPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0, 8, true, false);
        }
    }

    /**
     * 走原版挖掘流程（和 {@link BlockUtils#breakBlock} 同一套调用），区别只在挖掘面用自己算的
     */
    private void mine(BlockPos blockPos) {
        Direction direction = getBreakDirection(blockPos);

        if (mc.gameMode.isDestroying()) mc.gameMode.continueDestroyBlock(blockPos, direction);
        else mc.gameMode.startDestroyBlock(blockPos, direction);

        if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        else mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));

        breaking = true;
        breakingThisTick = true;
    }

    /**
     * 算出应该报给服务端的挖掘面：从眼睛朝方块中心打一条射线，取射线「进入方块」的那一个面。
     * <p>
     * 这个面必须是眼睛真正能看到的面（比如挖头顶上方的方块要报底面），否则 Grim 的 PositionBreakA
     * 会判定玩家不可能从那个角度挖这一面，直接把包取消掉。
     */
    private Direction getBreakDirection(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();

        double dx = pos.getX() + 0.5 - eye.x();
        double dy = pos.getY() + 0.5 - eye.y();
        double dz = pos.getZ() + 0.5 - eye.z();

        // 每个轴各算一个「射线进入方块」的参数，参数最大的那个轴就是真正的进入面
        double tx = entryT(eye.x(), dx, pos.getX());
        double ty = entryT(eye.y(), dy, pos.getY());
        double tz = entryT(eye.z(), dz, pos.getZ());

        if (tx >= ty && tx >= tz) return dx > 0 ? Direction.WEST : Direction.EAST;
        if (ty >= tx && ty >= tz) return dy > 0 ? Direction.DOWN : Direction.UP;
        return dz > 0 ? Direction.NORTH : Direction.SOUTH;
    }

    /**
     * 射线在某个轴上进入方块的参数 t。
     * <p>
     * 眼睛已经落在方块这个轴的范围内（t &lt; 0）或者射线和这个轴平行时返回 -∞，
     * 表示这个轴不决定进入面，由别的轴决定。
     */
    private double entryT(double eyeCoord, double dir, int blockMin) {
        if (dir == 0) return Double.NEGATIVE_INFINITY;

        double t = ((dir > 0 ? blockMin : blockMin + 1) - eyeCoord) / dir;
        return t < 0 ? Double.NEGATIVE_INFINITY : t;
    }
}
