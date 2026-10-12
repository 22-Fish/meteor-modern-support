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
import fish22.modernsupport.utils.ElytraFlySupport;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 重锤光环 — 战斗模块
 *
 * <p>只在鞘翅飞行时生效：锁一个目标后自动拉升到「比目标高过阈值高度」，
 * 再俯冲飞向目标，进入换甲范围换上胸甲，进入攻击范围用重锤砸一下，
 * 砸完立刻穿回鞘翅起飞重新拉升。
 *
 * <p>三个阶段：
 * <ul>
 *   <li><b>拉升</b>：默认斜向绕着目标盘旋上升（「斜向爬升」），够高后从一个斜角砸过去；
 *       关掉斜向才是对着目标直上直下（可选「更优爬升俯仰 / 偏航」45°）</li>
 *   <li><b>下砸</b>：高度 + 水平距离都够了就斜着朝目标俯冲，下砸中目标升到我们上面立刻改回拉升</li>
 *   <li><b>攻击</b>：换甲范围内换上胸甲，进入攻击范围后切锤砸一下，然后穿回鞘翅起飞</li>
 * </ul>
 *
 * <p><b>只在锁定目标期间</b>才碰鞘翅飞行（暂停简单控制 / 接管方向 / 解悬停冻结）；
 * 没锁目标时完全让鞘翅飞行自己跑。
 *
 * <p><b>自动起飞</b>（可选，不用索敌）：地上让输入包报「按下跳跃」起跳（由原版自己跳，不碰按键映射），
 * 空中换上鞘翅起飞（连着甲飞）；
 * 「兼容 grim 输入检测」时跳跃与起飞都配输入包：空中第一 tick 就把输入包的跳跃键报成「松开」
 * （见 {@link ElytraFlySupport#setExternalTakeoffPending(boolean)}），等服务端看到松开包才发起飞包，
 * 见 {@link #tickAutoTakeoff()} / {@link #startFly()}
 *
 * <p>起飞包被驳回（反作弊取消 / 服务端没进滑翔）不会卡死：起飞看门狗发现服务端一直没同步滑翔位，
 * 就把本地那个自己伪造的滑翔标志清掉重发（见 {@link ElytraFlySupport#watchdogClearUnconfirmedTakeoff()}）
 *
 * <p>转向走合法转头 API（{@link LegalRotation}），默认「固定相机」：
 * 只改发出去的朝向，视角不动；关掉就直接设置视角。
 * 锁定目标时按「暂停鞘翅简单控制」暂停鞘翅飞行模块的方向控制（顺带停甲飞 / 无限鞘翅换甲）
 *
 * <p>装甲交互：
 * <ul>
 *   <li>兼容甲飞 / 无限鞘翅：接管期间让它们让位（甲飞表现为「停止甲飞」）</li>
 *   <li>换甲就是三击互换（背包那件 ↔ 胸甲槽），和「鞘翅自动替换」同一套，不打断本地物品栏</li>
 *   <li>起飞走 {@link ElytraFlySupport#externalStartFlying()}，「兼容 grim 输入检测」时自动配一对跳跃输入包</li>
 * </ul>
 */
public class MaceAura extends Module {

    /** 状态机 */
    private enum Phase {
        IDLE,   // 没锁目标
        CLIMB,  // 拉升
        DIVE,   // 下砸
        ATTACK  // 换甲攻击
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMace = settings.createGroup("重锤");
    private final SettingGroup sgAim = settings.createGroup("瞄准");
    private final SettingGroup sgClimb = settings.createGroup("爬升");
    private final SettingGroup sgFirework = settings.createGroup("自动烟花");
    private final SettingGroup sgCompat = settings.createGroup("兼容");

    // ====== 常规 ======

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("目标范围")
        .description("多大的范围内搜索目标")
        .defaultValue(128.0)
        .min(8.0)
        .sliderMax(256.0)
        .build()
    );

    private final Setting<Set<EntityType<?>>> targetEntities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("目标实体")
        .description("要攻击的实体类型")
        .defaultValue(EntityType.PLAYER, EntityType.WITHER)
        .onlyAttackable()
        .build()
    );

    // ====== 重锤 ======

    private final Setting<Double> thresholdHeight = sgMace.add(new DoubleSetting.Builder()
        .name("阈值高度")
        .description("比目标 y 高过此高度以上才向目标下砸")
        .defaultValue(40.0)
        .min(1.0)
        .sliderMax(120.0)
        .build()
    );

    private final Setting<Double> attackRange = sgMace.add(new DoubleSetting.Builder()
        .name("攻击范围")
        .description("重锤的攻击距离，进这个范围就砸")
        .defaultValue(4.5)
        .min(1.0)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Double> throughWallRange = sgMace.add(new DoubleSetting.Builder()
        .name("穿墙攻击范围")
        .description("看不见目标时，在这个距离内照样能砸（隔墙攻击）")
        .defaultValue(4.5)
        .min(1.0)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Double> preSwapRange = sgMace.add(new DoubleSetting.Builder()
        .name("换甲范围")
        .description("进这个范围换上胸甲。脱离范围或攻击完就换回鞘翅起飞")
        .defaultValue(6.0)
        .min(1.0)
        .sliderMax(12.0)
        .build()
    );

    private final Setting<Double> minFallDistance = sgMace.add(new DoubleSetting.Builder()
        .name("最小下落距离")
        .description("下落距离不足这么多时不砸（重锤要有坠落差才疼）")
        .defaultValue(1.5)
        .min(0.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Boolean> autoSwapMace = sgMace.add(new BoolSetting.Builder()
        .name("自动切锤")
        .description("攻击前自动切到快捷栏里的重锤，砸完切回原物品")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> attackCooldown = sgMace.add(new IntSetting.Builder()
        .name("攻击冷却")
        .description("两次攻击之间至少隔多少 tick")
        .defaultValue(10)
        .min(0)
        .sliderMax(40)
        .build()
    );

    // ====== 瞄准 ======

    private final Setting<Boolean> spin = sgAim.add(new BoolSetting.Builder()
        .name("旋转")
        .description("砸中那一下转一次服务器偏航角（走合法转头），其余时间保持抬头方便复飞")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> spinSpeed = sgAim.add(new DoubleSetting.Builder()
        .name("旋转速度")
        .description("砸中那一下转多少度")
        .defaultValue(20.0)
        .min(1.0)
        .sliderMax(180.0)
        .visible(spin::get)
        .build()
    );

    private final Setting<Boolean> fixedCamera = sgAim.add(new BoolSetting.Builder()
        .name("固定相机")
        .description("开启后用合法转头转动（视角不动）；关闭直接设置视角")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rotationPriority = sgAim.add(new IntSetting.Builder()
        .name("合法转头优先级")
        .description("和别的模块抢转向时用的优先级，比鞘翅飞行 / 自动拉升高才压得住")
        .defaultValue(10)
        .sliderRange(-20, 20)
        .visible(fixedCamera::get)
        .build()
    );

    // ====== 爬升 ======

    private final Setting<Boolean> betterPitch = sgClimb.add(new BoolSetting.Builder()
        .name("更优爬升俯仰")
        .description("拉升时用 45° 俯仰（爬升更快）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> betterYaw = sgClimb.add(new BoolSetting.Builder()
        .name("更优爬升偏航")
        .description("拉升时在 4 个 45° 方向里挑一个（爬升更快）。只在关掉「斜向爬升」时起作用")
        .defaultValue(true)
        .visible(() -> !this.diagonal.get())
        .build()
    );

    private final Setting<Boolean> avoidObstacles = sgClimb.add(new BoolSetting.Builder()
        .name("避开障碍")
        .description("拉升时挑障碍最少的一条爬")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> diagonal = sgClimb.add(new BoolSetting.Builder()
        .name("斜向爬升")
        .description("爬升时绕着目标斜向盘旋上升，够高后从一个斜角砸下去；关掉就是对着目标直上直下")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> orbitRadius = sgClimb.add(new DoubleSetting.Builder()
        .name("盘旋半径")
        .description("斜向爬升时和目标保持的水平距离。下砸也从这段距离斜着砸过去")
        .defaultValue(20.0)
        .min(4.0)
        .sliderMax(64.0)
        .visible(diagonal::get)
        .build()
    );

    private final Setting<Double> obstacleScanDistance = sgClimb.add(new DoubleSetting.Builder()
        .name("障碍扫描距离")
        .description("看多远的方块算障碍")
        .defaultValue(24.0)
        .min(4.0)
        .sliderMax(64.0)
        .visible(avoidObstacles::get)
        .build()
    );

    // ====== 兼容 ======

    private final Setting<Boolean> pauseSimpleControl = sgCompat.add(new BoolSetting.Builder()
        .name("暂停鞘翅简单控制")
        .description("锁定目标时暂停鞘翅飞行模块的方向控制，由本模块接管")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgCompat.add(new BoolSetting.Builder()
        .name("自动起飞")
        .description("不用索敌：空中就换上鞘翅起飞（连着甲飞），地上就按跳跃键起跳")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> takeoffInterval = sgCompat.add(new IntSetting.Builder()
        .name("起飞尝试间隔")
        .description("两次起飞尝试之间至少隔多少 tick：第一次进空中照常起飞，被驳回了也不会连着重试")
        .defaultValue(20)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> grimInput = sgCompat.add(new BoolSetting.Builder()
        .name("兼容grim输入检测")
        .description("起飞时输入包配一对跳跃（起飞前松开、起飞后按下），绕 Grim ElytraB")
        .defaultValue(true)
        .build()
    );

    // ====== 自动烟花（和鞘翅飞行的自动烟花同一套：背包烟花 / 各等级间隔） ======

    private final Setting<Boolean> autoFirework = sgFirework.add(new BoolSetting.Builder()
        .name("自动烟花")
        .description("飞行中自动补烟花，掉了就放")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> takeoffFirework = sgFirework.add(new BoolSetting.Builder()
        .name("起飞自动烟花")
        .description("重新起飞时补一发：只要不在烟花加速中、或当前加速 2 tick 内结束就立刻补")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> backpackFirework = sgFirework.add(new BoolSetting.Builder()
        .name("背包烟花")
        .description("自动烟花允许使用背包中的烟花")
        .defaultValue(false)
        .visible(() -> autoFirework.get() || takeoffFirework.get())
        .build()
    );

    private final Setting<BackpackUse.Mode> backpackMode = sgFirework.add(new EnumSetting.Builder<BackpackUse.Mode>()
        .name("背包使用发包")
        .description("背包烟花的交换发包方式。SWAP：2 次 SWAP 点击；PICKUP：4 次 PICKUP 点击（走光标，背包满也能换）")
        .defaultValue(BackpackUse.Mode.SWAP)
        .visible(() -> backpackFirework.get() && (autoFirework.get() || takeoffFirework.get()))
        .build()
    );

    private final Setting<BackpackUse.TargetSlot> backpackTarget = sgFirework.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
        .name("目标槽位")
        .description("背包烟花换到哪一格使用。副手：换到副手使用；主手：换到手持那一格；快捷栏：换到除手持那一格以外的一个快捷栏格")
        .defaultValue(BackpackUse.TargetSlot.OFFHAND)
        .visible(() -> backpackFirework.get() && (autoFirework.get() || takeoffFirework.get()))
        .build()
    );

    private final Setting<Integer> fwIntervalLv1 = sgFirework.add(new IntSetting.Builder()
        .name("1级烟花间隔")
        .description("1 级烟花的释放间隔（tick）")
        .defaultValue(30)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> autoFirework.get() || takeoffFirework.get())
        .build()
    );

    private final Setting<Integer> fwIntervalLv2 = sgFirework.add(new IntSetting.Builder()
        .name("2级烟花间隔")
        .description("2 级烟花的释放间隔（tick）")
        .defaultValue(40)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> autoFirework.get() || takeoffFirework.get())
        .build()
    );

    private final Setting<Integer> fwIntervalLv3 = sgFirework.add(new IntSetting.Builder()
        .name("3级烟花间隔")
        .description("3 级烟花的释放间隔（tick）")
        .defaultValue(50)
        .min(1)
        .max(100)
        .sliderMax(100)
        .visible(() -> autoFirework.get() || takeoffFirework.get())
        .build()
    );

    // ====== 运行时状态 ======

    private Phase phase = Phase.IDLE;
    private Entity target;
    private int attackCooldownTicks;
    /** 起飞包重发冷却：避免等服务器同步的那几 tick 里每 tick 都补一个起飞包 */
    private int takeoffCooldownTicks;
    private float spinYaw;
    /** 胸甲槽上这件胸甲是不是本模块换上去的（只有是，结束/脱锁时才自动换回鞘翅） */
    private boolean swappedToChest;
    /** 「起飞自动烟花」窗口剩余 tick：刚发起飞包那一小段里检查要不要补一发烟花 */
    private int justTookOffTicks;
    /** 进入攻击阶段后的 tick 数：前 2 tick 仍看着目标，之后才抬头 */
    private int attackPhaseTicks;
    /** 斜向爬升的盘旋方向（+1 / -1）：锁定目标时按当前水平速度选定，之后不翻面，免得来回掉头 */
    private float orbitSide = 1.0f;
    private boolean orbitSideSet;
    /** 上一次算出来的爬升航向（水平距离太近时兜底用） */
    private float climbHeading;
    /** 自动起飞在地上按下跳跃的那一跳还挂着（只让输入包报「按下」，不动玩家的按键映射） */
    private boolean autoJumpHeld;
    /**
     * 自动起飞：我们松开跳跃键的那一 tick（-1 = 没按过 / 还没松开）。
     *
     * <p>起飞包的「延迟」从这一 tick 才开始记（见 {@link #tickAutoTakeoff()}）：
     * 没按过跳跃就是 0，马上能起飞；按过就要等这一发「松开跳跃」输入包发出去再发起飞包。
     */
    private int autoJumpReleasedTick = -1;

    public MaceAura() {
        super(Categories.Combat, "重锤光环",
            "鞘翅飞行时自动拉升、飞向目标、切甲重锤下砸，砸完穿回鞘翅重新拉升");

        // 自动烟花的背包设置交给 ElytraFlySupport（释放时机要用）
        ElytraFlySupport.maceBackpackFirework = backpackFirework;
        ElytraFlySupport.maceBackpackMode = backpackMode;
        ElytraFlySupport.maceBackpackTarget = backpackTarget;
        ElytraFlySupport.maceFwIntervalLv1 = fwIntervalLv1;
        ElytraFlySupport.maceFwIntervalLv2 = fwIntervalLv2;
        ElytraFlySupport.maceFwIntervalLv3 = fwIntervalLv3;
    }

    @Override
    public void onDeactivate() {
        stopAura(true);
        // 起飞前的输入配对预备：关模块必须清掉，不然空中的空格一直被我们按成「松开」
        ElytraFlySupport.setExternalTakeoffPending(false);
        ElytraFlySupport.clearTakeoffWatch();
        // 松开自动起飞按下的跳跃键（别把玩家的空格一直占着）
        releaseAutoJump();
        // 还登记着没发出去的那发起飞包也别发了
        ElytraFlySupport.cancelPendingTakeoff();
        // 清掉还排着队的那一发自动烟花（和鞘翅飞行的自动烟花共用队列）
        ElytraFlySupport.cancelPendingAutoFirework();
    }

    // ====== 每 tick 主逻辑 ======

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        // 鞘翅飞行模块没开时，它的「每 tick 收尾」没人跑：
        // 不清「本 tick 发过起飞包」这个标记，复飞的起飞包就永远发不出去
        ElytraFlySupport.onPreTickForExternal();
        // 自动起跳是「每 tick 一发」的输入脉冲：先清掉，需要时再由 tickAutoTakeoff 置上
        ElytraFlySupport.setExternalJumpInput(false);
        if (attackCooldownTicks > 0) attackCooldownTicks--;
        if (takeoffCooldownTicks > 0) takeoffCooldownTicks--;

        // 死亡 / 旁观 / 骑乘 / 创造飞行：不干活
        if (mc.player.isDeadOrDying() || mc.player.isSpectator() || mc.player.isPassenger()) {
            ElytraFlySupport.setExternalTakeoffPending(false);
            stopAura(false);
            return;
        }
        if (mc.player.getAbilities().flying) {
            ElytraFlySupport.setExternalTakeoffPending(false);
            stopAura(false);
            return;
        }

        // 起飞看门狗：起飞包被反作弊驳回时，本地那个滑翔标志是自己（或原版客户端）伪造的，
        // 必须清掉 —— 不清就一直以为「已经在飞」，再也不补起飞包
        ElytraFlySupport.watchdogClearUnconfirmedTakeoff();
        // 起飞前的输入配对预备：空中第一 tick 就让输入包报「松开空格」，
        // 等服务端看到松开包了才允许发起飞包（Grim ElytraB 的 no release）
        ElytraFlySupport.setExternalTakeoffPending(wantsTakeoffInputPairing());

        // 自动起飞（不用索敌，先于索敌跑）
        tickAutoTakeoff();

        // 落地就不是鞘翅飞行了：收工（身上是胸甲就换回鞘翅）
        if (mc.player.onGround()) {
            stopAura(true);
            return;
        }

        // 只在已经滑翔时生效（真鞘翅 / 甲飞窗口都算）；序列进行中也继续跑，不然换甲那一会就不管了
        if (!ElytraFlySupport.isGlidingNow() && phase == Phase.IDLE) {
            ElytraFlySupport.setExternalTakeover(false);
            ElytraFlySupport.setExternalControlSuspend(false);
            return;
        }

        Entity found = findTarget();
        if (found == null) {
            stopAura(true);
            return;
        }

        // 锁到目标才开始碰「鞘翅飞行」那套：解掉「合法悬停」留下的外部冻结，不然锁了目标也不动；
        // 独立「冻结」模块 / 别的模块请求的冻结不碰，那些照旧让位（连带把控制权还回去）
        if (Freeze.isFrozen()) {
            Freeze.setExternalFrozen(false);
            if (Freeze.isFrozen()) {
                stopAura(false);
                return;
            }
        }

        if (found != target) {
            // 换目标：盘旋方向重新挑，别拿着上一个目标的航向
            orbitSideSet = false;
            climbHeading = 0.0f;
        }
        target = found;
        // 首次锁目标：从拉升起步
        if (phase == Phase.IDLE) phase = Phase.CLIMB;

        // 锁定目标：分两种让位方式
        //  甲飞：滑翔就是靠甲飞换装换来的，飞行阶段只能暂停「方向控制」，换装得留给它；
        //        攻击阶段要自由落体砸，才整个停掉（胸甲留在身上，服务器会停滑）
        //  真鞘翅：不用甲飞那套换装，直接整个接管（按选项暂停鞘翅简单控制）
        if (isArmorFlyOn()) {
            boolean attacking = phase == Phase.ATTACK;
            ElytraFlySupport.setExternalTakeover(attacking);
            ElytraFlySupport.setExternalControlSuspend(!attacking);
        } else {
            ElytraFlySupport.setExternalControlSuspend(false);
            ElytraFlySupport.setExternalTakeover(
                pauseSimpleControl.get() || phase == Phase.ATTACK || !isElytraEquipped());
        }

        // 自动烟花：滑翔中按间隔补；刚起飞那一小段里优先「起飞自动烟花」
        if (ElytraFlySupport.isGlidingNow()) tickFirework();

        switch (phase) {
            case IDLE, CLIMB -> tickClimb();
            case DIVE -> tickDive();
            case ATTACK -> tickAttack();
        }
    }

    /** 拉升：斜向绕着目标盘旋上升到够高，转下砸 */
    private void tickClimb() {
        prepareFlight();
        // 还没有滑翔（刚换回鞘翅的那一 tick）：等下一 tick 再定角
        if (!ElytraFlySupport.isGlidingNow()) return;

        double above = mc.player.getY() - target.getY();
        double need = thresholdHeight.get();
        boolean high = above >= need;
        // 很高了就直接砸，免得盘旋半径怎么都拉不开时卡在爬升里
        boolean veryHigh = above >= need * 1.5;
        // 斜向：高度够了也要有水平距离才砸（斜着砸），离得太近就再盘旋开一点
        boolean farEnough = !diagonal.get() || horizontalDistance() >= diveStartRadius();
        if (high && (veryHigh || farEnough)) {
            phase = Phase.DIVE;
            return;
        }

        float yaw = climbYaw();
        applyAim(yaw, climbPitch(yaw));
    }

    /** 下砸：朝目标俯冲，进换甲范围换胸甲，进攻击范围就砸 */
    private void tickDive() {
        prepareFlight();

        // 下砸中目标升到我们上面：立即切回拉升
        if (target.getY() > mc.player.getY() + 0.5) {
            phase = Phase.CLIMB;
            return;
        }
        if (!ElytraFlySupport.isGlidingNow()) return;

        double dist = distanceTo(target);

        // 进换甲范围：换上胸甲进入攻击阶段（甲飞 / 无限鞘翅在这段被接管停掉）
        if (dist <= preSwapRange.get() && hasChestplate() && swapToChestplate()) {
            phase = Phase.ATTACK;
            attackPhaseTicks = 0;
            spinYaw = LegalRotation.getServerYaw();
            tickAttack();
            return;
        }

        // 沿目标方向俯冲
        Vec3 aim = aimPoint(target).subtract(mc.player.getEyePosition());
        applyAim(yawOf(aim), pitchOf(aim));

        // 没换甲（没胸甲 / 没到换甲范围）：在当前滑翔状态下能砸就砸
        if (canHit(dist)) {
            doAttack();
            phase = Phase.CLIMB;
        }
    }

    /** 攻击：换甲状态下瞄准 + 旋转，够近就砸，砸完立刻复飞 */
    private void tickAttack() {
        attackPhaseTicks++;

        if (target.getY() > mc.player.getY() + 0.5) {
            recoverAfterAttack();
            phase = Phase.CLIMB;
            return;
        }

        double dist = distanceTo(target);
        // 脱离换甲范围：复飞
        if (dist > preSwapRange.get()) {
            recoverAfterAttack();
            phase = Phase.CLIMB;
            return;
        }

        Vec3 toTarget = new Vec3(target.getX() - mc.player.getX(), 0.0, target.getZ() - mc.player.getZ());
        float upYaw = toTarget.lengthSqr() < 1.0E-4 ? mc.player.getYRot() : yawOf(toTarget);

        // 前 2 tick 还得看着目标：刚换上胸甲就抬头会打空（可能还在按滑翔运算往下走）
        // 2 tick 之后再抬头朝目标水平方向，换上鞘翅那一刻就直接是爬升方向
        float yaw;
        float pitch;
        if (attackPhaseTicks <= 2) {
            Vec3 aim = aimPoint(target).subtract(mc.player.getEyePosition());
            yaw = yawOf(aim);
            pitch = pitchOf(aim);
        } else {
            yaw = upYaw;
            pitch = climbPitch(upYaw);
        }

        boolean hitNow = canHit(dist);
        if (hitNow && spin.get()) {
            // 「旋转」只在真正砸中的这一 tick 转头：服务器看到的是一次自然转头，其余时间保持抬头
            spinYaw = Mth.wrapDegrees(spinYaw + spinSpeed.get().floatValue());
            applyAim(spinYaw, pitch);
        } else {
            applyAim(yaw, pitch);
        }

        if (hitNow) {
            doAttack();
            // 攻击后立刻复飞（视角已经朝上，复飞即拉升）
            recoverAfterAttack();
            phase = Phase.CLIMB;
        }
    }

    // ====== 结束 / 复位 ======

    /** 结束光环。reEquip 为真且身上是胸甲时，把鞘翅穿回去 + 起飞，别让玩家直接掉下去 */
    private void stopAura(boolean reEquip) {
        if (phase == Phase.IDLE && target == null
            && !ElytraFlySupport.isExternalTakeover() && !ElytraFlySupport.isExternalControlSuspend()) {
            return;
        }
        // 先把控制权还给鞘翅飞行（甲飞会自己把滑翔接回来）
        ElytraFlySupport.setExternalTakeover(false);
        ElytraFlySupport.setExternalControlSuspend(false);
        if (reEquip && swappedToChest && !isArmorFlyOn()) {
            ensureElytra();
        }
        phase = Phase.IDLE;
        target = null;
        spinYaw = 0.0f;
        justTookOffTicks = 0;
        attackPhaseTicks = 0;
        orbitSideSet = false;
        climbHeading = 0.0f;
    }

    // ====== 目标 ======

    private Entity findTarget() {
        double range = targetRange.get();
        AABB box = mc.player.getBoundingBox().inflate(range);
        Entity best = null;
        double bestDist = range * range;
        for (Entity e : mc.level.getEntities(mc.player, box)) {
            if (!isValidTarget(e)) continue;
            double d = mc.player.distanceToSqr(e);
            if (d <= bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private boolean isValidTarget(Entity e) {
        if (e == null || e == mc.player || e.isRemoved() || !e.isAlive()) return false;
        if (!targetEntities.get().contains(e.getType())) return false;
        return mc.player.distanceTo(e) <= targetRange.get();
    }

    /** 攻击点：目标碰撞箱上离玩家眼睛最近的点（和杀戮光环「瞄准点与范围优化」同一套算法） */
    private Vec3 aimPoint(Entity e) {
        return closestPointOnBox(mc.player.getEyePosition(), e.getBoundingBox());
    }

    /** 碰撞箱上离 from 最近的点（参考 LiquidBounce getNearestPointBB） */
    private static Vec3 closestPointOnBox(Vec3 from, AABB box) {
        return new Vec3(
            Mth.clamp(from.x(), box.minX, box.maxX),
            Mth.clamp(from.y(), box.minY, box.maxY),
            Mth.clamp(from.z(), box.minZ, box.maxZ)
        );
    }

    /** 眼睛到目标碰撞箱最近点的距离（= 眼睛到碰撞箱的距离，和服务器攻击判定一致） */
    private double distanceTo(Entity e) {
        return mc.player.getEyePosition().distanceTo(aimPoint(e));
    }

    /** 目标到玩家的水平距离（斜向盘旋用） */
    private double horizontalDistance() {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 够高后开始斜向下砸的最小水平距离（斜着砸，不直上直下） */
    private double diveStartRadius() {
        return Math.max(orbitRadius.get() * 0.5, 4.0);
    }

    // ====== 攻击 ======

    private boolean canHit(double dist) {
        if (attackCooldownTicks > 0) return false;
        if (mc.player.fallDistance < minFallDistance.get()) return false;
        if (dist <= attackRange.get()) return true;
        // 看不见目标时按穿墙范围放行
        return dist <= throughWallRange.get() && !PlayerUtils.canSeeEntity(target);
    }

    private void doAttack() {
        if (target == null || !target.isAlive()) return;
        attackCooldownTicks = attackCooldown.get();

        if (autoSwapMace.get()) {
            FindItemResult mace = InvUtils.findInHotbar(Items.MACE);
            if (mace.found()) {
                InvUtils.swap(mace.slot(), true);
                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
                return;
            }
        }
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ====== 转向 ======

    /**
     * 拉升偏航。
     *
     * <p>斜向（默认）：绕着目标盘旋上升 —— 水平距离比「盘旋半径」大就往里拐、小就往外拐、
     * 正好在半径上就走切线。航向连续变化，不会越过目标后来回掉头；
     * 盘旋方向在锁目标那一刻按当前水平速度定下来，之后不翻面。
     *
     * <p>关掉斜向：旧逻辑 —— 对着目标水平方向（可选 45° 量化）＋障碍规避。
     */
    private float climbYaw() {
        Vec3 toTarget = new Vec3(target.getX() - mc.player.getX(), 0.0, target.getZ() - mc.player.getZ());
        double r = toTarget.length();
        boolean overhead = r < 1.0E-4;
        float baseYaw = overhead ? (orbitSideSet ? climbHeading : mc.player.getYRot()) : yawOf(toTarget);

        if (!diagonal.get()) return legacyClimbYaw(baseYaw);

        if (!orbitSideSet) {
            orbitSide = overhead ? 1.0f : pickOrbitSide(baseYaw);
            orbitSideSet = true;
            climbHeading = mc.player.getYRot();
        }

        // 已经在目标头顶附近：航向没有参考方向，保持当前航向先平飞出去，别原地掉头
        double radius = orbitRadius.get();
        if (r < Math.min(4.0, radius * 0.5)) return avoidObstaclesContinuous(climbHeading);

        // 半径控制器：水平距离偏大就往目标方向收、偏小就往外散、正好在半径上时走切线
        double error = Mth.clamp((r - radius) / radius, -1.0, 1.0);
        double turn = Mth.clamp(90.0 - 90.0 * error, 20.0, 160.0);
        climbHeading = Mth.wrapDegrees(baseYaw + (float) (orbitSide * turn));
        return avoidObstaclesContinuous(climbHeading);
    }

    /** 盘旋方向：按当前水平速度挑同一边，开局不会先掉个头 */
    private float pickOrbitSide(float baseYaw) {
        Vec3 v = mc.player.getDeltaMovement();
        if (Math.sqrt(v.x * v.x + v.z * v.z) < 0.05) return 1.0f;
        float moveYaw = yawOf(new Vec3(v.x, 0.0, v.z));
        return Mth.wrapDegrees(moveYaw - baseYaw) >= 0.0f ? 1.0f : -1.0f;
    }

    /** 关掉斜向时的旧拉升偏航：朝目标水平方向，可选 45° 方向选择 + 障碍规避 */
    private float legacyClimbYaw(float baseYaw) {
        boolean want45 = betterYaw.get() || fireworkBoostOn();
        if (!avoidObstacles.get() && !want45) return baseYaw;

        List<Float> candidates = new ArrayList<>();
        if (want45) {
            // 更优爬升 / 烟花加速：只在 4 个 45° 方向里挑，空旷时自动落到离目标最近的 45°
            for (int i = 0; i < 8; i++) candidates.add(i * 45.0f);
        } else {
            candidates.add(baseYaw);
            if (avoidObstacles.get()) {
                for (int i = 0; i < 8; i++) candidates.add(i * 45.0f);
            }
        }

        float best = baseYaw;
        double bestScore = Double.MAX_VALUE;
        for (float cand : candidates) {
            double score = Math.abs(Mth.wrapDegrees(cand - baseYaw)) / 90.0 * 0.6;
            if (avoidObstacles.get()) {
                double hit = castDistance(cand, climbPitch(cand), obstacleScanDistance.get());
                if (hit < obstacleScanDistance.get()) {
                    score += 1.0 + (obstacleScanDistance.get() - hit) / obstacleScanDistance.get();
                }
            }
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return Mth.wrapDegrees(best);
    }

    /** 斜向拉升的障碍规避：在目标航向附近连续微调（15° 一档），不跳固定 45° 方向 */
    private float avoidObstaclesContinuous(float desiredYaw) {
        if (!avoidObstacles.get()) return desiredYaw;
        double scan = obstacleScanDistance.get();
        float best = desiredYaw;
        double bestScore = Double.MAX_VALUE;
        for (int i = -6; i <= 6; i++) {
            float cand = Mth.wrapDegrees(desiredYaw + i * 15.0f);
            double score = Math.abs(i * 15.0) / 90.0 * 0.6;
            double hit = castDistance(cand, climbPitch(cand), scan);
            if (hit < scan) score += 1.0 + (scan - hit) / scan;
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return best;
    }

    /** 拉升俯仰：更优爬升 / 烟花加速中一律 45° 抬头，否则跟自动拉升那套最佳爬升角 */
    private float climbPitch(float yaw) {
        if (betterPitch.get() || fireworkBoostOn()) return -45.0f;
        return ElytraFlySupport.pullupPitch(yaw);
    }

    /** 从眼睛沿指定朝向射一条线，返回撞到方块的最近距离（没撞到返回 max） */
    private double castDistance(float yaw, float pitch, double max) {
        Vec3 from = mc.player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw).normalize();
        Vec3 to = from.add(dir.scale(max));
        BlockHitResult hit = mc.level.clip(new ClipContext(
            from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return max;
        return from.distanceTo(hit.getLocation());
    }

    private void applyAim(float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90.0f, 90.0f);
        if (fixedCamera.get()) {
            // 攻击阶段的转向优先级比设置值高 1，确保攻击转头压得住别的（自动拉升等）转向
            int priority = rotationPriority.get() + (phase == Phase.ATTACK ? 1 : 0);
            LegalRotation.rotate(yaw, pitch, LegalRotation.Mode.QUIET, priority);
        } else {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.setYHeadRot(yaw);
        }
    }

    private boolean fireworkBoostOn() {
        FireworkBoost boost = Modules.get().get(FireworkBoost.class);
        return boost != null && boost.isActive();
    }

    private static float yawOf(Vec3 dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
    }

    private static float pitchOf(Vec3 dir) {
        double h = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        return (float) -Math.toDegrees(Math.atan2(dir.y, h));
    }

    // ====== 鞘翅 / 胸甲 ======

    /** 甲飞是否在跑（甲飞自己管甲和起飞，本模块不换鞘翅、不发自己的起飞包） */
    private static boolean isArmorFlyOn() {
        return ElytraFlySupport.isArmorFlyEnabled();
    }

    /**
     * 自动起飞：不用索敌。空中就换上鞘翅起飞（真鞘翅自己起飞，甲飞交给甲飞），
     * 地上让输入包报「按下跳跃」起跳。
     *
     * <p><b>起跳不碰按键映射</b>：只在输入包里把跳跃键报成「按下」，由原版自己起跳
     * （服务端按输入包预测这一跳，不能直接改速度，那样会回弹）。玩家的按键映射原样不动，
     * 原版双击空格的检测（创造飞行开关 / 鞘翅起飞）看到的还是玩家真实的按键——
     * 以前直接 {@code setDown(true)} 会把跳跃键一直按着，那个上升沿永远出不来。
     *
     * <p>创造/旁观本来就能飞（{@code mayfly}）时整个自动起飞都不做：那里双击空格是原版的开关，
     * 我们既不需要起跳、也不能去动跳跃输入。
     *
     * <p>「兼容 grim 输入检测」下两处都要配输入包：
     * <ul>
     *   <li><b>跳跃</b>：起跳让输入包报「按下跳跃」，由客户端自己跳、自己发带跳跃的输入包
     *       （服务端按输入包预测这一跳）；不能直接改速度，那样服务端预测不上会回弹。
     *       报的是输入包，不碰玩家的按键映射（见 {@code ElytraFlySupport#setExternalJumpInput}）</li>
     *   <li><b>起飞</b>：刚在地上跳完，输入包里还是「按着跳跃」，起飞包前面必须先发一个
     *       「松开跳跃」包，不然 Grim ElytraB 报 no release 直接把起飞包取消。
     *       延迟从「松开」那一 tick 才开始记 —— 没按过跳跃就是 0，立刻能起</li>
     * </ul>
     */
    private void tickAutoTakeoff() {
        // 索敌中 / 序列进行中不插手（攻击阶段要自由落体砸，这里换鞘翅会把它搅了）
        if (mc.player == null || !autoTakeoff.get() || phase != Phase.IDLE
            || mc.player.isDeadOrDying() || mc.player.isSpectator() || mc.player.isPassenger()
            || mc.player.getAbilities().flying || Freeze.isFrozen()) {
            releaseAutoJump();
            autoJumpReleasedTick = -1;
            return;
        }

        // 创造 / 旁观能自己飞：自动起飞没用，跳跃键也不能碰（双击空格是原版开关）
        if (mc.player.getAbilities().mayfly) {
            releaseAutoJump();
            autoJumpReleasedTick = -1;
            return;
        }

        // 地上：让这一 tick 的输入包报「按下跳跃」，原版自己起跳（服务端要从输入包里看到这一跳才算数）
        if (mc.player.onGround()) {
            ElytraFlySupport.setExternalJumpInput(true);
            autoJumpHeld = true;
            // 落地就把上一轮的「等松开」清掉：这一轮从按下重新开始，还没按就是 0（立刻能起）
            autoJumpReleasedTick = -1;
            // 落地算新的一轮：进空中那一次照常起飞，不隔间隔（间隔只管空中重试）
            takeoffCooldownTicks = 0;
            return;
        }

        // 空中：撤掉我们按下的那一跳。这一 tick 不发起飞包 —— 等这一发「松开跳跃」输入包
        // 发出去（下一个 tick）再起，服务端看到的顺序才是「松开 → 起飞包」
        if (autoJumpHeld) {
            autoJumpReleasedTick = mc.player.tickCount;
            releaseAutoJump();
            return;
        }
        // 延迟从「松开」那一 tick 才开始记；没按过跳跃（-1）就是 0，不用等
        if (autoJumpReleasedTick >= 0 && mc.player.tickCount - autoJumpReleasedTick < 1) return;

        prepareFlight();
    }

    /** 撤掉自动起飞按下的那一跳（只撤我们自己按的，玩家自己按的不动） */
    private void releaseAutoJump() {
        if (!autoJumpHeld) return;
        autoJumpHeld = false;
        ElytraFlySupport.setExternalJumpInput(false);
    }

    /** 飞行阶段准备：真鞘翅保证穿着鞘翅 + 在飞；甲飞交给甲飞自己 */
    private void prepareFlight() {
        if (isArmorFlyOn()) return;
        ensureElytra();
    }

    /**
     * 攻击后准备复飞：真鞘翅换回鞘翅 + 起飞；
     * 甲飞不用换甲，把控制权和换装一起还给鞘翅飞行，它自己会把滑翔接回来
     */
    private void recoverAfterAttack() {
        if (isArmorFlyOn()) {
            ElytraFlySupport.setExternalTakeover(false);
            ElytraFlySupport.setExternalControlSuspend(true);
            return;
        }
        ensureElytra();
    }

    /** 确保穿着鞘翅并在飞；没穿就换上去 + 起飞 */
    private void ensureElytra() {
        if (isElytraEquipped()) {
            swappedToChest = false;
        } else {
            if (!swapToElytra()) return;
            swappedToChest = false;
        }
        // 服务端已经认滑翔、本地标志却掉了：先把本地补回来，别发起飞包
        // （本地标志掉了会走普通空中运算，和服务端的滑翔预测每个 tick 都对不上 = 连续回弹）
        ElytraFlySupport.relockLocalGlide();
        if (!mc.player.isFallFlying()) startFly();
    }

    private void startFly() {
        if (mc.player.isFallFlying() || !isElytraEquipped()) return;
        // 服务端已经在滑翔：本地补上标志就行，别再发起飞包。
        // 原版服务端收到「已经在滑翔」的起飞包会直接 stopFallFlying（把滑翔打断），
        // Grim 那边是 ElytraA already gliding，把包取消
        if (ElytraFlySupport.serverSaysGliding()) {
            ElytraFlySupport.relockLocalGlide();
            return;
        }
        // 地面上不起飞（发了也会被服务器拒），避免站着一直补包
        if (mc.player.onGround()) return;
        if (takeoffCooldownTicks > 0) return;
        // 起飞包前面必须先有一个「松开跳跃」输入包（起跳那一下服务端看到的是按下），
        // 没等到就下一 tick 再发，别硬发（硬发会被 Grim ElytraB 的 no release 直接取消）。
        // 配对由「起飞前预备」/「兼容 grim 输入检测」/「自动替换鞘翅」任一撑起来，见 skipStartThisTick
        if (grimInput.get() && ElytraFlySupport.skipStartThisTick()) return;
        // 两次起飞尝试之隔：被反作弊驳回时不会每几 tick 连着重试（连着重试就是连续回弹）
        takeoffCooldownTicks = takeoffInterval.get();
        // 起飞包不当场发：登记到「本 tick 输入包要发出去」的那一刻（见 ElytraFlySupport#requestTakeoff），
        // 免得起飞包和「按下跳跃」输入包之间空出几毫秒让服务端的事务回包插队
        if (grimInput.get()) {
            // 走鞘翅飞行那套：发一个起飞包 + 配一对跳跃输入包
            ElytraFlySupport.requestTakeoff(ElytraFlySupport::externalStartFlying);
        } else {
            // 纯发包：登记外部起飞窗口 + 本地置滑翔，原版就不会再补一个起飞包
            ElytraFlySupport.requestTakeoff(ElytraFlySupport::externalStartFlyingPlain);
        }
        // 打开「起飞自动烟花」窗口：接下来十来 tick 里，没有烟花加速（或快结束）就补一发
        justTookOffTicks = 12;
        // 本地滑翔标志跟滑翔运算一起交给原版（和甲飞/自动替换鞘翅同一套），真滑翔与否还是看服务端同步：
        // 没同步上时起飞看门狗会把本地标志清掉重发，所以「切回鞘翅没起飞」不会卡死
    }

    /**
     * 这一轮需不需要在起飞前先把输入包配成「松开空格」。
     *
     * <p>Grim 的起飞包检测要求收到起飞包那一刻服务端看到的输入是松开的（否则 no release
     * 直接取消）。配对必须从空中第一 tick 就打开：玩家自己按着空格（自动起飞本来就是
     * 「按住跳跃键」触发）或模块刚在地上按着起跳时，不提前松开，第一发起飞包必被取消
     */
    private boolean wantsTakeoffInputPairing() {
        // 关掉「兼容 grim 输入检测」就不碰输入包（选项语义保持：开了才配这一对跳跃）
        if (!grimInput.get()) return false;
        // 甲飞在跑：起飞和输入配对都归甲飞那套管，本模块不插手
        if (isArmorFlyOn()) return false;
        // 创造 / 旁观能自己飞：空中把跳跃输入抹掉会让原版双击空格（开关飞行）点不出来
        if (mc.player.getAbilities().mayfly) return false;
        if (mc.player.onGround() || mc.player.isFallFlying()) return false;
        // 服务端已经认滑翔（本地标志还没同步上 / 被清掉了）：不需要再起，交给 relock
        if (ElytraFlySupport.serverSaysGliding()) return false;
        // 自动起飞待命，或索敌序列里（下砸完要复飞）
        return autoTakeoff.get() || phase != Phase.IDLE;
    }

    /**
     * 自动烟花：刚起飞那一小段优先「起飞自动烟花」（没有加速、或当前加速 2 tick 内结束才补），
     * 之后按间隔正常补。两者的释放时机、冷却、背包设置都走 {@link ElytraFlySupport} 那套，
     * 和鞘翅飞行的自动烟花共用冷却，不会同 tick 放两发。
     */
    private void tickFirework() {
        boolean inTakeoffWindow = justTookOffTicks > 0 && takeoffFirework.get();
        if (justTookOffTicks > 0) justTookOffTicks--;

        if (inTakeoffWindow) {
            // 刚起飞这一小段只走「起飞自动烟花」：补上了就关窗口；还在加速中就继续等
            if (ElytraFlySupport.maceTakeoffFirework()) justTookOffTicks = 0;
            return;
        }
        // 窗口外（或没开起飞烟花）：按间隔正常补
        if (autoFirework.get()) ElytraFlySupport.tickMaceFirework();
    }

    private boolean hasChestplate() {
        if (isChestplateEquipped()) return true;
        return findInInventory(MaceAura::isChestplate) != -1;
    }

    private boolean swapToChestplate() {
        if (isChestplateEquipped()) return true;
        if (!(mc.player.containerMenu instanceof InventoryMenu)) return false;
        int slot = findInInventory(MaceAura::isChestplate);
        if (slot == -1) return false;
        swapWithChest(slot);
        if (isChestplateEquipped()) {
            swappedToChest = true;
            return true;
        }
        return false;
    }

    private boolean swapToElytra() {
        if (isElytraEquipped()) return true;
        if (!(mc.player.containerMenu instanceof InventoryMenu)) return false;
        int slot = findInInventory(MaceAura::isElytra);
        if (slot == -1) return false;
        swapWithChest(slot);
        return isElytraEquipped();
    }

    /** 三击互换：拿起背包那件 → 放胸甲槽 → 换下来的放回原槽（和「鞘翅自动替换」同一套） */
    private void swapWithChest(int invIndex) {
        InvUtils.click().slot(invIndex);
        InvUtils.click().slotArmor(2);
        InvUtils.click().slot(invIndex);
    }

    /** 在快捷栏 + 主背包 + 副手找第一件满足条件的物品，返回槽位索引，找不到返回 -1 */
    private int findInInventory(Predicate<ItemStack> predicate) {
        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.MAIN_END; i++) {
            if (predicate.test(mc.player.getInventory().getItem(i))) return i;
        }
        if (predicate.test(mc.player.getInventory().getItem(SlotUtils.OFFHAND))) return SlotUtils.OFFHAND;
        return -1;
    }

    private boolean isElytraEquipped() {
        return isElytra(mc.player.getItemBySlot(EquipmentSlot.CHEST));
    }

    private boolean isChestplateEquipped() {
        return isChestplate(mc.player.getItemBySlot(EquipmentSlot.CHEST));
    }

    /** 鞘翅，或带滑翔组件的胸甲 */
    private static boolean isElytra(ItemStack stack) {
        return stack.is(Items.ELYTRA) || stack.has(DataComponents.GLIDER);
    }

    /** 可装备且装备槽是胸甲的物品（把鞘翅 / 带滑翔组件的排除掉） */
    private static boolean isChestplate(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST && !isElytra(stack);
    }
}
