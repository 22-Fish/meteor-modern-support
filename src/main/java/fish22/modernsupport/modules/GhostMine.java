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

import fish22.modernsupport.mixin.MultiPlayerGameModeDelayAccessor;
import fish22.modernsupport.mixin.MultiPlayerGameModeMiningAccessor;
import fish22.modernsupport.utils.BreakData;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.DoAttackEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
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
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;


public class GhostMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();   // 挖掘（Meteor 默认组）
    private final SettingGroup sgBypass = settings.createGroup("绕过");
    private final SettingGroup sgSwitch = settings.createGroup("切换");
    private final SettingGroup sgRebreak = settings.createGroup("重挖");
    private final SettingGroup sgRender = settings.createGroup("渲染设置");
    public static GhostMine INSTANCE;

    // ==================== 挖掘 ====================

    public Setting<Integer> range = sgGeneral
        .add(
            new IntSetting.Builder()
                .name("挖掘范围")
                .description("挖掘方块的最大距离")
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
                .description("挖掘的冷却")
                .defaultValue(6)
                .min(0)
                .sliderRange(0, 20)
                .build()
        );
    public Setting<Boolean> syncVanillaCooldown = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("跟原版冷却")
                .description("原版那边还有破坏延迟时不开始新的挖掘（和「秒破」这类走原版路径的模块同开时用）")
                .defaultValue(false)
                .build()
        );
    public Setting<Boolean> swingPacket = sgGeneral
        .add(
            new BoolSetting.Builder()
                .name("挥手")
                .description("挖掘开始/收尾时把挥手包真发给服务端（反作弊就指望每次挖掘都有挥手）；关掉只有本地动画，包拦住不发")
                .defaultValue(false)
                .build()
        );

    // ==================== 绕过 ====================

    public Setting<Boolean> fastBypass = sgBypass
        .add(
            new BoolSetting.Builder()
                .name("高空包绕过")
                .description("发送 START 时额外向高空发送一个相同的包，用于绕过反作弊的跟踪")
                .defaultValue(true)
                .build()
        );
    public Setting<Boolean> bypassGround = sgBypass
        .add(
            new BoolSetting.Builder()
                .name("滞空挖掘绕过")
                .description("在空中挖掘时按「踩在地面上」发包，让服务端按地面速度算进度")
                .defaultValue(false)
                .build()
        );
    public Setting<Boolean> adaptiveFace = sgBypass
        .add(
            new BoolSetting.Builder()
                .name("挖掘面自适应")
                .description("确保挖掘瞄准面合法")
                .defaultValue(true)
                .build()
        );
    public Setting<Boolean> acFeedback = sgBypass
        .add(
            new BoolSetting.Builder()
                .name("反馈闭环")
                .description("像反作弊那样给自己记分：抢跑就加分、按原版节奏挖就衰减，分高了自动退回原版节奏，高空包也只在抢跑时发")
                .defaultValue(true)
                .build()
        );
    public Setting<Integer> acThreshold = sgBypass
        .add(
            new IntSetting.Builder()
                .name("反馈阈值")
                .description("分数超过这个数就退回原版节奏（6 tick 内不再开始新的挖掘）")
                .defaultValue(300)
                .min(50)
                .sliderRange(50, 1000)
                .visible(acFeedback::get)
                .build()
        );

    // ==================== 切换 ====================

    public Setting<Integer> switchDamage = sgSwitch
        .add(
            new IntSetting.Builder()
                .name("挖掘方块阈值")
                .description("进度到这个百分比就发结束包收尾。进度就是服务端那套公式（收尾时会拿到手上的那把工具 × 开始包之后过了多少 tick + 1），服务端 70% 才会当场破坏，所以调 70 最快")
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
                .description("切工具后多少 tick 切回原来的槽位")
                .defaultValue(1)
                .sliderRange(1, 10)
                .visible(() -> switchBackMode.get() == SwitchBackMode.DELAYED)
                .build()
        );
    public Setting<Boolean> switchBackOnBreak = sgSwitch
        .add(
            new BoolSetting.Builder()
                .name("方块破坏切回")
                .description("方块被破坏后不等延迟，立即切回原来的槽位")
                .defaultValue(false)
                .visible(() -> switchBackMode.get() == SwitchBackMode.DELAYED)
                .build()
        );
    public Setting<Boolean> autoRetry = sgSwitch
        .add(
            new BoolSetting.Builder()
                .name("自动重试")
                .description("结束包没把方块挖掉时，下一 tick 自动重试。建议开启")
                .defaultValue(true)
                .visible(() -> switchBackMode.get() == SwitchBackMode.IMMEDIATE)
                .build()
        );

    // ==================== 精准采集 ====================

    public Setting<Boolean> silkTouch = sgSwitch
        .add(
            new BoolSetting.Builder()
                .name("精准采集")
                .description("列表里的方块优先用带精准采集的工具挖")
                .defaultValue(true)
                .build()
        );
    public Setting<List<Block>> silkBlocks = sgSwitch
        .add(
            new BlockListSetting.Builder()
                .name("精准采集列表")
                .description("这些方块优先用带精准采集的工具挖")
                .defaultValue(
                    Blocks.WHITE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS,
                    Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.LIME_STAINED_GLASS,
                    Blocks.PINK_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS, Blocks.LIGHT_GRAY_STAINED_GLASS,
                    Blocks.CYAN_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS,
                    Blocks.BROWN_STAINED_GLASS, Blocks.GREEN_STAINED_GLASS, Blocks.RED_STAINED_GLASS,
                    Blocks.BLACK_STAINED_GLASS, Blocks.GLOWSTONE, Blocks.SEA_LANTERN
                )
                .visible(silkTouch::get)
                .build()
        );

    // ==================== 背包切换 ====================

    public Setting<Boolean> inventorySwitch = sgSwitch
        .add(
            new BoolSetting.Builder()
                .name("背包切换")
                .description("允许使用背包中的工具")
                .defaultValue(true)
                .build()
        );
    public Setting<InvSwitchMode> invSwitchMode = sgSwitch
        .add(
            new EnumSetting.Builder<InvSwitchMode>()
                .name("背包切换发包模式")
                .description("如何使用背包中的工具")
                .defaultValue(InvSwitchMode.SWAP)
                .visible(inventorySwitch::get)
                .build()
        );

    // ==================== 重挖 ====================

    public Setting<Boolean> rebreak = sgRebreak
        .add(new BoolSetting.Builder().name("自动重挖").description("方块被破坏后，该位置再次出现方块时自动重新挖掘").defaultValue(true).build());
    public Setting<Integer> rebreakDelay = sgRebreak
        .add(
            new IntSetting.Builder()
                .name("重挖延迟")
                .description("重新挖掘方块的延迟时间")
                .sliderRange(0, 10)
                .defaultValue(0)
                .visible(rebreak::get)
                .build()
        );
    public Setting<Integer> maxBreaks = sgRebreak
        .add(
            new IntSetting.Builder()
                .name("放弃等待")
                .description("超过这么多 tick 还没挖掉就放弃")
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
    private final Setting<SettingColor> rebreakSideColor = sgRender
        .add(
            new ColorSetting.Builder()
                .name("重挖侧面颜色")
                .description("重挖位置半透明框的侧面颜色")
                .defaultValue(new SettingColor(255, 192, 203, 80))
                .build()
        );
    private final Setting<SettingColor> rebreakLineColor = sgRender
        .add(
            new ColorSetting.Builder()
                .name("重挖边框颜色")
                .description("重挖位置半透明框的边框颜色")
                .defaultValue(new SettingColor(255, 192, 203, 255))
                .build()
        );

    // ==================== 内部状态 ====================

    public static BlockDate firstBlockDate = null;
    public static BlockDate secondBlockDate = null;
    private BlockDate rebreakBlockDate = null;
    /** 重挖位置上的方块已经连续出现了多少 tick（方块消失/换新位置时清零） */
    private int rebreakTicks = 0;
    /** 本轮重挖是否已经发过 STOP（方块消失后重置，避免每 tick 重复切工具/重复发包） */
    private boolean rebreakTried = false;
    /** 挖掘延迟剩余 tick（>0 时不开始新的挖掘：延迟开头点的方块直接忽略，只剩最后 2 tick 时点的排队） */
    private int mineCooldownTicks = 0;
    /** 工具切换状态：是否已切到最佳工具等切回、已等待 tick 数 */
    private boolean hasSwitch = false;
    private int switchTicks = 0;
    /** 从背包换到手上的工具：它在背包里的槽位 / 换到哪个热栏槽位（-1 = 没换） */
    private int invToolSlot = -1;
    private int invToolHotbar = -1;
    /** 正在为「等服务端把延迟破坏槽位的方块挖掉」而拿住最佳工具的方块（没在等的时候是 null） */
    private BlockDate holdBlock = null;
    /** 上一 tick 开始挖掘时安排的「开局结束包」：这一 tick 单独发出去（双挖用） */
    private BlockPos pendingStopPos = null;
    private Direction pendingStopFace = null;
    /** 本 tick 已经为哪个位置发过结束包（同一个 tick 里不重复发，免得被当成「一瞬间挖了两下」） */
    private BlockPos stopSentThisTick = null;
    /** 本 tick 是不是已经挥过手了（同一个 tick 里只挥一次，免得连发两个挥手包） */
    private boolean swingSentThisTick = false;
    // 挖掘挥手包控制：拦截下一个 swing 包 —— 挖掘开始/结束的挥手只有本地动画，不发包
    // （不依赖瞄准状态，避免挖掘开始/结束瞬间没瞄准方块时包漏拦）
    private boolean blockSwingPacket = false;
    /** 已经过了多少 tick（自己数：收尾时机和抢跑计分都按它算） */
    private int mineTicks = 0;
    /** 上一次收尾（发结束包）是在第几 tick */
    private int lastFinishTick = 0;
    /** 抢跑计分：越大说明我们越「抢」，反馈闭环拿它决定要不要退回原版节奏 */
    private double gainedAdvantage = 0.0;

    /** 服务端 STOP 立即破坏的进度线：ServerPlayerGameMode 里进度 ≥ 0.7 才会立即破坏，否则退回延迟破坏 */
    private static final int INSTANT_BREAK_PERCENT = 70;
    /** 原版破坏延迟（tick）：反作弊眼里「上一块挖完到下一块开始」的合法间隔 */
    private static final int VANILLA_BREAK_DELAY = 5;
    /** 原版节奏的挖掘间隔（tick）：反馈超标后退回这个节奏 */
    private static final int VANILLA_MINE_GAP = 6;
    /** 秒切模式（立即切回）的自动重试次数上限 */
    private static final int MAX_AUTO_RETRY = 5;
    /** 挖掘延迟只剩这么多 tick 时，点到的方块才排队等延迟结束（更早点的直接忽略） */
    private static final int QUEUE_WINDOW_TICKS = 2;

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
        rebreakTried = false;
        mineCooldownTicks = 0;
        hasSwitch = false;
        switchTicks = 0;
        invToolSlot = -1;
        invToolHotbar = -1;
        holdBlock = null;
        pendingStopPos = null;
        pendingStopFace = null;
        stopSentThisTick = null;
        swingSentThisTick = false;
        blockSwingPacket = false;
        mineTicks = 0;
        lastFinishTick = 0;
        gainedAdvantage = 0.0;
    }

    @Override
    public void onDeactivate() {
        firstBlockDate = null;
        secondBlockDate = null;
        rebreakBlockDate = null;
        rebreakTicks = 0;
        rebreakTried = false;
        mineCooldownTicks = 0;
        holdBlock = null;
        pendingStopPos = null;
        pendingStopFace = null;
        stopSentThisTick = null;
        swingSentThisTick = false;
        blockSwingPacket = false;
        lastFinishTick = 0;
        gainedAdvantage = 0.0;

        // 恢复工具栏：从背包换到手上的工具换回背包，切过的热栏槽位切回去
        swapInvToolBack();
        if (hasSwitch) {
            InvUtils.swapBack();
            hasSwitch = false;
        }
    }

    // ==================== 核心 Tick 处理 ====================

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mineCooldownTicks > 0) mineCooldownTicks--;
        stopSentThisTick = null;
        swingSentThisTick = false;
        mineTicks++;

        // 上一 tick 安排的开局结束包：这一 tick 单独发（和 START、高空包分开，避免一个 tick 里两个位置）
        flushPendingStop();

        rangeCheck();

        // 切工具后的切回。放在挖掘逻辑之前：切回计时不包含切换发生的那个 tick
        handleSwitchBack();

        // 收尾 STOP 没把方块挖掉：冷却过后补一个（放在新目标之前，补的结束包不会被新方块的开始包顶掉位置）
        retryStop();

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
            // 不是我们自己发 STOP 收尾的（服务端自己挖掉的）：延迟从这一刻重新算
            if (!firstBlockDate.switched) blockFinished();
            recordRebreak(firstBlockDate);
            firstBlockDate = null;
        }

        // 2. 开始挖掘（发 START）；「挖掘延迟」没走完的目标在这里排队等延迟结束
        if (firstBlockDate != null && !firstBlockDate.isMining && miningGateOpen()) {
            startTarget(firstBlockDate);
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
            // 不是我们自己发 STOP 收尾的（服务端自己挖掉的）：延迟从这一刻重新算
            if (!firstBlockDate.switched) blockFinished();
            recordRebreak(firstBlockDate);
            firstBlockDate = null;
        }
        if (secondBlockDate != null && isBroken(secondBlockDate.pos)) {
            if (!secondBlockDate.switched) blockFinished();
            recordRebreak(secondBlockDate);
            secondBlockDate = null;
        }

        // 2. 开始挖掘：主挖先发 START，副挖后发
        //    「挖掘延迟」没走完的目标在这里排队等延迟结束（副挖一定要在延迟之后才开始）
        //    服务端的挖掘槽位最后停在副挖上：副挖靠阈值 STOP 破坏，主挖靠 START 时占住的延迟破坏槽位破坏
        if (firstBlockDate != null && !firstBlockDate.isMining && miningGateOpen()) {
            startTarget(firstBlockDate);
        }
        if (secondBlockDate != null && !secondBlockDate.isMining && miningGateOpen()) {
            startTarget(secondBlockDate);
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
     * 先按服务端那套公式重算进度（见 {@link BlockDate#freshProgress}），进度到阈值就切工具 + 发 STOP 收尾
     * （{@link #finishTarget}）—— 判定用的进度和服务端收到结束包时算出来的就是同一个数，不另算一套
     */
    private void tickTarget(BlockDate block) {
        if (block == null || !block.isMining) return;

        block.elapsedTicks++;
        block.freshProgress();

        // 秒挖方块：开始包发出去服务端就把它破坏了，不切工具、不补结束包
        if (block.instaBreak || block.switched) return;

        // 进度到「挖掘方块阈值」就收尾：服务端 70% 才会当场破坏，阈值调 70 最快
        if (block.fraction() * 100.0 >= stopPercent()) {
            finishTarget(block);
        }
    }

    /** 实际开始收尾的进度百分比：就是「切换工具阈值」设置的数 */
    private int stopPercent() {
        return switchDamage.get();
    }

    /**
     * 收尾一个方块：切到最佳工具 → 发 STOP →（按切工具模式）切回
     * <p>
     * 服务端收到 STOP 会用「当前手持工具」重算进度 = 单 tick 进度 × (START 之后经过的 tick + 1)，
     * 进度 ≥ 0.7 才立即破坏 —— 所以切工具和 STOP 必须在同一刻发出，且此时服务端记的方块必须是它
     * <p>
     * 收尾只发一次：目标留着（掉没掉由 {@link #isBroken} 清理），没生效由 {@link #retryStop()} 过冷却再补
     */
    private void finishTarget(BlockDate block) {
        // 这一 tick 已经为这个位置发过结束包了（双挖那个「开局结束包」，用的是当时手上的物品，
        // 进度不够 70% 的话只占了个延迟破坏名额）→ 这一 tick 不再发第二个（同一个 tick 里两个结束包
        // 会被反作弊连着记账），也**不能**标成已收尾：下一 tick 再按真正的收尾发一个
        if (block.pos.equals(stopSentThisTick)) return;

        block.switched = true;

        if (isBroken(block.pos)) return;

        // 服务端现在记的不是这个方块（它只占着服务端的「延迟破坏」名额）→ 发 STOP 也会被忽略，
        // 它走「拿着工具等服务端自己补完」那条流程（见 handleSwitchBack）
        if (!block.serverTracked) return;

        block.stopSent = true;
        switchToBestTool(block);
        sendStop(block.pos, block.direction);

        // 自动重挖：先记下位置，但要等这个方块真的掉了（rebreakTried = true 挡住"方块还在就补STOP"）
        if (rebreak.get() && block.rebreak && !block.instaBreak && block.serverTracked) {
            recordRebreak(block);
            rebreakTried = true;
        }

        // 原版挖掘这条流程：切工具 → STOP → 切回，一次做完。立即切回就是同一 tick 切回去，没有兜底
        if (switchBackMode.get() == SwitchBackMode.IMMEDIATE) switchBackNow();
    }

    /**
     * 收尾 STOP 没生效（方块还在服务端）→ 过了「挖掘冷却」再补一个
     * <p>
     * 为什么必须补：服务端是拿「收到 STOP 那一刻经过了多少 tick」重算进度的，进度不够 0.7 的结束包
     * 会被丢进「延迟破坏」名额 —— 名额被别的方块占着的时候那个包就等于没发。容易挖的方块（石头这类）
     * 客户端的模拟进度一两 tick 就到阈值，结束包正好卡在服务端刚记下挖掘位置的时刻，算出来的进度最小
     * （1 tick = 单 tick 进度），最容易掉进这个坑；挖得慢的方块等了几十 tick，算出来早就过了 0.7，所以没事
     * <p>
     * 补发时机：位置必须还是服务端记着的那个（新的开始包会把它顶掉，所以补发排在开始新方块之前），
     * 而且每个结束包都得重启挖掘延迟（见 {@link #sendStopPacket}），所以间隔就是「挖掘冷却」这 6 tick
     */
    private void retryStop() {
        BlockDate block = retryTarget();
        if (block == null) return;

        switchToBestTool(block);
        sendStop(block.pos, block.direction);
        block.retryCount++;

        if (switchBackMode.get() == SwitchBackMode.IMMEDIATE) switchBackNow();
    }

    /** 需要补结束包的方块（先挖的优先），没有则返回 null */
    private BlockDate retryTarget() {
        if (needsRetry(firstBlockDate)) return firstBlockDate;
        if (needsRetry(secondBlockDate)) return secondBlockDate;
        return null;
    }

    /**
     * 发过收尾 STOP、方块还在 → 该补一个
     * <p>
     * 秒切模式（立即切回）+ 自动重试：不看挖掘冷却，下一 tick 就补，最多 5 次
     * （服务端还认这个位置才补 —— 位置被别的方块顶掉时结束包会被直接忽略）；
     * 其余情况照旧按挖掘冷却补发
     */
    private boolean needsRetry(BlockDate block) {
        if (block == null
            || !block.isMining
            || !block.stopSent
            || isBroken(block.pos)
            || holdBlock != null) return false;

        if (switchBackMode.get() == SwitchBackMode.IMMEDIATE && autoRetry.get()) {
            return block.serverTracked && block.retryCount < MAX_AUTO_RETRY;
        }

        return mineCooldownTicks <= 0;
    }

    /**
     * 一个方块挖完了（服务端把它破坏掉）
     * <p>
     * 「挖掘延迟」是「开始一次挖掘之后隔这么多 tick 才能开始下一次」。
     * 反作弊（Grim 的 FastBreak）看的是包：收到开始包时算「距上一个结束包过了多久」，
     * 间隔不到 275ms 就往缓冲里加料 —— 所以本模块**每发一个结束包就重新计时**，
     * 不管是收尾、双挖开局那个，还是重挖补的那个（见 {@link #sendStopPacket}）。
     */
    private void blockFinished() {
        mineCooldownTicks = mineCooldown.get();
        lastFinishTick = mineTicks;
    }

    /**
     * 现在能不能开始一次新的挖掘
     * <p>
     * 三个闸门：自己的挖掘冷却、原版那边的破坏延迟（可选，和走原版路径的模块共用一个）、
     * 反馈分数超标后退回的原版节奏
     */
    private boolean miningGateOpen() {
        if (mineCooldownTicks > 0) return false;
        if (syncVanillaCooldown.get() && vanillaDestroyDelay() > 0) return false;
        if (advantageOverThreshold() && mineTicks - lastFinishTick < VANILLA_MINE_GAP) return false;
        return true;
    }

    /** 原版客户端的破坏延迟（原版挖掘、秒破模块用的就是这一个） */
    private int vanillaDestroyDelay() {
        if (mc.gameMode == null) return 0;
        return ((MultiPlayerGameModeDelayAccessor) mc.gameMode).meteorsupport$getDestroyDelay();
    }

    /** 原版那条挖掘路正在挖别的方块（手动点、别的模块）：这一 tick 不抢着发我们的开始包 */
    private boolean vanillaBusyOnOther(BlockPos pos) {
        if (mc.gameMode == null) return false;

        MultiPlayerGameModeMiningAccessor vanilla = (MultiPlayerGameModeMiningAccessor) mc.gameMode;
        return vanilla.meteorsupport$isDestroying() && !pos.equals(vanilla.meteorsupport$getDestroyBlockPos());
    }

    /**
     * 抢跑计分（照反作弊的思路给自己记分）
     * <p>
     * 距离上一次收尾越近加得越多（间隔 0 加 300，每多等 1 tick 少加 50），按原版节奏挖（≥6 tick）就乘 0.9 衰减
     */
    private void scoreAdvantage() {
        if (!acFeedback.get() || lastFinishTick <= 0) return;

        int since = mineTicks - lastFinishTick;
        if (since >= VANILLA_MINE_GAP) gainedAdvantage *= 0.9;
        else gainedAdvantage += 300 - since * 50;

        gainedAdvantage = Mth.clamp(gainedAdvantage, -1000.0, 1000.0);
    }

    /** 分数超过阈值：该退回原版节奏了 */
    private boolean advantageOverThreshold() {
        return acFeedback.get() && gainedAdvantage > acThreshold.get();
    }

    /**
     * 要不要发高空绕过包
     * <p>
     * 反馈闭环开着时只在「确实在抢跑」（分数 > 0）时发，完全按原版节奏挖的时候一个都不发；
     * 反馈关掉就是老行为：只要开着「高空包绕过」就每次都发
     */
    private boolean bypassWanted() {
        if (!fastBypass.get()) return false;
        if (!acFeedback.get()) return true;
        return gainedAdvantage > 0.0;
    }

    /** 服务端那边算的「人在不在面上」：在地面上，或者「滞空挖掘绕过」把空中骗成了地面 */
    private boolean serverOnGround() {
        return mc.player == null || mc.player.onGround() || bypassGround.get();
    }

    /** 收尾时会切到手上的那把工具（热栏没有就看背包，和 switchToBestTool 用同一套） */
    private ItemStack bestToolStack(BlockState state) {
        int slot = getBestToolToUse(state);
        return slot < 0 ? mc.player.getMainHandItem() : mc.player.getInventory().getItem(slot);
    }

    /**
     * 服务端这一刻收到结束包会算出来的进度（工具按「收尾时会切到手上的那把」算）
     * <p>
     * 全模块就这一套进度：显示、收尾判定、要等多久都用它，和服务端算的是同一个数
     */
    private double predictedStopProgress(BlockDate block) {
        BlockState state = mc.level.getBlockState(block.pos);
        if (state.isAir()) return 1.0;

        return BreakData.stopProgress(state, block.pos, bestToolStack(state), block.elapsedTicks, serverOnGround());
    }

    /**
     * 挖掘开始 / 收尾的挥手
     * <p>
     * 本地动画照旧（和手动挖一样）；「挥手」开着的时候顺手把这个挥手包真发出去 ——
     * 反作弊是按「每次挖掘都该有一次挥手」看的，从头到尾不发就少了这一下
     * <p>
     * 一个 tick 只挥一次：结束包和开始包落在同一个 tick 时不会连发两个挥手包
     */
    private void swingForBreak() {
        if (swingSentThisTick) return;

        swingSentThisTick = true;
        blockSwingPacket = !swingPacket.get();
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /** 进度走完后等待服务端确认破坏，等待超过「放弃等待」则放弃该方块 */
    private boolean giveUp(BlockDate block) {
        if (!block.done) return false;

        block.timeoutTicks++;
        if (block.timeoutTicks < maxBreaks.get()) return false;

        recordRebreak(block);
        return true;
    }

    /**
     * 记录重挖位置（自动重挖开启时）
     * <p>
     * 只记「服务端还记着正在挖」的那个位置，也就是最后一次收到开始包、由我们发结束包收尾的方块。
     * 重挖的原理是：服务端破坏方块后 {@code destroyPos} 不会清掉，位置再次出现方块时补一个结束包，
     * 进度按「最后一次开始包到现在」算、早就 ≥70% → 瞬间破坏。位置对不上 destroyPos 的话，
     * 结束包会被服务端直接忽略（要 {@code pos.equals(this.destroyPos)} 才认），根本挖不掉。
     * <p>
     * 双挖时先点的方块是服务端自己走「延迟破坏」补完的，它早就不是 destroyPos 了 —— 记它没用，
     * 还会把真正能重挖的位置顶掉（先点的方块一般先掉），所以不记。
     */
    private void recordRebreak(BlockDate block) {
        if (!rebreak.get() || !block.rebreak) return;
        // 秒挖的方块服务端不记 destroyPos（收到开始包就当场破坏了），补结束包会被直接忽略，记了没用
        if (block.instaBreak) return;
        if (!block.serverTracked) return;
        rebreakBlockDate = new BlockDate(block.pos, block.direction);
        rebreakTicks = 0;
        rebreakTried = false;
    }

    /**
     * 方块是否已经不在了（变成空气，或者被水/岩浆顶掉：破坏完的格子经常立刻进流体，
     * 例如挖冰、挖水下的方块，位置上一格空气都没有）
     */
    private boolean isBroken(BlockPos pos) {
        Block block = mc.level.getBlockState(pos).getBlock();
        return block == Blocks.AIR || block == Blocks.WATER || block == Blocks.LAVA;
    }

    // ==================== 工具切换 ====================

    /** 切到该方块的最佳工具：普通收尾用，重新开始「切回」计时 */
    private void switchToBestTool(BlockDate block) {
        switchToBestTool(block, true);
    }

    /**
     * 切到该方块的最佳工具（热栏里没有合适的工具时，看「背包切换」要不要从背包拿）
     *
     * @param restartTimer 是否重新开始「切回」计时。普通收尾（发 STOP）要重新开始；
     *                     等延迟破坏槽位的方块掉下来时不要 —— 那时候工具停多久由方块什么时候掉决定，
     *                     不能和切回延迟叠加成「普通收尾 1 tick + 等待 1 tick」
     */
    private void switchToBestTool(BlockDate block, boolean restartTimer) {
        BlockState state = mc.level.getBlockState(block.pos);
        int slot = getBestToolToUse(state);

        // 最适合的那把在背包里 → 背包切换：换到主手（用完由 switchBackNow 换回背包）
        // 热栏那把只是「也能挖」（拿剑、拿锹挖石头之类）的时候一样会走到这里
        if (slot >= 9 && invToolSlot == -1) {
            swapInvToolToHand(slot);
            if (restartTimer) switchTicks = 0;
            return;
        }

        if (slot < 0 || slot > 8 || slot == mc.player.getInventory().getSelectedSlot()) return;

        // 不切回：不需要记录原槽位
        if (switchBackMode.get() == SwitchBackMode.NONE) {
            InvUtils.swap(slot, false);
            return;
        }

        InvUtils.swap(slot, true);
        hasSwitch = true;
        if (restartTimer) switchTicks = 0;
    }

    /** 切回原来的槽位（从背包换到手上的工具也要换回背包） */
    private void switchBackNow() {
        swapInvToolBack();

        if (hasSwitch) {
            InvUtils.swapBack();
            hasSwitch = false;
            switchTicks = 0;
        }
    }

    /**
     * 把背包里的工具换到主手
     * <p>
     * 和按数字键换位一模一样：工具进当前选中的热栏槽位（手立刻拿着），原来那个槽位的物品进背包，
     * 用完 {@link #swapInvToolBack()} 再换一次就换回去了。一次只拿一件，拿的时候记下两个槽位
     * <p>
     * 发包模式：交换 = 一个换位包（ContainerInput.SWAP）；光标 = 拾取 + 放下两个包（PICKUP）
     */
    private void swapInvToolToHand(int invSlot) {
        int hotbar = mc.player.getInventory().getSelectedSlot();
        invToolSlot = invSlot;
        invToolHotbar = hotbar;
        sendInvSwap(invSlot, hotbar);
    }

    /** 把从背包换到手上的工具换回背包（没换过就什么都不做） */
    private void swapInvToolBack() {
        if (invToolSlot == -1) return;

        int invSlot = invToolSlot;
        int hotbar = invToolHotbar;
        invToolSlot = -1;
        invToolHotbar = -1;
        sendInvSwap(invSlot, hotbar);
    }

    /** 背包槽位 ↔ 热栏槽位对换（换过去和换回来用的是同一个操作） */
    private void sendInvSwap(int invSlot, int hotbar) {
        if (invSwitchMode.get() == InvSwitchMode.SWAP) {
            // 换位包：button 是热栏槽位号（0-8），slot 是背包槽位在那个界面里的编号
            InvUtils.quickSwap().fromId(hotbar).to(invSlot);
        } else {
            // 光标：先拾起背包那一格，再点一下热栏那一格放下去
            InvUtils.move().from(invSlot).toHotbar(hotbar);
        }
    }

    /**
     * 切工具后的处理 —— 两条流程各走各的，不互相兜底
     * <p>
     * 1. 延迟破坏槽位的方块（服务端拿「手上的工具」自己补完的那个）：客户端进度走完 → 切到最佳工具
     * 一直拿着（服务端每个 tick 都用它重算进度），方块被破坏后再按「切工具模式」切回
     * 2. 原版挖掘（我们自己发 STOP 收尾的方块）：切工具 → STOP → 切回都在 {@link #finishTarget} 里做完，
     * 这里只管「延迟切回」的计时；结束包没生效由 {@link #retryStop()} 过冷却补发
     */
    private void handleSwitchBack() {
        // ===== 1. 延迟破坏槽位：一直拿着最佳工具 =====
        BlockDate need = holdNeeded();
        if (need != null) {
            // 服务端这一 tick 用「手上的工具」重算它的进度，顺带把「踩在地面上」发过去（滞空挖掘绕过）
            switchToBestTool(need, false);
            holdBlock = need;   // 拿着不放，直到服务端把它破坏掉
            sendFakeGround();
            return;
        }
        if (holdBlock != null) {
            // 那个方块已经不在了（被破坏 / 被放弃）→ 切回
            holdBlock = null;
            switchBackNow();
            return;
        }

        // ===== 2. 原版挖掘：按「切工具模式」切回 =====
        if (!hasSwitch && invToolSlot == -1) return;

        boolean back = switch (switchBackMode.get()) {
            // 立即切回：正常在发 STOP 的那一 tick 已经切回了，这里收尾被别的事拖住的
            case IMMEDIATE -> true;
            case DELAYED -> {
                if (switchBackOnBreak.get() && !hasLiveStopTarget()) yield true;
                if (switchTicks < switchBackDelay.get()) switchTicks++;
                yield switchTicks >= switchBackDelay.get();
            }
            // 不切回：保持最佳工具
            case NONE -> false;
        };

        // 背包换到手上的工具一定要换回去（「不切回」也一样，不然热栏少一格、背包里还少了一件）
        if (back || invToolSlot != -1) switchBackNow();
    }

    /**
     * 方块进度已经走完、但服务端还没把它破坏掉 → 需要拿着最佳工具让服务端把进度补完
     * <p>
     * 只有「服务端已经不认这个位置」的方块才需要这样：双挖里先点的那个（服务端记的挖掘位置已经被后点
     * 的那个顶掉）只能靠服务端的「延迟破坏」自己补完，而它每个 tick 都用「当前手上的工具」重算进度，
     * 手上不拿挖得动的工具它就掉不下来 —— 所以得一直拿着，直到方块被破坏（或者被 giveUp 放弃）
     * <p>
     * 服务端还记着这个位置的时候不用拿工具等：直接补一个结束包，服务端按「手上的工具」重算进度、
     * ≥ 70% 当场就破坏了（{@link #finishTarget}）
     */
    private boolean needsHelp(BlockDate block) {
        return block != null
            && block.isMining
            && block.delayedDestroy
            && !block.stopSent
            && !block.serverTracked
            && block.done
            && !isBroken(block.pos);
    }

    /** 正在等服务端把方块挖掉（需要拿住最佳工具的那个方块，先点的优先），没有则返回 null */
    private BlockDate holdNeeded() {
        if (needsHelp(firstBlockDate)) return firstBlockDate;
        if (needsHelp(secondBlockDate)) return secondBlockDate;
        return null;
    }

    /** 发过 STOP 但还没被破坏的方块（服务端当前记着的那个才有意义） */
    private boolean isLiveStopTarget(BlockDate block) {
        return block != null && block.switched && block.serverTracked && !isBroken(block.pos);
    }

    private boolean hasLiveStopTarget() {
        return isLiveStopTarget(firstBlockDate) || isLiveStopTarget(secondBlockDate);
    }

    // ==================== 辅助方法 ====================

    /**
     * 停止挖掘指定位置（高空包抵消）
     */
    public static void stopMine(BlockPos pos) {
        BlockPos bypassPos = pos.above(300);
        // 也要走客户端的预测序列：自己构造包时 sequence 是 0，反作弊会当成「乱序的挖掘包」直接取消
        MeteorClient.mc.gameMode.startPrediction(MeteorClient.mc.level, id ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, bypassPos, Direction.UP, id));
        MeteorClient.mc.gameMode.startPrediction(MeteorClient.mc.level, id ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, bypassPos, Direction.UP, id));
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
     * <p>
     * 原理：服务端破坏方块后 {@code destroyPos} 仍然指着那个位置，而进度是按「当前手持工具速度 ×
     * (gameTicks - destroyProgressStart + 1)」重算的，时间过去越久越大 —— 所以位置再次出现方块时
     * 不需要 START，切最佳工具补一个 STOP 就能瞬间挖掉（进度 ≥ 0.7 服务端立即破坏）。
     * <p>
     * 时机：方块一出现就立刻收尾（受「重挖延迟」限制，0 = 本 tick）。无条件执行，正在挖别的方块也照样收尾，
     * 不会去动那些方块的目标/进度，只是额外补一个 STOP。
     * <p>
     * 方块出现后超过「放弃等待」这么久仍未挖掉，说明服务端已经不认这个位置了，直接放弃并删除该重挖位置。
     */
    private void handleRebreak() {
        if (!rebreak.get() || rebreakBlockDate == null) return;

        BlockState state = mc.level.getBlockState(rebreakBlockDate.pos);
        if (state.getBlock() == Blocks.AIR || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA) {
            // 方块还没（重新）出现：等待期间不计时，也不重复收尾
            rebreakTicks = 0;
            rebreakTried = false;
            return;
        }

        rebreakTicks++;

        // 方块出现太久都没能挖掉 → 放弃这个重挖位置，避免永远卡着
        if (rebreakTicks > maxBreaks.get()) {
            rebreakBlockDate = null;
            return;
        }

        if (rebreakTried) return;   // 本轮已经发过 STOP，等方块被破坏
        if (rebreakTicks <= rebreakDelay.get()) return;   // 重挖延迟

        rebreakTried = true;
        rebreakNow();
    }

    /**
     * 重挖收尾：切到最佳工具 → 发 STOP →（立即切回模式）切回
     * <p>
     * 必须和正常收尾一个顺序：STOP 是在服务端处理包的那一刻用「当前手持工具」重算进度的，
     * 所以切回不能赶在 STOP 前面。延迟切回 / 不切回沿用「切工具模式」，由 {@link #handleSwitchBack()} 处理。
     * <p>
     * 这里只切槽位和补 STOP，不碰 {@code firstBlockDate} / {@code secondBlockDate}，所以正在挖的方块不受影响；
     * {@link InvUtils#swap(int, boolean)} 也不会覆盖已经记下的「切回槽位」，正常挖掘待处理的切回仍然有效。
     */
    private void rebreakNow() {
        switchToBestTool(rebreakBlockDate);
        sendStopPacket(rebreakBlockDate.pos, rebreakBlockDate.direction);
        if (switchBackMode.get() == SwitchBackMode.IMMEDIATE) switchBackNow();
    }

    // ==================== 发包方法 ====================

    /**
     * 开始一个目标：发 START（双挖模式下 {@link #mineBlock} 会紧跟着补一个 STOP，
     * 让这个方块占住服务端的「延迟破坏」名额），并把它标成服务端当前记着的挖掘方块。
     * <p>
     * 服务端只会认「最后发过 START 的那个方块」为目标，另一个方块只能靠延迟破坏名额被服务端补完。
     * <p>
     * 双挖时还要认清这个方块占的是哪个名额：START 后面紧跟的那个 STOP，服务端算出来的进度是
     * 「单tick进度 × 1」——≥ 70% 服务端当场就把它破坏了（不用我们停留）；< 70% 才会被记成
     * 「延迟破坏」，之后每个 tick 用「当前手上的工具」重算进度、够 100% 才掉（这种才需要拿着工具等）。
     */
    private void startTarget(BlockDate block) {
        BlockState state = mc.level.getBlockState(block.pos);

        // 原版那条挖掘路正在挖别的方块（手动点、别的模块）：这一 tick 不抢，等它那一套走完再由 tick 逻辑重试
        if (vanillaBusyOnOther(block.pos)) return;

        // 抢跑计分（反馈闭环拿它决定要不要退回原版节奏、要不要发高空包）
        scoreAdvantage();

        // 开始包和收尾包可能落在同一个 tick：服务端那时算的是「单 tick 进度 × 1」，
        // 所以这里从 -1 起算，进 tick 逻辑加 1 之后正好是 0
        block.elapsedTicks = -1;

        // 原版能秒挖的方块：只发一个开始包（服务端收到就当场破坏了），不走双挖/切工具阈值那套
        if (isInstaBreak(block.pos, state)) {
            mineInstaBlock(block);
            return;
        }

        mineBlock(block.pos, block.direction);
        block.isMining = true;
        block.serverTracked = true;

        if (firstBlockDate != null && firstBlockDate != block) firstBlockDate.serverTracked = false;
        if (secondBlockDate != null && secondBlockDate != block) secondBlockDate.serverTracked = false;

        // 延迟破坏名额只有一个、先占者得：已经被别的方块占着的时候，这个方块的 STOP 会被服务端直接忽略
        block.delayedDestroy = doubleBreak.get()
            && !otherDelayedDestroy(block)
            && initialStopProgress(state, block.pos) < INSTANT_BREAK_PERCENT / 100.0;

        mineCooldownTicks = mineCooldown.get();
    }

    /**
     * 原版能秒挖的方块：挥手 + 一个开始包，剩下交给服务端
     * <p>
     * 26.1 服务端的开始包分支：方块不空、用当前手持工具算出的单 tick 进度 ≥ 1.0 → 直接
     * {@code destroyAndAck(pos, seq, "insta mine")} 并 return（不记 destroyPos，也不进挖掘/延迟破坏流程）。
     * 原版客户端秒挖时同样只发一个开始包（进度够了就本地破坏，不补结束包）。
     * <p>
     * 所以这里不安排「双挖开局结束包」、也不等切换工具阈值：多发一个结束包反而会被反作弊
     * （Grim 的 FastBreak）按「挖穿这一格该用多久」算出提前收尾，往缓冲里加料。
     */
    private void mineInstaBlock(BlockDate block) {
        swingForBreak();

        // 开始包走客户端的预测序列（sequence 每次 +1，和原版一模一样）
        Direction instaFace = breakFace(block.pos, block.direction);
        mc.gameMode.startPrediction(mc.level, id ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                block.pos, instaFace, id));

        block.isMining = true;
        block.serverTracked = true;
        block.instaBreak = true;
        block.done = true;
        block.progress = 1.0;   // 服务端收到开始包就当场破坏了，进度直接算满

        if (firstBlockDate != null && firstBlockDate != block) firstBlockDate.serverTracked = false;
        if (secondBlockDate != null && secondBlockDate != block) secondBlockDate.serverTracked = false;

        mineCooldownTicks = mineCooldown.get();
    }

    /** 原版能秒挖的方块：硬度 0（单 tick 进度就是 1.0） */
    private boolean isInstaBreak(BlockPos pos, BlockState state) {
        return state.getDestroySpeed(mc.level, pos) == 0;
    }

    /**
     * 双挖时紧跟在 START 后面那个 STOP 的进度：服务端用「收到 STOP 那一刻手上的工具」算，
     * 也就是「这个方块单 tick 的进度 × 1」
     */
    private double initialStopProgress(BlockState state, BlockPos pos) {
        // 服务端算的是「单 tick 进度 × 1」，用的是收到这个结束包那一刻手上的工具
        return BreakData.perTick(state, pos, mc.player.getMainHandItem(), serverOnGround());
    }

    /** 别的目标是不是已经占着服务端的「延迟破坏」名额 */
    private boolean otherDelayedDestroy(BlockDate self) {
        return (firstBlockDate != null && firstBlockDate != self && firstBlockDate.delayedDestroy)
            || (secondBlockDate != null && secondBlockDate != self && secondBlockDate.delayedDestroy);
    }

    /**
     * 开始挖掘（发 START；双挖模式下补一个「下一 tick 才发」的 STOP）
     * <p>
     * 双挖原理（对应 26.1 原版服务端 ServerPlayerGameMode.handleBlockBreakAction）：
     * - 服务端只有「一个」挖掘槽位：收到 START 就记录 destroyPos，并中止上一个方块
     * - 收到 STOP 时用当前手持工具重算进度 = 单tick进度 * (gameTicks - destroyProgressStart + 1)：
     * - 进度 ≥ 0.7 → 立即破坏；进度 < 0.7 → 交给服务端 hasDelayedDestroy 自己走完
     * - 延迟破坏槽位同样只有一个，先占者得：START 之后补的那一下 STOP 就是抢这个名额的
     * - 先挖的方块占住延迟破坏槽位（服务端自己会破坏它），后挖的方块留在挖掘槽位上等待真实 STOP
     * <p>
     * 那个 STOP 放在**下一个 tick** 发（和原版 meteor-miku 的写法一致，它是延时 50ms 发）：
     * 同一个 tick 里对两个不同位置发挖掘包（主方块 + 高空包）本来就是反作弊眼里的「一秒点了两下」，
     * 让结束包单独占一个 tick，就不会被这种检查顺手连坐掉（先点的那个方块要靠这个结束包占延迟破坏名额，
     * 结束包被丢掉它就永远挖不掉了 —— 这就是「双挖第二下、容易挖的方块挖不掉」的来源）
     */
    public void mineBlock(BlockPos pos, Direction direction) {
        // 报的面按当前眼睛位置重算（这次点击点的面只在准星正好还指着它的时候才算数）
        Direction face = breakFace(pos, direction);

        swingForBreak();

        // 1. 发送 START 包：必须走客户端的预测序列（sequence 每次 +1）
        //    自己构造包时 sequence 是 0，服务器那边每挖一次都要「上一个 +1」，0 号开始包会被当成乱序取消
        mc.gameMode.startPrediction(mc.level, id ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, id));

        // 2. 高空包绕过：向 Y=321 发送一个额外的 START 包（sequenced packet 保证顺序）
        if (bypassWanted()) {
            BlockPos bypassPos = new BlockPos(pos.getX(), 321, pos.getZ());
            mc.gameMode.startPrediction(mc.level, id ->
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, bypassPos, Direction.DOWN, id));
        }

        // 3. 双挖模式：安排一个下一 tick 的 STOP，让服务端接管这个方块
        //    - 进度 < 0.7 时：服务端记录 hasDelayedDestroy，之后按原版进度自己把方块破坏掉
        //    - 进度 ≥ 0.7 时（软方块）：服务端直接破坏
        if (doubleBreak.get()) {
            pendingStopPos = pos;
            pendingStopFace = face;
        }
    }

    /** 上一 tick 安排的「开局结束包」：这一 tick 单独发出去（不带挥手、不带高空包） */
    private void flushPendingStop() {
        if (pendingStopPos == null) return;

        BlockPos pos = pendingStopPos;
        Direction face = pendingStopFace;
        pendingStopPos = null;
        pendingStopFace = null;

        sendStopPacket(pos, face);
    }

    /**
     * 只发一个 STOP 包（收尾 / 重挖用，不带挥手）
     * <p>
     * 服务端是拿「收到 STOP 那一刻手上的工具」按
     * {@code 单tick进度 × (gameTicks - destroyProgressStart + 1)} 重算整个进度的，
     * 所以滞空挖掘绕过要在 STOP 之前先把「踩在地面上」发出去（空中惩罚是最后一步 {@code speed /= 5}，
     * 去掉它整个进度就按地面速度算），否则在空中这一下会被算成 1/5，进度不够 0.7 就被丢掉。
     */
    private void sendStopPacket(BlockPos pos, Direction direction) {
        // 反作弊的 swing 检查：这一 tick 还没挥过手（比如这一下是补发的结束包）就补一个
        swingForBreak();
        sendFakeGround();

        // 报的面按当前眼睛位置重算（挖掘期间人可能已经绕到方块另一边了）
        Direction face = breakFace(pos, direction);

        mc.gameMode.startPrediction(mc.level, id ->
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, id));

        // 本模块每发一个结束包就重新计时：下一个开始包必须离它一个延迟
        // （结束包刚过去就发下一个开始包，在反作弊眼里就是「间隔 0」，延迟设多大都没用）
        blockFinished();
        stopSentThisTick = pos;
    }

    /**
     * 滞空挖掘绕过：给服务端补一个「踩在地面上」的位置包
     * <p>
     * 服务端的挖掘进度最后一步是 {@code if (!player.onGround()) speed /= 5}（26.1 {@code Player.getDestroySpeed}），
     * 而 onGround 这个标记就是客户端发过来的，所以先发一个 onGround = true 的位置包，
     * 服务端接下来这一 tick 就会按地面速度算进度。Y 微调 1.0e-9 只是让这个包不和上一 tick 的位置完全一样。
     * <p>
     * 必须在 STOP 之前发（服务端按收到的顺序处理），并且延迟破坏那个方块也要按 tick 补，
     * 否则服务端每个 tick 用它自己的 onGround 重算，会一直按空中的 1/5 走。
     */
    private void sendFakeGround() {
        if (!bypassGround.get() || mc.player.onGround() || mc.player.isFallFlying()) return;

        mc.getConnection().send(
            new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY() + 1.0e-9, mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot(), true, mc.player.horizontalCollision));
    }

    /**
     * 发挖掘包时用的面：按「玩家眼睛现在在方块的哪一侧」重算，保证这个面从当前位置看得见
     * <p>
     * Grim 的 PositionBreakA 在「报的面位于玩家眼睛的反面」时会标记 + 取消这个包（表现就是方块挖不掉），
     * 而挖掘期间人一动（绕到方块另一边、跳起来、被活塞顶开）原来点的那个面就可能不成立了。
     * 挑不出面（人就在方块里）时用原来点的那个面 —— 那种情况反作弊本来就直接放行。
     */
    private Direction breakFace(BlockPos pos, Direction fallback) {
        if (!adaptiveFace.get() || mc.player == null) return fallback;

        Vec3 eye = mc.player.getEyePosition();
        double x1 = pos.getX(), y1 = pos.getY(), z1 = pos.getZ();

        // 每个方向算「眼睛在这个面外面多远」，取最外面的那个；必须真的在外面（留一点余量，免得贴面时抖）
        double best = 1.0e-3;
        Direction face = fallback;

        if (x1 - eye.x > best) { best = x1 - eye.x; face = Direction.WEST; }
        if (eye.x - (x1 + 1.0) > best) { best = eye.x - (x1 + 1.0); face = Direction.EAST; }
        if (y1 - eye.y > best) { best = y1 - eye.y; face = Direction.DOWN; }
        if (eye.y - (y1 + 1.0) > best) { best = eye.y - (y1 + 1.0); face = Direction.UP; }
        if (z1 - eye.z > best) { best = z1 - eye.z; face = Direction.NORTH; }
        if (eye.z - (z1 + 1.0) > best) { best = eye.z - (z1 + 1.0); face = Direction.SOUTH; }

        return face;
    }

    /**
     * 发送 STOP 包 - 带绕过技术
     * 1. 高空 STOP 抵消
     * 2. 使用 sequenced packet 发送主 STOP（滞空挖掘绕过的位置包在里面）
     */
    private void sendStop(BlockPos pos, Direction direction) {
        // 主 STOP 包先发（使用 sequenced packet 保证顺序正确）：
        // 「一个 tick 里对两个不同位置发挖掘包」会被反作弊盯上，主包放前面，被连坐的是后面那个假的
        sendStopPacket(pos, direction);

        // 高空 STOP 抵消
        if (bypassWanted()) {
            BlockPos bypassPos = new BlockPos(pos.getX(), 321, pos.getZ());
            mc.gameMode.startPrediction(mc.level, id ->
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, bypassPos, Direction.DOWN, id));
        }
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
            // 挖掘开始/结束的挥手包一律拦住（本地动画还在），其他来源的 swing 包照常放行
            if (blockSwingPacket) {
                blockSwingPacket = false;
                event.cancel();
                return;
            }
            // 其余 swing 包（攻击挥手、其他来源）照常放行：单击方块时的攻击挥手已由 onDoAttack
            // （拦截 startAttack）处理，这里不按瞄准状态过滤
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

        // 挖掘延迟还没走完：延迟开头点的方块直接忽略（不记目标，也不动正在挖的那个）；
        // 只剩最后 2 tick 时点的方块才排队，等延迟走完由 tick 逻辑补发 START
        // （双挖的第二个走这条路，所以它一定在延迟之后才开始，不会连着发两个开始包）
        if (mineCooldownTicks > QUEUE_WINDOW_TICKS) return;

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

        // 延迟还没结束：排队等 tick 逻辑补发 START
        if (!miningGateOpen()) return;

        startTarget(target);
    }

    // ==================== 渲染 ====================

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        if (firstBlockDate != null && mc.level.getBlockState(firstBlockDate.pos).getBlock() != Blocks.AIR) {
            renderBlock(event, firstBlockDate.pos, firstBlockDate.fraction());
        }

        if (secondBlockDate != null && mc.level.getBlockState(secondBlockDate.pos).getBlock() != Blocks.AIR) {
            renderBlock(event, secondBlockDate.pos, secondBlockDate.fraction());
        }

        // 重挖位置始终显示（方块被破坏、还没重新出现的等待期也显示）
        if (rebreak.get() && rebreakBlockDate != null) {
            BlockPos blockPos = rebreakBlockDate.pos;
            event.renderer.box(
                blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                blockPos.getX() + 1, blockPos.getY() + 1, blockPos.getZ() + 1,
                rebreakSideColor.get(), rebreakLineColor.get(), shapeMode.get(), 0);
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
     * 方块在「精准采集列表」里时优先挑带精准采集的工具（没有的话退回最快的）
     */
    public int getBestTool(BlockState blockState) {
        boolean silk = wantSilkTouch(blockState);

        int slot = findBestTool(blockState, 0, 8, silk);
        if (slot == -1 && silk) slot = findBestTool(blockState, 0, 8, false);
        return slot;
    }

    /** 背包（9-35）里挖得最快的工具，规则和热栏那个一样 */
    private int getBestInventoryTool(BlockState blockState) {
        boolean silk = wantSilkTouch(blockState);

        int slot = findBestTool(blockState, 9, 35, silk);
        if (slot == -1 && silk) slot = findBestTool(blockState, 9, 35, false);
        return slot;
    }

    /**
     * 最终拿在手上的那把工具：热栏里那把不如背包里那把时（「背包切换」会把它换到手上）给背包里的槽位
     * <p>
     * 进度模拟和收尾前的切工具都用这个，两边必须一致：模拟用哪把算，收尾时就得把哪把换到手上
     */
    private int getBestToolToUse(BlockState state) {
        int slot = getBestTool(state);
        if (!inventorySwitch.get()) return slot;

        int invSlot = getBestInventoryTool(state);
        if (invSlot == -1) return slot;

        ItemStack invStack = mc.player.getInventory().getItem(invSlot);
        ItemStack hotbarStack = slot == -1 ? ItemStack.EMPTY : mc.player.getInventory().getItem(slot);
        return isBetterTool(invStack, state, hotbarStack, wantSilkTouch(state)) ? invSlot : slot;
    }

    /** 这个方块要不要优先用带精准采集的工具 */
    private boolean wantSilkTouch(BlockState blockState) {
        return silkTouch.get() && silkBlocks.get().contains(blockState.getBlock());
    }

    /**
     * a 是不是比 b 更适合挖这个方块：先看精准采集（列表里的方块，带精准采集的赢），一样再看挖掘速度
     * <p>
     * 用于「背包里那把和热栏里那把哪个更好」，两边都是各自范围里挑出来的最好的，所以这样比就够了
     */
    private boolean isBetterTool(ItemStack a, BlockState state, ItemStack b, boolean silk) {
        boolean silkA = silk && Utils.getEnchantmentLevel(a, Enchantments.SILK_TOUCH) > 0;
        boolean silkB = silk && Utils.getEnchantmentLevel(b, Enchantments.SILK_TOUCH) > 0;
        if (silkA != silkB) return silkA;

        return a.getDestroySpeed(state) > b.getDestroySpeed(state);
    }

    /** 在 [from, to] 这些槽位里找挖得最快的工具；onlySilk = 只认带精准采集的 */
    private int findBestTool(BlockState state, int from, int to, boolean onlySilk) {
        double bestScore = -1.0;
        int bestSlot = -1;

        for (int i = from; i <= to; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isTool(stack)) continue;
            if (onlySilk && Utils.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH) <= 0) continue;

            double score = stack.getDestroySpeed(state);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    /** 镐/斧/锹/剑（26.1 用 ItemTags 判断，不再依赖 PickaxeItem/SwordItem 类） */
    private boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES)
            || stack.is(ItemTags.AXES)
            || stack.is(ItemTags.SHOVELS)
            || stack.is(ItemTags.SWORDS);
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
        /**
         * 服务端那套进度（0-1，可能超过 1）：单 tick 进度（收尾时会拿到手上的那把工具）× (开始包之后过了多少 tick + 1)
         * <p>
         * 显示、收尾判定、要等多久都用这一个数，不再另算一套
         */
        public double progress;
        public BlockState blockState;
        /** 开始包发出去之后过了多少 tick（服务端就是从开始包那一刻开始数的） */
        public int elapsedTicks = 0;
        public boolean isMining = false;
        /** 服务端当前记着的挖掘方块（最后发过 START 的那一个）；另一个方块只占着服务端的「延迟破坏」名额 */
        public boolean serverTracked = false;
        /** 占着服务端「延迟破坏」名额（START 后的那个 STOP 进度 < 70%）：服务端要拿「手上的工具」自己把它补完 */
        public boolean delayedDestroy = false;
        /** 已经发过收尾的 STOP（原版挖掘那条流程走完了，不再需要拿工具等） */
        public boolean stopSent = false;
        /** 是否已经达到切换工具阈值（切过工具、发过 STOP） */
        public boolean switched = false;
        /** 秒切模式自动重试已经补发了几次结束包（到上限就不再补） */
        public int retryCount = 0;
        /** 进度走完后等待服务端确认破坏的 tick 数 */
        public int timeoutTicks = 0;
        /** 原版能秒挖的方块（硬度 0）：只发开始包，服务端收到就当场破坏，不需要结束包 */
        public boolean instaBreak = false;
        public boolean rebreak = true;

        public BlockDate(BlockPos pos, Direction direction) {
            this(pos, direction, true);
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
         * 重算进度（每 tick 一次）
         * <p>
         * 服务端就是这么算的：单 tick 进度 × (开始包之后过了多少 tick + 1)。工具、附魔、急迫、挖掘疲劳、
         * 上没上地面变了它都跟着变，所以每一次都重新算，不缓存
         * <p>
         * tick 数是自己数的：服务端数的是它自己的 gameTicks，两边都是「开始包之后过了多少 tick」，同一个口径
         */
        public void freshProgress() {
            blockState = mc.level.getBlockState(pos);
            progress = predictedStopProgress(this);

            // 进度到 100% 就是「该挖穿了」，剩下只是等服务端那边把它破坏掉
            if (progress >= 1.0) done = true;
        }

        /** 进度百分比（0-1）：进度本身就是百分比，夹一下就行（拿着工具等延迟破坏的时候画满） */
        public double fraction() {
            double f = done ? Math.max(progress, 1.0) : progress;
            return Mth.clamp(f, 0.0, 1.0);
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

    /** 背包切换怎么跟服务端说 */
    public enum InvSwitchMode {
        SWAP,
        PICKUP
    }
}
