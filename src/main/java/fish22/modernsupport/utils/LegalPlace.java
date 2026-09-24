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

import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 最佳放置角度 查询接口
 *
 * <p>只算角度，不发包：传入「目标位置」+「允许点击的面」，算出在这个位置放置方块的
 * 最佳角度（yaw / pitch）；一个合法角度都算不出来就返回 {@code null}。
 *
 * <p>本类<b>不发任何包、不转视角、不动玩家朝向、不碰移动运算</b>（既不会改走路方向，
 * 也不会额外发旋转包）—— 要不要转、什么时候转、转完怎么放，全由调用方自己决定。
 *
 * <p>眼睛位置用「这一 tick 走完之后」的预判位置（玩家位置 + 速度 + 这一 tick 的输入加速度）：
 * 移动包先于放置包发出去，服务器校验放置时看到的是走完之后的你，不预判的话散落中算出来的角度会偏。
 *
 * <h3>算出来的角度「最佳」在哪</h3>
 *
 * 先看「不转」能不能用：现在这个朝向本来就合法的话直接返回它，并在
 * {@link Aim#rotated()} 里标 false（调用方拿到这个就不该转）。否则在允许的面上取 3×3
 * 个采样点，量化 + 校验后按「等级 → 转动代价」取第一个：
 *
 * <ul>
 *   <li>等级 0：射线第一个打到的就是这个方块的这一面（真人准星点得到）；</li>
 *   <li>等级 1：命中豁免（人就在方块位置里）；</li>
 *   <li>等级 2：射线打进了这个方块的碰撞箱（只满足反作弊的最低要求）。</li>
 * </ul>
 *
 * 同一等级里先挑瞄得最靠中间的那个（面上 3×3 采样点，越靠中间越禁得住预判误差），
 * 再挑相对「服务器此刻记录的角度」转得最少的那个（见 {@link LegalRotation#getServerYaw()}）；
 * 如果和上一次发出去的角度完全一样，会优先换一个同样合法、但角度不同的采样点。
 *
 * <h3>「合法」的判定（对齐 Grim 的放置检查，GPL-3.0）</h3>
 *
 * 放置包里点的是<b>被点击的那个方块</b>（支撑方块），目标位置是它朝 face 方向的邻居：
 *
 * <ol>
 *   <li>被点方块有效（空气 / 可替换方块 / 流体不行 —— 原版点到可替换方块时会把方块放进那一格，
 *       目标位置就歪了；可交互方块（箱子/熔炉/门这些）平时也不行，点了会开界面，
 *       但<b>潜行时允许</b>，原版潜行右键不会开界面、会照常把方块放上去）；</li>
 *   <li>够得着：眼睛到被点方块碰撞箱最近点 ≤ 方块交互距离（生存 4.5、创造 5.0）；</li>
 *   <li>豁免：眼睛点（± {@link #EPSILON}）就在被点方块碰撞箱里时直接放行
 *       （人就在这一格里，反作弊判不了朝向）；</li>
 *   <li>不豁免时：眼睛要在点击面这一侧，并且射线要能打进被点方块的碰撞箱
 *       （所以瞄准点从点击面往被点方块里推 {@link #INSIDE_OFFSET} 格）；</li>
 *   <li>角度可量化：返回的角度相对「服务器此刻记录的角度」差整数格鼠标灵敏度
 *       （{@link #sensitivityStep()}，原版 0.15 × 8 × (0.6 × 灵敏度 + 0.2)³ 度）。</li>
 * </ol>
 *
 * <p>提示：{@link Aim#hitPos()} 是建议的交互命中点（落在被点方块的点击面上），
 * 调用方拿它构造 {@code BlockHitResult} 即可。
 */
public final class LegalPlace {

    /**
     * 一次查询的结果
     *
     * @param yaw          偏航（已经量化到鼠标灵敏度的格子上）
     * @param pitch        俯仰（同上）
     * @param face         点击方向：目标方块 → 被点击方块的方向
     * @param clickedBlock 应该去点的方块（被点击方块 = 目标方块向 face 方向偏移一格）
     * @param hitPos       建议的交互命中点（落在被点方块的点击面上）
     * @param rotated      要不要转头：false 表示「现在这个朝向本来就合法」，
     *                     {@link #yaw()} / {@link #pitch()} 就是玩家当前视角，别转
     */
    public record Aim(float yaw, float pitch, Direction face, BlockPos clickedBlock, Vec3 hitPos, boolean rotated) {
    }

    /**
     * 一次「空中放置」查询的结果。
     *
     * @param yaw     偏航（已经量化到鼠标灵敏度的格子上）
     * @param pitch   俯仰（同上）
     * @param face    目标方块被点击的面（外法线方向，也就是朝着眼睛的那一面）
     * @param hitPos  建议的交互命中点（在目标方块内部、靠近点击面）
     * @param rotated 要不要转头：false 表示服务器现在记着的朝向已经能打到这个方块，别转
     */
    public record AirAim(float yaw, float pitch, Direction face, Vec3 hitPos, boolean rotated) {
    }

    /**
     * 一次「右键交互方块」查询的结果。
     *
     * @param yaw     偏航（已经量化到鼠标灵敏度的格子上）
     * @param pitch   俯仰（同上）
     * @param face    要点击的面（方块外法线方向）
     * @param hitPos  建议的交互命中点（落在这个面上）
     * @param rotated 要不要转头：false 表示「现在这个朝向本来就点得到」，别转
     */
    public record InteractAim(float yaw, float pitch, Direction face, Vec3 hitPos, boolean rotated) {
    }

    /** 采样出来的候选角度（margin 是采样点离面中心的距离，越小越靠中间、越稳） */
    private record Candidate(int tier, double margin, double cost, float yaw, float pitch, Vec3 hitPos, Direction face, BlockPos clicked) {
    }

    /** 空中放置的采样候选角度 */
    private record AirCandidate(double margin, double cost, float yaw, float pitch, Direction face, Vec3 hitPos) {
    }

    /** 交互的采样候选角度（tier 0 = 原版射线真的打中这一面，tier 1 = 眼睛在方块里的豁免） */
    private record InteractCandidate(int tier, double margin, double cost, float yaw, float pitch, Direction face, Vec3 hitPos) {
    }

    /**
     * 瞄准点从点击面往被点击方块内部推的距离。
     *
     * <p>推得深一点（1/4 格），眼睛刚好越过点击面所在的平面时射线也能确实插进方块里头：
     * 只推 0.05 格的话，这种边界情况下射线是擦着方块面走的，预判差一点点就打飞。
     */
    private static final double INSIDE_OFFSET = 0.25;

    /**
     * 点击面上的采样位置（0~1，取远离边缘的 3×3 个点）
     *
     * <p>留 0.3 的边距：眼睛位置是预判的，差个零点几格也还打在方块上，不会擦着棱打飞。
     */
    private static final double[] FRACTIONS = {0.3, 0.5, 0.7};

    /** 交互查询默认允许的面：六个面全试，内部挑最优的那个 */
    private static final List<Direction> ALL_FACES = List.of(Direction.values());

    /** 「和上一次发出去的角度完全相同」的代价惩罚，远大于任何角度差 */
    private static final double DUPLICATE_LOOK_PENALTY = 1.0E6;

    /** 判定用的浮点容差 */
    private static final double EPSILON = 1.0E-3;

    private LegalPlace() {
    }

    // ====== 查询接口 ======

    /**
     * 算最佳放置角度。算不出合法角度返回 {@code null}。
     *
     * @param target       要放置方块的位置
     * @param allowedFaces 允许点击的面（目标方块 → 被点击方块的方向）
     */
    public static Aim compute(BlockPos target, Collection<Direction> allowedFaces) {
        return compute(target, allowedFaces, true);
    }

    /**
     * 算最佳放置角度。
     *
     * @param predictMovement true = 按「这一 tick 会走完」的预测眼睛位置算（正常放置）；
     *                        false = 按当前眼睛位置算（绕过模式会冻结玩家，这一 tick 不会走）
     */
    public static Aim compute(BlockPos target, Collection<Direction> allowedFaces, boolean predictMovement) {
        return compute(target, allowedFaces, predictMovement, 0.0);
    }

    /**
     * 算最佳放置角度。
     *
     * @param predictMovement true = 按「这一 tick 会走完」的预测眼睛位置算（正常放置）；
     *                        false = 按当前眼睛位置算（绕过模式会冻结玩家，这一 tick 不会走）
     * @param eyeHeightOffset 眼睛高度再挪多少格（负数 = 更低）。自动潜行要按「潜行蹲下后的眼睛高度」
     *                        算角度时用它：潜行会把视角压低，站着算出来的朝向蹲下后可能就不合法了。
     */
    public static Aim compute(BlockPos target, Collection<Direction> allowedFaces, boolean predictMovement, double eyeHeightOffset) {
        return compute(target, allowedFaces, predictMovement, eyeHeightOffset, defaultReach());
    }

    /**
     * 算最佳放置角度，reach 自己指定。
     *
     * @param reach 够得着的距离上限（原版生存 4.5 / 创造 5.0）。调用方想放得比原版远（服务器允许的话）
     *              就传自己的范围设置
     */
    public static Aim compute(BlockPos target, Collection<Direction> allowedFaces, boolean predictMovement,
                              double eyeHeightOffset, double reach) {
        List<Aim> aims = computeCandidates(target, allowedFaces, predictMovement, eyeHeightOffset, reach);
        return aims.isEmpty() ? null : aims.getFirst();
    }

    /**
     * 和 {@link #compute} 一样算，但把<b>所有</b>算得出来的角度按优先顺序全列出来（第一个就是
     * {@link #compute} 会返回的那个）。
     *
     * <p>顺序：先放「现在这个朝向本来就合法」的那些（不用转头，最省事），再放按
     * 「等级 → 瞄得靠不靠中间 → 转动代价」排好的采样角度。
     *
     * <p>为什么要一整串：同一个位置能点的支撑面有好几个，但放出来的东西不一定一样 ——
     * 火把/告示牌「立着的」和「墙上的」是两个方块，点错面就放成了另一个；楼梯/拉杆这些朝向
     * 也是点哪儿算哪儿。调用方拿到整串才能逐个验「这一下放出来跟投影是不是一模一样」，
     * 挑第一个对得上的。
     *
     * @param predictMovement true = 按「这一 tick 会走完」的预测眼睛位置算（正常放置）；
     *                        false = 按当前眼睛位置算（绕过模式会冻结玩家，这一 tick 不会走）
     * @param eyeHeightOffset 眼睛高度再挪多少格（负数 = 更低）。自动潜行要按「潜行蹲下后的眼睛高度」
     *                        算角度时用它：潜行会把视角压低，站着算出来的朝向蹲下后可能就不合法了。
     */
    public static List<Aim> computeCandidates(BlockPos target, Collection<Direction> allowedFaces, boolean predictMovement, double eyeHeightOffset) {
        return computeCandidates(target, allowedFaces, predictMovement, eyeHeightOffset, defaultReach());
    }

    /**
     * 和上面一样，但 reach 自己指定（够得着与被点方块的距离上限）。
     */
    public static List<Aim> computeCandidates(BlockPos target, Collection<Direction> allowedFaces, boolean predictMovement,
                                              double eyeHeightOffset, double reach) {
        List<Aim> aims = new ArrayList<>();
        if (mc.player == null || mc.level == null || target == null) return aims;
        if (allowedFaces == null || allowedFaces.isEmpty()) return aims;

        // 服务器校验放置时看到的是这一 tick 走完之后的你（移动包先发），所以眼睛也按走完之后算
        Vec3 eye = eyePosition(predictMovement, eyeHeightOffset);
        AABB eyeBox = eyeBox(eye, EPSILON);

        // 先看「不转头」能不能用：用「这一 tick 实际会发给服务器的朝向」来判定。
        // 本 tick 没有 rotate() 时移动包带相机视角；有 rotate()（或上一 tick 末尾设置、
        // 留给本 tick 发）时移动包带 realYaw/realPitch。见 LegalRotation 的时序说明。
        float checkYaw = LegalRotation.isRotating() ? LegalRotation.getRealYaw() : mc.player.getYRot();
        float checkPitch = LegalRotation.isRotating() ? LegalRotation.getRealPitch() : mc.player.getXRot();
        Vec3 viewDir = mc.player.calculateViewVector(checkPitch, checkYaw);
        for (Direction face : allowedFaces) {
            if (face == null) continue;
            if (!faceIsLegal(target, face, eye, viewDir, reach, eyeBox)) continue;

            aims.add(new Aim(
                Mth.wrapDegrees(checkYaw), checkPitch,
                face, target.relative(face), faceCenter(target, face), false
            ));
        }

        double step = sensitivityStep();
        float baseYaw = LegalRotation.getServerYaw();
        float basePitch = LegalRotation.getServerPitch();

        List<Candidate> samples = new ArrayList<>();

        for (Direction face : allowedFaces) {
            if (face == null) continue;

            BlockPos clicked = target.relative(face);
            if (!isValidSupport(clicked)) continue;

            AABB clickedBox = new AABB(clicked);
            if (clickedBox.distanceToSqr(eye) > reach * reach) continue;

            boolean exempt = eyeBox.intersects(clickedBox);
            if (!exempt && !eyeOnVisibleSide(eye, clickedBox, face.getOpposite())) continue;

            Vec3 planeCenter = faceCenter(target, face);

            for (double u : FRACTIONS) {
                for (double v : FRACTIONS) {
                    // 面上采样点，再往被点方块里推一点（射线真的插进那个方块）
                    Vec3 surface = planeCenter.add(inPlaneOffset(face, u, v));
                    Vec3 aimPoint = surface.add(
                        face.getStepX() * INSIDE_OFFSET,
                        face.getStepY() * INSIDE_OFFSET,
                        face.getStepZ() * INSIDE_OFFSET
                    );

                    float yaw = snapAngle(baseYaw, yawTo(eye, aimPoint), step, true);
                    float pitch = snapAngle(basePitch, pitchTo(eye, aimPoint), step, false);
                    Vec3 end = eye.add(mc.player.calculateViewVector(pitch, yaw).scale(reach));

                    // 先做便宜的碰撞箱判定，过了才走真正的准星射线
                    if (!exempt && !rayHitsBox(eye, end, clickedBox)) continue;
                    boolean realClick = firstHitIs(eye, end, clicked, face.getOpposite());

                    int tier = realClick ? 0 : (exempt ? 1 : 2);
                    double cost = angularCost(baseYaw, basePitch, yaw, pitch);
                    if (yaw == baseYaw && pitch == basePitch) cost += DUPLICATE_LOOK_PENALTY;

                    double margin = Math.hypot(u - 0.5, v - 0.5);
                    samples.add(new Candidate(tier, margin, cost, yaw, pitch, surface, face, clicked));
                }
            }
        }

        samples.sort(Comparator.comparingInt(Candidate::tier)
            .thenComparingDouble(Candidate::margin)
            .thenComparingDouble(Candidate::cost));

        for (Candidate sample : samples) {
            aims.add(new Aim(sample.yaw(), sample.pitch(), sample.face(), sample.clicked(), sample.hitPos(), true));
        }

        return aims;
    }

    /** 便利版：允许所有「能当支撑」的面 */
    public static Aim compute(BlockPos target) {
        return compute(target, supportFaces(target));
    }

    /** 便利版：自己指定若干面 */
    public static Aim compute(BlockPos target, Direction... allowedFaces) {
        return compute(target, List.of(allowedFaces));
    }

    /**
     * 算「空中放置」角度：不需要支撑方块，直接点击目标方块自己朝眼睛的那一面。
     *
     * <p>这个模式<b>不是 Grim 安全的</b>：Grim 的 {@code AirLiquidPlace} 会检查「被点击的方块」
     * 是不是空气，空中放置点的就是空气方块，所以生存模式下会被直接取消（创造模式跳过这个检查）。
     * 它适合没有这条检查的服务端；在 Grim 服上请用 {@link #compute(BlockPos)}。
     */
    public static AirAim computeAir(BlockPos target) {
        return computeAir(target, true);
    }

    /**
     * 算「空中放置」角度。
     *
     * @param predictMovement true = 按「这一 tick 会走完」的预测眼睛位置算（正常放置）；
     *                        false = 按当前眼睛位置算（绕过模式会冻结玩家，这一 tick 不会走）
     */
    public static AirAim computeAir(BlockPos target, boolean predictMovement) {
        return computeAir(target, List.of(Direction.values()), predictMovement);
    }

    /**
     * 算「空中放置」角度，reach 自己指定。
     *
     * @param reach 够得着的距离上限（原版生存 4.5 / 创造 5.0）
     */
    public static AirAim computeAir(BlockPos target, boolean predictMovement, double reach) {
        return computeAir(target, List.of(Direction.values()), predictMovement, reach);
    }

    /**
     * 算「空中放置」角度，但只允许点 {@code allowedFaces} 里的面。
     *
     * <p>给「朝向只看点的是哪一面」的方块用（漏斗、原木/柱子这类）：想放成某个朝向，就只能点
     * 它对应的那一面，点别的面放出来朝向就错了，所以调用方把面限死再算。
     */
    public static AirAim computeAir(BlockPos target, Collection<Direction> allowedFaces, boolean predictMovement) {
        return computeAir(target, allowedFaces, predictMovement, defaultReach());
    }

    /**
     * 算「空中放置」角度，reach 自己指定（够得着与被点方块的距离上限）。
     */
    public static AirAim computeAir(BlockPos target, Collection<Direction> allowedFaces, boolean predictMovement, double reach) {
        if (mc.player == null || mc.level == null || target == null) return null;
        if (!mc.level.getBlockState(target).canBeReplaced()) return null;
        if (allowedFaces == null || allowedFaces.isEmpty()) return null;

        Vec3 eye = predictMovement ? predictedEye() : mc.player.getEyePosition();
        AABB box = new AABB(target);
        if (box.distanceToSqr(eye) > reach * reach) return null;

        AABB eyeBox = eyeBox(eye, EPSILON);
        boolean exempt = eyeBox.intersects(box);

        // 先看「这一 tick 实际会发给服务器的朝向」能不能直接用：射线只要穿过目标方块碰撞箱
        // 就算命中（Grim RotationPlace 查的就是这个；PositionPlace 另外要求眼睛在点击面这一侧）。
        //
        // 注意不能拿上一 tick 已经发出去、这一 tick 会被相机视角覆盖的旧角度来判定：
        // 本 tick 没有继续 rotate() 时，移动包带的是相机视角；本 tick 有 rotate()（或上一 tick
        // 末尾设置、留给本 tick 发）时，移动包带的是 realYaw/realPitch。放置包排在移动包之后，
        // 服务器看到的必然是这两者之一。
        float checkYaw = LegalRotation.isRotating() ? LegalRotation.getRealYaw() : mc.player.getYRot();
        float checkPitch = LegalRotation.isRotating() ? LegalRotation.getRealPitch() : mc.player.getXRot();
        Vec3 checkDir = mc.player.calculateViewVector(checkPitch, checkYaw);
        for (Direction face : allowedFaces) {
            if (!exempt && !eyeOnVisibleSide(eye, box, face)) continue;
            if (!rayHitsBox(eye, eye.add(checkDir.scale(reach)), box)) continue;

            return new AirAim(Mth.wrapDegrees(checkYaw), checkPitch, face, insideFacePoint(target, face), false);
        }

        float baseYaw = LegalRotation.getServerYaw();
        float basePitch = LegalRotation.getServerPitch();
        double step = sensitivityStep();
        List<AirCandidate> candidates = new ArrayList<>();

        for (Direction face : allowedFaces) {
            if (!exempt && !eyeOnVisibleSide(eye, box, face)) continue;

            Vec3 planeCenter = faceCenter(target, face);
            for (double u : FRACTIONS) {
                for (double v : FRACTIONS) {
                    // 面上采样点，再往目标方块内部推一点：射线确实穿过目标方块，命中点也不会落在边界上
                    Vec3 surface = planeCenter.add(inPlaneOffset(face, u, v));
                    Vec3 aimPoint = surface.add(
                        face.getStepX() * -INSIDE_OFFSET,
                        face.getStepY() * -INSIDE_OFFSET,
                        face.getStepZ() * -INSIDE_OFFSET
                    );

                    float yaw = snapAngle(baseYaw, yawTo(eye, aimPoint), step, true);
                    float pitch = snapAngle(basePitch, pitchTo(eye, aimPoint), step, false);
                    Vec3 end = eye.add(mc.player.calculateViewVector(pitch, yaw).scale(reach));

                    if (!rayHitsBox(eye, end, box)) continue;

                    double cost = angularCost(baseYaw, basePitch, yaw, pitch);
                    if (yaw == baseYaw && pitch == basePitch) cost += DUPLICATE_LOOK_PENALTY;

                    double margin = Math.hypot(u - 0.5, v - 0.5);
                    candidates.add(new AirCandidate(margin, cost, yaw, pitch, face, aimPoint));
                }
            }
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingDouble(AirCandidate::margin)
            .thenComparingDouble(AirCandidate::cost));
        AirCandidate best = candidates.getFirst();
        return new AirAim(best.yaw(), best.pitch(), best.face(), best.hitPos(), true);
    }

    /** 目标方块某个面往内部推一点的点（给服务器当命中点用） */
    private static Vec3 insideFacePoint(BlockPos target, Direction face) {
        return faceCenter(target, face).add(
            face.getStepX() * -INSIDE_OFFSET,
            face.getStepY() * -INSIDE_OFFSET,
            face.getStepZ() * -INSIDE_OFFSET
        );
    }

    // ====== 交互（右键方块）======

    /**
     * 算「右键交互这个方块」的最佳角度，六个面都允许。算不出合法角度返回 {@code null}。
     *
     * <p>够不着（返回 null）时调用方应该继续靠近目标；拿到结果之后按
     * {@link InteractAim#rotated()} 决定要不要转头。
     */
    public static InteractAim computeInteract(BlockPos target) {
        return computeInteract(target, ALL_FACES, true, defaultReach());
    }

    /**
     * 算「右键交互这个方块」的最佳角度。算不出合法角度返回 {@code null}。
     *
     * <p>和放置的区别：放置点的是目标位置旁边的支撑方块，这里点的是<b>目标方块自己</b>，
     * 所以不需要「支撑方块」那一套判定。合法性只看（对齐 Grim 的 RotationPlace）：
     *
     * <ol>
     *   <li>方块是实体方块（空气 / 可替换方块不行）；</li>
     *   <li>够得着：眼睛到方块碰撞箱最近点 ≤ reach；</li>
     *   <li>眼睛在要点的这一面外侧（能看见这一面）；</li>
     *   <li>不豁免时，原版选方块那条射线第一个打到的就是这个方块的这一面
     *       （用 OUTLINE 形状，所以按钮 / 拉杆 / 活板门这些非完整方块也按自己的形状判定）；</li>
     *   <li>角度可量化：相对「服务器此刻记录的角度」差整数格鼠标灵敏度
     *       （见 {@link #sensitivityStep()}），和 {@link #compute} 同一套。</li>
     * </ol>
     *
     * <p>返回的 {@link InteractAim#hitPos()} 落在方块被点的那一面上，直接拿它构造
     * {@code BlockHitResult} 即可（视线正好指向它，Grim 的位置校验必然过）。
     *
     * @param predictMovement true = 按「这一 tick 会走完」的预测眼睛位置算（走路时交互）；
     *                        false = 按当前眼睛位置算
     */
    public static InteractAim computeInteract(BlockPos target, Collection<Direction> allowedFaces,
                                              boolean predictMovement, double reach) {
        if (mc.player == null || mc.level == null || target == null) return null;
        if (allowedFaces == null || allowedFaces.isEmpty()) return null;

        BlockState state = mc.level.getBlockState(target);
        if (state.isAir() || state.canBeReplaced()) return null;

        Vec3 eye = eyePosition(predictMovement, 0.0);
        AABB box = new AABB(target);
        if (box.distanceToSqr(eye) > reach * reach) return null;

        AABB eyeBox = eyeBox(eye, EPSILON);
        boolean exempt = eyeBox.intersects(box);

        // 先看「不转头」能不能直接用：判定用的角度和放置那边一样，是这一 tick 移动包实际会发出去的朝向
        float checkYaw = LegalRotation.isRotating() ? LegalRotation.getRealYaw() : mc.player.getYRot();
        float checkPitch = LegalRotation.isRotating() ? LegalRotation.getRealPitch() : mc.player.getXRot();
        Vec3 checkEnd = eye.add(mc.player.calculateViewVector(checkPitch, checkYaw).scale(reach));
        for (Direction face : allowedFaces) {
            if (face == null) continue;
            if (!eyeOnVisibleSide(eye, box, face)) continue;
            if (exempt) {
                if (!rayHitsBox(eye, checkEnd, box)) continue;
            } else if (!firstHitIs(eye, checkEnd, target, face)) {
                continue;
            }
            return new InteractAim(Mth.wrapDegrees(checkYaw), checkPitch, face, faceCenter(target, face), false);
        }

        float baseYaw = LegalRotation.getServerYaw();
        float basePitch = LegalRotation.getServerPitch();
        double step = sensitivityStep();
        List<InteractCandidate> candidates = new ArrayList<>();

        for (Direction face : allowedFaces) {
            if (face == null) continue;
            if (!eyeOnVisibleSide(eye, box, face)) continue;

            Vec3 planeCenter = faceCenter(target, face);
            for (double u : FRACTIONS) {
                for (double v : FRACTIONS) {
                    // 瞄准点就在点击面上：交互的命中点应该落在方块表面（原版选方块的结果就是表面点）
                    Vec3 surface = planeCenter.add(inPlaneOffset(face, u, v));

                    float yaw = snapAngle(baseYaw, yawTo(eye, surface), step, true);
                    float pitch = snapAngle(basePitch, pitchTo(eye, surface), step, false);
                    Vec3 end = eye.add(mc.player.calculateViewVector(pitch, yaw).scale(reach));

                    boolean realClick = firstHitIs(eye, end, target, face);
                    if (!realClick && !(exempt && rayHitsBox(eye, end, box))) continue;

                    double cost = angularCost(baseYaw, basePitch, yaw, pitch);
                    if (yaw == baseYaw && pitch == basePitch) cost += DUPLICATE_LOOK_PENALTY;

                    double margin = Math.hypot(u - 0.5, v - 0.5);
                    candidates.add(new InteractCandidate(realClick ? 0 : 1, margin, cost, yaw, pitch, face, surface));
                }
            }
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingInt(InteractCandidate::tier)
            .thenComparingDouble(InteractCandidate::margin)
            .thenComparingDouble(InteractCandidate::cost));
        InteractCandidate best = candidates.getFirst();
        return new InteractAim(best.yaw(), best.pitch(), best.face(), best.hitPos(), true);
    }

    /**
     * 这个角度能不能点一个「马上会被放下的方块」的面。
     *
     * <p>给「支持块」用：支撑块和目标是同一 tick 放下的两块，放目标那一下在客户端本地世界里
     * 支撑块还不存在，走不了原版准星那条射线，所以判定退一档 —— 眼睛在要点的这一面外侧、
     * 射线打进它的碰撞箱就算数（和 {@link #compute} 里等级 2 一个标准）。服务器那边两块是
     * 按顺序放下的，轮到目标时支撑块已经在了，这条射线自然成立。
     *
     * @param clicked     支撑块的位置（此刻还是空气）
     * @param clickedFace 要点的面（支撑块朝目标的那一面）
     */
    public static boolean canHitFuture(BlockPos clicked, Direction clickedFace, float yaw, float pitch, double reach) {
        if (mc.player == null || clicked == null || clickedFace == null) return false;

        Vec3 eye = eyePosition(true, 0.0);
        AABB box = new AABB(clicked);
        if (box.distanceToSqr(eye) > reach * reach) return false;

        if (eyeBox(eye, EPSILON).intersects(box)) return true;
        if (!eyeOnVisibleSide(eye, box, clickedFace)) return false;

        Vec3 end = eye.add(mc.player.calculateViewVector(pitch, yaw).scale(reach));
        return rayHitsBox(eye, end, box);
    }

    /**
     * 目标位置所有「能点的面」：邻居不是空气、不是可替换方块（草/雪层这些）、不含流体；
     * 可交互方块（箱子/门/按钮这些点了会开界面）默认不算，但潜行时算
     * （原版潜行右键它们不会开界面，会照常放方块）。
     */
    public static List<Direction> supportFaces(BlockPos target) {
        List<Direction> faces = new ArrayList<>(6);
        if (mc.level == null || target == null) return faces;

        for (Direction face : Direction.values()) {
            if (isValidSupport(target.relative(face))) faces.add(face);
        }
        return faces;
    }

    /**
     * 当前鼠标灵敏度下「一格鼠标」对应的旋转角度（度）。
     *
     * <p>原版 {@code MouseHandler#turnPlayer} 倍率是 {@code 8 × (0.6 × 灵敏度 + 0.2)³}，
     * {@code Entity#turn} 再乘 0.15。
     */
    public static double sensitivityStep() {
        double f = mc.options.sensitivity().get() * 0.6 + 0.2;
        return Math.max(f * f * f * 8.0 * 0.15, 1.0E-4);
    }

    /** 原版的方块交互距离（生存 4.5、创造 5.0）；玩家还没加载时按 4.5 算 */
    public static double defaultReach() {
        return mc.player == null ? 4.5 : mc.player.blockInteractionRange();
    }

    /**
     * 把偏航量化到「相对服务器当前角度差整数格鼠标灵敏度」。
     *
     * <p>给调用方自己算角度时对齐 {@link #compute} 那套用（Grim 的旋转检查要的是「差的鼠标格数
     * 是整数」）。量化基准是 {@link LegalRotation#getServerYaw()}，和 {@link #compute} 一致。
     */
    public static float snapYaw(float yaw) {
        return snapAngle(LegalRotation.getServerYaw(), yaw, sensitivityStep(), true);
    }

    /** 把俯仰量化到「相对服务器当前角度差整数格鼠标灵敏度」，语义同 {@link #snapYaw} */
    public static float snapPitch(float pitch) {
        return snapAngle(LegalRotation.getServerPitch(), pitch, sensitivityStep(), false);
    }

    /**
     * 眼睛现在能不能「正面看到」这一格：从眼睛到方块中心这条射线上没有被别的方块挡住。
     *
     * <p>判定用碰撞箱（{@code ClipContext.Block.COLLIDER}）：草、火把、告示牌这些没有碰撞箱的
     * 方块不算遮挡（真人也是透过它们看）。射线在走到方块中心之前就撞上东西 → 看不见。
     *
     * <p>「看不见的目标」要不要放由调用方决定（见各模块的「穿墙范围」）：本方法只回答看得见看不见。
     */
    public static boolean canSee(BlockPos target) {
        if (mc.player == null || mc.level == null || target == null) return false;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);

        BlockHitResult hit = mc.level.clip(new ClipContext(
            eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));

        if (hit.getType() != HitResult.Type.BLOCK) return true;
        if (hit.getBlockPos().equals(target)) return true;

        // 命中的东西在方块中心之后（例如视角刚好贴着这一格的棱）→ 中间没有遮挡，算看得见
        return hit.getLocation().distanceToSqr(eye) >= eye.distanceToSqr(center) - EPSILON;
    }

    // ====== 内部 ======

    /**
     * 邻居能不能当支撑。
     *
     * <p>空气、可替换方块（草/雪层这些）、流体都不行；可交互方块（箱子/熔炉/工作台/门/按钮
     * 这些点了会开界面或被使用的）默认也不行，但<b>潜行时允许</b>：原版潜行右键点它们不会
     * 开界面，会照常把方块放上去（服务器按「潜行 + 手上有方块」走放置那一支）。
     */
    public static boolean isValidSupport(BlockPos pos) {
        if (mc.level == null || !mc.level.isLoaded(pos)) return false;

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced()) return false;
        if (isInteractive(state.getBlock()) && !isSneaking()) return false;
        return state.getFluidState().isEmpty();
    }

    /**
     * 这个方块右键点上去会不会被「用掉」（开界面 / 被触发 / 被吃掉）。
     *
     * <p>原版不潜行时右键先给方块用，被用掉了这一下就轮不到手里的方块 —— 放置包会变成
     * 「开了个箱子 / 拉了一下拉杆 / 点了一下按钮 / 红石粉被点成十字」，方块根本没放上去。
     * 潜行时原版会跳过方块的交互（{@code isSecondaryUseActive}），照常把方块放上去，
     * 所以这些方块只有在潜行时才能当支撑面（见 {@link #isValidSupport}）。
     *
     * <p>判定分三层，命中任意一层就算：
     *
     * <ol>
     *   <li>Meteor 自带的那份名单（{@code BlockUtils.isClickable}：容器、门、活板门、栅栏门、
     *       按钮、压力板、床、工作台这些）；</li>
     *   <li>下面 {@link #isKnownInteractive} 里点名的那几个（名单里漏掉、又确认会吃掉右键的：
     *       拉杆、红石粉、中继器、比较器、告示牌/悬挂告示牌、蛋糕、花盆、堆肥桶、阳光传感器、
     *       龙蛋）—— 这几个其实反射那层也认得出来，写在这儿是万一以后原版改了方法名还能兜住；</li>
     *   <li>反射兜底：这个方块的类（顺着继承链）自己实现了 {@code useWithoutItem}
     *       （见 {@link #declaresInteraction}）—— 原版四十来个这样的方块类（熔炉、箱子、活板门、
     *       拉杆、红石粉、中继器、比较器、告示牌、堆肥桶、甜浆果丛……），子类继承的也算，
     *       装了别的 mod 也一样能认出来。</li>
     * </ol>
     *
     * <p>只认 {@code useWithoutItem}（「空手/拿什么都点得动」的那一套），不认 {@code useItemOn}：
     * 后者的重写基本都是「手里拿着某个特定东西才有反应」（斧子剪涂蜡铜块、剪刀剪南瓜、打火石点
     * TNT、桶舀炼药锅这些），拿着方块右键时它们会把这一下原样还给放置，不算可交互方块。
     */
    public static boolean isInteractive(Block block) {
        return BlockUtils.isClickable(block)
            || isKnownInteractive(block)
            || (declaresInteraction(block.getClass()) && !onlyReactsToHeldItem(block));
    }

    /**
     * 反射那份会多认出来的几个：它们的 {@code useWithoutItem} 只对「手里拿着特定东西」才有反应，
     * 拿着方块右键其实什么都不会发生，当普通方块处理就是对的。
     *
     * <p>目前只有栅栏：它的 {@code useWithoutItem} 只是 {@code LeadItem.bindPlayerMobs}
     * （手里有拴绳才绑生物，没有就直接返回「这一下我不管」）。
     */
    private static boolean onlyReactsToHeldItem(Block block) {
        return block instanceof FenceBlock;
    }

    /** 名单里漏掉、又确认右键会被方块自己吃掉的几个（反射那份是主力，这里是保底） */
    private static boolean isKnownInteractive(Block block) {
        return block instanceof SignBlock
            || block instanceof LeverBlock
            || block instanceof RedStoneWireBlock
            || block instanceof RepeaterBlock
            || block instanceof ComparatorBlock
            || block instanceof CakeBlock
            || block instanceof FlowerPotBlock
            || block instanceof ComposterBlock
            || block instanceof DaylightDetectorBlock
            || block instanceof DragonEggBlock;
    }

    /** 「这个方块类自己有没有实现右键交互」的缓存：方块类就那么些，一类问一次就够 */
    private static final Map<Class<?>, Boolean> INTERACTION_CACHE = new HashMap<>();

    /**
     * 这个方块的类（顺着继承链往上找）有没有自己实现 {@code useWithoutItem}（右键「用一下」）。
     *
     * <p>原版方块基类 {@link BlockBehaviour} 里它什么都不做（返回「这一下我不管」），
     * 所以「继承链上有人重写了它」就等于「这个方块右键有自己的动作」。子类继承父类的实现也算
     * （是在类上找的，不是在实例上），所以「立着的告示牌 / 墙上的告示牌」这种共用一个父类的都能认出来。
     */
    private static boolean declaresInteraction(Class<?> blockClass) {
        return INTERACTION_CACHE.computeIfAbsent(blockClass, cls -> {
            for (Class<?> current = cls; current != null && current != BlockBehaviour.class; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getName().equals("useWithoutItem")) return true;
                }
            }
            return false;
        });
    }

    /** 玩家此刻按不按着潜行：潜行时原版右键点可交互方块不会开界面，而是放方块 */
    private static boolean isSneaking() {
        return mc.player != null && mc.player.isShiftKeyDown();
    }

    /** 共用面中心：目标方块中心 + 半格 */
    private static Vec3 faceCenter(BlockPos target, Direction face) {
        return Vec3.atCenterOf(target)
            .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    /** 眼睛点按容差扩大成的小方块（判断「眼睛就在被点方块里」用；Grim 的豁免最多就这么宽） */
    private static AABB eyeBox(Vec3 eye, double expand) {
        return new AABB(
            eye.x - expand, eye.y - expand, eye.z - expand,
            eye.x + expand, eye.y + expand, eye.z + expand
        );
    }

    /**
     * 移动包会带的眼睛位置：现在的速度 + 这一 tick 会加上去的输入加速度。
     *
     * <p>原版这一 tick 的位移就是「起始速度 + moveRelative 的输入加速度」（重力是移动之后才加的，
     * 不算在里面），所以这两项加起来就是移动包里那个位置。走路时输入那项差不多 0.1 格 —— 
     * 搭路时正好是「眼睛刚越过脚下方块侧面」这种边界情况下差的那一点点。
     *
     * <p>公开给「自己算射线」的调用方用（例如包围的「一个角度放两块」，要拿射线去求交点）：
     * 和 {@link #compute} 内部用的完全是同一个起点。
     */
    public static Vec3 predictedEye() {
        return eyePosition(true, 0.0);
    }

    /**
     * 算角度用的眼睛位置。
     *
     * @param predictMovement true = 再加上这一 tick 的位移预测（速度 + 输入加速度）
     * @param eyeHeightOffset 眼睛高度再挪多少格（自动潜行按蹲下后的视角算角度时传负值）
     */
    private static Vec3 eyePosition(boolean predictMovement, double eyeHeightOffset) {
        Vec3 eye = mc.player.getEyePosition().add(0.0, eyeHeightOffset, 0.0);
        if (!predictMovement) return eye;
        return eye.add(mc.player.getDeltaMovement()).add(inputAcceleration());
    }

    /** 这一 tick 输入带来的那点位移（地面走路约 0.1 格，空中约 0.01 格；没输入就是 0） */
    private static Vec3 inputAcceleration() {
        double x = mc.player.xxa;
        double z = mc.player.zza;
        double lengthSqr = x * x + z * z;
        if (lengthSqr < 1.0E-7) return Vec3.ZERO;

        double speed = mc.player.getSpeed();
        if (mc.player.onGround()) {
            float friction = mc.level.getBlockState(mc.player.blockPosition().below()).getBlock().getFriction();
            speed *= 0.21600002 / (double) (friction * friction * friction);
        } else {
            speed *= 0.1;
        }

        double scale = lengthSqr > 1.0 ? speed / Math.sqrt(lengthSqr) : speed;
        double vx = x * scale;
        double vz = z * scale;

        float yawRad = mc.player.getYRot() * ((float) Math.PI / 180.0f);
        float sin = Mth.sin(yawRad);
        float cos = Mth.cos(yawRad);
        return new Vec3(vx * cos - vz * sin, 0.0, vz * cos + vx * sin);
    }

    /** 给定角度能不能合法地点到这个面 */
    private static boolean faceIsLegal(BlockPos target, Direction face, Vec3 eye, Vec3 dir, double reach, AABB eyeBox) {
        BlockPos clicked = target.relative(face);
        if (!isValidSupport(clicked)) return false;

        AABB clickedBox = new AABB(clicked);
        if (clickedBox.distanceToSqr(eye) > reach * reach) return false;
        if (eyeBox.intersects(clickedBox)) return true;
        if (!eyeOnVisibleSide(eye, clickedBox, face.getOpposite())) return false;
        return rayHitsBox(eye, eye.add(dir.scale(reach)), clickedBox);
    }

    /** 眼睛在不在点击面这一侧（能不能看见这个面） */
    private static boolean eyeOnVisibleSide(Vec3 eye, AABB box, Direction clickedFace) {
        return switch (clickedFace) {
            case UP -> eye.y >= box.maxY - EPSILON;
            case DOWN -> eye.y <= box.minY + EPSILON;
            case NORTH -> eye.z <= box.minZ + EPSILON;
            case SOUTH -> eye.z >= box.maxZ - EPSILON;
            case WEST -> eye.x <= box.minX + EPSILON;
            case EAST -> eye.x >= box.maxX - EPSILON;
        };
    }

    /** 射线第一个打到的方块 / 面是不是指定的那个（用的也是原版选方块那条射线） */
    private static boolean firstHitIs(Vec3 eye, Vec3 end, BlockPos clicked, Direction clickedFace) {
        BlockHitResult hit = mc.level.clip(new ClipContext(
            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        return hit.getBlockPos().equals(clicked) && hit.getDirection() == clickedFace;
    }

    /** 射线有没有打进这个方块的碰撞箱（眼睛在方块里时同样算打进） */
    private static boolean rayHitsBox(Vec3 eye, Vec3 end, AABB box) {
        if (box.contains(eye)) return true;
        return box.clip(eye, end).isPresent();
    }

    /** 面内偏移向量（面法线方向的分量为 0，另外两个轴各按 u / v 偏移） */
    private static Vec3 inPlaneOffset(Direction face, double u, double v) {
        Direction.Axis axis = face.getAxis();
        double du = u - 0.5;
        double dv = v - 0.5;

        if (axis == Direction.Axis.Y) return new Vec3(du, 0.0, dv);
        if (axis == Direction.Axis.X) return new Vec3(0.0, du, dv);
        return new Vec3(du, dv, 0.0);
    }

    /** 眼睛 → 点的偏航 */
    public static double yawTo(Vec3 eye, Vec3 point) {
        return Math.toDegrees(Math.atan2(point.z - eye.z, point.x - eye.x)) - 90.0;
    }

    /** 眼睛 → 点的俯仰（正数向下看，和 Player#getXRot 同向） */
    public static double pitchTo(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        return -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    /** 把角度量化到「相对 base 差整数格」：base + round((目标 − base) / 格) × 格 */
    private static float snapAngle(float base, double target, double step, boolean wrap) {
        double delta = wrap ? Mth.wrapDegrees(target - base) : (target - base);
        double snapped = base + Math.round(delta / step) * step;
        return (float) (wrap ? Mth.wrapDegrees(snapped) : Mth.clamp(snapped, -90.0, 90.0));
    }

    /** 相对服务器当前角度的转动代价（偏航按 ±180 处理，越小越好） */
    private static double angularCost(float baseYaw, float basePitch, float yaw, float pitch) {
        double dYaw = Mth.wrapDegrees(yaw - baseYaw);
        double dPitch = pitch - basePitch;
        return dYaw * dYaw + dPitch * dPitch;
    }
}
