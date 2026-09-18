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

import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.LegalPlace;
import fish22.modernsupport.utils.LegalRotation;
import fish22.modernsupport.utils.LitematicaCompat;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 放置模块基类 —— 「简单放置」和「投影打印机」共用的那一套放置实现。
 *
 * <p>这里放的是放置相关的<b>全部设置项</b>（协议 / 范围 / 穿墙范围 / 检查实体 / 挥手 /
 * 空中放置 / 完全合法 / 背包放置 这些组）和放置的<b>全部逻辑</b>。两个子类
 * （{@link SimplePlace} / {@link Printer}）各自继承一份：配置里是两个模块各自的选项
 * （值互相独立，各存各的），放置逻辑共用同一份代码 —— 所以「投影打印机」不需要依赖
 * 「简单放置」模块，它的设置是内嵌自己这一份。
 *
 * <p>子类的差别只有「怎么挑出要放的那一格」：
 *
 * <ul>
 *   <li>{@link SimplePlace}：按住快捷键，准星指哪放哪（{@link #placeAtCrosshair()}）；</li>
 *   <li>{@link Printer}：每隔「延迟」个 tick 扫一遍周围，把范围内待放的格子挨个试过去，
 *       直到放过一块或者全部试完（{@link #placeAt(BlockPos)}）。</li>
 * </ul>
 *
 * <p>协议分两组：
 *
 * <ul>
 *   <li><b>投影原有模式（自动 / v3 / v2 / 仅半砖 / 关）</b>：直接调用投影自己的
 *       {@code EasyPlaceUtils}，每一档对应投影「轻松放置 - 协议版本」里的一个模式；拾取方块、
 *       半砖处理和放置限制也都由投影处理。选了哪一档，只在这一档的放置期间改投影的协议，
 *       放完还原。</li>
 *   <li><b>合法-空中放置</b>：不需要支撑方块，直接点目标方块自己朝眼睛的那一面；
 *       用 {@link LegalPlace#computeAir(BlockPos)} 算一个朝向，{@link LegalRotation}
 *       转到那个朝向，放置包排在「带着这份朝向的移动包」之后。注意：Grim 的
 *       {@code AirLiquidPlace} 会取消生存模式的空中放置，这个模式适合没有该检查的服务器。</li>
 *   <li><b>完全合法</b>：用 {@link LegalPlace#compute(BlockPos)}（脚手架 mixin 用的同一个
 *       最佳合法位置检查）找一个能点到的支撑面。挑一个「放出来跟投影一样」的角度（方块种类 +
 *       朝哪边 + 上下半）；差的只是「转视角能改」的属性时照旧放，改不了的就不放这一格。适合 Grim 服。</li>
 * </ul>
 *
 * <p>两个自定义模式只校验「摆放姿态」：方块种类、朝哪边、是上半还是下半（楼梯/活板门的
 * {@code half}、半砖的 {@code type}）。水位、激活状态、连接位这些一律不管；开了「grim非法朝向
 * 绕过」连姿态都不用对得上（绕过搜不到对得上的角度时按老规矩放）。
 *
 * <p><b>范围 / 穿墙范围</b>：目标方块到眼睛的距离上限（「范围」，同时也是算角度用的 reach）；
 * 看不见的方块（视线被别的方块挡住）只要在「穿墙范围」以内也照样算目标（0 = 关闭，只放看得见的）。
 * 准星被墙挡住时，模块还会沿视线往墙后面看一段（步长 1/4 格），把墙后面要放的投影方块找出来。
 *
 * <p>放置逻辑只有这一份：「投影打印机」（{@link Printer}）扫到要放的格子后，也是调这里的
 * {@link #placeAt} —— 两个模块的区别只在「怎么挑出这一格」，放置那一套完全一样。
 *
 * <p>「放出来的得是投影那个方块」是硬要求：火把、告示牌、旗帜、悬挂告示牌这些「立着的」和
 * 「墙上的」在游戏里是两个方块，由物品按点的是哪一面挑一个，所以这里拿物品自己的
 * {@code BlockItem#getPlacementState} 把每个候选角度预演一遍 —— 瞄准点只有落在「能放出投影
 * 那个方块」的支撑面上才算数，点别的面会放出另一个方块（墙上的火把点天花板 → 放成立在地上的），
 * 那就这一格不放。朝向本身就是「看视角」的那类方块（活塞、楼梯、拉杆）跟以前一样：候选面里
 * 找得到对得上的就用它，找不到照旧放，不因为朝向对不上就跳过。
 *
 * <p>潜行（按着 Shift）时箱子/熔炉/拉杆/按钮这类可交互方块也能当支撑面：原版潜行右键点它们
 * 不会开界面、也不会被用掉，会照常把方块放上去。
 *
 * <p>投影是软前置：本 mod 不在 {@code fabric.mod.json} / 构建脚本里写死，运行时通过反射探测；
 * 没装投影时投影那几档会拒绝工作，另外两个模式仍能按原版准星做一个简版放置。
 *
 * <p>只放投影当前渲染出来的方块：落在投影「渲染层范围」之外的方块不画出来，模块也不去放。
 */
public abstract class PlaceModule extends Module {

    public enum Protocol {
        PROJECT_AUTO("投影-自动(v1)", "auto", "v1"),
        PROJECT_V3("投影-v3", "v3"),
        PROJECT_V2("投影-v2", "v2"),
        PROJECT_SLAB_ONLY("投影-仅半砖", "slabs_only"),
        PROJECT_NONE("投影-关", "none"),
        LEGAL_AIR("合法-空中放置"),
        FULLY_LEGAL("完全合法");

        private final String displayName;
        private final String[] projectProtocols;

        Protocol(String displayName, String... projectProtocols) {
            this.displayName = displayName;
            this.projectProtocols = projectProtocols;
        }

        /** 走投影自己的轻松放置（带指定的投影协议），而不是本模块自己算角度 */
        boolean isProject() {
            return projectProtocols.length > 0;
        }

        /** 投影配置里对应的协议名（投影不认的时候会按顺序往后试） */
        String[] projectProtocols() {
            return projectProtocols;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 「通用」组：子类会往这里加自己那一两项（快捷键 / 放置间隔、延迟），再调 {@link #addPlacementSettings()} */
    protected final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAir = settings.createGroup("空中放置");
    private final SettingGroup sgLegal = settings.createGroup("完全合法");
    private final SettingGroup sgBackpack = settings.createGroup("背包放置");

    private final Setting<Protocol> protocol = sgGeneral.add(new EnumSetting.Builder<Protocol>()
        .name("精准放置协议")
        .description("精准放置协议")
        .defaultValue(Protocol.PROJECT_AUTO)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("范围")
        .description("放置的最大距离")
        .defaultValue(4.5)
        .min(0.5)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Double> throughWallsRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("穿墙范围")
        .description("穿墙放置的最大距离")
        .defaultValue(4.5)
        .min(0)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Boolean> checkEntities = sgGeneral.add(new BoolSetting.Builder()
        .name("检查实体")
        .description("目标位置有实体时不放置")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("挥手")
        .description("放置成功后挥手")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> backpackPlace = sgBackpack.add(new BoolSetting.Builder()
        .name("背包放置")
        .description("放置背包中的方块")
        .defaultValue(true)
        .build()
    );

    private final Setting<BackpackUse.Mode> backpackMode = sgBackpack.add(new EnumSetting.Builder<BackpackUse.Mode>()
        .name("背包放置发包模式")
        .description("2次SWAP点击；4 次PICKUP点击")
        .defaultValue(BackpackUse.Mode.SWAP)
        .visible(backpackPlace::get)
        .build()
    );

    private final Setting<BackpackUse.TargetSlot> backpackSlot = sgBackpack.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
        .name("背包放置目标槽位")
        .description("背包放置切换的槽位")
        .defaultValue(BackpackUse.TargetSlot.OFFHAND)
        .visible(backpackPlace::get)
        .build()
    );

    private final Setting<LegalRotation.Mode> airRotation = sgAir.add(new EnumSetting.Builder<LegalRotation.Mode>()
        .name("合法转头")
        .description("空中放置时怎么把服务器视角转到目标方向")
        .defaultValue(LegalRotation.Mode.SEVERE)
        .visible(() -> protocol.get() == Protocol.LEGAL_AIR)
        .build()
    );

    private final Setting<Integer> airRotationPriority = sgAir.add(new IntSetting.Builder()
        .name("合法转头优先级")
        .description("合法转头的优先级")
        .defaultValue(0)
        .sliderRange(-20, 20)
        .visible(() -> protocol.get() == Protocol.LEGAL_AIR)
        .build()
    );

    private final Setting<Boolean> airBypass = sgAir.add(new BoolSetting.Builder()
        .name("grim非法朝向绕过")
        .description("利用grim的特性放置任意朝向的方块")
        .defaultValue(false)
        .visible(() -> protocol.get() == Protocol.LEGAL_AIR)
        .build()
    );

    private final Setting<Boolean> airBypassFreeze = sgAir.add(new BoolSetting.Builder()
        .name("grim非法朝向绕过期间冻结")
        .description("绕过要跨两 tick，期间冻结玩家防止失败")
        .defaultValue(false)
        .visible(() -> protocol.get() == Protocol.LEGAL_AIR && airBypass.get())
        .build()
    );

    private final Setting<LegalRotation.Mode> legalRotation = sgLegal.add(new EnumSetting.Builder<LegalRotation.Mode>()
        .name("合法转头")
        .description("完全合法模式怎么把服务器视角转到算出来的最佳角度")
        .defaultValue(LegalRotation.Mode.SEVERE)
        .visible(() -> protocol.get() == Protocol.FULLY_LEGAL)
        .build()
    );

    private final Setting<Integer> legalRotationPriority = sgLegal.add(new IntSetting.Builder()
        .name("合法转头优先级")
        .description("合法转头的优先级")
        .defaultValue(0)
        .sliderRange(-20, 20)
        .visible(() -> protocol.get() == Protocol.FULLY_LEGAL)
        .build()
    );

    private final Setting<Boolean> autoSneak = sgLegal.add(new BoolSetting.Builder()
        .name("自动潜行")
        .description("自动为你潜行")
        .defaultValue(true)
        .visible(() -> protocol.get() == Protocol.FULLY_LEGAL)
        .build()
    );

    private final Setting<Boolean> legalBypass = sgLegal.add(new BoolSetting.Builder()
        .name("grim非法朝向绕过")
        .description("利用grim的特性放置任意朝向的方块")
        .defaultValue(false)
        .visible(() -> protocol.get() == Protocol.FULLY_LEGAL)
        .build()
    );

    private final Setting<Boolean> legalBypassFreeze = sgLegal.add(new BoolSetting.Builder()
        .name("绕过期间冻结")
        .description("绕过要跨两 tick，期间冻结玩家防止失败")
        .defaultValue(true)
        .visible(() -> protocol.get() == Protocol.FULLY_LEGAL && legalBypass.get())
        .build()
    );

    private boolean warnedMissingLitematica;
    private boolean warnedOriginalUnavailable;
    private boolean warnedProtocolUnsupported;

    /**
     * 本 tick 是不是已经有放置模块动过手（绕过发了旋转 / 排上了放置包）。
     *
     * <p>两个模块共用这一个标记：同一 tick 只放一块，绕过跨的那两 tick 里更不会插进第二次放置。
     * 每 tick 末尾（{@link #onTickPost}）清掉。
     */
    private static boolean actedThisTick;

    /**
     * 有几个模块的「grim非法朝向绕过」正在跨 tick 走（两个模块共用一个计数）。
     *
     * <p>任何一个绕过没走完（还有一 tick 的放置包没发）时，谁都不该发起新的放置 ——
     * 这就是「绕过期间不发起第二次放置」里说的那条。
     */
    private static int bypassesInFlight;

    /** 上一次「投影原有模式」那一放投影说它没放成（放置包在 tick 末尾才发，只能下一 tick 告诉调用方） */
    private BlockPos projectFailure;

    /**
     * 一次放置允许的距离限制。
     *
     * <p>由本模块自己的「范围 / 穿墙范围」设置拼出来（每个模块各有一份设置，所以每次放置前现拼）。
     *
     * @param range            眼睛到目标方块的距离上限，同时也是算角度时用的 reach
     * @param throughWallRange 看不见的目标（视线被挡）允许放的距离；0 = 关闭，只能放看得见的
     */
    public record Limits(double range, double throughWallRange) {
    }

    /**
     * 名字本身表示「摆放姿态」的属性。
     *
     * <p>绝大多数这类属性的值就是 {@link Direction} / {@link Direction.Axis}（= {@code facing}、
     * {@code axis} 这些），直接按值类型判断就够了；这里额外补上几个值是数字或别的类型的
     * 方向类属性（告示牌/旗帜的 16 向 {@code rotation} 等）。
     */
    private static final Set<String> PLACEMENT_PROPERTY_NAMES = Set.of(
        "facing", "axis", "orientation", "rotation", "horizontal_facing", "vertical_direction"
    );

    /** 绕过搜索用的俯仰档位：正上/正下 + 抬头、平视、低头各三档（±45° 附近是分档边界） */
    private static final float[] BYPASS_PITCHES = {-90.0f, -60.0f, -20.0f, 0.0f, 20.0f, 60.0f, 90.0f};

    /** 绕过搜索结果的缓存时间（毫秒）：按住不放时同一个方块不重复搜 */
    private static final long BYPASS_CACHE_MILLIS = 1000L;

    /** 沿视线找墙后面目标时的步长（格）：够细，不会跳过 1 格宽的缝 */
    private static final double THROUGH_WALL_STEP = 0.25;

    private PendingBypass pendingBypass;
    private boolean bypassFreezeHeld;
    private boolean bypassMovePacketHeld;
    private boolean releaseFreezePost;

    /** 「自动潜行」按下去的那一下是不是我们按的（是的话本 tick 末松开） */
    private boolean autoSneakHeld;

    /** 上一次绕过搜索的结果（按「目标位置 + 投影状态」缓存，按住不放时不用每 tick 重搜） */
    private BlockPos bypassCachePos;
    private BlockState bypassCacheState;
    private BypassAim bypassCacheAim;
    private boolean bypassCacheRequireMatch;
    private long bypassCacheTime;

    protected PlaceModule(String name, String description) {
        super(Categories.World, name, description);
    }

    @Override
    public void onActivate() {
        abortBypass();
        actedThisTick = false;
        projectFailure = null;
        bypassCachePos = null;
        bypassCacheState = null;
        bypassCacheAim = null;
        warnedMissingLitematica = false;
        warnedOriginalUnavailable = false;
        warnedProtocolUnsupported = false;
    }

    @Override
    public void onDeactivate() {
        abortBypass();
        projectFailure = null;
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        tickPost();
    }

    /**
     * tick 收尾：清掉本 tick 的「动过手」标记，绕过跨 tick 的冻结 / 放行在这里恢复，
     * 自动潜行只按这一 tick。两个模块各自收尾自己那一份（谁开着谁跑）。
     */
    private void tickPost() {
        actedThisTick = false;

        if (releaseFreezePost) {
            releaseFreezePost = false;
            releaseBypassHolds();
        }

        // 自动潜行只按这一 tick
        releaseAutoSneak();
    }

    /** 换世界/重连时清掉上一次可能残留的绕过冻结状态 */
    @EventHandler
    protected void onGameJoined(GameJoinedEvent event) {
        abortBypass();
    }

    // ====== 投影原有模式 ======

    private boolean doProject(Protocol current, Limits limits) {
        if (!LitematicaCompat.isAvailable()) {
            warnMissingLitematica();
            return false;
        }
        if (!LitematicaCompat.isOriginalEasyPlaceAvailable()) {
            warnOriginalUnavailable();
            return false;
        }
        // 投影自己的放置逻辑不管渲染层：点到原版方块时，它也会把方块放进没渲染出来的层里。
        // 这里先自己探一下准星，确认这一下要放的位置真的是画出来的那一块。
        if (isTargetHiddenByRenderLayers(limits)) return false;

        String[] protocols = current.projectProtocols();
        if (!LitematicaCompat.isProtocolAvailable(protocols)) warnProtocolUnsupported(current);

        return LitematicaCompat.runOriginalEasyPlace(protocols);
    }

    /**
     * 准星这一下要放的位置，投影是不是压根没渲染（落在「渲染层范围」之外）。
     *
     * <p>只在自己探出来的位置确实有投影方块、而且没渲染时返回 {@code true}；探不出来（没装投影、
     * 接口对不上）时返回 {@code false}，交给投影自己去处理。
     */
    private boolean isTargetHiddenByRenderLayers(Limits limits) {
        if (mc.player == null) return false;

        LitematicaCompat.TargetHit targetHit = LitematicaCompat.getTargetHit(limits.range());
        if (targetHit == null || !(targetHit.hit() instanceof BlockHitResult hit)) return false;

        BlockPos pos = targetHit.schematic()
            ? hit.getBlockPos()
            : hit.getBlockPos().relative(hit.getDirection());

        Level schematic = LitematicaCompat.getSchematicWorld();
        if (schematic == null) return false;

        BlockState desired = schematic.getBlockState(pos);
        return !desired.isAir() && !LitematicaCompat.isPositionRendered(pos);
    }

    private void warnMissingLitematica() {
        if (warnedMissingLitematica) return;
        warnedMissingLitematica = true;
        error("未检测到投影模组（Litematica）；「简单放置」的投影相关模式需要先安装投影模组");
    }

    private void warnOriginalUnavailable() {
        if (warnedOriginalUnavailable) return;
        warnedOriginalUnavailable = true;
        error("检测到投影模组，但当前版本的轻松放置入口不可用");
    }

    private void warnProtocolUnsupported(Protocol current) {
        if (warnedProtocolUnsupported) return;
        warnedProtocolUnsupported = true;
        error("当前投影版本没有「%s」这一档协议，本次按投影自己的协议设置放置", current);
    }

    /**
     * 投影原有模式（投影打印机用的入口）：对指定的一格调用投影自带的轻松放置。
     *
     * <p>投影自己的轻松放置是「按玩家此刻的朝向打射线找目标」的（准星指哪放哪），所以要先让视角
     * 对准这一格再去调它：客户端朝向临时转过去（投影射线读的是客户端朝向，调完立刻恢复），
     * 真正发给服务器的朝向由「合法转头」负责 —— 放置包排在带着这份朝向的移动包之后，
     * 服务器看到的朝向才和这一放对得上。
     *
     * <p>这是「尽量让投影按我们的意思放」的写法：投影不支持临时转向时（或者版本对不上）
     * 就放不出来，这一格留给下一轮。
     *
     * <p>转向用的是「严格」模式（和 {@code LegalRotation.rotate(yaw, pitch)} 的默认档一致，
     * 即真实旋转 + 客户端静默），优先级取「合法转头API配置 → 默认优先级」。
     */
    private boolean placeProjectAt(Protocol current, BlockPos pos, Limits limits) {
        if (!LitematicaCompat.isAvailable()) {
            warnMissingLitematica();
            return false;
        }
        if (!LitematicaCompat.isOriginalEasyPlaceAvailable()) {
            warnOriginalUnavailable();
            return false;
        }
        // 投影自己的放置逻辑不管渲染层：点到没渲染出来的那一层，它也会把方块放进去，这里自己挡掉
        if (!LitematicaCompat.isPositionRendered(pos)) return false;
        if (!withinLimits(pos, limits)) return false;
        // 投影是按玩家此刻的朝向打射线找目标的：前面挡着方块时，它会点到挡路的那一块上，
        // 放出来的就不是这一格 —— 所以这一档不做穿墙，看不见就不放
        if (!LegalPlace.canSee(pos)) return false;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, flat)), -90.0, 90.0);

        // 被更高优先级的旋转顶掉就不放这一格（发出去的角度和服务器视角对不上）
        if (!LegalRotation.rotate(yaw, pitch, LegalRotation.Mode.SEVERE, LegalRotation.defaultPriority())) return false;

        actedThisTick = true;

        String[] protocols = current.projectProtocols();
        if (!LitematicaCompat.isProtocolAvailable(protocols)) warnProtocolUnsupported(current);

        LegalRotation.runAfterSend(() -> {
            if (mc.player == null) return;

            float oldYaw = mc.player.getYRot();
            float oldPitch = mc.player.getXRot();
            try {
                // 投影的射线追踪是从玩家当前朝向打出去的，不临时转过去就看不到这一格
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                if (!LitematicaCompat.runOriginalEasyPlace(protocols)) projectFailure = pos;
            } finally {
                mc.player.setYRot(oldYaw);
                mc.player.setXRot(oldPitch);
            }
        });

        return true;
    }

    /**
     * 这一格在不在距离限制里：看得见就按「范围」，看不见就按「穿墙范围」
     * （穿墙范围 0 = 看不见的方块不作为目标）。
     */
    private boolean withinLimits(BlockPos pos, Limits limits) {
        if (mc.player == null || pos == null) return false;

        double distance = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        return withinLimits(limits, distance, LegalPlace.canSee(pos));
    }

    /**
     * 距离限制的判定本身（距离和「看得见吗」都由调用方算好）。
     *
     * <p>看得见按「范围」，看不见按「穿墙范围」（0 = 不放看不见的）。
     */
    private static boolean withinLimits(Limits limits, double distance, boolean visible) {
        if (visible) return distance <= limits.range();
        return limits.throughWallRange() > 0.0 && distance <= limits.throughWallRange();
    }

    // ====== 合法-空中放置 ======

    private boolean doAirPlace(Target target, Limits limits) {
        BlockItem item = requiredBlockItem(target.schematicState());
        if (item == null) return false;
        if (!BlockUtils.canPlaceBlock(target.pos(), checkEntities.get(), item.getBlock())) return false;

        LegalPlace.AirAim aim = LegalPlace.computeAir(target.pos(), true, limits.range());
        if (aim == null) return false;

        BlockState desired = target.schematicState();
        boolean hasDesiredState = desired != null && !desired.isAir();
        ItemStack stack = hasDesiredState ? requiredStack(desired, item) : ItemStack.EMPTY;

        // 这个角度点下去放出来的得是投影那个方块：火把/告示牌这种「立着的」和「墙上的」在游戏里
        // 是两个方块，点错面放出来就是另一个（投影要立着的、放成墙上的，看着就是「放到下面去」了）。
        // 对不上就换别的面重算，六个面都放不出投影那个方块 → 这一下不放（放下去等于放错）。
        if (hasDesiredState && !matchesDirection(desired, aim, target.pos(), stack)) {
            AirScan scan = findAirAimForDirection(target.pos(), desired, stack, !isVariantItem(stack), limits.range());

            if (scan.match() != null) {
                aim = scan.match();
            } else if (scan.loose() != null) {
                // 方块是这个方块、只是「这一下角度不对」（活塞/楼梯这种朝向看视角的）：照旧宽松放
                aim = scan.loose();
            } else {
                // 绕过能转出任意角度，先让它试（这里要求真搜到「放出来跟投影一致」的角度才放）；
                // 搜不出来就不放这一格
                if (airBypass.get() && airRotation.get() != LegalRotation.Mode.OFF
                    && armAirBypass(target, item, aim, true, limits)) return true;

                return false;
            }
        }

        // Grim 非法朝向绕过：单次合法朝向放出来跟投影对不上时，第一 tick 发合法朝向，第二 tick
        // 发「算出能放出投影状态」的朝向再放。只有「差的属性转视角能改」的才值得绕（改不了的属性，
        // 绕过也白冻结两 tick）；搜不到对得上的角度还是照常拿宽松角度放下去。
        if (airBypass.get() && airRotation.get() != LegalRotation.Mode.OFF && hasDesiredState) {
            // 绕过会冻结玩家，这一 tick 不会真的走；用当前眼睛位置重新算合法朝向
            LegalPlace.AirAim bypassAim = airBypassFreeze.get()
                ? LegalPlace.computeAir(target.pos(), false, limits.range())
                : aim;

            if (bypassAim != null
                && !matchesDirection(desired, bypassAim, target.pos(), stack)
                // 对不上的属性里只要有一个转视角也改不了，绕过就救不了，跳过
                && mismatchIsLookOnly(desired, bypassAim, target.pos(), stack)
                && armAirBypass(target, item, bypassAim, false, limits)) {
                return true;
            }
        }

        return placeAir(item, target.pos(), aim, airRotation.get(), airRotationPriority.get());
    }

    private void interactAir(BlockItem item, BlockPos target, LegalPlace.AirAim aim) {
        interact(item, new BlockHitResult(aim.hitPos(), aim.face(), target, false));
    }

    // ====== 完全合法 ======

    private boolean doFullyLegal(Target target, Limits limits) {
        BlockItem item = requiredBlockItem(target.schematicState());
        if (item == null) return false;
        if (!BlockUtils.canPlaceBlock(target.pos(), checkEntities.get(), item.getBlock())) return false;

        // 自动潜行：先按住潜行再来算角度 —— 只有潜行时容器（箱子/熔炉）和拉杆/按钮这些
        // 「点了会被用掉」的可交互方块才会进候选面。算完发现这一下用不上它们就立刻松开，
        // 真要拿它们当支撑面才留到本 tick 末。
        holdAutoSneak();

        BlockState desired = target.schematicState();
        boolean hasDesiredState = desired != null && !desired.isAir();
        ItemStack stack = hasDesiredState ? requiredStack(desired, item) : ItemStack.EMPTY;

        // 这一格能点的支撑面每个都算一遍角度，逐个看放出来是什么：投影要的往往只有其中一个
        // —— 火把/告示牌点错面会放成另一个方块（立着的 ↔ 墙上的），朝向类方块点错面方向也是错的。
        List<LegalPlace.Aim> candidates = LegalPlace.computeCandidates(
            target.pos(), LegalPlace.supportFaces(target.pos()), true, 0.0, limits.range()
        );
        if (candidates.isEmpty()) {
            releaseAutoSneak();
            return false;
        }

        LegalPlace.Aim aim = candidates.getFirst();
        if (hasDesiredState) {
            LegalPlace.Aim match = null;  // 放出来跟投影一模一样（方块 + 方向）的角度
            LegalPlace.Aim loose = null;  // 方块对得上、差的只有「转视角能改」的朝向（宽松兜底用）

            for (LegalPlace.Aim candidate : candidates) {
                BlockState placed = simulatedState(stack, candidate);
                if (placed == null) continue;

                if (samePlacement(placed, desired)) {
                    match = candidate;
                    break;
                }
                if (loose == null && !isVariantItem(stack)
                    && placed.getBlock().equals(desired.getBlock())
                    && mismatchIsLookOnly(desired, candidate, stack)) {
                    loose = candidate;
                }
            }

            if (match != null) {
                aim = match;
            } else if (loose != null) {
                // 方块是这个方块、只是「这一下角度不对」（活塞/楼梯这种朝向看视角的）：照旧宽松放
                aim = loose;
            } else {
                // 这一下怎么点都放不出投影要的那个东西 → 交给绕过（能转出任意角度），
                // 绕过真搜到对得上的角度才放（requireMatch），搜不出来就不放这一格。
                if (legalBypass.get() && legalRotation.get() != LegalRotation.Mode.OFF
                    && armLegalBypass(target, item, candidates.getFirst(), true, limits)) return true;

                releaseAutoSneak();
                return false;
            }
        }

        // 这一下要拿可交互方块（容器、拉杆、按钮这些）当支撑面 → 得潜行。但潜行会把视角压低
        // （站着 1.62 格、蹲下 1.27 格），极限角度下站着算出来的朝向、蹲下之后可能就不合法了，
        // 所以按蹲下后的眼睛高度把这个支撑面的角度再算一遍：还算得出「放出来是同一个东西」的
        // 角度就潜行放下去，算不出来就不放这一格（硬潜行发出去，服务器那边看到的就是个擦着棱角
        // 打飞的包）。
        if (isClickableSupport(aim.clickedBlock())) {
            LegalPlace.Aim sneakingAim = findCrouchingAim(target, aim, desired, stack, limits.range());

            if (sneakingAim == null) {
                releaseAutoSneak();
                return false;
            }

            aim = sneakingAim;
        } else {
            // 这一下点的是真方块、不是可交互方块 → 用不上潜行，松开
            releaseAutoSneak();
        }

        // 「grim非法朝向绕过」：单次转向放出来的方向不对时，第一 tick 发一个合法朝向，第二 tick
        // 转到一个「算出能放出投影状态」的角度再放（活塞、熔炉、拉杆都算）。绕过会冻结玩家，
        // 所以这一 tick 不会真的走；用当前眼睛位置重新算第一 tick 的合法朝向。
        if (legalBypass.get() && legalRotation.get() != LegalRotation.Mode.OFF && hasDesiredState) {
            // 已经对得上就不用绕过；「朝向只看点的是哪一面」的方块绕过也改不了，一样跳过
            if (!matchesDirection(desired, aim, stack)
                && mismatchIsLookOnly(desired, aim, stack)) {
                LegalPlace.Aim from = legalBypassFreeze.get()
                    ? LegalPlace.compute(target.pos(), LegalPlace.supportFaces(target.pos()), false, 0.0, limits.range())
                    : aim;

                if (from != null && armLegalBypass(target, item, from, false, limits)) return true;
            }
        }

        return placeLegal(item, aim, legalRotation.get(), legalRotationPriority.get());
    }

    /** 发出「转过去 → 放置」这一套；返回 true 表示这一下已经安排好了（旋转+放置包已排上） */
    private boolean placeAir(BlockItem item, BlockPos target, LegalPlace.AirAim aim, LegalRotation.Mode mode, int priority) {
        if (aim.rotated()) {
            if (mode == LegalRotation.Mode.OFF) return false;
            // 被更高优先级的旋转顶掉：这一下不放（不然发出去的角度和服务器视角对不上）
            if (!LegalRotation.rotate(aim.yaw(), aim.pitch(), mode, priority)) return false;
        }

        actedThisTick = true;
        LegalRotation.runAfterSend(() -> interactAir(item, target, aim));
        return true;
    }

    /** 发出「转过去 → 放置」这一套；返回 true 表示这一下已经安排好了（旋转+放置包已排上） */
    private boolean placeLegal(BlockItem item, LegalPlace.Aim aim, LegalRotation.Mode mode, int priority) {
        if (aim.rotated()) {
            if (mode == LegalRotation.Mode.OFF) return false;
            // 被更高优先级的旋转顶掉：这一下不放（不然发出去的角度和服务器视角对不上）
            if (!LegalRotation.rotate(aim.yaw(), aim.pitch(), mode, priority)) return false;
        }

        actedThisTick = true;
        LegalRotation.runAfterSend(() -> interactSupport(item, aim));
        return true;
    }

    /**
     * 放出来的「摆放姿态」和投影是不是一致：方块种类 + 朝哪边 + 是上半还是下半。
     *
     * <p>比的是那些「放置本身决定的东西」（{@link #isPlacementProperty}）：活塞/熔炉朝哪边、
     * 原木立着还是躺着、梯子朝哪边、拉杆挂着/立着/贴墙、**楼梯/活板门的上下半、
     * 半砖的上/下半**。
     *
     * <p>不比水位、{@code powered} / {@code extended} / {@code lit} 这类激活状态、
     * 栅栏/墙/红石线的连接位、楼梯与铁轨的 {@code shape} —— 这些要么不是放置能决定的
     * （邻居变了之后原版自己会更新），要么本来就由别的方块/红石决定。
     */
    private boolean samePlacement(BlockState placed, BlockState desired) {
        if (!placed.getBlock().equals(desired.getBlock())) return false;

        for (Property<?> property : desired.getProperties()) {
            if (!isPlacementProperty(property, desired)) continue;
            if (!desired.getValue(property).equals(placed.getValue(property))) return false;
        }

        return true;
    }

    /**
     * 这个属性算不算「摆放姿态」—— 放置的时候由「点的是哪一面 / 面里的哪个位置 / 视角朝哪边」
     * 决定、放置包说什么就是什么的那些属性：
     *
     * <ul>
     *   <li>值是方向 / 朝向轴 / 附着面 {@link AttachFace} 的（{@code facing}、{@code axis}、
     *       拉杆按钮的 {@code face}）；</li>
     *   <li>值是上下的 {@link Half}（楼梯、活板门的 {@code half}）和 {@link SlabType}
     *       （半砖的 {@code type}：上/下/双层）—— 这些「上下」也是点哪一面、点面里的哪个位置
     *       定的，跟方向一样不能忽略；</li>
     *   <li>名字本身就是姿态类的（告示牌 16 向 {@code rotation} 等，值不是上面这些类型）。</li>
     * </ul>
     */
    private boolean isPlacementProperty(Property<?> property, BlockState state) {
        Object value = state.getValue(property);
        if (value instanceof Direction || value instanceof Direction.Axis) return true;
        if (value instanceof AttachFace || value instanceof Half || value instanceof SlabType) return true;
        return PLACEMENT_PROPERTY_NAMES.contains(property.getName());
    }

    /** 完全合法模式：这个角度点下去放出来，跟投影是不是同一个状态（方块 + 方向） */
    private boolean matchesDirection(BlockState desired, LegalPlace.Aim aim, ItemStack stack) {
        return matchesDirection(desired, stack, aim.yaw(), aim.pitch(), aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock());
    }

    /** 空中放置模式：这个角度点下去放出来，跟投影是不是同一个状态（方块 + 方向） */
    private boolean matchesDirection(BlockState desired, LegalPlace.AirAim aim, BlockPos target, ItemStack stack) {
        return matchesDirection(desired, stack, aim.yaw(), aim.pitch(), aim.hitPos(), aim.face(), target);
    }

    /** 把角度代进原版放置上下文，看看放出来是不是投影要的那个状态 */
    private boolean matchesDirection(BlockState desired, ItemStack stack, float yaw, float pitch, Vec3 hitPos,
                                     Direction hitFace, BlockPos clicked) {
        BlockState placed = simulatePlacement(stack, yaw, pitch, hitPos, hitFace, clicked);
        return placed != null && samePlacement(placed, desired);
    }

    /** 完全合法模式：这个角度点下去会放成什么状态 */
    private BlockState simulatedState(ItemStack stack, LegalPlace.Aim aim) {
        return simulatePlacement(stack, aim.yaw(), aim.pitch(), aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock());
    }

    /** 空中放置模式：这个角度点下去会放成什么状态 */
    private BlockState simulatedState(ItemStack stack, LegalPlace.AirAim aim, BlockPos target) {
        return simulatePlacement(stack, aim.yaw(), aim.pitch(), aim.hitPos(), aim.face(), target);
    }

    /**
     * 把这个角度代进原版放置上下文，看看<b>手上这个物品</b>会放成什么状态（这个角度放不出来时返回 {@code null}）。
     *
     * <p>走的必须是物品自己那一套（{@link BlockItem#getPlacementState}），不能拿投影那个方块的
     * {@code getStateForPlacement} 顶替：火把、告示牌、旗帜、悬挂告示牌这些「立着的」和「墙上的」
     * 在游戏里是两个方块，由物品按点的是哪一面、视角朝哪边挑一个。拿方块自己算的话，明明要立着的，
     * 用墙上的那个方块去算也会算出立着的（反过来也一样），照着它挑角度就会点到别的面上、
     * 把方块放到别的地方去（墙上的火把点天花板 → 放成立在地上的）。
     */
    private BlockState simulatePlacement(ItemStack stack, float yaw, float pitch, Vec3 hitPos, Direction hitFace,
                                         BlockPos clicked) {
        if (mc.player == null) return null;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;

        float oldYaw = mc.player.getYRot();
        float oldPitch = mc.player.getXRot();
        try {
            // BlockPlaceContext 里的「6 向视线」是在取值时读玩家当前角度的，模拟前先把角度换过去
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);

            // 和 BlockItem#useOn 一样：先让物品自己挪一下放置上下文，再问它会放成什么
            BlockPlaceContext context = blockItem.updatePlacementContext(new BlockPlaceContext(
                mc.player,
                InteractionHand.MAIN_HAND,
                stack,
                new BlockHitResult(hitPos, hitFace, clicked, false)
            ));

            return context == null ? null : blockItem.getPlacementState(context);
        } finally {
            mc.player.setYRot(oldYaw);
            mc.player.setXRot(oldPitch);
        }
    }

    /**
     * 这个物品是不是「一个物品对应两个方块」的那种（火把/红石火把/告示牌/旗帜/悬挂告示牌）：
     * 立着的和墙上的各算一个方块，由物品按点的是哪一面挑。
     *
     * <p>这类只要点不出投影那一种就绝不放：放宽成「方向对不上也照旧放」的话，点立面会放出立着的、
     * 点天花板会放出挂在顶上的，放下去全是错的。
     */
    private boolean isVariantItem(ItemStack stack) {
        return stack.getItem() instanceof StandingAndWallBlockItem;
    }

    /** 按「蹲下后的眼睛高度」把同一个支撑面的角度再算一遍（放出来得还是同一个东西） */
    private LegalPlace.Aim findCrouchingAim(Target target, LegalPlace.Aim aim, BlockState desired, ItemStack stack, double reach) {
        // 站着那一下放出来的是什么：宽松兜底（朝向对不上照旧放）时拿它当基准
        BlockState wanted = simulatedState(stack, aim);

        for (LegalPlace.Aim candidate : LegalPlace.computeCandidates(
            target.pos(), List.of(aim.face()), true, crouchingEyeHeightOffset(), reach)) {
            BlockState placed = simulatedState(stack, candidate);
            if (placed == null) continue;
            if (desired != null && samePlacement(placed, desired)) return candidate;
            if (wanted != null && samePlacement(placed, wanted)) return candidate;
        }

        return null;
    }

    /** 完全合法模式：这个候选跟投影的差距，是不是只有「转视角能改」的那些属性 */
    private boolean mismatchIsLookOnly(BlockState desired, LegalPlace.Aim aim, ItemStack stack) {
        return mismatchIsLookOnly(
            desired, stack, aim.yaw(), aim.pitch(), aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock()
        );
    }

    /** 空中放置模式：这个候选跟投影的差距，是不是只有「转视角能改」的那些属性 */
    private boolean mismatchIsLookOnly(BlockState desired, LegalPlace.AirAim aim, BlockPos target, ItemStack stack) {
        return mismatchIsLookOnly(desired, stack, aim.yaw(), aim.pitch(), aim.hitPos(), aim.face(), target);
    }

    /**
     * 这个候选放出来的东西跟投影对不上，那这些「对不上的姿态属性」是不是转视角就能改？
     *
     * <p>姿态属性分两种：一种是「点的是哪一面 / 面里的哪个位置」定的（漏斗朝向、拉杆的
     * {@code face} 挂着/立着/贴墙、火把立着还是贴墙、**楼梯/活板门的上下半、半砖的上/下半**），
     * 转视角也改不了；另一种是「视角朝哪边」定的（活塞朝向、楼梯朝向、拉杆横摆方向）。
     * 对不上的如果全都是后者，那这一下只是「这次角度不巧」，还有救（转视角 / 开绕过扫角度），
     * 可以按老规矩宽松放；只要有一个是前者（例如投影里拉杆挂在顶上、这一点只能立在地板上），
     * 这一下怎么转都放不出投影要的样子 —— 那就不能放宽，宁可这一格不放。
     *
     * <p>判断方式：同一个命中点用相差 180° 的两个视角各模拟一次。某个属性两个视角放出来一模一样、
     * 却又跟投影不一样 → 它不看视角 → 是前者 → 不放宽。
     */
    private boolean mismatchIsLookOnly(BlockState desired, ItemStack stack, float yaw, float pitch,
                                       Vec3 hitPos, Direction hitFace, BlockPos clicked) {
        BlockState placed = simulatePlacement(stack, yaw, pitch, hitPos, hitFace, clicked);
        if (placed == null || !placed.getBlock().equals(desired.getBlock())) return false;

        BlockState flipped = simulatePlacement(stack, Mth.wrapDegrees(yaw + 180.0f), -pitch, hitPos, hitFace, clicked);
        if (flipped == null) return false;

        for (Property<?> property : desired.getProperties()) {
            if (!isPlacementProperty(property, desired)) continue;
            if (desired.getValue(property).equals(placed.getValue(property))) continue;      // 这一项本来就对
            if (placed.getValue(property).equals(flipped.getValue(property))) return false;  // 转视角也改不了
        }

        return true;
    }

    /** 空中放置逐个面重算的结果：完全一致的 + 宽松兜底用的（方块对得上、差的只有朝向） */
    private record AirScan(LegalPlace.AirAim match, LegalPlace.AirAim loose) {
    }

    /**
     * 漏斗这类「朝向只看点的是哪一面」的方块：逐个面重算空中放置朝向（每次只允许点那一个面），
     * 挑一个放出来跟投影一致的。六个面都放不出跟投影一致的，就退回
     * {@link AirScan#loose()}（方块对得上、差的只有「转视角能改」的朝向）；两个都空就是这一下不放。
     *
     * @param allowLoose 允许宽松兜底吗（火把/告示牌这种「一个物品两个方块」的不允许：
     *                   点错面方块就换了一个，必须完全一致）
     */
    private AirScan findAirAimForDirection(BlockPos target, BlockState desired, ItemStack stack, boolean allowLoose, double reach) {
        LegalPlace.AirAim loose = null;

        for (Direction face : Direction.values()) {
            LegalPlace.AirAim candidate = LegalPlace.computeAir(target, List.of(face), true, reach);
            if (candidate == null) continue;

            if (matchesDirection(desired, candidate, target, stack)) return new AirScan(candidate, candidate);

            if (loose == null && allowLoose && mismatchIsLookOnly(desired, candidate, target, stack)) {
                loose = candidate;
            }
        }

        return new AirScan(null, loose);
    }

    // ====== Grim 非法朝向绕过（两 tick） ======

    /**
     * 一次「朝向 + 点哪里」的组合。
     *
     * <p>命中点带着走是因为：原版算方块状态时既看玩家朝向，也看点的是哪一面的哪一个点
     * （拉杆的附着面、楼梯的上半/形状都跟命中的面有关）。所以第二 tick 的放置包必须用
     * 「算这个状态时用的那一份命中点」，不能拿第一 tick 的合法命中点顶替。
     */
    private record BypassAim(float yaw, float pitch, Vec3 hitPos, Direction hitFace, BlockPos clicked) {
    }

    private record PendingBypass(
        BlockItem item,
        BlockPos target,
        BlockState desired,
        BypassAim firstAim,
        BypassAim stateAim,
        LegalRotation.Mode mode,
        int priority,
        boolean freeze,
        double reach
    ) {
    }

    private boolean armAirBypass(Target target, BlockItem item, LegalPlace.AirAim legalAim, boolean requireMatch, Limits limits) {
        BlockState desired = target.schematicState();
        if (desired == null || desired.isAir()) return false;

        BypassAim firstAim = new BypassAim(legalAim.yaw(), legalAim.pitch(), legalAim.hitPos(), legalAim.face(), target.pos());
        BypassAim stateAim = searchStateAim(
            target, desired, requiredStack(desired, item),
            legalAim.yaw(), legalAim.pitch(), List.of(firstAim), requireMatch
        );
        if (stateAim == null) return false;

        return armBypass(new PendingBypass(
            item, target.pos(), desired,
            firstAim, stateAim,
            airRotation.get(), airRotationPriority.get(), airBypassFreeze.get(), limits.range()
        ));
    }

    private boolean armLegalBypass(Target target, BlockItem item, LegalPlace.Aim legalAim, boolean requireMatch, Limits limits) {
        BlockState desired = target.schematicState();
        if (desired == null || desired.isAir()) return false;

        BypassAim stateAim = searchStateAim(
            target, desired, requiredStack(desired, item),
            legalAim.yaw(), legalAim.pitch(), legalCandidates(target, legalAim, limits.range()), requireMatch
        );
        if (stateAim == null) return false;

        return armBypass(new PendingBypass(
            item, target.pos(), desired,
            toBypassAim(legalAim), stateAim,
            legalRotation.get(), legalRotationPriority.get(), legalBypassFreeze.get(), limits.range()
        ));
    }

    private BypassAim toBypassAim(LegalPlace.Aim aim) {
        return new BypassAim(aim.yaw(), aim.pitch(), aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock());
    }

    /**
     * 搜「能放出投影方向」的角度，结果按「目标位置 + 投影状态」缓存：按住快捷键不放时，
     * 同一个方块只会搜一次（这一搜可能要模拟上百次原版放置）。
     */
    private BypassAim searchStateAim(Target target, BlockState desired, ItemStack stack,
                                     float fromYaw, float fromPitch, List<BypassAim> candidates, boolean requireMatch) {
        long now = System.currentTimeMillis();
        if (bypassCachePos != null && bypassCachePos.equals(target.pos()) && bypassCacheState == desired
            && bypassCacheRequireMatch == requireMatch && now - bypassCacheTime < BYPASS_CACHE_MILLIS) {
            return bypassCacheAim;
        }

        BypassAim aim = findBypassAim(desired, fromYaw, fromPitch, stack, candidates, requireMatch);

        bypassCachePos = target.pos();
        bypassCacheState = desired;
        bypassCacheRequireMatch = requireMatch;
        bypassCacheAim = aim;
        bypassCacheTime = now;

        return aim;
    }

    /**
     * 绕过搜索的候选「点哪里」：先试第一 tick 那个合法角度点的地方，再试其它能当支撑的面
     * （每个面各算一个合法角度，用它的命中点）。
     */
    private List<BypassAim> legalCandidates(Target target, LegalPlace.Aim legalAim, double reach) {
        List<BypassAim> candidates = new ArrayList<>();
        candidates.add(toBypassAim(legalAim));

        for (Direction face : LegalPlace.supportFaces(target.pos())) {
            LegalPlace.Aim aim = LegalPlace.compute(target.pos(), List.of(face), true, 0.0, reach);
            if (aim == null) continue;
            if (aim.face() == legalAim.face() && aim.clickedBlock().equals(legalAim.clickedBlock())) continue;
            candidates.add(toBypassAim(aim));
        }

        return candidates;
    }

    /**
     * 找一个「转过去之后原版会放成投影姿态」的朝向。
     *
     * <p>不再只枚举六向视线：活塞/发射器/观察者那种方向完全由「最近视线方向」决定的，六向就够；
     * 但拉杆、活板门、楼梯这些还要看俯仰落在哪一档（抬头 / 平视 / 低头），以及点中的是哪一个面，
     * 所以这里按 15° 扫 yaw、按档扫 pitch，并且允许换支撑面点。
     *
     * <p>只比摆放姿态（{@link #samePlacement}，含楼梯/活板门/半砖的上下）：水位、激活状态
     * 这些对不上都无所谓。
     *
     * <p>{@code requireMatch} = true 时搜不到「放出来跟投影一致」的角度就返回 {@code null}
     * （调用方据此决定这一格不放，别再放成别的样子）；false 时退回第一个算得出来的角度
     * （老规矩：朝向只是这一下不巧的，照旧放）。
     */
    private BypassAim findBypassAim(BlockState desired, float fromYaw, float fromPitch,
                                    ItemStack stack, List<BypassAim> candidates, boolean requireMatch) {
        float[][] grid = bypassGrid(fromYaw, fromPitch);
        BypassAim firstAny = null;

        for (BypassAim candidate : candidates) {
            for (float[] pair : grid) {
                float yaw = pair[0];
                float pitch = pair[1];

                BlockState placed = simulatePlacement(
                    stack, yaw, pitch, candidate.hitPos(), candidate.hitFace(), candidate.clicked()
                );
                if (placed == null) continue;

                BypassAim aim = new BypassAim(yaw, pitch, candidate.hitPos(), candidate.hitFace(), candidate.clicked());

                if (samePlacement(placed, desired)) return aim;
                if (firstAny == null) firstAny = aim;
            }
        }

        return requireMatch ? null : firstAny;
    }

    /** 搜索用的朝向组合，按「相对第一 tick 那个角度转得最少」排好（先试代价小的） */
    private float[][] bypassGrid(float fromYaw, float fromPitch) {
        List<float[]> combos = new ArrayList<>();

        for (float yaw = -180.0f; yaw < 180.0f; yaw += 15.0f) {
            for (float pitch : BYPASS_PITCHES) {
                combos.add(new float[]{yaw, pitch});
            }
        }

        combos.sort(Comparator.comparingDouble(pair -> {
            double dYaw = Mth.wrapDegrees(pair[0] - fromYaw);
            double dPitch = pair[1] - fromPitch;
            return dYaw * dYaw + dPitch * dPitch;
        }));

        return combos.toArray(new float[0][]);
    }

    private boolean armBypass(PendingBypass pending) {
        // 第一 tick：发一个 Grim 能通过的合法朝向（射线打得到被点方块）
        // 被更高优先级的旋转顶掉就不启动绕过（冻结两 tick 也白搭，这一格不放）
        if (!LegalRotation.rotate(pending.firstAim().yaw(), pending.firstAim().pitch(),
            pending.mode(), pending.priority())) {
            return false;
        }

        pendingBypass = pending;
        bypassesInFlight++;
        actedThisTick = true;

        // 绕过要跨两 tick；冻结可能拦下带位置的移动包，这里让带旋转的包放行
        Freeze.requestRotatedMovePackets();
        bypassMovePacketHeld = true;

        // 两 tick 都冻结，防止位置变化导致第二 tick 的判定对不上
        if (pending.freeze()) {
            Freeze.requestExternalFreeze();
            bypassFreezeHeld = true;
        }

        return true;
    }

    private void tickPendingBypass() {
        PendingBypass pending = pendingBypass;
        if (pending == null) return;

        // 绕过这一 tick 也算「引擎动过手」：同一 tick 里另一个驱动模块（简单放置 / 投影打印机）不能再放
        actedThisTick = true;

        if (!canPlacePending(pending)) {
            abortBypass();
            return;
        }

        // 第二 tick：转到一个「原版能放成投影状态」的朝向（搜索出来的那个），放置包仍排在移动包之后；
        // 此时服务器 lastYaw/lastPitch 仍是第一 tick 的合法朝向，Grim RotationPlace 会按它放行。
        BypassAim stateAim = pending.stateAim();

        // 真正点下去的是这一 tick：这一下点在容器上就补个自动潜行
        if (isClickableSupport(stateAim.clicked())) holdAutoSneak();

        // 被更高优先级的旋转顶掉：这一格不放，直接收尾（解冻 / 放行移动包）
        if (!LegalRotation.rotate(stateAim.yaw(), stateAim.pitch(), pending.mode(), pending.priority())) {
            abortBypass();
            return;
        }

        BlockHitResult hit = new BlockHitResult(stateAim.hitPos(), stateAim.hitFace(), stateAim.clicked(), false);
        LegalRotation.runAfterSend(() -> interact(pending.item(), hit));

        pendingBypass = null;
        if (bypassesInFlight > 0) bypassesInFlight--;
        releaseFreezePost = true;
    }

    private boolean canPlacePending(PendingBypass pending) {
        if (mc.player == null || mc.level == null) return false;
        if (!BlockUtils.canPlaceBlock(pending.target(), checkEntities.get(), pending.item().getBlock())) return false;
        if (!hasItem(pending.item())) return false;
        if (!LitematicaCompat.isPositionRendered(pending.target())) return false;

        double reach = pending.reach();
        if (new AABB(pending.firstAim().clicked()).distanceToSqr(mc.player.getEyePosition()) > reach * reach) return false;

        if (pending.desired() != null) {
            BlockState current = LitematicaCompat.getSchematicState(pending.target());
            if (current == null || current.isAir()) return false;
        }

        return true;
    }

    private void abortBypass() {
        if (pendingBypass != null) {
            pendingBypass = null;
            if (bypassesInFlight > 0) bypassesInFlight--;
        }
        releaseFreezePost = false;
        releaseBypassHolds();
        releaseAutoSneak();
    }

    private void releaseBypassHolds() {
        if (bypassMovePacketHeld) {
            bypassMovePacketHeld = false;
            Freeze.releaseRotatedMovePackets();
        }
        if (bypassFreezeHeld) {
            bypassFreezeHeld = false;
            Freeze.releaseExternalFreeze();
        }
    }

    /**
     * 自动潜行：把潜行按下去（玩家自己按着、或者没开「自动潜行」时不动）。
     *
     * <p>潜行状态存在「输入记录」（{@code ClientInput.keyPresses}）里：客户端每 tick 会按真实按键
     * 重算一遍，服务器收到的也是这一份（{@code ServerboundPlayerInputPacket}）。所以这里两手都做 ——
     * 把潜行键按下去（重算时算出来还是潜行），再直接把当前这份记录也改成潜行（这一 tick 里模块自己
     * 读到的潜行状态才是对的，角度计算才会把容器算进候选面）。
     *
     * <p>这样放置包发出去时服务器看到的是「潜行 + 手上有方块」，点是容器也是放方块、不会开界面。
     * 按下去的这一下在本 tick 末自动松开（见 {@link #onTickPost}）。
     */
    private void holdAutoSneak() {
        if (mc.player == null || autoSneakHeld) return;
        if (!autoSneak.get() || mc.options.keyShift.isDown() || mc.player.isShiftKeyDown()) return;

        mc.options.keyShift.setDown(true);
        setInputShift(true);
        autoSneakHeld = true;
    }

    /** 松开「自动潜行」按下去的那一下（玩家自己按着的不动） */
    private void releaseAutoSneak() {
        if (!autoSneakHeld) return;

        autoSneakHeld = false;
        mc.options.keyShift.setDown(false);
        setInputShift(false);
    }

    /**
     * 改「输入记录」里的潜行位。
     *
     * <p>原版按它决定这一 tick 是不是潜行，它也是发给服务器的输入包里的那个 {@code Input}，
     * 所以改这里等于「这一 tick 真的按着潜行键」；改回 false 后原版下一 tick 会自己把这次变化
     * 发出去，服务器就知道不潜行了。
     */
    private void setInputShift(boolean shift) {
        if (mc.player == null) return;

        Input old = mc.player.input.keyPresses;
        if (old.shift() == shift) return;

        mc.player.input.keyPresses = new Input(
            old.forward(), old.backward(), old.left(), old.right(), old.jump(), shift, old.sprint()
        );
    }

    /** 这个位置上的方块是不是「右键会被用掉」的那种（容器、拉杆、按钮这些） */
    private boolean isClickableSupport(BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        return LegalPlace.isInteractive(mc.level.getBlockState(pos).getBlock());
    }

    /** 蹲下后的眼睛高度相对当前眼睛高度的差值（自动潜行要按蹲下的视角算角度，所以是负值） */
    private double crouchingEyeHeightOffset() {
        return mc.player.getEyeHeight(Pose.CROUCHING) - mc.player.getEyeHeight();
    }

    private ItemStack requiredStack(BlockState state, BlockItem fallback) {
        if (state != null && !state.isAir()) {
            ItemStack stack = LitematicaCompat.getRequiredItem(state);
            if (!stack.isEmpty()) return stack;
        }
        return fallback.getDefaultInstance();
    }

    private void interactSupport(BlockItem item, LegalPlace.Aim aim) {
        interact(item, new BlockHitResult(aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock(), false));
    }

    /**
     * 用这个方块物品点这一下。
     *
     * <p>开了「背包放置」就交给 {@link BackpackUse}：快捷栏/副手优先（静默切换），
     * 都没有才从背包任意位置换到「目标槽位」放置，同一 tick 内换回；
     * 关闭时保持原来的行为（只认快捷栏/副手，背包里的不动）。
     */
    private void interact(BlockItem item, BlockHitResult hit) {
        if (backpackPlace.get()) {
            BackpackUse.place(stack -> stack.is(item), hit, backpackMode.get(), backpackSlot.get(), swingHand.get());
            return;
        }

        FindItemResult found = InvUtils.findInHotbar(item);
        if (!found.found()) return;

        boolean offhand = found.isOffhand();
        boolean swap = !offhand && !found.isMainHand();
        if (swap) InvUtils.swap(found.slot(), true);

        BlockUtils.interact(hit, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, swingHand.get());

        if (swap) InvUtils.swapBack();
    }

    // ====== 目标与物品 ======

    private record Target(BlockPos pos, BlockState schematicState) {
    }

    /**
     * 找这次要放的投影方块。
     *
     * <p>优先用投影自己的射线追踪：命中投影幽灵方块就把那一格当目标；命中原版方块就把
     * 「原版方块朝准星方向隔壁那一格」当目标。投影接口不可用或没命中时退回原版
     * {@code mc.hitResult}，行为和普通搭路一样（点支撑方块，放到它面向玩家的隔壁）。
     */
    private Target findTarget(Limits limits) {
        Target visible = findVisibleTarget(limits.range());
        if (visible != null) return visible;

        // 准星被方块挡住（或者根本没指到投影方块）时，只要「穿墙范围」开着，就往墙后面看一看
        return findThroughWallTarget(limits.throughWallRange());
    }

    /**
     * 看得见的那一批：准星指到的那一格。
     *
     * <p>射线长度用「范围」而不是原版的交互距离：服务器允许放得更远时，把范围调大就能
     * 看到 / 放到更远的投影方块（原版准星 {@code mc.hitResult} 仍按原版距离算，够不到就够不到）。
     */
    private Target findVisibleTarget(double reach) {
        boolean hasLitematica = LitematicaCompat.isAvailable();
        Level schematic = LitematicaCompat.getSchematicWorld();

        LitematicaCompat.TargetHit targetHit = LitematicaCompat.getTargetHit(reach);
        if (targetHit != null && targetHit.hit() instanceof BlockHitResult hit) {
            if (targetHit.schematic()) {
                Target target = targetAt(hit.getBlockPos(), schematic, hasLitematica);
                if (target != null) return target;
            } else {
                Target target = targetAt(hit.getBlockPos().relative(hit.getDirection()), schematic, hasLitematica);
                if (target != null) return target;
            }
        }

        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            Target target = targetAt(hit.getBlockPos().relative(hit.getDirection()), schematic, hasLitematica);
            if (target != null) return target;

            target = targetAt(hit.getBlockPos(), schematic, hasLitematica);
            if (target != null) return target;
        }

        return null;
    }

    /**
     * 看不见的那一批：沿视线「穿过」挡在前面的方块，找墙后面的投影方块。
     *
     * <p>准星被方块挡住时人是看不到后面的，但服务器判定放置只看「眼睛 → 被点方块」够不够得着、
     * 朝向对不对，所以只要「穿墙范围」开着、距离够近，墙后面那一格一样能放（放的时候点的是
     * 能点到的那一面，见 {@link LegalPlace}）。
     *
     * <p>做法是从眼睛沿视线一步步往前走（步长 1/4 格），遇到的第一个「投影里有方块、
     * 客户端这一格可替换」的位置就是目标。
     *
     * @param throughWallRange 穿墙范围；0 = 关闭（看不见的方块不作为目标）
     */
    private Target findThroughWallTarget(double throughWallRange) {
        if (mc.player == null || throughWallRange <= 0.0) return null;
        if (!LitematicaCompat.isAvailable()) return null;

        Level schematic = LitematicaCompat.getSchematicWorld();
        if (schematic == null) return null;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 direction = mc.player.calculateViewVector(mc.player.getXRot(), mc.player.getYRot());

        BlockPos last = null;
        for (double step = THROUGH_WALL_STEP; step <= throughWallRange; step += THROUGH_WALL_STEP) {
            BlockPos pos = BlockPos.containing(eye.add(direction.scale(step)));
            if (pos.equals(last)) continue;
            last = pos;

            Target target = targetAt(pos, schematic, true);
            if (target != null) return target;
        }

        return null;
    }

    /**
     * 某个位置能不能当这次放置的目标。
     *
     * @param pos             候选位置
     * @param schematic       投影世界（没有投影时为 null）
     * @param requireSchematic 投影已安装时，要求这个位置在投影里真的有一个不同的方块
     */
    private Target targetAt(BlockPos pos, Level schematic, boolean requireSchematic) {
        if (pos == null || mc.level == null || !mc.level.isLoaded(pos)) return null;

        BlockState client = mc.level.getBlockState(pos);
        if (!client.canBeReplaced()) return null;

        if (requireSchematic) {
            if (schematic == null) return null;
            // 渲染层范围之外的方块投影不画，也不该去放
            if (!LitematicaCompat.isPositionRendered(pos)) return null;
            BlockState desired = schematic.getBlockState(pos);
            if (desired.isAir()) return null;
            if (desired == client || desired.equals(client)) return null;
            return new Target(pos, desired);
        }

        return new Target(pos, null);
    }

    /**
     * 这次要用的方块物品。
     *
     * <p>有投影状态时按投影状态找（优先走投影的 MaterialCache，拿不到时退回方块物品）；
     * 没有投影状态时用主手物品。只接受能当方块放的物品，并且只找快捷栏/副手
     * （自定义模式不会动背包里的物品，投影原有模式的拾取方块不受这个限制）。
     */
    private BlockItem requiredBlockItem(BlockState schematicState) {
        ItemStack stack = schematicState != null && !schematicState.isAir()
            ? LitematicaCompat.getRequiredItem(schematicState)
            : mc.player.getMainHandItem();

        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) return null;
        if (!hasItem(blockItem)) return null;
        return blockItem;
    }

    /** 这个方块物品现在能不能用上：开了「背包放置」就允许背包（主区）里的 */
    private boolean hasItem(BlockItem item) {
        return backpackPlace.get()
            ? InvUtils.find(stack -> stack.is(item)).found()
            : InvUtils.findInHotbar(item).found();
    }

    // ====== 子类（两个模块）用的接口 ======

    /** 当前的距离限制：本模块自己的「范围 / 穿墙范围」设置 */
    private Limits limits() {
        return new Limits(range.get(), throughWallsRange.get());
    }

    /** 当前的精准放置协议 */
    protected Protocol protocol() {
        return protocol.get();
    }

    /** 能放多远（眼睛到目标方块的距离上限） */
    protected double range() {
        return range.get();
    }

    /** 看不见的方块允许放多远（0 = 不放看不见的） */
    protected double throughWallsRange() {
        return throughWallsRange.get();
    }

    /** 两个距离限制里松的那个（扫描范围用） */
    protected double maxRange() {
        return Math.max(range.get(), throughWallsRange.get());
    }

    /**
     * 这一格在不在距离限制里：看得见按「范围」，看不见按「穿墙范围」
     * （穿墙范围 0 = 看不见的方块不作为目标）。可见性由调用方算好传进来。
     */
    protected boolean placementAllowed(BlockPos pos, boolean visible) {
        if (mc.player == null || pos == null) return false;

        double distance = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        return withinLimits(limits(), distance, visible);
    }

    /**
     * 接着走跨 tick 的「grim非法朝向绕过」。
     *
     * <p>绕过是「第一 tick 发合法朝向、第二 tick 转过去放」的两 tick 流程，状态存在模块自己这里，
     * 所以驱动方每 tick 都得推一下，不然第二 tick 那一放就永远不来了。
     *
     * @return {@code true} = 这一 tick 走的是绕过（调用方本 tick 不要再发起放置）
     */
    protected boolean tickPendingPlacement() {
        if (pendingBypass == null) return false;
        tickPendingBypass();
        return true;
    }

    /**
     * 这一 tick 是不是已经有放置模块动过手（绕过在跨 tick / 本 tick 已经排上了放置包）。
     *
     * <p>驱动方看到 true 就本 tick 别再放 —— 绕过那两 tick 里连放两块，服务器一眼就看得出来。
     */
    protected boolean engineBusy() {
        // 自己或别的模块的绕过还没走完，或者本 tick 已经动过手 → 这次不放
        return pendingBypass != null || bypassesInFlight > 0 || actedThisTick;
    }

    /** 收掉还没走完的绕过（关模块 / 打开界面 / 换世界时调） */
    protected void abortEngine() {
        abortBypass();
    }

    /**
     * 取走「上一 tick 那一放投影自己说没放成」的位置（没有就返回 {@code null}），取完就清掉。
     *
     * <p>投影原有模式的放置包是在 tick 末尾（移动包之后）才发的，那一刻已经没法回头告诉调用方，
     * 所以只能留在这里等调用方下一 tick 来问 —— 「投影打印机」拿到之后会把这一格短暂记进失败表，
     * 不然它会卡在同一个放不出来的格子上一直重试。
     */
    protected BlockPos takeProjectFailure() {
        BlockPos pos = projectFailure;
        projectFailure = null;
        return pos;
    }

    /**
     * 便宜的前置检查（「投影打印机」扫候选用）：这一格现在值不值得试。
     *
     * <p>只做便宜的判断：投影里这一格有方块要放、客户端这一格能替换、手上（开着「背包放置」
     * 时算上背包）有这个方块。算角度那部分很贵（可能要模拟上百次原版放置），在
     * {@link #placeAt} 里做。
     *
     * <p>投影原有模式（投影-自动 / v3 / v2 / 仅半砖）不查手上那一格 —— 那几档是投影自己去
     * 背包里拾取方块的 —— 但会确认背包里真有这个方块：没有的话投影也放不成，白转一次视角。
     */
    protected boolean canPlaceAt(BlockPos pos) {
        if (mc.player == null || mc.level == null || pos == null) return false;
        if (!LitematicaCompat.isAvailable()) return false;

        Target target = targetAt(pos, LitematicaCompat.getSchematicWorld(), true);
        if (target == null) return false;

        if (protocol.get().isProject()) return hasMaterial(target.schematicState());

        BlockItem item = requiredBlockItem(target.schematicState());
        if (item == null) return false;
        return BlockUtils.canPlaceBlock(pos, checkEntities.get(), item.getBlock());
    }

    /**
     * 背包（含快捷栏、副手）里有没有放这个投影状态要用的方块。
     *
     * <p>投影自己的轻松放置是从背包里拾取方块的，所以这里查的是整个背包，不看「背包放置」设置。
     * 投影拿不出这个物品（或者拿出来的不是方块物品，例如红石线用的是红石粉）时不拦。
     */
    private boolean hasMaterial(BlockState desired) {
        if (desired == null || desired.isAir()) return false;

        ItemStack stack = LitematicaCompat.getRequiredItem(desired);
        if (stack.isEmpty()) return true;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return true;

        return InvUtils.find(s -> s.is(blockItem)).found();
    }

    /**
     * 对指定的一格跑一遍当前的放置逻辑（「投影打印机」用）。
     *
     * @return {@code true} = 这一格这一下安排好了（旋转 + 放置包已经排上；开了绕过的话是
     *         第一 tick 的旋转已经发出去、第二 tick 才落方块）；{@code false} = 这一格这次放不了，
     *         调用方可以去试下一格
     */
    protected boolean placeAt(BlockPos pos) {
        if (mc.player == null || mc.level == null || pos == null) return false;
        // 绕过还没走完 / 本 tick 已经动过手：不发起新的放置（不然就是绕过期间连放两块）
        if (pendingBypass != null || actedThisTick) return false;

        Limits limits = limits();
        if (!withinLimits(pos, limits)) return false;

        Protocol current = protocol.get();
        if (current.isProject()) return placeProjectAt(current, pos, limits);

        Target target = targetAt(pos, LitematicaCompat.getSchematicWorld(), true);
        if (target == null) return false;

        return current == Protocol.LEGAL_AIR ? doAirPlace(target, limits) : doFullyLegal(target, limits);
    }

    /**
     * 对「准星指到的那一格」跑一遍放置逻辑（「简单放置」的驱动方式）。
     *
     * @return {@code true} = 已经安排好了（旋转 + 放置包排在移动包之后）
     */
    protected boolean placeAtCrosshair() {
        Limits limits = limits();
        Protocol current = protocol.get();

        if (current.isProject()) return doProject(current, limits);

        Target target = findTarget(limits);
        if (target == null) return false;

        return current == Protocol.LEGAL_AIR ? doAirPlace(target, limits) : doFullyLegal(target, limits);
    }
}
