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
import meteordevelopment.meteorclient.events.entity.player.DoAttackEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
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
import net.minecraft.util.Mth;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 发包挖掘（GhostMine）— 从 meteor-miku 移植（26.1 Mojmap 适配）
 *
 * <p>核心机制（对应 26.1 原版服务端 {@code ServerPlayerGameMode.handleBlockBreakAction}）：
 * 1. 进度模拟：用 BlockUtils.getBreakDelta 按原版挖掘速度累加进度（含工具/附魔/药水/空中惩罚）
 * 2. 触发破坏：进度达到「切换工具阈值」时切到最佳工具并发 STOP 包；服务端收到 STOP 会用
 *    当前手持工具重算进度，进度 ≥ 0.7 立即破坏，&lt; 0.7 则交给服务端的延迟破坏补完
 * 3. 双挖：服务端的「挖掘槽位」和「延迟破坏槽位」各只有一个，所以主挖在 START 后补一个 STOP
 *    占住延迟破坏槽位（服务端自己会把它挖掉），副挖占住挖掘槽位并靠阈值 STOP 破坏
 * 4. 绕过技术：高空包、滞空挖掘微调、sequenced packet（startPrediction）
 * 5. 挖掘冷却：开始一个挖掘后的一段时间内忽略其他方块，适配反作弊的挖掘延迟检查
 * 6. 超时放弃：进度走完后等待服务端确认破坏，超时仍未被破坏则放弃该方块
 */
public class GhostMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();   // 挖掘（Meteor 默认组）
    private final SettingGroup sgBypass = settings.createGroup("绕过");
    private final SettingGroup sgSwing = settings.createGroup("挥手");
    private final SettingGroup sgSwitch = settings.createGroup("切换");
    private final SettingGroup sgRebreak = settings.createGroup("重挖");
    private final SettingGroup sgRender = settings.createGroup("渲染设置");
    public static GhostMine INSTANCE;

    // ==================== 挖掘 ====================

    public Setting<Integer> range = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("挖掘范围")
                .description("设置挖掘方块的最大距离")
                .sliderRange(1, 6)
                .defaultValue(6)
                .build()
        );
    public Setting<Boolean> ignoreRangeWhileMining = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("挖掘中无视距离")
                .description("距离只影响能否开始挖掘；开始挖掘之后无论走多远都继续挖")
                .defaultValue(true)
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
    public Setting<Integer> mineCooldown = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("挖掘冷却")
                .description("开始一个挖掘后，多少 tick 内点击其他方块直接忽略（适配反作弊的挖掘延迟检查，0 = 关闭）")
                .defaultValue(6)
                .min(0)
                .sliderRange(0, 20)
                .build()
        );

    // ==================== 绕过 ====================

    public Setting<Boolean> fastBypass = sgBypass
        .add(
            new BoolSetting.Builder()
                .name("高空包绕过")
                .description("发送 START 时额外向高空发送一个相同的包")
                .defaultValue(true)
                .build()
        );
    public Setting<Boolean> bypassGround = sgBypass
        .add(
            new BoolSetting.Builder()
                .name("滞空挖掘绕过")
                .description("在发送 STOP 前微调 Y 坐标")
                .defaultValue(false)
                .build()
        );

    // ==================== 挥手 ====================

    public Setting<Boolean> swingStart = sgSwing
        .add(
            new BoolSetting.Builder()
                .name("挖掘开始挥手")
                .description("挖掘开始时挥手。勾选：挥手动画+swing 包发给服务器（其他玩家可见）；不勾选：只有本地挥手动画，不发包")
                .defaultValue(true)
                .build()
        );
    public Setting<Boolean> swingEnd = sgSwing
        .add(
            new BoolSetting.Builder()
                .name("挖掘结束挥手")
                .description("挖掘结束时挥手。勾选：挥手动画+swing 包发给服务器（其他玩家可见）；不勾选：只有本地挥手动画，不发包")
                .defaultValue(true)
                .build()
        );

    // ==================== 切换 ====================

    public Setting<Integer> switchDamage = sgSwitch
        .add(
            new IntSetting.Builder()
                .name("切换工具阈值")
                .description("方块挖掘进度达到此百分比时切到最佳工具，并发 STOP 包尝试挖掉方块")
                .defaultValue(95)
                .min(1)
                .sliderMax(100)
                .build()
        );
    public Setting<SwitchBackMode> switchBackMode = sgSwitch
        .add(new EnumSetting.Builder<SwitchBackMode>()
            .name("切工具模式")
            .description("切到最佳工具后如何切回原来的槽位")
            .defaultValue(SwitchBackMode.DELAYED)
            .build()
        );
    public Setting<Integer> switchBackDelay = sgSwitch
        .add(
            new IntSetting.Builder()
                .name("延迟")
                .description("延迟切回：切工具后多少 tick 切回原来的槽位")
                .defaultValue(1)
                .sliderRange(1, 10)
                .visible(() -> switchBackMode.get() == SwitchBackMode.DELAYED)
                .build()
        );
    public Setting<Boolean> switchBackOnBreak = sgSwitch
        .add(
            new BoolSetting.Builder()
                .name("方块破坏切回")
                .description("延迟切回：方块被破坏后不等延迟，立即切回原来的槽位")
                .defaultValue(false)
                .visible(() -> switchBackMode.get() == SwitchBackMode.DELAYED)
                .build()
        );
    public Setting<Boolean> loopStop = sgSwitch
        .add(
            new BoolSetting.Builder()
                .name("循环发包")
                .description("延迟切回：等待切回期间每 tick 都发 STOP 包尝试挖掘（方块被破坏后停发）")
                .defaultValue(true)
                .visible(() -> switchBackMode.get() == SwitchBackMode.DELAYED)
                .build()
        );

    // ==================== 重挖 ====================

    public Setting<Boolean> rebreak = sgRebreak
        .add(new BoolSetting.Builder().name("自动重挖").description("方块被破坏后，该位置再次出现方块时自动重新挖掘").defaultValue(true).build());
    public Setting<Integer> rebreakDelay = sgRebreak
        .add(
            new IntSetting.Builder()
                .name("重挖延迟")
                .description("重新挖掘方块前的延迟时间(tick)")
                .sliderRange(0, 10)
                .defaultValue(0)
                .visible(rebreak::get)
                .build()
        );
    public Setting<Integer> maxBreaks = sgRebreak
        .add(
            new IntSetting.Builder()
                .name("放弃等待")
                .description("进度走完后，等待服务器确认破坏的 tick 数（20 tick = 1 秒），超时仍未确认则放弃该方块")
                .defaultValue(60)
                .min(10)
                .sliderRange(10, 300)
                .build()
        );

    // ==================== 渲染设置 ====================

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

    public static BlockDate firstBlockDate = null;
    public static BlockDate secondBlockDate = null;
    private BlockDate rebreakBlockDate = null;
    private int rebreakTicks = 0;
    /** 挖掘冷却剩余 tick（>0 时忽略对其他方块的点击） */
    private int mineCooldownTicks = 0;
    /** 工具切换状态：是否已切到最佳工具等待切回、等待了多少 tick、本次切换要破坏的方块 */
    private boolean hasSwitch = false;
    private int switchTicks = 0;
    private final List<BlockDate> switchTargets = new ArrayList<>();
    // 挖掘挥手包控制：allow=放行下一个 swing 包，block=拦截下一个 swing 包（由「挖掘开始/结束挥手」设置决定，
    // 不依赖瞄准状态，避免挖掘开始/结束瞬间没瞄准方块时包漏拦/漏放）
    private boolean allowSwingPacket = false;
    private boolean blockSwingPacket = false;

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
        rebreakTicks = 0;
        mineCooldownTicks = 0;
        hasSwitch = false;
        switchTicks = 0;
        switchTargets.clear();
    }

    @Override
    public void onDeactivate() {
        firstBlockDate = null;
        secondBlockDate = null;
        rebreakBlockDate = null;
        rebreakTicks = 0;
        mineCooldownTicks = 0;
        switchTargets.clear();

        // 恢复工具栏
        if (hasSwitch) {
            InvUtils.swapBack();
            hasSwitch = false;
        }
    }

    // ==================== 核心 Tick 处理 ====================

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mineCooldownTicks > 0) mineCooldownTicks--;
        rangeCheck();
        rebreakTicks++;

        // 切工具后的切回/循环发包。放在挖掘逻辑之前：切回计时不包含切换发生的那个 tick
        handleSwitchBack();

        if (!doubleBreak.get()) {
            tickSingle();
        } else {
            tickDouble();
        }

        // 重挖逻辑
        handleRebreak();
    }

    // ==================== 单挖模式 ====================

    private void tickSingle() {
        // 1. 已被服务端破坏 → 清理并记录重挖位置
        if (firstBlockDate != null && isBroken(firstBlockDate.pos)) {
            recordRebreak(firstBlockDate);
            firstBlockDate = null;
        }

        // 2. 开始挖掘（发 START）
        if (firstBlockDate != null && !firstBlockDate.isMining) {
            mineBlock(firstBlockDate.pos, firstBlockDate.direction);
            firstBlockDate.isMining = true;
        }

        // 3. 进度累加 + 达到阈值切工具/发 STOP
        tickTarget(firstBlockDate);

        // 4. 超时未确认破坏 → 放弃
        if (firstBlockDate != null && giveUp(firstBlockDate)) firstBlockDate = null;
    }

    // ==================== 双挖模式 ====================

    private void tickDouble() {
        // 1. 已被服务端破坏 → 清理并记录重挖位置
        if (firstBlockDate != null && isBroken(firstBlockDate.pos)) {
            recordRebreak(firstBlockDate);
            firstBlockDate = null;
        }
        if (secondBlockDate != null && isBroken(secondBlockDate.pos)) {
            recordRebreak(secondBlockDate);
            secondBlockDate = null;
        }

        // 2. 开始挖掘：主挖先发 START，副挖后发
        //    服务端的挖掘槽位最后停在副挖上：副挖靠阈值 STOP 破坏，主挖靠 START 时占住的延迟破坏槽位破坏
        if (firstBlockDate != null && !firstBlockDate.isMining) {
            mineBlock(firstBlockDate.pos, firstBlockDate.direction);
            firstBlockDate.isMining = true;
        }
        if (secondBlockDate != null && !secondBlockDate.isMining) {
            mineBlock(secondBlockDate.pos, secondBlockDate.direction);
            secondBlockDate.isMining = true;
        }

        // 3. 进度累加 + 达到阈值切工具/发 STOP
        tickTarget(firstBlockDate);
        tickTarget(secondBlockDate);

        // 4. 超时未确认破坏 → 放弃
        if (firstBlockDate != null && giveUp(firstBlockDate)) firstBlockDate = null;
        if (secondBlockDate != null && giveUp(secondBlockDate)) secondBlockDate = null;
    }

    // ==================== 挖掘目标处理 ====================

    /**
     * 单个目标的每 tick 处理
     * <p>
     * 进度按原版挖掘速度累加；进度达到「切换工具阈值」时切到最佳工具并发 STOP 尝试破坏：
     * 服务端收到 STOP 会用「当前手持工具」重算进度，工具越好进度越高，
     * 进度 ≥ 0.7 服务端立即破坏，否则由服务端的延迟破坏补完。
     */
    private void tickTarget(BlockDate block) {
        if (block == null || !block.isMining) return;

        if (!block.done) block.freshProgress();

        if (!block.switched && block.progress * 100.0 >= switchDamage.get()) {
            block.switched = true;

            // 方块已经不在了：不用切工具/发包
            if (isBroken(block.pos)) return;

            switchToBestTool(block);
            sendStop(block.pos, block.direction);

            // 立即切回：同一 tick 内按 切工具 → STOP → 切回 的顺序发完，这样不重置攻击冷却
            if (switchBackMode.get() == SwitchBackMode.IMMEDIATE) switchBackNow();
        }
    }

    /** 进度走完后等待服务端确认破坏，等待超过「放弃等待」则放弃该方块 */
    private boolean giveUp(BlockDate block) {
        if (!block.done) return false;

        block.timeoutTicks++;
        if (block.timeoutTicks < maxBreaks.get()) return false;

        recordRebreak(block);
        return true;
    }

    /** 记录重挖位置（自动重挖开启时） */
    private void recordRebreak(BlockDate block) {
        if (!rebreak.get() || !block.rebreak) return;
        rebreakBlockDate = new BlockDate(block.pos, block.direction);
    }

    /** 方块是否已被破坏（变成空气） */
    private boolean isBroken(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() == Blocks.AIR;
    }

    // ==================== 工具切换 ====================

    /** 切到该方块的最佳工具（热栏里没有合适的工具时不切） */
    private void switchToBestTool(BlockDate block) {
        int slot = getBestTool(mc.level.getBlockState(block.pos));
        if (slot == -1 || slot == mc.player.getInventory().getSelectedSlot()) return;

        // 不切回：不需要记录原槽位
        if (switchBackMode.get() == SwitchBackMode.NONE) {
            InvUtils.swap(slot, false);
            return;
        }

        InvUtils.swap(slot, true);
        hasSwitch = true;
        switchTicks = 0;
        switchTargets.add(block);
    }

    /** 切回原来的槽位 */
    private void switchBackNow() {
        if (!hasSwitch) return;

        InvUtils.swapBack();
        hasSwitch = false;
        switchTicks = 0;
        switchTargets.clear();
    }

    /**
     * 切工具后的处理（延迟切回）
     * <p>
     * - 循环发包：等待切回期间每 tick 给还没被破坏的方块补 STOP 包（方块被破坏后停发）
     * - 方块破坏切回：方块被破坏后不等延迟直接切回
     * - 到达「延迟」后切回
     */
    private void handleSwitchBack() {
        if (!hasSwitch) return;

        switchTargets.removeIf(block -> isBroken(block.pos));

        switch (switchBackMode.get()) {
            // 立即切回在切工具时已经同 tick 完成
            case IMMEDIATE -> switchBackNow();
            case DELAYED -> {
                if (switchBackOnBreak.get() && switchTargets.isEmpty()) {
                    switchBackNow();
                    return;
                }

                switchTicks++;
                if (switchTicks >= switchBackDelay.get()) {
                    switchBackNow();
                    return;
                }

                if (loopStop.get()) {
                    for (BlockDate block : switchTargets) sendStopPacket(block.pos, block.direction);
                }
            }
            // 不切回：保持最佳工具
            case NONE -> { }
        }
    }

    // ==================== 辅助方法 ====================

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
     * 开启「挖掘中无视距离」时距离只限制开始挖掘（onClickBlock），已开始的目标不受距离影响
     */
    public void rangeCheck() {
        if (ignoreRangeWhileMining.get()) return;

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
        if (hasSwitch) return;   // 切工具还没切回，等切回再重挖（避免换槽位互相干扰）
        if (!rebreak.get() || rebreakTicks < rebreakDelay.get() * 4) return;

        BlockState state = mc.level.getBlockState(rebreakBlockDate.pos);
        if (state.getBlock() == Blocks.AIR || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA)
            return;

        // 切到最佳工具并沿用「切工具模式」处理切回：服务端收到 STOP 用当前手持工具重算进度
        int slotx = getBestTool(state);
        if (slotx != -1 && slotx != mc.player.getInventory().getSelectedSlot()) {
            if (switchBackMode.get() == SwitchBackMode.NONE) {
                InvUtils.swap(slotx, false);
            } else {
                InvUtils.swap(slotx, true);
                hasSwitch = true;
                switchTicks = 0;
                switchTargets.add(rebreakBlockDate);
                if (switchBackMode.get() == SwitchBackMode.IMMEDIATE) switchBackNow();
            }
        }

        sendStopPacket(rebreakBlockDate.pos, rebreakBlockDate.direction);
        rebreakTicks = 0;
    }

    // ==================== 发包方法 ====================

    /**
     * 开始挖掘（发 START；双挖模式下紧接着补一个 STOP）
     * <p>
     * 双挖原理（对应 26.1 原版服务端 ServerPlayerGameMode.handleBlockBreakAction）：
     * - 服务端只有「一个」挖掘槽位：收到 START 就记录 destroyPos，并中止上一个方块
     * - 收到 STOP 时用当前手持工具重算进度 = 单tick进度 * (gameTicks - destroyProgressStart + 1)：
     * - 进度 ≥ 0.7 → 立即破坏；进度 < 0.7 → 交给服务端 hasDelayedDestroy 自己走完
     * - 延迟破坏槽位同样只有一个，先占者得：所以 START 之后必须立刻补 STOP，
     * - 先挖的方块占住延迟破坏槽位（服务端自己会破坏它），后挖的方块留在挖掘槽位上等待真实 STOP
     */
    public void mineBlock(BlockPos pos, Direction direction) {
        // 开始挖掘挥手：勾选「挖掘开始挥手」时 swing 包强制放行（服务器可见），不勾选强制拦截（只有本地动画）
        if (swingStart.get()) allowSwingPacket = true;
        else blockSwingPacket = true;
        mc.player.swing(InteractionHand.MAIN_HAND);

        // 1. 发送 START 包
        mc.getConnection().send(
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction));

        // 2. 高空包绕过：向 Y=321 发送一个额外的 START 包（sequenced packet 保证顺序）
        if (fastBypass.get()) {
            BlockPos bypassPos = new BlockPos(pos.getX(), 321, pos.getZ());
            mc.gameMode.startPrediction(mc.level, id ->
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, bypassPos, Direction.DOWN, id));
        }

        // 3. 双挖模式：紧接着补一个 STOP，让服务端接管这个方块
        //    - 进度 < 0.7 时：服务端记录 hasDelayedDestroy，之后按原版进度自己把方块破坏掉
        //    - 进度 ≥ 0.7 时（软方块）：服务端直接破坏
        //    必须在 START 的同一 tick 发出：延迟破坏槽位只有一个，晚发会被另一个方块的 STOP 抢占，
        //    结果就是其中一个方块永远挖不掉（双挖只能挖掉一个方块的根因）
        if (doubleBreak.get()) {
            mc.gameMode.startPrediction(mc.level, id ->
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, id));
        }
    }

    /**
     * 只发一个 STOP 包（循环发包 / 重挖用，不带挥手与绕过）
     */
    private void sendStopPacket(BlockPos pos, Direction direction) {
        mc.gameMode.startPrediction(mc.level, id ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, id));
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
        sendStopPacket(pos, direction);

        // 挖掘结束挥手：勾选「挖掘结束挥手」时 swing 包强制放行（服务器可见），不勾选强制拦截（只有本地动画）
        if (swingEnd.get()) allowSwingPacket = true;
        else blockSwingPacket = true;
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ==================== 事件处理 ====================

    /**
     * 拦截点击方块时的攻击：原版 startAttack 无论结果如何都会无条件挥手（swing），
     * swing 包会重置服务端攻击冷却，干扰真实攻击。
     * 瞄准方块时直接取消 startAttack，挖掘由按住左键的 continueDestroyBlock → startDestroyBlock 链路接管。
     * 没瞄准方块（空气/实体）时放行，攻击不受影响。
     */
    @EventHandler
    private void onDoAttack(DoAttackEvent event) {
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            event.cancel();
        }
    }

    @EventHandler
    public void onPacket(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundSwingPacket) {
            // 挖掘开始/结束的挥手包按配置强制放行或拦截（勾选=放行，不勾选=拦截）
            if (allowSwingPacket) {
                allowSwingPacket = false;
                return;
            }
            if (blockSwingPacket) {
                blockSwingPacket = false;
                event.cancel();
                return;
            }
            // 其余 swing 包（攻击挥手、其他来源）正常放行：
            // 单击方块时的攻击挥手已由 onDoAttack（拦截 startAttack）处理，这里不再按瞄准状态过滤，
            // 避免误伤挖掘挥手包
            return;
        }

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
            || PlayerUtils.distanceTo(pos) > range.get().intValue()) {
            return;
        }

        // 已经在挖这个方块
        if ((firstBlockDate != null && pos.equals(firstBlockDate.pos))
            || (secondBlockDate != null && pos.equals(secondBlockDate.pos))) {
            return;
        }

        // 挖掘冷却：开始一个挖掘后的一段时间内忽略其他方块，适配反作弊的挖掘延迟检查
        if (mineCooldownTicks > 0) return;

        BlockDate target;
        if (firstBlockDate == null) {
            firstBlockDate = new BlockDate(pos, direction);
            target = firstBlockDate;
        } else if (doubleBreak.get()) {
            // 双挖：放到副挖槽位（原来有副挖则直接替换）
            secondBlockDate = new BlockDate(pos, direction);
            target = secondBlockDate;
        } else {
            // 单挖：换成新方块
            firstBlockDate = new BlockDate(pos, direction);
            target = firstBlockDate;
        }

        mineBlock(target.pos, target.direction);
        target.isMining = true;
        mineCooldownTicks = mineCooldown.get();
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
        double progress = Mth.clamp(rawProgress, 0.0, 1.0);

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
        /** 是否已经达到切换工具阈值（切过工具、发过 STOP） */
        public boolean switched = false;
        /** 进度走完后等待服务端确认破坏的 tick 数 */
        public int timeoutTicks = 0;
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
         * 额外处理：空中挖掘惩罚（原版在空中挖掘速度降到 1/5）
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

            // 原版挖掘速度：进度累加到 1.0 表示挖完
            progress += delta;
            if (progress >= 1.0) {
                done = true;
                progress = 1.0;
            }
        }
    }

    /** 切工具后如何切回原来的槽位 */
    public enum SwitchBackMode {
        IMMEDIATE("立即切回"),
        DELAYED("延迟切回"),
        NONE("不切回");

        private final String displayName;

        SwitchBackMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
