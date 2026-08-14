package fish22.modernsupport.utils;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 背包交换工具：把物品从背包任意位置交换到手上使用/放置，用完换回。
 *
 * <p>两种发包模式（由调用方模块选择，默认 2p 模式）：
 * <ul>
 *   <li>{@link Mode#SWAP}（1p 模式）：2 个 SWAP 包（目标槽 ↔ 手持槽互换 × 2），
 *       交换到手 → 操作 → 换回</li>
 *   <li>{@link Mode#PICKUP}（2p 模式）：4 个 PICKUP 包（参考原版手动点击，本地同步执行），
 *       拿起 → 互换 → 操作 → 换回 → 放回，中间不需要空位（背包满也能换）</li>
 * </ul>
 * 两种模式的包 + 操作都在同一 tick 内按顺序发完，不跨 tick。
 * 26.1 服务端对 stateId 失配的点击仍会执行（只触发完整状态重同步），
 * 因此换回/放回照常生效，无需延后到下一 tick。
 *
 * <p>「一键使用物品」「合法平飞背包烟花」「防止生成背包放置」共用。
 */
public class BackpackUse {

    /** 发包模式：1p = SWAP（2 包），2p = PICKUP（4 包） */
    public enum Mode {
        SWAP("1p模式"),
        PICKUP("2p模式");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private BackpackUse() {
    }

    /**
     * 交换使用：快捷栏（副手/主手/热栏）有目标物品则按快捷栏静默方案使用；
     * 否则背包交换（按 mode 选 SWAP 或 PICKUP）。
     *
     * @return 是否触发了使用
     */
    public static boolean use(Predicate<ItemStack> target, Mode mode) {
        return operate(target, mode, hand -> mc.gameMode.useItem(mc.player, hand));
    }

    /**
     * 交换放置：逻辑同 {@link #use}，只是把"使用"换成"放置"（右键方块）。
     *
     * @param hitResult 放置目标（点击位置/面/方块），由调用方计算
     * @return 是否触发了放置
     */
    public static boolean place(Predicate<ItemStack> target, BlockHitResult hitResult, Mode mode) {
        return operate(target, mode, hand -> mc.gameMode.useItemOn(mc.player, hand, hitResult));
    }

    /** 统一的交换操作：快捷栏静默方案，或背包交换（1p SWAP / 2p PICKUP，同一 tick 内发完） */
    private static boolean operate(Predicate<ItemStack> target, Mode mode, Consumer<InteractionHand> action) {
        if (mc.player == null) return false;

        // 快捷栏（副手/主手/热栏）：静默切换操作后换回，优先于背包交换
        FindItemResult result = InvUtils.findInHotbar(target);
        if (result.found()) {
            if (result.isOffhand()) {
                action.accept(InteractionHand.OFF_HAND);
                return true;
            }
            if (result.isMainHand()) {
                action.accept(InteractionHand.MAIN_HAND);
                return true;
            }
            InvUtils.swap(result.slot(), true);
            action.accept(InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
            return true;
        }

        // 背包主区找目标物品
        result = InvUtils.find(target);
        if (!result.found()) return false;

        int invSlot = result.slot();
        int hotbarSlot = mc.player.getInventory().getSelectedSlot();

        if (mode == Mode.SWAP) {
            // 1p：2 个 SWAP 包（目标槽 ↔ 手持槽互换）
            int slotId = SlotUtils.indexToId(invSlot);
            int containerId = mc.player.containerMenu.containerId;
            int stateId = mc.player.containerMenu.getStateId();

            sendSwap(containerId, stateId, slotId, hotbarSlot);      // 交换到手
            action.accept(InteractionHand.MAIN_HAND);                 // 使用 / 放置
            sendSwap(containerId, stateId + 1, slotId, hotbarSlot);  // 换回
        } else {
            // 2p：4 个 PICKUP 包（拿起 → 互换 → 操作 → 换回 → 放回）
            InvUtils.click().slot(invSlot);      // 1. 拿起目标物品（进光标，原槽空）
            InvUtils.click().slot(hotbarSlot);   // 2. 目标物品 ↔ 手持物品互换
            action.accept(InteractionHand.MAIN_HAND); // 使用 / 放置
            InvUtils.click().slot(hotbarSlot);   // 3. 手持物品回手，目标物品进光标
            InvUtils.click().slot(invSlot);      // 4. 目标物品放回原槽
        }

        return true;
    }

    /** 直发 SWAP 点击包：slotId 槽与热栏槽 hotbarSlot 互换（1p 模式） */
    private static void sendSwap(int containerId, int stateId, int slotId, int hotbarSlot) {
        mc.getConnection().send(new ServerboundContainerClickPacket(
            containerId,
            stateId,
            (short) slotId,
            (byte) hotbarSlot,
            ContainerInput.SWAP,
            new Int2ObjectOpenHashMap<>(),
            HashedStack.EMPTY
        ));
    }
}
