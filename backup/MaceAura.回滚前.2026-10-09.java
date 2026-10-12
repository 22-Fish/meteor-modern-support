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
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
 *   <li><b>拉升</b>：斜向绕着目标盘旋上升（「斜向爬升下砸」），够高后从一个斜角砸过去；
 *       关掉斜向才是对着目标直上直下（可选「更优爬升俯仰 / 偏航」45°）</li>
 *   <li><b>下砸</b>：高度 + 水平距离都够了就斜着朝目标俯冲，下砸中目标升到我们上面立刻改回拉升</li>
 *   <li><b>攻击</b>：换甲范围内换上胸甲，进入攻击范围后切锤砸一下，然后穿回鞘翅起飞</li>
 * </ul>
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
    private final SettingGroup sgAir = settings.createGroup("空战");
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
        .description("重锤的攻击距离（眼睛到目标碰撞箱最近点）。超过原版手长会被服务端拒掉，所以实际取和原版手长的较小值")
        .defaultValue(3.0)
        .min(1.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Double> throughWallRange = sgMace.add(new DoubleSetting.Builder()
        .name("穿墙攻击范围")
        .description("看不见目标时，在这个距离内照样能砸（隔墙攻击）。同样不会超过原版手长")
        .defaultValue(3.0)
        .min(1.0)
        .sliderMax(6.0)
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

    private final Setting<Double> aimLead = sgAim.add(new DoubleSetting.Builder()
        .name("瞄准提前量")
        .description("按目标速度往前瞄多少 tick（打移动目标用）。0 = 只瞄当前位置")
        .defaultValue(1.0)
        .min(0.0)
        .sliderMax(6.0)
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
        .description("拉升时在 4 个 45° 方向里挑一个（爬升更快）。只在关掉「斜向爬升下砸」时起作用")
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
        .name("斜向爬升下砸")
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

    // ====== 空战 ======

    private final Setting<Boolean> airCombat = sgAir.add(new BoolSetting.Builder()
        .name("空中追击")
        .description("目标是空中目标（在飞 / 在掉）时不盘旋，直接追着打，瞄准用按到达时间算的预测点")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> airHeight = sgAir.add(new DoubleSetting.Builder()
        .name("空中打击高度")
        .description("比空中目标高过这么多就开砸（空中目标不用像地面目标那样爬很高）")
        .defaultValue(12.0)
        .min(2.0)
        .sliderMax(40.0)
        .visible(airCombat::get)
        .build()
    );

    private final Setting<Integer> airLag = sgAir.add(new IntSetting.Builder()
        .name("空战延迟补偿")
        .description("判攻击距离时把目标按速度往前补多少 tick。服务端那边的目标位置比客户端显示的超前，补少了就是「挥手了但服务端判没够到」")
        .defaultValue(3)
        .min(0)
        .max(6)
        .sliderMax(6)
        .visible(airCombat::get)
        .build()
    );

    // ====== 兼容 ======

    private final Setting<Boolean> pauseSimpleControl = sgCompat.add(new BoolSetting.Builder()
        .name("暂停鞘翅简单控制")
        .description("锁定目标时暂停鞘翅飞行模块的方向控制，由本模块接管")
        .defaultValue(true)
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
    /** 砸中后要带到下一次转向的偏航偏移（砸中那一发必须正对目标，转一下放到下一 tick 发） */
    private float pendingSpin;
    /** 胸甲槽上这件胸甲是不是本模块换上去的（只有是，结束/脱锁时才自动换回鞘翅） */
    private boolean swappedToChest;
    /** 「起飞自动烟花」窗口剩余 tick：刚发起飞包那一小段里检查要不要补一发烟花 */
    private int justTookOffTicks;
    /** 斜向爬升的盘旋方向（+1 / -1）：锁定目标时按当前水平速度选定，之后不翻面，免得来回掉头 */
    private float orbitSide = 1.0f;
    private boolean orbitSideSet;
    /** 上一次算出来的爬升航向（水平距离太近时兜底用） */
    private float climbHeading;
    /** 目标上一 tick 的位置 / 估算出的每 tick 位移（瞄准提前量用） */
    private Vec3 lastTargetPos;
    private Vec3 targetVelocity = Vec3.ZERO;

    /** 空战瞄准：提前量最多几格（防止目标瞬移 / 回弹时把落点甩飞） */
    private static final double MAX_LEAD_BLOCKS = 6.0;
    /** 26.1 服务端 handleAttack 固定加在攻击射程上的延迟补偿额度（isWithinAttackRange 的那个 3.0） */
    private static final double SERVER_ATTACK_PADDING = 3.0;

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
        // 清掉还排着队的那一发自动烟花（和鞘翅飞行的自动烟花共用队列）
        ElytraFlySupport.cancelPendingAutoFirework();
    }

    // ====== 每 tick 主逻辑 ======

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (attackCooldownTicks > 0) attackCooldownTicks--;
        if (takeoffCooldownTicks > 0) takeoffCooldownTicks--;

        // 死亡 / 旁观 / 骑乘 / 冻结 / 创造飞行：不干活
        if (mc.player.isDeadOrDying() || mc.player.isSpectator() || mc.player.isPassenger()) {
            stopAura(false);
            return;
        }
        if (Freeze.isFrozen()) {
            // 「鞘翅飞行」合法悬停留下的外部冻结：本模块要接管就解掉，不然锁了目标也不动；
            // 独立「冻结」模块 / 别的模块请求的冻结不碰，那些照旧让位
            Freeze.setExternalFrozen(false);
            if (Freeze.isFrozen()) {
                stopAura(false);
                return;
            }
        }
        if (mc.player.getAbilities().flying) {
            stopAura(false);
            return;
        }
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
        if (found != target) {
            // 换目标：盘旋方向重新挑、速度估计清空，别拿上一个目标的动量去瞄
            orbitSideSet = false;
            lastTargetPos = null;
            targetVelocity = Vec3.ZERO;
        }
        target = found;
        updateTargetVelocity(found);
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

    /** 拉升：顶上目标上方，够高了转下砸 */
    private void tickClimb() {
        prepareFlight();
        // 还没有滑翔（刚换回鞘翅的那一 tick）：等下一 tick 再定角
        if (!ElytraFlySupport.isGlidingNow()) return;

        boolean air = airChase();
        // 空中目标不用像地面目标那样爬很高（对面本来就在天上，砸下来那点落差就够）
        double need = air ? airHeight.get() : thresholdHeight.get();
        double above = mc.player.getY() - target.getY();
        boolean high = above >= need;
        // 很高了就直接砸，免得盘旋半径怎么都拉不开时卡在爬升里
        boolean veryHigh = above >= need * 1.5;
        // 斜向：高度够了也要有水平距离才砸（斜着砸），离得太近就再盘旋开一点；
        // 空中追击不要求盘旋半径 —— 追着咬上去，够高就砸
        boolean farEnough = air || !diagonal.get() || horizontalDistance() >= diveStartRadius();
        if (high && (veryHigh || farEnough)) {
            phase = Phase.DIVE;
            return;
        }

        float yaw = climbYaw();
        if (pendingSpin != 0.0f) {
            // 砸中后那一下旋转：放到砸完的第一次转向带出去，不占砸中的这一发
            yaw = Mth.wrapDegrees(yaw + pendingSpin);
            pendingSpin = 0.0f;
        }
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
        // 空中目标：真的要在他上面（坠落距离才有地方攒），不是平着贴上去
        boolean aboveEnough = !airChase()
            || mc.player.getY() >= target.getY() + Math.max(minFallDistance.get() + 1.0, 2.5);

        // 进换甲范围：换上胸甲进入攻击阶段（甲飞 / 无限鞘翅在这段被接管停掉）
        if (dist <= preSwapRange.get() && aboveEnough && hasChestplate() && swapToChestplate()) {
            phase = Phase.ATTACK;
            tickAttack();
            return;
        }

        // 斜着俯冲：瞄目标（带提前量），有水平距离就是一条斜线，不是直上直下
        Vec3 aim = aimPoint(target).subtract(mc.player.getEyePosition());
        applyAim(yawOf(aim), pitchOf(aim));

        // 没换甲（没胸甲 / 没到换甲范围）：在当前滑翔状态下能砸就砸
        if (canHit()) {
            doAttack();
            phase = Phase.CLIMB;
        }
    }

    /** 攻击：换甲状态下正对目标（带提前量），够近就砸，砸完立刻复飞 */
    private void tickAttack() {
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

        // 全程正对目标（带提前量）：攻击包是排在移动包之后发的，服务端按这一份朝向做视角检查，
        // 视角歪着砸就会「明明够得着却打不到」。复飞让下一 tick 的拉升阶段自己转回去。
        Vec3 aim = aimPoint(target).subtract(mc.player.getEyePosition());
        applyAim(yawOf(aim), pitchOf(aim));

        if (canHit()) {
            // 「旋转」：砸中的这一发必须正对目标，转动放到砸完后的第一次转向（下一 tick 拉升）
            if (spin.get()) pendingSpin = spinSpeed.get().floatValue();
            doAttack();
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
        pendingSpin = 0.0f;
        justTookOffTicks = 0;
        orbitSideSet = false;
        climbHeading = 0.0f;
        lastTargetPos = null;
        targetVelocity = Vec3.ZERO;
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

    /** 用最近两 tick 的位置差估算目标速度（其它实体的 getDeltaMovement 客户端不一定准） */
    private void updateTargetVelocity(Entity e) {
        Vec3 pos = e.position();
        if (lastTargetPos != null) targetVelocity = pos.subtract(lastTargetPos);
        lastTargetPos = pos;
    }

    /**
     * 攻击点：目标眼睛 + 提前量。
     *
     * <p>空战：目标按「空战延迟补偿」往前补。服务端判定用的是它那边的目标位置，
     * 客户端显示的目标被实体插值拖后好几 tick，对面飞得快时这个差值能有好几格，
     * 不补正就是「挥手了但服务端判没够到」。
     */
    private Vec3 aimPoint(Entity e) {
        Vec3 point = e.getEyePosition();
        if (e == target && airChase()) {
            Vec3 move = targetVelocity.scale(airLag.get());
            if (move.lengthSqr() > MAX_LEAD_BLOCKS * MAX_LEAD_BLOCKS) {
                move = move.normalize().scale(MAX_LEAD_BLOCKS);
            }
            return point.add(move);
        }
        double lead = aimLead.get();
        if (lead <= 0.0) return point;
        Vec3 move = targetVelocity.scale(lead);
        // 目标瞬移 / 回弹时别跟着甩出去，最多提前两格
        if (move.lengthSqr() > 4.0) move = move.normalize().scale(2.0);
        return point.add(move);
    }

    /** 空战追击：目标是空中目标（在飞 / 在掉）且开着「空中追击」 */
    private boolean airChase() {
        if (!airCombat.get() || target == null) return false;
        return !target.onGround() || (target instanceof LivingEntity living && living.isFallFlying());
    }

    /** 水平距离（斜向盘旋用） */
    private double horizontalDistance() {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 够高后开始斜向下砸的最小水平距离（斜着砸，不直上直下） */
    private double diveStartRadius() {
        return Math.max(orbitRadius.get() * 0.5, 4.0);
    }

    /** 眼睛到碰撞箱最近点的距离（服务端攻击判定用的就是这种算法） */
    private static double distanceToBox(Vec3 eye, AABB box) {
        double dx = Math.max(Math.max(box.minX - eye.x, 0.0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0.0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0.0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double distanceTo(Entity e) {
        return distanceToBox(mc.player.getEyePosition(), e.getBoundingBox());
    }

    /** 地面目标用：模块设置和原版手长取小的，再退一点余量 */
    private double serverReach(double wanted) {
        double reach = wanted;
        if (mc.player != null) reach = Math.min(reach, mc.player.entityInteractionRange());
        return Math.max(reach - 0.15, 0.5);
    }

    /**
     * 服务端真正接受的攻击距离：原版手长 + 服务端的延迟补偿额度。
     *
     * <p>26.1 服务端 {@code handleAttack} 是 {@code isWithinAttackRange(物品, 碰撞箱, 3.0)}，
     * 那个 3.0 是加在射程上的（不是上限），也就是实际能打到「手长 + 3」。退 0.4 做余量。
     */
    private double serverAttackReach() {
        double reach = mc.player == null ? 3.0 : mc.player.entityInteractionRange();
        return Math.max(reach + SERVER_ATTACK_PADDING - 0.4, 0.5);
    }

    // ====== 攻击 ======

    /** 在攻击距离内（不看坠落距离） */
    private boolean inReach() {
        if (target == null || !target.isAlive()) return false;
        if (airChase()) {
            // 空中：按服务端那边看到的目标位置判距离（客户端位置按速度往前补 airLag tick）
            AABB serverBox = target.getBoundingBox().move(targetVelocity.scale(airLag.get()));
            return distanceToBox(mc.player.getEyePosition(), serverBox) <= serverAttackReach();
        }
        double dist = distanceTo(target);
        if (dist <= serverReach(attackRange.get())) return true;
        // 看不见目标时按穿墙范围放行
        return dist <= serverReach(throughWallRange.get()) && !PlayerUtils.canSeeEntity(target);
    }

    /**
     * 现在砸不砸：冷却好了 + 够得着，且
     * <ul>
     *   <li>坠落距离够了（吃到重锤下砸加成），或者</li>
     *   <li>空战：这一 tick 擦过去就是最后一次机会（下一 tick 就出范围），没攒够也先砸上</li>
     * </ul>
     */
    private boolean canHit() {
        if (attackCooldownTicks > 0) return false;
        if (!inReach()) return false;
        // 重锤要坠落距离比 1.5 大才吃到下砸加成（原版是严格大于），所以这里也判严格大于
        if (mc.player.fallDistance > minFallDistance.get()) return true;
        return airChase() && mustAttackNow();
    }

    /** 按双方速度外推：下一 tick 就要飞出攻击范围（这一 tick 是最后一次机会） */
    private boolean mustAttackNow() {
        if (target == null || target.isRemoved()) return false;
        Vec3 to;
        double dist;
        if (airChase()) {
            AABB serverBox = target.getBoundingBox().move(targetVelocity.scale(airLag.get()));
            to = serverBox.getCenter().subtract(mc.player.getEyePosition());
            dist = distanceToBox(mc.player.getEyePosition(), serverBox);
        } else {
            to = aimPoint(target).subtract(mc.player.getEyePosition());
            dist = distanceTo(target);
        }
        if (to.lengthSqr() < 1.0E-6) return false;
        // 相对速度在「我们 → 目标」方向上的分量，>0 = 正在接近
        double closing = mc.player.getDeltaMovement().subtract(targetVelocity).dot(to.normalize());
        return dist - closing > (airChase() ? serverAttackReach() : serverReach(attackRange.get()));
    }

    private void doAttack() {
        if (target == null || !target.isAlive()) return;
        attackCooldownTicks = attackCooldown.get();
        Entity victim = target;
        // 攻击包必须排在「带朝向的移动包」之后：合法转头是随移动包一起发出去的，
        // 抢在它前面发的话服务端看到的还是旧视角，够得着也会被判视角没过 → 打空
        LegalRotation.runAfterSend(() -> attackNow(victim));
    }

    private void attackNow(Entity victim) {
        if (victim == null || !victim.isAlive() || victim.isRemoved()) return;
        if (autoSwapMace.get()) {
            FindItemResult mace = InvUtils.findInHotbar(Items.MACE);
            if (mace.found()) {
                InvUtils.swap(mace.slot(), true);
                mc.gameMode.attack(mc.player, victim);
                mc.player.swing(InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
                return;
            }
        }
        mc.gameMode.attack(mc.player, victim);
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
        Vec3 lead = aimPoint(target);
        Vec3 toTarget = new Vec3(lead.x - mc.player.getX(), 0.0, lead.z - mc.player.getZ());
        double r = toTarget.length();
        boolean overhead = r < 1.0E-4;
        float baseYaw = overhead ? (orbitSideSet ? climbHeading : mc.player.getYRot()) : yawOf(toTarget);

        // 空中追击：直接朝预测落点咬上去。空中目标飞来飞去，绕着圆圈慢慢转是永远咬不住的
        if (airChase()) {
            climbHeading = baseYaw;
            return avoidObstaclesContinuous(baseYaw);
        }

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

    /** 旧拉升偏航：朝目标水平方向，可选 45° 方向选择 + 障碍规避 */
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

    /** 斜向拉升的障碍规避：在目标航向附近连续微调（15° 一档），不再跳固定 45° 方向 */
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
            // 甲飞没有「自己发起飞包」这一步，起飞烟花窗口在这里开：
            // 下一个滑翔窗口（甲飞换装）里补一发，把砸完掉下来的速度接回去
            justTookOffTicks = 12;
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
        if (!mc.player.isFallFlying()) startFly();
    }

    private void startFly() {
        if (mc.player.isFallFlying() || !isElytraEquipped()) return;
        // 地面上不起飞（发了也会被服务器拒），避免站着一直补包
        if (mc.player.onGround()) return;
        if (takeoffCooldownTicks > 0) return;
        takeoffCooldownTicks = 3;
        if (grimInput.get()) {
            // 走鞘翅飞行那套：发一个起飞包 + 配一对跳跃输入包
            ElytraFlySupport.externalStartFlying();
        } else {
            // 纯发包：登记外部起飞窗口，让 MixinPlayerTakeoff 拦掉原版同一 tick 那发
            ElytraFlySupport.markExternalTakeoff();
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
        // 打开「起飞自动烟花」窗口：接下来十来 tick 里，没有烟花加速（或快结束）就补一发
        justTookOffTicks = 12;
        // 不设本地滑翔状态：滑翔与否完全交给服务器同步（原版鞘翅行为）。
        // 没同步上就一直重试（每 3 tick 一发），所以「切回鞘翅没起飞」不会卡死
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
