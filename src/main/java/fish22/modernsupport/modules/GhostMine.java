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

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 发包挖掘（GhostMine）— 从 meteor-miku 移植（26.1 Mojmap 适配）
 *
 * <p>核心机制：
 * 1. 进度模拟：使用 BlockUtils.getBreakDelta 真实模拟原版挖掘时间
 * 2. 发包顺序：START → 等待进度 → STOP（不再发送多余的 STOP/ABORT）
 * 3. 双挖：副挖只发 START，完成时客户端清除不发 STOP
 * 4. 绕过技术：高空包、滞空挖掘微调、sequenced packet（startPrediction）
 * 5. 工具切换：在进度达到阈值时自动切换最佳工具
 * 6. 失败计数：连续失败达到阈值自动放弃
 */
public class GhostMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    public static GhostMine INSTANCE;

    // ==================== 基本设置 ====================

    public Setting<Integer> range = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("挖掘范围")
                .description("设置挖掘方块的最大距离")
                .sliderRange(1, 6)
                .defaultValue(6)
                .build()
        );
    public Setting<Double> speed = sgGeneral
        .add(
            new DoubleSetting.Builder()
                .name("挖掘速度")
                .description("控制挖掘方块的速度倍率")
                .sliderRange(0.65, 1.0)
                .defaultValue(0.85)
                .build()
        );
    public Setting<Boolean> doubleBreak = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("双挖")
                .description("同时挖掘两个方块以提高效率")
                .defaultValue(true)
                .build()
        );
    public Setting<Boolean> rebreak = sgGeneral
        .add(new BoolSetting.Builder().name("自动重挖").description("自动重新挖掘已破坏的方块").defaultValue(true).build());
    public Setting<Integer> rebreakDelay = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("重挖延迟")
                .description("重新挖掘方块前的延迟时间(tick)")
                .sliderRange(0, 10)
                .defaultValue(0)
                .visible(rebreak::get)
                .build()
        );
    public Setting<SwapMode> swapModeSetting = sgGeneral
        .add(new EnumSetting.Builder<SwapMode>()
            .name("切换模式")
            .description("选择工具切换的方式")
            .defaultValue(SwapMode.NORMAL)
            .visible(() -> false)
            .build()
        );

    // ==================== 绕过设置 ====================

    public Setting<Boolean> fastBypass = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("高空包绕过")
                .description("发送 START 时额外向高空发送一个相同的包")
                .defaultValue(true)
                .build()
        );
    public Setting<Boolean> bypassGround = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("滞空挖掘绕过")
                .description("在发送 STOP 前微调 Y 坐标")
                .defaultValue(false)
                .build()
        );
    public Setting<Boolean> swingHand = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("挥手")
                .description("挖掘时是否挥手")
                .defaultValue(true)
                .build()
        );

    // ==================== 工具切换设置 ====================

    public Setting<Integer> switchDamage = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("切换工具阈值")
                .description("挖掘进度达到此百分比时切换最佳工具")
                .defaultValue(95)
                .min(0)
                .sliderMax(100)
                .build()
        );
    public Setting<Boolean> switchBack = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("切回工具")
                .description("切换工具后自动切回原来的槽位")
                .defaultValue(true)
                .build()
        );
    public Setting<Integer> switchBackDelay = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("切回延迟")
                .description("切换工具后延迟多少 tick 再切回原来的槽位")
                .defaultValue(1)
                .sliderRange(1, 10)
                .visible(switchBack::get)
                .build()
        );

    // ==================== 失败保护设置 ====================

    public Setting<Integer> maxBreaks = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("最大尝试次数")
                .description("连续破坏失败达到此次数时放弃挖掘")
                .defaultValue(6)
                .min(1)
                .sliderMax(20)
                .build()
        );

    // ==================== 秒挖设置 ====================

    public Setting<Boolean> instantMine = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("秒挖")
                .description("完成后持续发送 STOP 触发服务端破坏")
                .defaultValue(false)
                .build()
        );
    public Setting<Integer> instantDelay = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("秒挖间隔")
                .description("秒挖模式下 STOP 包的发送间隔(ms)")
                .defaultValue(50)
                .min(0)
                .sliderMax(500)
                .visible(instantMine::get)
                .build()
        );

    // ==================== 渲染设置 ====================

    private final SettingGroup sgRender = settings.createGroup("渲染设置");
    private final Setting<Boolean> render = sgRender
        .add(
            new BoolSetting.Builder()
                .name("显示渲染")
                .description("是否显示正在挖掘方块的可视化效果")
                .defaultValue(true)
                .build()
        );

    private final Setting<ShapeMode> shapeMode = sgRender
        .add(new EnumSetting.Builder<ShapeMode>()
            .name("形状")
            .description("选择渲染形状的显示方式")
            .defaultValue(ShapeMode.Both)
            .build()
        );
    private final Setting<SettingColor> readySideColor = sgRender
        .add(
            new ColorSetting.Builder()
                .name("完成侧面颜色")
                .description("方块挖掘完成时侧面的颜色")
                .defaultValue(new SettingColor(255, 192, 203, 80))
                .build()
        );
    private final Setting<SettingColor> readyLineColor = sgRender
        .add(
            new ColorSetting.Builder()
                .name("完成边框颜色")
                .description("方块挖掘完成时边框的颜色")
                .defaultValue(new SettingColor(255, 192, 203, 255))
                .build()
        );
    private final Setting<SettingColor> sideColor = sgRender
        .add(
            new ColorSetting.Builder()
                .name("侧面颜色")
                .description("正在挖掘方块侧面的颜色")
                .defaultValue(new SettingColor(255, 192, 203, 80))
                .build()
        );
    private final Setting<SettingColor> lineColor = sgRender
        .add(
            new ColorSetting.Builder()
                .name("边框颜色")
                .description("正在挖掘方块边框的颜色")
                .defaultValue(new SettingColor(255, 192, 203, 255))
                .build()
        );

    // ==================== 内部状态 ====================

    private final List<BlockPos> breakBlocks = new ArrayList<>();
    public static BlockDate firstBlockDate = null;
    public static BlockDate secondBlockDate = null;
    private BlockDate rebreakBlockDate = null;
    public static BlockDate tempBlockDate = null;
    private int rebreakTicks = 0;
    private int breakAttempts = 0;
    private boolean hasSwitch = false;
    private int switchTicks = 0;
    private long lastInstantTime = 0;

    public static final List<Block> unbreakableBlocks = Arrays.asList(
        Blocks.COMMAND_BLOCK,
        Blocks.LAVA_CAULDRON,
        Blocks.LAVA,
        Blocks.WATER_CAULDRON,
        Blocks.WATER,
        Blocks.BEDROCK,
        Blocks.BARRIER,
        Blocks.END_PORTAL,
        Blocks.NETHER_PORTAL,
        Blocks.END_PORTAL_FRAME
    );

    // ==================== 构造与生命周期 ====================

    public GhostMine() {
        super(Categories.World, "发包挖掘", "使用发包快速挖掘方块");
        INSTANCE = this;
    }

    public static GhostMine getInstance() {
        return INSTANCE;
    }

    @Override
    public void onActivate() {
        super.onActivate();
        firstBlockDate = null;
        secondBlockDate = null;
        rebreakBlockDate = null;
        tempBlockDate = null;
        rebreakTicks = 0;
        breakAttempts = 0;
        hasSwitch = false;
        switchTicks = 0;
        lastInstantTime = 0;
    }

    @Override
    public void onDeactivate() {
        firstBlockDate = null;
        secondBlockDate = null;
        rebreakBlockDate = null;
        tempBlockDate = null;
        rebreakTicks = 0;
        breakAttempts = 0;

        // 恢复工具栏
        if (hasSwitch) {
            InvUtils.swapBack();
            hasSwitch = false;
        }
    }

    // ==================== 核心 Tick 处理 ====================

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        rangeCheck();
        rebreakTicks++;

        if (!doubleBreak.get()) {
            tickSingle();
        } else {
            tickDouble();
        }

        // 工具切回计时（不依赖当前挖掘目标，挖掘结束后也能切回）
        handleToolSwitchBack();
    }

    // ==================== 单挖模式 ====================

    private void tickSingle() {
        // 1. 清除已变为空气的方块
        if (firstBlockDate != null && mc.level.getBlockState(firstBlockDate.pos).getBlock() == Blocks.AIR) {
            firstBlockDate = null;
        }

        // 2. 开始挖掘（只发 START）
        if (firstBlockDate != null && !firstBlockDate.isMining) {
            mineBlock(firstBlockDate.pos, firstBlockDate.direction);
            firstBlockDate.isMining = true;
        }

        // 3. 检测方块是否已被服务端破坏（自动重挖）
        if (firstBlockDate != null
            && firstBlockDate.isMining
            && mc.level.getBlockState(firstBlockDate.pos).getBlock() == Blocks.AIR
            && rebreak.get()) {
            firstBlockDate.isBreaked = true;
        }

        // 4. 累加进度
        if (firstBlockDate != null && firstBlockDate.isMining && !firstBlockDate.done) {
            firstBlockDate.freshProgress();
        }

        // 5. 完成挖掘 → 发送 STOP（带绕过）
        if (firstBlockDate != null && !firstBlockDate.isBreaked && firstBlockDate.isMining && firstBlockDate.done) {
            // 工具切换：发送 STOP 前切换到最佳工具
            if (swapModeSetting.get() == SwapMode.SILENT) {
                int slot = getBestTool(mc.level.getBlockState(firstBlockDate.pos));
                if (slot != -1) {
                    InvUtils.swap(slot, true);
                }
            }

            sendStop(firstBlockDate.pos, firstBlockDate.direction);

            if (swapModeSetting.get() == SwapMode.SILENT) {
                InvUtils.swapBack();
                hasSwitch = false;
                switchTicks = 0;
            }

            if (firstBlockDate.rebreak) {
                rebreakBlockDate = firstBlockDate;
            } else {
                rebreakBlockDate = null;
            }

            firstBlockDate = null;
            breakAttempts = 0;
        }

        // 6. 失败计数
        handleBreakAttempts();

        // 7. 工具切换时机优化
        handleToolSwitch(firstBlockDate);

        // 8. 秒挖模式
        handleInstantMine(firstBlockDate);

        // 9. 重挖逻辑
        handleRebreak();
    }

    // ==================== 双挖模式 ====================

    private void tickDouble() {
        // 1. 清除已变为空气的方块
        if (firstBlockDate != null && mc.level.getBlockState(firstBlockDate.pos).getBlock() == Blocks.AIR) {
            firstBlockDate = null;
        }
        if (secondBlockDate != null && mc.level.getBlockState(secondBlockDate.pos).getBlock() == Blocks.AIR) {
            secondBlockDate = null;
        }

        // 2. 开始挖掘（mineBlock 内部会发 START + 延迟 STOP）
        if (firstBlockDate != null && !firstBlockDate.isMining && secondBlockDate == null) {
            mineBlock(firstBlockDate.pos, firstBlockDate.direction);
            firstBlockDate.isMining = true;
        }

        if (secondBlockDate != null && !secondBlockDate.isMining) {
            // 确保 first 也在挖掘
            if (firstBlockDate != null && !firstBlockDate.isMining) {
                mineBlock(firstBlockDate.pos, firstBlockDate.direction);
                firstBlockDate.isMining = true;
            }

            mineBlock(secondBlockDate.pos, secondBlockDate.direction);
            secondBlockDate.isMining = true;
        }

        // 3. 累加进度
        if (firstBlockDate != null && firstBlockDate.isMining && !firstBlockDate.done) {
            firstBlockDate.freshProgress();
        }
        if (secondBlockDate != null && secondBlockDate.isMining && !secondBlockDate.done) {
            secondBlockDate.freshProgress();
        }
        if (tempBlockDate != null && tempBlockDate.isMining && !tempBlockDate.done) {
            tempBlockDate.freshProgress();
        }
        if (tempBlockDate != null && tempBlockDate.done) {
            tempBlockDate = null;
        }

        // 4. 主挖完成（无论副挖是否完成）→ 发送真实 STOP
        if (firstBlockDate != null && !firstBlockDate.isBreaked && firstBlockDate.isMining && firstBlockDate.done) {
            // 工具切换
            if (swapModeSetting.get() == SwapMode.SILENT) {
                int slotx = getBestTool(mc.level.getBlockState(firstBlockDate.pos));
                if (slotx != -1) InvUtils.swap(slotx, true);
            }
            if (swapModeSetting.get() == SwapMode.NORMAL) {
                int firstSlot = getBestTool(mc.level.getBlockState(firstBlockDate.pos));
                int secondSlot = secondBlockDate != null
                    ? getBestTool(mc.level.getBlockState(secondBlockDate.pos)) : -1;

                // 选择要切到的目标槽位（优先主挖，两把工具时优先镐）
                int targetSlot = -1;
                if (firstSlot == -1 && secondSlot != -1) {
                    targetSlot = secondSlot;
                } else if (firstSlot != -1 && secondSlot == -1) {
                    targetSlot = firstSlot;
                } else if (firstSlot != -1 && secondSlot != -1) {
                    if (firstSlot == secondSlot) {
                        targetSlot = firstSlot;
                    } else if (mc.player.getInventory().getItem(firstSlot).is(ItemTags.PICKAXES)) {
                        targetSlot = firstSlot;
                    } else if (mc.player.getInventory().getItem(secondSlot).is(ItemTags.PICKAXES)) {
                        targetSlot = secondSlot;
                    }
                }

                // 切到目标工具；用 swap(true) 保留旧槽位记录，稍后由 handleToolSwitchBack 延迟切回
                if (targetSlot != -1 && targetSlot != mc.player.getInventory().getSelectedSlot()) {
                    InvUtils.swap(targetSlot, true);
                    hasSwitch = true;
                    switchTicks = 0;
                }
            }

            // 主挖发真实 STOP（带绕过）
            sendStop(firstBlockDate.pos, firstBlockDate.direction);

            if (firstBlockDate.rebreak) {
                rebreakBlockDate = new BlockDate(firstBlockDate.pos, firstBlockDate.direction);
            } else {
                rebreakBlockDate = null;
            }

            firstBlockDate = null;
            breakAttempts = 0;
            if (swapModeSetting.get() == SwapMode.SILENT) {
                InvUtils.swapBack();
                hasSwitch = false;
                switchTicks = 0;
            }
        }

        // 5. 副挖完成 → 客户端清除（延迟 STOP 已在 mineBlock 中发送，不需要再发 STOP）
        if (secondBlockDate != null && secondBlockDate.done) {
            // 记录重挖信息
            if (secondBlockDate.rebreak) {
                rebreakBlockDate = new BlockDate(secondBlockDate.pos, secondBlockDate.direction);
            }

            // 客户端直接清除，不发 STOP 给服务器
            // （mineBlock 中的延迟 STOP 已经告诉服务器"停止挖掘该方块"）
            secondBlockDate = null;
        }

        // 6. 失败计数
        handleBreakAttempts();

        // 7. 工具切换时机优化
        handleToolSwitch(firstBlockDate);

        // 8. 秒挖模式
        handleInstantMine(firstBlockDate);

        // 9. 重挖逻辑
        handleRebreak();
    }

    // ==================== 辅助方法 ====================

    /**
     * 失败计数与自动放弃
     * 当方块完成多次但仍未被服务端破坏时，放弃挖掘
     */
    private void handleBreakAttempts() {
        BlockDate block = firstBlockDate != null ? firstBlockDate : secondBlockDate;
        if (block == null || !block.done) return;

        breakAttempts++;
        if (breakAttempts >= maxBreaks.get() * 10) {
            firstBlockDate = null;
            secondBlockDate = null;
            rebreakBlockDate = null;
            breakAttempts = 0;
        }
    }

    /**
     * 工具切换：进度达到阈值时切换最佳工具
     */
    private void handleToolSwitch(BlockDate blockDate) {
        if (blockDate == null || !blockDate.isMining || blockDate.done) return;

        double progressPercent = blockDate.progress * (1.0 / speed.get()) * 100;
        if (progressPercent >= switchDamage.get() && !hasSwitch) {
            int bestSlot = getBestTool(mc.level.getBlockState(blockDate.pos));
            if (bestSlot != -1) {
                InvUtils.swap(bestSlot, true);
                hasSwitch = true;
                switchTicks = 0;
            }
        }
    }

    /**
     * 工具切回：切换工具后延迟「切回延迟」tick 恢复原来的槽位
     */
    private void handleToolSwitchBack() {
        if (!hasSwitch) return;
        if (!switchBack.get()) return;

        switchTicks++;
        if (switchTicks >= switchBackDelay.get()) {
            InvUtils.swapBack();
            hasSwitch = false;
        }
    }

    /**
     * 秒挖模式：完成后持续发送 STOP 触发服务端破坏
     */
    private void handleInstantMine(BlockDate blockDate) {
        if (!instantMine.get() || blockDate == null || !blockDate.done) return;
        if (System.currentTimeMillis() - lastInstantTime < instantDelay.get()) return;
        if (!mc.level.isEmptyBlock(blockDate.pos)) {
            sendStop(blockDate.pos, blockDate.direction);
            lastInstantTime = System.currentTimeMillis();
        }
    }

    /**
     * 停止挖掘指定位置（高空包抵消）
     */
    public static void stopMine(BlockPos pos) {
        MeteorClient.mc.getConnection().send(
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos.above(300), Direction.UP));
        MeteorClient.mc.getConnection().send(
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos.above(300), Direction.UP));
    }

    /**
     * 范围检查：超出距离时清除目标
     */
    public void rangeCheck() {
        if (firstBlockDate != null && PlayerUtils.distanceTo(firstBlockDate.pos) > range.get().intValue()) {
            firstBlockDate = null;
        }
        if (secondBlockDate != null && PlayerUtils.distanceTo(secondBlockDate.pos) > range.get().intValue()) {
            secondBlockDate = null;
        }
        if (rebreakBlockDate != null && PlayerUtils.distanceTo(rebreakBlockDate.pos) > range.get().intValue()) {
            rebreakBlockDate = null;
        }
    }

    /**
     * 重挖处理
     */
    private void handleRebreak() {
        if (rebreakBlockDate == null || firstBlockDate != null || secondBlockDate != null) return;
        if (!rebreak.get() || rebreakTicks < rebreakDelay.get() * 4) return;

        BlockState state = mc.level.getBlockState(rebreakBlockDate.pos);
        if (state.getBlock() == Blocks.AIR || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA)
            return;

        int slotx = getBestTool(state);
        if (slotx != -1 && slotx != mc.player.getInventory().getSelectedSlot() && swapModeSetting.get() == SwapMode.SILENT) {
            InvUtils.swap(slotx, true);
        }
        if (slotx != -1 && slotx != mc.player.getInventory().getSelectedSlot() && swapModeSetting.get() == SwapMode.NORMAL) {
            InvUtils.swap(slotx, false);
        }

        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, rebreakBlockDate.pos, rebreakBlockDate.direction));
        rebreakTicks = 0;

        if (slotx != -1 && swapModeSetting.get() == SwapMode.SILENT) {
            InvUtils.swapBack();
        }
    }

    // ==================== 发包方法 ====================

    /**
     * 开始挖掘
     * 单挖：只发 START，等进度完成后发真实 STOP
     * 双挖：发 START + 延迟 STOP（对齐 PacketMine 的 sendStart 机制）
     * <p>
     * 双挖原理：
     * - 每个方块都会收到 START + 延迟 STOP
     * - 延迟 STOP 告诉服务器"我停止了对该方块的挖掘"，满足反作弊的包序列检查
     * - 客户端独立追踪进度，副挖完成后只做客户端清除（不发真实 STOP）
     * - 主挖完成后发真实 STOP + 绕过技术
     */
    public void mineBlock(BlockPos pos, Direction direction) {
        if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);

        // 1. 发送 START 包
        mc.getConnection().send(
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction));

        // 2. 高空包绕过：向 Y=321 发送一个额外的 START 包（sequenced packet 保证顺序）
        if (fastBypass.get()) {
            BlockPos bypassPos = new BlockPos(pos.getX(), 321, pos.getZ());
            mc.gameMode.startPrediction(mc.level, id ->
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, bypassPos, Direction.DOWN, id));
        }

        // 3. 双挖模式：对每个方块都发送延迟 STOP（对齐 PacketMine 的 sendStart）
        //    延迟 STOP 的作用：告诉服务器"我开始并停止了挖掘"，满足反作弊检查
        //    实际的方块破坏由客户端进度追踪决定
        if (doubleBreak.get()) {
            new java.util.Timer().schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    mc.execute(() -> {
                        if (!mc.player.isRemoved() && mc.level != null) {
                            mc.getConnection().send(
                                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction));
                        }
                    });
                }
            }, 50);
        }
    }

    /**
     * 发送 STOP 包 - 带绕过技术
     * 1. 滞空挖掘绕过：微调 Y 坐标
     * 2. 高空 STOP 抵消
     * 3. 使用 sequenced packet 发送主 STOP
     */
    private void sendStop(BlockPos pos, Direction direction) {
        // 滞空挖掘绕过：在 STOP 前微调 Y 坐标（26.1 无 Player.onLanding，只保留位置微调）
        if (bypassGround.get() && !mc.player.isFallFlying() && pos != null
            && !mc.level.isEmptyBlock(pos) && !mc.player.onGround()) {
            mc.getConnection().send(
                new ServerboundMovePlayerPacket.PosRot(
                    mc.player.getX(), mc.player.getY() + 1.0e-9, mc.player.getZ(),
                    mc.player.getYRot(), mc.player.getXRot(), true, mc.player.horizontalCollision));
        }

        // 高空 STOP 抵消
        if (fastBypass.get()) {
            BlockPos bypassPos = new BlockPos(pos.getX(), 321, pos.getZ());
            mc.gameMode.startPrediction(mc.level, id ->
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, bypassPos, Direction.DOWN, id));
        }

        // 主 STOP 包（使用 sequenced packet 保证顺序正确）
        mc.gameMode.startPrediction(mc.level, id ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, id));

        if (swingHand.get()) mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ==================== 事件处理 ====================

    @EventHandler
    public void onPacket(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundPlayerActionPacket playerActionPacket) {
            if (playerActionPacket.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && unbreakableBlocks.contains(mc.level.getBlockState(playerActionPacket.getPos()).getBlock())) {
                event.cancel();
                stopMine(playerActionPacket.getPos());
            }

            if (playerActionPacket.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                && unbreakableBlocks.contains(mc.level.getBlockState(playerActionPacket.getPos()).getBlock())) {
                event.cancel();
                stopMine(playerActionPacket.getPos());
            }

            if (playerActionPacket.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                && unbreakableBlocks.contains(mc.level.getBlockState(playerActionPacket.getPos()).getBlock())) {
                event.cancel();
                stopMine(playerActionPacket.getPos());
            }
        }
    }

    @EventHandler
    public void onClickBlock(StartBreakingBlockEvent event) {
        BlockPos pos = event.blockPos;
        Direction direction = event.direction;

        // 拦截原版挖掘：模块激活时所有方块点击都取消原版挖掘流程（不发原版挖掘包、不产生本地挖掘动画），
        // 统一由发包挖掘接管（按方块→加入目标→发 START/STOP 包）
        event.cancel();

        if (unbreakableBlocks.contains(mc.level.getBlockState(pos).getBlock())
            || breakBlocks.contains(pos)
            || PlayerUtils.distanceTo(pos) > range.get().intValue()) {
            return;
        }

        if ((firstBlockDate == null || !pos.equals(firstBlockDate.pos))
            && (secondBlockDate == null || !pos.equals(secondBlockDate.pos))) {

            if (firstBlockDate != null && !pos.equals(firstBlockDate.pos) && doubleBreak.get()) {
                if (secondBlockDate == null || !pos.equals(secondBlockDate.pos)) {
                    secondBlockDate = new BlockDate(pos, direction);
                    firstBlockDate.progress = firstBlockDate.progress - (1.0 - speed.get()) * 0.7;
                }
            } else if (firstBlockDate == null || !pos.equals(firstBlockDate.pos)) {
                firstBlockDate = new BlockDate(pos, direction);
            }

            // 双挖时立即发 START 给新方块
            if (doubleBreak.get()) {
                mineBlock(pos, direction);
            }
        }
    }

    // ==================== 渲染 ====================

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        if (firstBlockDate != null && mc.level.getBlockState(firstBlockDate.pos).getBlock() != Blocks.AIR) {
            renderBlock(event, firstBlockDate.pos, firstBlockDate.progress);
        }

        if (secondBlockDate != null && mc.level.getBlockState(secondBlockDate.pos).getBlock() != Blocks.AIR) {
            renderBlock(event, secondBlockDate.pos, secondBlockDate.progress);
        }

        if (tempBlockDate != null && mc.level.getBlockState(tempBlockDate.pos).getBlock() != Blocks.AIR) {
            renderBlock(event, tempBlockDate.pos, tempBlockDate.progress);
        }

        if (rebreak.get()
            && rebreakBlockDate != null
            && mc.level.getBlockState(rebreakBlockDate.pos).getBlock() != Blocks.AIR
            && mc.level.getBlockState(rebreakBlockDate.pos).getBlock() != Blocks.WATER
            && mc.level.getBlockState(rebreakBlockDate.pos).getBlock() != Blocks.LAVA
            && firstBlockDate == null
            && secondBlockDate == null) {
            BlockPos blockPos = rebreakBlockDate.pos;
            event.renderer.box(
                blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                blockPos.getX() + 1, blockPos.getY() + 1, blockPos.getZ() + 1,
                readySideColor.get(), readyLineColor.get(), shapeMode.get(), 0);
        }
    }

    private void renderBlock(Render3DEvent event, BlockPos blockPos, double rawProgress) {
        double progress = rawProgress * (1.0 / speed.get());
        if (progress > 1.0) progress = 1.0;
        if (progress < 0.0) progress = 0.0;

        double x1 = blockPos.getX() + (0.5 - 0.5 * progress);
        double y1 = blockPos.getY() + (0.5 - 0.5 * progress);
        double z1 = blockPos.getZ() + (0.5 - 0.5 * progress);
        double x2 = blockPos.getX() + 0.5 + 0.5 * progress;
        double y2 = blockPos.getY() + 0.5 + 0.5 * progress;
        double z2 = blockPos.getZ() + 0.5 + 0.5 * progress;

        int side_r = sideColor.get().r + (int) ((readySideColor.get().r - sideColor.get().r) * progress);
        int side_g = sideColor.get().g + (int) ((readySideColor.get().g - sideColor.get().g) * progress);
        int side_b = sideColor.get().b + (int) ((readySideColor.get().b - sideColor.get().b) * progress);
        int side_a = sideColor.get().a + (int) ((readySideColor.get().a - sideColor.get().a) * progress);
        int line_r = lineColor.get().r + (int) ((readyLineColor.get().r - lineColor.get().r) * progress);
        int line_g = lineColor.get().g + (int) ((readyLineColor.get().g - lineColor.get().g) * progress);
        int line_b = lineColor.get().b + (int) ((readyLineColor.get().b - lineColor.get().b) * progress);
        int line_a = lineColor.get().a + (int) ((readyLineColor.get().a - lineColor.get().a) * progress);

        SettingColor _sideColor = new SettingColor(side_r, side_g, side_b, side_a);
        SettingColor _lineColor = new SettingColor(line_r, line_g, line_b, line_a);
        event.renderer.box(x1, y1, z1, x2, y2, z2, _sideColor, _lineColor, shapeMode.get(), 0);
    }

    // ==================== 工具相关 ====================

    public static int getMiningBlockCount() {
        int count = 0;
        if (firstBlockDate != null && MeteorClient.mc.level.getBlockState(firstBlockDate.pos).getBlock() != Blocks.AIR) {
            count++;
        }
        if (secondBlockDate != null && MeteorClient.mc.level.getBlockState(secondBlockDate.pos).getBlock() != Blocks.AIR) {
            count++;
        }
        return count;
    }

    /**
     * 找出热栏中对方块挖掘速度最快的槽位。
     * 只接受镐/斧/锹/剑（26.1 用 ItemTags 判断，不再依赖 PickaxeItem/SwordItem 类）。
     */
    public int getBestTool(BlockState blockState) {
        double bestScore = -1.0;
        int bestSlot = -1;

        for (int i = 0; i < 9; i++) {
            double score = mc.player.getInventory().getItem(i).getDestroySpeed(blockState);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        ItemStack stack = mc.player.getInventory().getItem(bestSlot);
        boolean isTool = stack.is(ItemTags.PICKAXES)
            || stack.is(ItemTags.AXES)
            || stack.is(ItemTags.SHOVELS)
            || stack.is(ItemTags.SWORDS);
        return isTool ? bestSlot : -1;
    }

    public BlockDate getBlockDate(BlockPos pos, Direction direction) {
        return new BlockDate(pos, direction);
    }

    public BlockDate getBlockDate(BlockPos pos, Direction direction, boolean rebreak) {
        return new BlockDate(pos, direction, rebreak);
    }

    // ==================== 内部类 ====================

    public class BlockDate {
        public BlockPos pos;
        public Direction direction;
        public boolean done = false;
        public double progress;
        public BlockState blockState;
        public boolean isMining = false;
        public boolean isBreaked = false;
        public boolean rebreak = true;

        public BlockDate(BlockPos pos, Direction direction) {
            this.pos = pos;
            this.direction = direction;
            this.done = false;
            this.progress = 0.0;
            blockState = mc.level.getBlockState(pos);
        }

        public BlockDate(BlockPos pos, Direction direction, boolean rebreak) {
            this.pos = pos;
            this.direction = direction;
            this.done = false;
            this.progress = 0.0;
            blockState = mc.level.getBlockState(pos);
            this.rebreak = rebreak;
        }

        /**
         * 真实进度模拟
         * 使用 BlockUtils.getBreakDelta 累加进度（已含工具、附魔、药水效果）
         * 额外处理：空中挖掘惩罚
         */
        public void freshProgress() {
            // 瞬间破坏的方块（硬度为0）
            if (blockState.getDestroySpeed(mc.level, pos) == 0) {
                done = true;
                progress = 1.0;
                return;
            }

            // 使用 BlockUtils 获取每 tick 的进度增量
            // 该方法已考虑：工具类型、效率附魔、急迫/挖掘疲劳效果
            int slot = getBestTool(blockState);
            double delta = BlockUtils.getBreakDelta(
                slot != -1 ? slot : mc.player.getInventory().getSelectedSlot(), blockState);

            // 空中挖掘惩罚：原版在空中挖掘速度大幅降低
            if (!mc.player.onGround()) {
                delta *= 0.2;
            }

            if (progress <= 1.0 * GhostMine.this.speed.get()) {
                progress += delta;
            } else {
                done = true;
                progress = 1.0;
            }
        }
    }

    public static enum SwapMode {
        SILENT,
        NORMAL;
    }
}
