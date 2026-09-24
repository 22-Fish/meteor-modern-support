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

import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.DoublePlaceAim;
import fish22.modernsupport.utils.LegalPlace;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.DirectionAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.combat.Surround;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.Dir;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「包围」的放置：放哪几格照旧由原版挑（其他设置照旧生效），这里只把「怎么放」换成
 * 合法放置，并新增下面几个选项。
 *
 * <ul>
 *   <li><b>空中放置</b>（就是原版的 air-place，默认开）：允许把空气本身当瞄准面 —— 直接点目标
 *       方块自己朝眼睛的那一面，用 {@link LegalPlace#computeAir} 算角度。关掉之后就只点方块不点
 *       空气了（下面那条），Grim 服要关掉它。</li>
 *   <li><b>支持块</b>（空中放置关掉后才显示，默认开）：目标位置没有能点的面时，先在旁边放一块
 *       支撑块帮它变成可放。挑支撑块的时候，优先挑「同一个角度既能放支撑块、又能顺手把目标也放下」
 *       的那一个（这样本 tick 就能放两块）；挑不到就这一 tick 只放支撑块，目标下一 tick 再放。</li>
 *   <li><b>仅合法</b>（默认关）：只在算得出合法放置角度时放，算不出来就跳过这一格。</li>
 *   <li><b>合法转头</b>（默认关）：转视角从原版 Meteor 旋转换成 {@link LegalRotation}
 *       （转向 {@link LegalPlace} 算出来的角度，交互包排在带着这份朝向的移动包之后）。
 *       关着就是原版：Meteor 自己转到算出来的那个命中点。</li>
 *   <li><b>包围脚底 / 包围头顶</b>（两个默认都开）：脚那一层、头那一层各放不放。头顶那层不用
 *       再开「双重高度」，勾上就围（开着「双重高度」也一样）。</li>
 *   <li><b>空中也围</b>（默认开）：人在空中时也照围 —— 不再被原版「只在地面上」拦、
 *       也不会被「换高度自动关」在跳起来的那一下直接关掉。</li>
 *   <li><b>背包放置</b>（默认开）：快捷栏 / 副手都没有要用的方块时，从背包里换一块出来放
 *       （{@link BackpackUse}，同一 tick 内换回）。</li>
 * </ul>
 *
 * <p>「包围头顶」开着时，每 tick 还会先给每个方向试一次「一个角度同 tick
 * 把脚底和头顶两块都放下」（{@link #tryDoublePlace}）：两块必须落在同一条射线上，而一份朝向只对应
 * 一条射线 —— 所以头上那块点的是「马上要放下的脚底块」的顶面，做法是脚底那块先发、服务器按顺序
 * 处理第二下时它已经在世界上了。找不到这样的角度就照旧一 tick 一块。
 *
 * <p>四个老选项全按默认值（空中放置开、支持块开但用不上、仅合法关、合法转头关）时本类什么都不做，
 * 完全走原版。
 */
@Mixin(value = Surround.class, remap = false)
public abstract class MixinSurround {

    @Shadow
    private Setting<List<Block>> blocks;

    @Shadow
    private Setting<Integer> delay;

    @Shadow
    private Setting<Integer> blocksPerTick;

    @Shadow
    private Setting<Surround.Center> center;

    @Shadow
    private Setting<Boolean> doubleHeight;

    @Shadow
    private Setting<Boolean> onlyOnGround;

    @Shadow
    private Setting<Boolean> airPlace;

    @Shadow
    private Setting<Boolean> toggleOnYChange;

    @Shadow
    private Setting<Boolean> toggleOnComplete;

    @Shadow
    private Setting<Boolean> rotate;

    @Shadow
    private Setting<Boolean> swing;

    @Shadow
    private Setting<Boolean> render;

    @Shadow
    private Setting<Boolean> renderBelow;

    @Shadow
    private int timer;

    @Shadow
    protected abstract boolean isAirPlace(BlockPos pos);

    @Shadow
    protected abstract boolean place(BlockPos placePos, FindItemResult block);

    @Shadow
    protected abstract void draw(BlockPos renderPos, Render3DEvent event, int exclude);

    @Unique
    private Setting<LegalRotation.Mode> legalRotation;

    @Unique
    private Setting<Integer> legalRotationPriority;

    @Unique
    private Setting<Boolean> onlyLegal;

    @Unique
    private Setting<Boolean> supportBlock;

    @Unique
    private Setting<Boolean> surroundFeet;

    @Unique
    private Setting<Boolean> surroundHead;

    @Unique
    private Setting<Boolean> surroundAir;

    @Unique
    private Setting<Boolean> backpackPlace;

    @Unique
    private Setting<BackpackUse.Mode> backpackMode;

    @Unique
    private Setting<BackpackUse.TargetSlot> backpackSlot;

    /** 本 tick 已经排上放置包的位置：原版那一步先放了支撑块时，后面那一步不要再放一遍 */
    @Unique
    private Set<BlockPos> queuedThisTick;

    /**
     * 上一 tick 排过放置包、可这一 tick 看还是空的格子（服务端没接受 / 刚被挖掉）。
     *
     * <p>这种格每 tick 都会「排包成功、方块没上」，会一直吃掉「每 tick 放几块」的名额，
     * 把它后面的其他方向全挤掉 —— 所以这一 tick 先放别的格，这些格挪到最后再试。
     */
    @Unique
    private Set<BlockPos> stalled;

    /** 本 tick 合法转头转过的角度（一 tick 只能带一份朝向，第二份角度不同的就不放了） */
    @Unique
    private boolean rotatedThisTick;

    @Unique
    private float rotatedYaw;

    @Unique
    private float rotatedPitch;

    /**
     * 「一个角度放两块」第一轮的采样位置（瞄准点在脚底块放好之后的顶面上，0~1 是这一格里的位置）。
     *
     * <p>顺序就是试的顺序：先试靠中间的（命中的那个点在块面上离棱远，服务器那边预判差一点也还打在
     * 这一面上）。旁边有墙可点时用的就是这一批。
     */
    @Unique
    private double[] doubleTopFractions;

    /**
     * 第二轮的采样位置（瞄准点在脚底那一格的底面上，也就是脚下那块地板的顶面）。
     *
     * <p>脚下是地板、旁边没东西可点时就只剩这一条路：两块都落在同一竖列上，角度很陡（将近 50 度），
     * 而且命中的那个点贴着这一格的棱 —— 能不能成还得看人站得离目标那一列够不够近，站远了没有解。
     */
    @Unique
    private double[] doubleBottomFractions;

    /** 两个方向都用这几个采样位置（横着这一列里的左右位置） */
    @Unique
    private double[] doubleSideFractions;

    /**
     * 成员兜底初始化。
     *
     * <p>Mixin 自带的「字段初始化」在有些环境下不生效（它是拿构造函数里的行号去切出字段初始化那几行
     * 指令，javac 给这份 mixin 生成的行号正好把字段那几行框在构造函数范围里，切出来是空的），
     * 所以这些成员统一在这儿初始化：构造函数注入里调一次，各个入口再兜一次。
     */
    @Unique
    private void ensureState() {
        if (queuedThisTick == null) queuedThisTick = new HashSet<>();
        if (stalled == null) stalled = new HashSet<>();
        if (doubleTopFractions == null) {
            doubleTopFractions = new double[]{0.5, 0.3, 0.7, 0.12, 0.88};
            doubleBottomFractions = new double[]{0.5, 0.7, 0.85, 0.95, 0.3, 0.6, 0.8};
            doubleSideFractions = new double[]{0.5, 0.3, 0.7};
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        ensureState();

        Surround self = (Surround) (Object) this;
        SettingGroup general = self.settings.getDefaultGroup();
        SettingGroup backpack = self.settings.createGroup("背包放置");

        supportBlock = new BoolSetting.Builder()
            .name("支持块")
            .description("目标位置没有能点的面时，先放一块支撑块帮它变成可放（能同 tick 放两块就一起放）。")
            .defaultValue(true)
            .visible(() -> !airPlace.get())
            .build();

        onlyLegal = new BoolSetting.Builder()
            .name("仅合法")
            .description("只在算得出合法放置角度时放，算不出来就跳过这一格。")
            .defaultValue(false)
            .build();

        legalRotation = new EnumSetting.Builder<LegalRotation.Mode>()
            .name("合法转头")
            .description("开启后把原版转头换成合法转头。")
            .defaultValue(LegalRotation.Mode.OFF)
            .build();

        legalRotationPriority = new IntSetting.Builder()
            .name("合法转头优先级")
            .description("合法转头的优先级")
            .defaultValue(0)
            .sliderRange(-20, 20)
            .visible(() -> legalRotation.get() != LegalRotation.Mode.OFF)
            .build();

        surroundFeet = new BoolSetting.Builder()
            .name("包围脚底")
            .description("在脚那一层围一圈方块。")
            .defaultValue(true)
            .build();

        surroundHead = new BoolSetting.Builder()
            .name("包围头顶")
            .description("在头那一层也围一圈方块（不用再开「双重高度」，它自己就是一层）")
            .defaultValue(true)
            .build();

        surroundAir = new BoolSetting.Builder()
            .name("空中也围")
            .description("人在空中（跳/飞/被打飞）时照常围：不受「只在地面上」拦，也不会被「换高度自动关」关掉")
            .defaultValue(true)
            .build();

        backpackPlace = new BoolSetting.Builder()
            .name("背包放置")
            .description("快捷栏/副手都没有要用的方块时，从背包里换出来放。")
            .defaultValue(true)
            .build();

        backpackMode = new EnumSetting.Builder<BackpackUse.Mode>()
            .name("背包放置发包模式")
            .description("2次SWAP点击；4 次PICKUP点击")
            .defaultValue(BackpackUse.Mode.SWAP)
            .visible(backpackPlace::get)
            .build();

        backpackSlot = new EnumSetting.Builder<BackpackUse.TargetSlot>()
            .name("背包放置目标槽位")
            .description("背包放置切换的槽位")
            .defaultValue(BackpackUse.TargetSlot.OFFHAND)
            .visible(backpackPlace::get)
            .build();

        insertAfter(general, "air-place", supportBlock);
        insertAfter(general, "double-height", surroundFeet);
        insertAfter(general, "包围脚底", surroundHead);
        insertAfter(general, "包围头顶", surroundAir);
        insertAfter(general, "protect", onlyLegal);
        insertAfter(general, "仅合法", legalRotation);
        insertAfter(general, "合法转头", legalRotationPriority);

        backpack.add(backpackPlace);
        backpack.add(backpackMode);
        backpack.add(backpackSlot);
    }

    // ====== 每 tick ======

    /**
     * 接管「包围」的每 tick 逻辑。
     *
     * <p>原版那一套照抄（延迟、y 变化自动关、只站在地上时工作、中心对齐、放完自动关），
     * 只多出「包围脚底 / 包围头顶 / 双重高度下一个角度放两块 / 背包放置」这几件事。
     */
    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void onTickHead(TickEvent.Pre event, CallbackInfo ci) {
        ci.cancel();
        surroundTick();
    }

    /** 关掉再开时把「上一 tick 的记录」清掉，免得拿上一次开启时的旧位置来判断 */
    @Inject(method = "onActivate", at = @At("TAIL"))
    private void onActivateReset(CallbackInfo ci) {
        ensureState();
        queuedThisTick.clear();
        stalled.clear();
    }

    @Unique
    private void surroundTick() {
        ensureState();

        // 上一 tick 排过包、这一 tick 还是空的格：本 tick 排到最后处理，别把其他能放的格挡住
        stalled.clear();
        if (mc.level != null) {
            for (BlockPos pos : queuedThisTick) {
                if (mc.level.getBlockState(pos).canBeReplaced()) stalled.add(pos);
            }
        }

        // 每 tick 开头清掉「这一 tick 已经排过放置 / 已经转过角度」的记录（原版那一步在这里）
        queuedThisTick.clear();
        rotatedThisTick = false;

        Surround self = (Surround) (Object) this;

        // 延迟
        if (timer++ < delay.get()) return;

        // 人在空中：开着「空中也围」时照常干活（下面两个「地面对策」都让路）
        boolean inAir = !mc.player.onGround();
        boolean workInAir = surroundAir.get() && inAir;

        // 换高度（走一步、跳一下）自动关（空中不关：人在空中高度每 tick 都在变，一关就永远不围了）
        if (toggleOnYChange.get() && !workInAir && mc.player.yo != mc.player.getY()) {
            self.toggle();
            return;
        }

        // 只站在地上时工作（「空中也围」开着时，人在空中也干活）
        if (onlyOnGround.get() && inAir && !workInAir) return;

        // 有没有能用的方块（开了「背包放置」连背包一起找）
        FindItemResult item = findBlock();
        if (!item.found()) return;

        // 人在空中不拉中心：会把飞行带跑
        if (center.get() == Surround.Center.Always && !inAir) PlayerUtils.centerPlayer();

        int placedCount = 0;
        boolean complete = true;
        BlockPos playerPos = mc.player.blockPosition();
        // 「包围头顶」自己就是一层，不再挂在「双重高度」上
        boolean headLayer = surroundHead.get();
        Block block = blockOf(item);

        // 方向顺序：能放的排前面，上一 tick 没放上的排最后（顺序本身还是原版那个固定顺序）
        List<Direction> dirs = new ArrayList<>(List.of(DirectionAccessor.meteor$getHorizontal()));
        dirs.sort(Comparator.comparingInt((Direction direction) -> stalled.contains(playerPos.relative(direction)) ? 1 : 0));

        // 脚那一层
        if (surroundFeet.get()) {
            for (Direction direction : dirs) {
                BlockPos placePos = playerPos.relative(direction);

                // 这一格现在放不了（被方块占着 / 有实体挡着）：跳过去看下一个方向，
                // 别让它把「每 tick 放几块」的名额吃掉；四个方向全放不了本 tick 才什么都不做
                if (!stillOpen(placePos, block)) continue;

                // 双重高度：先试「一个角度这一 tick 把脚底和它上面那块一起放下」
                if (headLayer
                    && mc.level.getBlockState(placePos).canBeReplaced()
                    && mc.level.getBlockState(placePos.above()).canBeReplaced()
                    && tryDoublePlace(placePos, item)) {
                    placedCount += 2;
                    if (placedCount >= blocksPerTick.get()) break;
                    continue;
                }

                // 空中放置关着时先垫一块支撑（原版那一步；这里顺带把「空中放置关掉就不垫」也包进来了）
                if (!airPlace.get() && isAirPlace(placePos) && mc.level.getBlockState(placePos).canBeReplaced()) {
                    if (place(placePos.below(), item) && ++placedCount >= blocksPerTick.get()) break;
                    if (stillOpen(placePos.below(), block)) complete = false;
                }

                if (place(placePos, item) && ++placedCount >= blocksPerTick.get()) break;
                if (stillOpen(placePos, block)) complete = false;
            }
        }

        // 头那一层。
        // 不再等「脚底那层全放满」：自己站的那几格里有人/实体占着时脚底那一格永远放不上，
        // 老写法（complete 才放头）会连带头顶一层永远不围
        if (headLayer) {
            for (Direction direction : dirs) {
                BlockPos placePos = playerPos.relative(direction).above();

                // 同上：头这层放不了的格跳过，不占名额
                if (!stillOpen(placePos, block)) continue;

                if (place(placePos, item) && ++placedCount >= blocksPerTick.get()) break;
                if (stillOpen(placePos, block)) complete = false;
            }
        }

        timer = 0;

        // 都放完了就自动关
        if (complete && toggleOnComplete.get()) {
            self.toggle();
            return;
        }

        // 还没放完就继续把玩家往中心拉，免得卡在方块边上
        if (!complete && !inAir && center.get() == Surround.Center.Incomplete) PlayerUtils.centerPlayer();
    }

    /** 这一 tick 要用的方块：先说快捷栏 / 副手，都没有并且开着「背包放置」时才去背包里找 */
    @Unique
    private FindItemResult findBlock() {
        FindItemResult hotbar = InvUtils.findInHotbar(this::isSurroundBlock);
        if (hotbar.found() || !backpackPlace.get()) return hotbar;
        return InvUtils.find(this::isSurroundBlock);
    }

    /**
     * 这格是不是「还空着、而且放得下」。
     *
     * <p>用来判断这一层放完了没有。占着的实体（人站在这一格里、箱子这些）不算：
     * 那种格永远放不上，不能拖着模块一直算「没收尾」
     */
    @Unique
    private boolean stillOpen(BlockPos pos, Block block) {
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;
        return block == null || BlockUtils.canPlaceBlock(pos, true, block);
    }

    /** 这个物品是不是模块「方块」列表里的方块 */
    @Unique
    private boolean isSurroundBlock(ItemStack stack) {
        return blocks.get().contains(Block.byItem(stack.getItem()));
    }

    // ====== 渲染 ======

    /**
     * 渲染里把「包围脚底 / 包围头顶」算进去。
     *
     * <p>两层都在原位（脚底开，双重高度开着时头顶也开）时不插手，完全交给原版；
     * 关掉其中任意一层才自己画一遍（画的就是原版那几个框，只是少画一层）。
     */
    @Inject(method = "onRender3D", at = @At("HEAD"), cancellable = true)
    private void onRender3DTweaks(Render3DEvent event, CallbackInfo ci) {
        ensureState();

        if (!render.get()) return;

        boolean headLayer = surroundHead.get();
        if (surroundFeet.get() && headLayer == doubleHeight.get()) return;

        ci.cancel();

        BlockPos playerPos = mc.player.blockPosition();

        if (renderBelow.get()) draw(playerPos.below(), event, 0);

        for (Direction direction : DirectionAccessor.meteor$getHorizontal()) {
            BlockPos renderPos = playerPos.relative(direction);
            if (surroundFeet.get()) draw(renderPos, event, headLayer ? Dir.UP : 0);
            if (headLayer) draw(renderPos.above(), event, Dir.DOWN);
        }
    }

    // ====== 放置 ======

    @Redirect(
        method = "place(Lnet/minecraft/core/BlockPos;Lmeteordevelopment/meteorclient/utils/player/FindItemResult;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;place(Lnet/minecraft/core/BlockPos;Lmeteordevelopment/meteorclient/utils/player/FindItemResult;ZIZZ)Z"
        )
    )
    private boolean redirectPlace(BlockPos placePos, FindItemResult item, boolean doRotate, int rotationPriority, boolean swingHand, boolean checkEntities) {
        return placeSmart(placePos, item, doRotate, rotationPriority, swingHand, checkEntities);
    }

    /**
     * 放这一格。
     *
     * <p>「空中放置 / 支持块 / 仅合法 / 合法转头 / 背包放置」一个都没用上（默认就是这种）时完全
     * 走原版，行为跟没装这个 mixin 一样。
     */
    @Unique
    private boolean placeSmart(BlockPos pos, FindItemResult item, boolean doRotate, int rotationPriority, boolean swingHand, boolean checkEntities) {
        ensureState();

        if (mc.player == null || mc.level == null || pos == null) return false;
        if (queuedThisTick.contains(pos)) return false;

        Block block = blockOf(item);
        if (block == null) return false;

        boolean air = airPlace.get();
        boolean support = !air && supportBlock.get();
        boolean legal = legalRotation.get() != LegalRotation.Mode.OFF;

        // 原版那一档：方块在快捷栏/副手、其它选项也都没动 → 完全走原版
        if (air && !legal && !onlyLegal.get() && !needBackpack(item)) {
            return BlockUtils.place(pos, item, doRotate, rotationPriority, swingHand, checkEntities);
        }

        if (!BlockUtils.canPlaceBlock(pos, checkEntities, block)) return false;

        double reach = LegalPlace.defaultReach();

        if (air) {
            // 空中放置：直接把目标方块自己（空气）当瞄准面
            LegalPlace.AirAim aim = LegalPlace.computeAir(pos, true, reach);
            if (aim != null) {
                return send(item, swingHand, doRotate, rotationPriority, aim.yaw(), aim.pitch(), aim.rotated(),
                    List.of(new BlockHitResult(aim.hitPos(), aim.face(), pos, false)),
                    List.of(pos));
            }
        } else {
            // 只能用现成的支撑面
            LegalPlace.Aim aim = LegalPlace.compute(pos, LegalPlace.supportFaces(pos), true, 0.0, reach);
            if (aim != null) {
                return send(item, swingHand, doRotate, rotationPriority, aim.yaw(), aim.pitch(), aim.rotated(),
                    List.of(new BlockHitResult(aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock(), false)),
                    List.of(pos));
            }

            if (support && placeWithSupport(pos, block, item, doRotate, rotationPriority, swingHand, checkEntities, reach)) {
                return true;
            }
        }

        // 算不出合法角度：开了「仅合法」就不放；「空中放置」关着也不点空气
        if (onlyLegal.get() || !air) return false;
        return placeVanillaLike(pos, item, doRotate, rotationPriority, swingHand, checkEntities);
    }

    /**
     * 原版那一放。
     *
     * <p>方块在快捷栏/副手时就是 Meteor 自己的 {@code BlockUtils.place}（行为完全不变）；
     * 只有背包里有时按同样的规矩自己算一个命中点，交给背包交换那条路。
     */
    @Unique
    private boolean placeVanillaLike(BlockPos pos, FindItemResult item, boolean doRotate, int rotationPriority, boolean swingHand, boolean checkEntities) {
        if (!needBackpack(item)) {
            return BlockUtils.place(pos, item, doRotate, rotationPriority, swingHand, checkEntities);
        }

        Vec3 hitPos = Vec3.atCenterOf(pos);
        BlockPos neighbour;
        Direction side = BlockUtils.getPlaceSide(pos);
        if (side == null) {
            side = Direction.UP;
            neighbour = pos;
        } else {
            neighbour = pos.relative(side);
            hitPos = hitPos.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        }

        return send(item, swingHand, doRotate, rotationPriority,
            (float) Rotations.getYaw(hitPos), (float) Rotations.getPitch(hitPos), doRotate,
            List.of(new BlockHitResult(hitPos, side.getOpposite(), neighbour, false)),
            List.of(pos));
    }

    /**
     * 目标位置没有能点的面时，找一块支撑块放下去帮它变成可放。
     *
     * <p>优先挑「同一个角度既能放支撑块、又能顺手把目标也放下」的那种（本 tick 两块）；找不到就
     * 这一 tick 只放支撑块，目标下一 tick 再来（那时支撑块已经在客户端世界里了，正常走上面那条）。
     *
     * @return true = 这一下已经安排好了
     */
    @Unique
    private boolean placeWithSupport(BlockPos target, Block block, FindItemResult item, boolean doRotate, int rotationPriority,
                                     boolean swingHand, boolean checkEntities, double reach) {
        // 先脚下，再四周，最后头顶
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};

        BlockPos fallbackPos = null;
        LegalPlace.Aim fallbackAim = null;

        for (Direction dir : order) {
            BlockPos support = target.relative(dir);
            if (queuedThisTick.contains(support)) continue;
            if (!mc.level.getBlockState(support).canBeReplaced()) continue;
            if (!BlockUtils.canPlaceBlock(support, checkEntities, block)) continue;

            LegalPlace.Aim aim = LegalPlace.compute(support, LegalPlace.supportFaces(support), true, 0.0, reach);
            if (aim == null) continue;

            Direction clickFace = dir.getOpposite();
            if (LegalPlace.canHitFuture(support, clickFace, aim.yaw(), aim.pitch(), reach)) {
                Vec3 hitPos = Vec3.atCenterOf(target)
                    .add(dir.getStepX() * 0.5, dir.getStepY() * 0.5, dir.getStepZ() * 0.5);

                return send(item, swingHand, doRotate, rotationPriority, aim.yaw(), aim.pitch(), aim.rotated(),
                    List.of(
                        new BlockHitResult(aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock(), false),
                        new BlockHitResult(hitPos, clickFace, support, false)
                    ),
                    List.of(support, target));
            }

            if (fallbackAim == null) {
                fallbackPos = support;
                fallbackAim = aim;
            }
        }

        if (fallbackAim == null) return false;

        return send(item, swingHand, doRotate, rotationPriority, fallbackAim.yaw(), fallbackAim.pitch(), fallbackAim.rotated(),
            List.of(new BlockHitResult(fallbackAim.hitPos(), fallbackAim.face().getOpposite(), fallbackAim.clickedBlock(), false)),
            List.of(fallbackPos));
    }

    /**
     * 排好「转视角 + 交互包」。
     *
     * <p>{@code hits} 里的命中点按顺序发（前一下放好之后后一下才成立的那种也照发：客户端本地世界
     * 已经放进去了，服务器那边也是按顺序处理的）。开了「合法转头」走 {@link LegalRotation}
     * （交互包排在带着这份朝向的移动包之后）；关着走原版 Meteor 旋转（回调就是「移动包发完」那一刻）。
     *
     * @return false = 要转的那个角度被优先级更高的旋转顶掉了，这一下没放
     */
    @Unique
    private boolean send(FindItemResult item, boolean swingHand, boolean doRotate, int rotationPriority,
                         float yaw, float pitch, boolean needsRotate,
                         List<BlockHitResult> hits, List<BlockPos> placed) {
        Runnable action = () -> placeHits(item, swingHand, hits);

        if (needsRotate) {
            if (legalRotation.get() != LegalRotation.Mode.OFF) {
                // 这一 tick 已经转过别的角度：移动包只能带一份朝向，这一下放了也是错的
                if (rotatedThisTick && (yaw != rotatedYaw || pitch != rotatedPitch)) return false;
                if (!LegalRotation.rotate(yaw, pitch, legalRotation.get(), legalRotationPriority.get())) return false;

                rotatedThisTick = true;
                rotatedYaw = yaw;
                rotatedPitch = pitch;

                LegalRotation.runAfterSend(action);
            } else {
                if (!doRotate) return false;
                Rotations.rotate(yaw, pitch, rotationPriority, action);
            }
        } else {
            LegalRotation.runAfterSend(action);
        }

        queuedThisTick.addAll(placed);
        return true;
    }

    /** 按顺序点这几下：开了「背包放置」就交给 {@link BackpackUse}（快捷栏、副手优先，其次背包） */
    @Unique
    private void placeHits(FindItemResult item, boolean swingHand, List<BlockHitResult> hits) {
        if (hits.isEmpty()) return;

        if (backpackPlace.get()) {
            BackpackUse.place(this::isSurroundBlock, hits, backpackMode.get(), backpackSlot.get(), swingHand);
            return;
        }

        for (BlockHitResult hit : hits) interact(item, hit, swingHand);
    }

    /** 用这个方块物品点这一下（点的是 hit 里那个方块，命中点用算好的那个） */
    @Unique
    private void interact(FindItemResult item, BlockHitResult hit, boolean swingHand) {
        if (mc.player == null || mc.gameMode == null) return;

        boolean offhand = item.isOffhand();
        boolean swap = !offhand && !item.isMainHand();
        if (swap) InvUtils.swap(item.slot(), true);

        BlockUtils.interact(hit, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, swingHand);

        if (swap) InvUtils.swapBack();
    }

    // ====== 双重高度：一个角度同 tick 放两块 ======

    /**
     * 双重高度：这一 tick 试着把「脚底块 + 它上面那块」用一个角度一起放下。
     *
     * @return true = 两下都排好了（调用方本 tick 别再单独放这两格）
     */
    @Unique
    private boolean tryDoublePlace(BlockPos feet, FindItemResult item) {
        BlockPos head = feet.above();

        Block block = blockOf(item);
        if (block == null) return false;
        if (!BlockUtils.canPlaceBlock(feet, true, block)) return false;
        if (!BlockUtils.canPlaceBlock(head, true, block)) return false;

        DoublePlaceAim aim = findDoubleAim(feet);
        if (aim == null) return false;

        // 先脚底、后头顶：头顶点的是脚底块的顶面，脚底那块得先在服务器那边放下去
        return send(item, swing.get(), rotate.get(), 100, aim.yaw(), aim.pitch(), true,
            List.of(
                new BlockHitResult(aim.firstHit(), aim.firstFace(), aim.firstClicked(), false),
                new BlockHitResult(aim.secondHit(), Direction.UP, feet, false)
            ),
            List.of(feet, head));
    }

    /**
     * 找「脚底和它上面那块同 tick 一起放下」的角度。
     *
     * <p>两块得落在同一条射线上（一份朝向只对应一条射线），能成的排法只有一种：头上那块点的是
     * 「马上会放下的脚底块」的顶面 —— 脚底那块先发，服务器按顺序处理时它已经在了。
     *
     * <p>做法：撒两批采样点（先「脚底块放好之后的顶面」，再「脚底那一格的底面」），眼睛连到每个
     * 采样点就是一条候选射线；
     * 脚底那一下直接按这条射线做一次真正的准星射线（第一下打到的方块必须是「点它能放到脚底那一格」
     * 的支撑），顺便就把「中间有没有被挡住」一起验了。
     */
    @Unique
    private DoublePlaceAim findDoubleAim(BlockPos feet) {
        if (mc.player == null || mc.level == null) return null;

        double top = feet.getY() + 1.0;     // 脚底块放好之后的顶面
        double bottom = feet.getY();        // 脚底那一格的底面（脚下那块地板的顶面）
        Vec3 eye = LegalPlace.predictedEye();
        if (eye.y <= top + 1.0E-3) return null;  // 眼睛得在顶面上方才点得到这一面

        double reach = LegalPlace.defaultReach();
        boolean snap = legalRotation.get() != LegalRotation.Mode.OFF;

        // 先把「瞄准脚底块顶面」那一批试完（旁边有墙可点时用的就是这一批，角度平缓、命中点也稳）
        DoublePlaceAim aim = searchDoubleAim(eye, feet, top, top, reach, snap, doubleTopFractions);
        if (aim != null) return aim;

        // 再试「瞄准脚底那一格底面」那一批（脚下是地板、旁边没东西可点时只有这一条路）
        return searchDoubleAim(eye, feet, bottom, top, reach, snap, doubleBottomFractions);
    }

    /**
     * 在 {@code y = planeY} 这个平面上按采样点找一条能同 tick 放两块的射线。
     *
     * @param planeY      采样点所在的平面（顶面那一批传顶面，底面那一批传底面）
     * @param top         脚底块放好之后的顶面（判定头顶那一下用，两个批次都一样）
     * @param uFractions  这一列里的纵向采样位置，顺序就是试的顺序
     */
    @Unique
    private DoublePlaceAim searchDoubleAim(Vec3 eye, BlockPos feet, double planeY, double top,
                                              double reach, boolean snap, double[] uFractions) {
        for (double u : uFractions) {
            for (double v : doubleSideFractions) {
                Vec3 point = new Vec3(feet.getX() + u, planeY, feet.getZ() + v);
                if (point.distanceToSqr(eye) > reach * reach) continue;

                float yaw = (float) LegalPlace.yawTo(eye, point);
                float pitch = (float) LegalPlace.pitchTo(eye, point);

                if (snap) {
                    yaw = LegalPlace.snapYaw(yaw);
                    pitch = LegalPlace.snapPitch(pitch);
                }

                DoublePlaceAim aim = verifyDoubleAim(eye, yaw, pitch, feet, top, reach);
                if (aim != null) return aim;
            }
        }

        return null;
    }

    /** 这个角度是不是真的能同 tick 放两块（脚底那一下按准星射线算） */
    @Unique
    private DoublePlaceAim verifyDoubleAim(Vec3 eye, float yaw, float pitch, BlockPos feet, double top, double reach) {
        Vec3 dir = mc.player.calculateViewVector(pitch, yaw);
        if (dir.y >= -1.0E-3) return null;  // 得低头，不然射线碰不到「顶面」

        // 头顶那一下：射线和脚底块顶面的交点
        double t = (top - eye.y) / dir.y;
        if (t <= 0.0) return null;

        Vec3 headHit = eye.add(dir.scale(t));
        // 命中点离这一格的棱至少留 0.02 格：服务器那边预判差一点也还打在这一面上
        if (!insideColumn(headHit, feet, 0.02)) return null;
        if (headHit.distanceToSqr(eye) > reach * reach) return null;

        // 脚底那一下：射线第一下真正打到的方块，点它得能放到脚底那一格
        Vec3 end = eye.add(dir.scale(reach));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        if (!hit.getBlockPos().relative(hit.getDirection()).equals(feet)) return null;
        if (!LegalPlace.isValidSupport(hit.getBlockPos())) return null;
        if (new AABB(hit.getBlockPos()).distanceToSqr(eye) > reach * reach) return null;

        // 头顶那一下要排在「第一下真打到的东西」前面，中间不能被挡
        if (hit.getLocation().distanceToSqr(eye) < headHit.distanceToSqr(eye) - 1.0E-4) return null;

        return new DoublePlaceAim(yaw, pitch, hit.getLocation(), hit.getBlockPos(), hit.getDirection(), headHit);
    }

    /** 命中点在不在「这一格」里（边上留 margin 格） */
    @Unique
    private static boolean insideColumn(Vec3 point, BlockPos pos, double margin) {
        return point.x >= pos.getX() + margin && point.x <= pos.getX() + 1 - margin
            && point.z >= pos.getZ() + margin && point.z <= pos.getZ() + 1 - margin;
    }

    // ====== 小工具 ======

    /** 这个 FindItemResult 指的物品是哪个方块；不是方块物品时返回 null */
    @Unique
    private Block blockOf(FindItemResult item) {
        if (mc.player == null || item == null) return null;

        ItemStack stack = item.isOffhand()
            ? mc.player.getInventory().getItem(SlotUtils.OFFHAND)
            : mc.player.getInventory().getItem(item.slot());

        return stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
    }

    /** 要用的方块只有背包里有（快捷栏、副手都没有），得走背包那条路 */
    @Unique
    private boolean needBackpack(FindItemResult item) {
        return backpackPlace.get() && !item.isHotbar() && !item.isOffhand();
    }

    /** 插到某个设置后面（Meteor 只能末尾追加，得自己按位置插） */
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
