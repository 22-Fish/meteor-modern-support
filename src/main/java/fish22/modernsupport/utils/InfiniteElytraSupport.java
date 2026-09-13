package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.Deque;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 无限鞘翅（「鞘翅飞行」模块里的「无限鞘翅」板块，逻辑照搬 AEfish 的 InfiniteElytra / SlimefunHelper 的 unbreakable-elytra）
 *
 * <p>核心思路：服务端滑翔耐久消耗只看 fallFlyTicks（每累计 20 tick 掉 1 点），
 * 该计数只在「不滑翔」时归零。因此滑翔中定期脱一下鞘翅，服务端 canGlide=false
 * 会自动停滑（fallFlyTicks 归零），再穿回鞘翅重新起飞，计数永远到不了 20。
 *
 * <p>与旧实现的关键区别：不再「主动发停滑包」，而是脱鞘翅后等服务器广播停滑，
 * 在客户端拦截这次停滑广播（把 FALL_FLYING 标志位和 POSE 改回滑翔状态），
 * 使客户端视觉上滑翔不闪断，同时服务端已完成 fallFlyTicks 归零。
 *
 * <p>设置由 {@link fish22.modernsupport.mixin.MixinElytraFly} 注入；
 * 本板块只在「鞘翅飞行」模块开着时生效（与「甲飞」互斥、「发包」模式互斥，见 MixinElytraFly）。
 */
public class InfiniteElytraSupport {

    // ====== 设置（MixinElytraFly 创建后注入） ======

    /** 「无限鞘翅」总开关 */
    public static Setting<Boolean> infiniteElytra;

    /** 刷新周期（tick）：滑翔累计达到该值就脱鞘翅刷新一次 */
    public static Setting<Integer> period;

    /** 静音：屏蔽装备穿戴音效 */
    public static Setting<Boolean> mute;

    // ====== 状态 ======

    // 实体同步数据 id（Entity 类 defineId 顺序固定，26.1 与 1.21.1 一致）
    private static final int ID_FLAGS = 0;               // DATA_SHARED_FLAGS_ID
    private static final int ID_POSE = 6;                // DATA_POSE
    private static final int FALL_FLYING_FLAG_INDEX = 7; // FLAG_FALL_FLYING

    /** 已触发「停滑」、正在等待服务器广播停滑（防 lag 重复触发） */
    private static boolean nextTimeLaunchElytraUnbreakable = false;

    /** 脱鞘翅时鞘翅暂存的背包槽（-1 = 未脱鞘翅，只是发了重复起飞包） */
    private static int elytraUnbreakableSwitchSlot = -1;

    /** 滑翔累计 tick 计数 */
    private static int durabilityCounter = 0;

    /** 脱鞘翅期间被拦截、待穿回后重发的烟花（手） */
    private static final Deque<InteractionHand> delayedFireworkHands = new ArrayDeque<>();

    /** 正在 flush 延迟烟花（防止重发时被自己拦截） */
    private static boolean flushing = false;

    /** 本次实体数据同步做了「穿回+起飞」，数据应用后需要 flush 延迟烟花 */
    private static boolean pendingFlush = false;

    private InfiniteElytraSupport() {}

    /** 板块是否生效：开关打开 且「鞘翅飞行」模块处于开启状态 */
    public static boolean isActive() {
        if (infiniteElytra == null || !infiniteElytra.get()) return false;
        ElytraFly module = Modules.get().get(ElytraFly.class);
        return module != null && module.isActive();
    }

    /** 重置全部状态（模块开关时调用） */
    public static void reset() {
        durabilityCounter = 0;
        nextTimeLaunchElytraUnbreakable = false;
        elytraUnbreakableSwitchSlot = -1;
        delayedFireworkHands.clear();
        flushing = false;
        pendingFlush = false;
    }

    /** 是否屏蔽装备穿戴音效（换甲声） */
    public static boolean shouldMuteArmorSounds() {
        return isActive() && mute != null && mute.get();
    }

    /** 每 tick 主逻辑（由 MixinElytraFly 在官方 onTick 最前面调用，不分模式） */
    public static void onTick() {
        if (!isActive() || mc.player == null) return;

        // 滑翔计数：滑翔时累加，否则归零
        if (mc.player.isFallFlying()) {
            durabilityCounter += 1;
        } else {
            durabilityCounter = 0;
        }

        if (!shouldElytraUnbreakable() || durabilityCounter < periodValue() || !canContinueGliding() || !mc.player.isFallFlying()) {
            return;
        }

        if (nextTimeLaunchElytraUnbreakable) {
            // 上一次事务还没走完（网络延迟），跳过本次
            durabilityCounter = 0;
            return;
        }

        elytraUnbreakableSwitchSlot = findSwitchSlotForElytra();
        if (elytraUnbreakableSwitchSlot == -1) {
            // 找不到能和胸甲互换的槽：发重复起飞包，利用服务端
            // 「已滑翔时收到起飞包会停滑」的行为让 fallFlyTicks 归零
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            nextTimeLaunchElytraUnbreakable = true;
            durabilityCounter = 0;
        } else {
            // 脱鞘翅（与胸甲互换/放到空槽），服务端随后自动停滑；
            // 交换失败（容器异常）时不设等待标志，下个周期重试
            if (switchSlotToArmor(elytraUnbreakableSwitchSlot)) {
                nextTimeLaunchElytraUnbreakable = true;
            }
            durabilityCounter = 0;
        }
    }

    /** 脱鞘翅期间拦截烟花使用包，延迟到穿回起飞后重发 */
    public static void onPacketSend(PacketEvent.Send event) {
        if (!isActive() || mc.player == null) return;
        if (!(event.packet instanceof ServerboundUseItemPacket packet)) return;
        // 只在脱鞘翅期间（鞘翅被换到背包槽）拦截，且 flush 重发时不拦截自己
        if (elytraUnbreakableSwitchSlot == -1 || flushing) return;
        ItemStack stack = mc.player.getItemInHand(packet.getHand());
        if (!stack.is(Items.FIREWORK_ROCKET)) return;
        delayedFireworkHands.add(packet.getHand());
        event.cancel();
    }

    /**
     * 处理单个实体同步数据项（由 {@link fish22.modernsupport.mixin.MixinSynchedEntityData} 调用）。
     *
     * <p>在服务端广播停滑（FALL_FLYING 位清零 / POSE 变站立）时，
     * 把这两项改回滑翔状态，使客户端本地滑翔不闪断。
     */
    public static SynchedEntityData.DataValue<?> processDataValue(SynchedEntityData.DataValue<?> item) {
        int id = item.id();

        if (id == ID_FLAGS) {
            byte data = (Byte) item.value();
            boolean canRun = false;

            // 服务端广播停滑：FALL_FLYING 位被清零
            if ((data & (1 << FALL_FLYING_FLAG_INDEX)) == 0) {
                // 无论模块是否还在工作，都消费掉等待标志，避免残留状态
                if (nextTimeLaunchElytraUnbreakable) {
                    nextTimeLaunchElytraUnbreakable = false;
                    canRun = true;
                }
            }

            if (isActive() && canRun) {
                // 穿回鞘翅（若此前脱过）
                if (elytraUnbreakableSwitchSlot != -1) {
                    if (!switchSlotToArmor(elytraUnbreakableSwitchSlot)) {
                        // 穿回失败（容器已不是玩家背包界面）：清空状态让停滑正常生效，
                        // 避免本地假滑翔而服务器已停滑导致坠落
                        delayedFireworkHands.clear();
                        elytraUnbreakableSwitchSlot = -1;
                        return item;
                    }
                }
                if (canContinueGliding()) {
                    // 本地把 FALL_FLYING 位改回 1，取消这次停滑广播
                    byte newData = (byte) (data | (1 << FALL_FLYING_FLAG_INDEX));
                    // 发起飞前松开跳跃、起飞后按下跳跃，伪装成原版起跳，绕过反作弊起飞合法性检查
                    sendPreStartFallFlying();
                    if (mc.getConnection() != null) {
                        mc.getConnection().send(new ServerboundPlayerCommandPacket(
                            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    }
                    sendPostStartFallFlying();
                    // 数据应用后重发延迟烟花
                    pendingFlush = true;
                    elytraUnbreakableSwitchSlot = -1;
                    return withValue(item, newData);
                }
                // 不能继续滑翔（落地/入水等），让停滑正常生效，丢弃延迟的烟花
                delayedFireworkHands.clear();
                elytraUnbreakableSwitchSlot = -1;
            }
            return item;

        } else if (id == ID_POSE) {
            if (isActive() && canContinueGliding()) {
                Object pose = item.value();
                // 本地把姿态改回滑翔姿态
                if (pose != net.minecraft.world.entity.Pose.FALL_FLYING) {
                    return withValue(item, net.minecraft.world.entity.Pose.FALL_FLYING);
                }
            }
            return item;
        }

        return item;
    }

    /** 穿回起飞、数据应用后，重发延迟的烟花（由 MixinSynchedEntityData 的 TAIL 调用） */
    public static void flushDelayedFireworks() {
        if (!pendingFlush) return;
        pendingFlush = false;
        if (delayedFireworkHands.isEmpty() || mc.player == null) return;

        flushing = true;
        try {
            while (!delayedFireworkHands.isEmpty()) {
                InteractionHand hand = delayedFireworkHands.poll();
                ItemStack stack = mc.player.getItemInHand(hand);
                if (!stack.is(Items.FIREWORK_ROCKET)) continue;
                // 此时本地已恢复滑翔，useItem 走原版流程发烟花包
                mc.gameMode.useItem(mc.player, hand);
            }
        } finally {
            flushing = false;
        }
    }

    /** 刷新周期（设置未注入时用默认 16） */
    private static int periodValue() {
        return period == null ? 16 : Math.max(1, period.get());
    }

    /** 是否应执行无限耐久：玩家存在 + 胸甲鞘翅没有「不可破坏」属性 */
    private static boolean shouldElytraUnbreakable() {
        return mc.player != null
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).get(DataComponents.UNBREAKABLE) == null;
    }

    /** 是否还能继续滑翔（用于决定是否拦截停滑） */
    private static boolean canContinueGliding() {
        return mc.player != null
            && !mc.player.isInWater()
            && !mc.player.getAbilities().flying
            && !mc.player.onGround()
            && !mc.player.isPassenger()
            && !mc.player.hasEffect(MobEffects.LEVITATION);
    }

    /**
     * 找一个能和胸甲槽互换的背包槽：
     * 优先找背包里的胸甲（脱鞘翅后胸甲槽直接穿上胸甲，鞘翅落到胸甲原位置），
     * 没有胸甲才退而求其次找空槽（热栏 → 背包 → 副手）。
     */
    private static int findSwitchSlotForElytra() {
        // 只有当前是玩家背包界面（没开箱子等容器）才能安全交换
        if (!(mc.player.containerMenu instanceof InventoryMenu)) return -1;

        // 第一轮：优先找胸甲（非鞘翅且可装备胸甲的物品）
        for (int i = 0; i < 9; i++) {
            if (isSwappableChestItem(mc.player.getInventory().getItem(i))) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (isSwappableChestItem(mc.player.getInventory().getItem(i))) return i;
        }
        if (isSwappableChestItem(mc.player.getInventory().getItem(SlotUtils.OFFHAND))) return SlotUtils.OFFHAND;

        // 第二轮：没有胸甲，找空槽兜底
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        if (mc.player.getInventory().getItem(SlotUtils.OFFHAND).isEmpty()) return SlotUtils.OFFHAND;

        return -1;
    }

    private static boolean isSwappableChestItem(ItemStack stack) {
        return !stack.isEmpty()
            && !stack.has(DataComponents.GLIDER)
            && mc.player.isEquippableInSlot(stack, EquipmentSlot.CHEST);
    }

    /**
     * 交换胸甲槽 ↔ 目标背包槽：
     * 热栏/副手用 SWAP 数字键原子交换，背包主体用三个 PICKUP 包瞬间互换。
     *
     * @return 是否成功发出交换操作（容器已不是玩家背包界面时返回 false）
     */
    private static boolean switchSlotToArmor(int idx) {
        if (!(mc.player.containerMenu instanceof InventoryMenu)) return false;

        // 换装前伪造静止输入（停止冲刺 + 无移动），降低反作弊对换装的怀疑
        sendPacketsForInventoryAction();

        if (idx >= 0 && idx <= 8 || idx == SlotUtils.OFFHAND) {
            // 热栏/副手：SWAP 数字键交换（一次点击、原子，button 用 inventory 索引）
            swapHotbarToArmor(idx);
            return true;
        }

        // 背包主体：三包 PICKUP 互换（鼠标携带物品也能正确互换，无需检查）
        swapMainToArmor(idx);
        return true;
    }

    /**
     * 背包主体与胸甲槽三包互换（三个包瞬间发完）：
     * 1. 拿起背包槽物品（胸甲/鞘翅）
     * 2. 点击胸甲槽交换（胸甲槽物品 ↔ 鼠标物品）
     * 3. 把换出的物品放回背包槽
     *
     * <p>脱鞘翅（胸甲↔鞘翅互换）与穿回（鞘翅↔胸甲互换）两个方向共用同一序列。
     */
    private static void swapMainToArmor(int idx) {
        int chest = chestSlotId();
        for (int slot : new int[] { idx, chest, idx }) {
            mc.gameMode.handleContainerInput(
                mc.player.containerMenu.containerId,
                slot,
                0,
                ContainerInput.PICKUP,
                mc.player
            );
        }
    }

    /**
     * 胸甲槽在 InventoryMenu 里的 slot id（26.1 协议标准映射：装备槽 = 8 - EquipmentSlot.getIndex()）。
     *
     * <p>不用 Meteor SlotUtils.indexToId/toArmor 的映射，避免依赖其内部实现：
     * 26.1 的装备槽 slot id 顺序与旧版相反（5=头盔,6=胸甲,7=护腿,8=靴子），
     * 而 Meteor 的 survivalInventory 按旧版顺序映射，两者叠加会错位到护腿槽。
     */
    private static int chestSlotId() {
        return 8 - EquipmentSlot.CHEST.getIndex();
    }

    /** SWAP 交换热栏/副手（button = inventory 索引 0~8 或 40）与胸甲槽 */
    private static void swapHotbarToArmor(int hotbarIndex) {
        mc.gameMode.handleContainerInput(
            mc.player.containerMenu.containerId,
            chestSlotId(),
            hotbarIndex,
            ContainerInput.SWAP,
            mc.player
        );
    }

    /** 换装前伪造静止输入：停止冲刺 + 发送无移动输入包 */
    private static void sendPacketsForInventoryAction() {
        if (mc.player == null || mc.getConnection() == null) return;
        // 冲刺中换装会被反作弊判为异常，先停止冲刺
        if (mc.player.isSprinting()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }
        // 无移动输入包（WASD/跳跃/冲刺归零，保留潜行）
        Input cur = mc.player.input.keyPresses;
        Input noMove = new Input(false, false, false, false, false, cur.shift(), false);
        mc.getConnection().send(new ServerboundPlayerInputPacket(noMove));
        mc.player.input.keyPresses = noMove;
    }

    /** 发起飞包前：伪造「松开跳跃」输入，避免反作弊把跳跃与起飞同时发生判为异常 */
    private static void sendPreStartFallFlying() {
        if (mc.player == null || mc.getConnection() == null) return;
        Input cur = mc.player.input.keyPresses;
        Input pre = new Input(cur.forward(), cur.backward(), cur.left(), cur.right(), false, cur.shift(), cur.sprint());
        mc.getConnection().send(new ServerboundPlayerInputPacket(pre));
        mc.player.input.keyPresses = pre;
    }

    /** 发起飞包后：伪造「按下跳跃」输入，模拟原版起跳触发鞘翅 */
    private static void sendPostStartFallFlying() {
        if (mc.player == null || mc.getConnection() == null) return;
        Input cur = mc.player.input.keyPresses;
        Input post = new Input(cur.forward(), cur.backward(), cur.left(), cur.right(), true, cur.shift(), cur.sprint());
        mc.getConnection().send(new ServerboundPlayerInputPacket(post));
        mc.player.input.keyPresses = post;
    }

    /** 用新值重建一个 DataValue（记录不可变，只能新建） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SynchedEntityData.DataValue<?> withValue(SynchedEntityData.DataValue<?> item, Object value) {
        return new SynchedEntityData.DataValue(item.id(), item.serializer(), value);
    }
}
