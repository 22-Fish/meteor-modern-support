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

import fish22.modernsupport.utils.LegalPlace;
import fish22.modernsupport.utils.LitematicaCompat;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 投影打印机（世界分类）— 配合投影模组（Litematica）自动搭投影。
 *
 * <p>和「简单放置」的区别只有一个：简单放置是「按住快捷键，准星指哪放哪」，本模块是
 * 「每过一段延迟，自己扫一遍周围，挑一格放」。
 *
 * <h3>配置（全部内嵌自己这一份）</h3>
 *
 * <p>本模块的配置就是简单放置那一整套（继承 {@link PlaceModule}，设置项一一对应），
 * 另外多一个「延迟」。两边各存各的值、互不影响，也<b>不需要开着「简单放置」</b>：
 *
 * <ul>
 *   <li><b>延迟</b>：每轮之间至少等多少 tick（1 = 每 tick 扫一轮）。</li>
 *   <li><b>精准放置协议</b>：投影-自动(v1) / v3 / v2 / 仅半砖 / 关 / 合法-空中放置 / 完全合法。</li>
 *   <li><b>范围 / 穿墙范围</b>：能放多远；看不见的方块允许放到多远（0 = 只放看得见的）。</li>
 *   <li><b>检查实体 / 挥手</b>，以及「空中放置」「完全合法」「背包放置」三个组里的
 *       合法转头与优先级、grim 非法朝向绕过、绕过期间冻结、自动潜行、背包放置三件套。</li>
 * </ul>
 *
 * <h3>一轮里做什么</h3>
 *
 * <ol>
 *   <li>把「范围 / 穿墙范围」这个立方体里的格子按离眼睛近的排好（不会每 tick 重排）；</li>
 *   <li>从近到远挨个看：投影里有方块要放、这一格现在放得下、手上有（或背包里有）这个方块；</li>
 *   <li>第一个「现在就能放」的格子交给放置逻辑（{@link PlaceModule#placeAt(BlockPos)}）——
 *       算最佳合法角度、转视角、发放置包，和手动按快捷键时的行为完全一致；</li>
 *   <li>这一轮就到此为止（放过一块了），等「延迟」个 tick 再扫下一轮。</li>
 * </ol>
 *
 * <p><b>放不了的格子不会卡住扫描</b>：一轮里会一直往下看，直到真的放下一块，或者范围内的格子
 * 全看完。这一轮放不掉的那一格<b>没有冷却</b>，下一轮照样排在最前面重新试 —— 你挪一步、
 * 多放一块垫脚、材料补上，下一 tick 可能就能放了。（之前是「一轮只看一格」，碰上放不了的
 * 格子就整轮空转，看起来就是一会儿放一会儿不放。）
 *
 * <p>唯一例外是投影原有那几档（投影-自动 / v3 / v2 / 仅半砖）：它们的一放要先转视角再交给
 * 投影，成不成只有投影自己知道（下一 tick 才回话），所以投影说「没放成」的那一格会被挪到
 * 候选表末尾，下一轮先试别的，它自己照样轮得到（也不会有冷却时间）。
 *
 * <h3>绕过为什么不能连放</h3>
 *
 * <p>开了「grim非法朝向绕过」时，一格的放置要跨两个 tick（第一 tick 发合法朝向，第二 tick
 * 转过去发放置包）。绕过这两 tick 里本模块不再发起第二次放置 —— 服务器看到的会是
 * 「转完立刻连放两块」，正常玩家做不到这个。（两个模块的"本 tick 是否动过手"是共用的，
 * 所以简单放置和投影打印机同开时，同一 tick 里也只会动一次手。）
 */
public class Printer extends PlaceModule {

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("延迟")
        .description("打印机搜索的延迟")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    /** 距离下一轮扫描还剩几 tick */
    private int timer;

    /** 候选位置（按离眼睛的距离排好），搜索半径或玩家所在方块变了就重建 */
    private final List<BlockPos> candidates = new ArrayList<>();
    private int cachedRadius = -1;
    private BlockPos cachedOrigin;

    private boolean warnedMissingLitematica;

    public Printer() {
        super("投影打印机",
            "在范围内自动搭投影");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        resetScan();
        warnedMissingLitematica = false;
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        resetScan();
    }

    /** 换世界/重连：清掉上一次的扫描状态和绕过状态 */
    @Override
    protected void onGameJoined(GameJoinedEvent event) {
        super.onGameJoined(event);
        resetScan();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.screen != null) {
            abortEngine();
            return;
        }

        // 「grim非法朝向绕过」要跨两 tick：这两 tick 里只把绕过走完，不再发起第二次放置
        if (tickPendingPlacement()) return;

        // 这个 tick 已经动过手了（绕过，或者「简单放置」自己刚放过）
        if (engineBusy()) return;

        // 上一 tick 投影原有模式那一放没放成（投影自己说的）：把这一格挪到队尾，
        // 下一轮先试别的 —— 不然它会一直挡在最前面，别的格子永远轮不到
        BlockPos projectFailure = takeProjectFailure();
        if (projectFailure != null) pushBack(projectFailure);

        if (timer > 0) {
            timer--;
            return;
        }

        if (!LitematicaCompat.isAvailable()) {
            warnMissingLitematica();
            return;
        }
        // 没加载投影（没打开投影文件）时没什么可搭的
        if (LitematicaCompat.getSchematicWorld() == null) return;

        sweep();
    }

    // ====== 一轮扫描 ======

    /**
     * 扫一轮：从近到远把范围内待放的格子挨个试，放下一块就停；全部试完都放不了就等下一轮。
     *
     * <p>「试」之前先做便宜和中等成本的过滤（有没有方块要放 / 放得下 / 有材料 / 距离 / 可见性），
     * 真正贵的「算合法角度」只留到最后一步（{@link PlaceModule#placeAt(BlockPos)}）。
     */
    private void sweep() {
        rebuildCandidates();

        Vec3 eye = mc.player.getEyePosition();
        double limit = maxRange();
        double limitSqr = limit * limit;

        for (BlockPos pos : candidates) {
            // 表是按「重建那一刻」离眼睛的距离排好的：这里只当优先级用（近的先试），
            // 距离判定照旧按当前眼睛位置单独算，免得人走过之后表里的顺序过时了漏掉格子
            if (distanceSqr(pos, eye) > limitSqr) continue;

            if (!canPlaceAt(pos)) continue;

            boolean visible = LegalPlace.canSee(pos);
            // 投影原有那几档是「投影自己按准星打射线」放的：看不见的格子它会点到挡在
            // 前面的方块上，所以这些协议下穿墙范围不生效，只放看得见的
            if (!visible && protocol().isProject()) continue;
            if (!placementAllowed(pos, visible)) continue;

            // 这一格现在就能放：交给放置逻辑；这一下放不出来就继续看下一格（不卡在这儿）
            if (placeAt(pos)) break;
        }

        timer = Math.max(1, delay.get()) - 1;
    }

    /** 眼睛到方块中心的距离平方（扫描时用，省得每次都造一个 Vec3） */
    private static double distanceSqr(BlockPos pos, Vec3 eye) {
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 重建候选位置表：以玩家所在方块为中心、半径 {@code maxRange} 的立方体，按离眼睛的距离排好。
     *
     * <p>半径和玩家所在方块都没变就不重建（排序只在范围设置变化、或者人走过一格时发生）。
     */
    private void rebuildCandidates() {
        BlockPos origin = mc.player.blockPosition();
        int radius = (int) Math.ceil(maxRange());

        if (radius == cachedRadius && origin.equals(cachedOrigin) && !candidates.isEmpty()) return;

        cachedRadius = radius;
        cachedOrigin = origin;
        candidates.clear();

        Vec3 eye = mc.player.getEyePosition();
        Level level = mc.level;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (level.isOutsideBuildHeight(pos)) continue;
                    candidates.add(pos);
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
    }

    private void resetScan() {
        timer = 0;
        candidates.clear();
        cachedRadius = -1;
        cachedOrigin = null;
    }

    /**
     * 把这一格挪到候选表末尾。
     *
     * <p>给「投影原有模式里投影自己放不出来」的格子用：这一格没放成，下一轮先试别的，
     * 它自己照样会被轮到（不会一直卡在队首，也不会被彻底跳过）。人一动、材料一补、下一 tick
     * 视角一变，它可能就能放了，所以这里不留任何「冷却时间」。
     */
    private void pushBack(BlockPos pos) {
        if (candidates.remove(pos)) candidates.add(pos);
    }


    private void warnMissingLitematica() {
        if (warnedMissingLitematica) return;
        warnedMissingLitematica = true;
        error("未检测到投影模组（Litematica）；「投影打印机」需要先安装投影模组");
    }
}
