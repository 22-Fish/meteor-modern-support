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

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 背包交换使用工具：把物品从背包任意位置交换到手上使用，用完换回。
 *
 * <p>同一 tick 内连续发包：交换到手上 → 使用 → 交换回去。
 * 使用物品（消耗品数量变化等）可能让服务器容器 stateId 递增，
 * 导致换回包因 stateId 失配被拒；因此交换后持续几 tick 检查是否换回，
 * 未换回则用最新 stateId 重试，换回成功即结束。
 *
 * <p>「一键使用物品」与「合法平飞背包烟花」共用。
 */
public class BackpackUse {

    /** 换回确认检查窗口（tick）：每次背包交换使用后持续检查，未换回则重试 */
    private static final int SWAP_BACK_CHECK_TICKS = 4;

    /** 待确认换回的状态 */
    private static PendingSwapBack pending;

    private static class PendingSwapBack {
        /** 目标物品原所在槽（inventory 索引） */
        final int invSlot;
        /** 交换用的热栏槽（手上） */
        final int hotbarSlot;
        /** 目标物品判断（换回后该槽应恢复目标物品） */
        final Predicate<ItemStack> target;
        /** 剩余检查 tick */
        int ticksLeft = SWAP_BACK_CHECK_TICKS;

        PendingSwapBack(int invSlot, int hotbarSlot, Predicate<ItemStack> target) {
            this.invSlot = invSlot;
            this.hotbarSlot = hotbarSlot;
            this.target = target;
        }
    }

    private BackpackUse() {
    }

    /**
     * 交换使用：快捷栏（副手/主手/热栏）有目标物品则按快捷栏静默方案使用；
     * 否则背包交换三步（交换到手上 → 使用 → 交换回去）+ 换回确认重试。
     *
     * @return 是否触发了使用
     */
    public static boolean use(Predicate<ItemStack> target) {
        if (mc.player == null) return false;

        // 快捷栏（副手/主手/热栏）：静默切换使用后换回，优先于背包交换
        FindItemResult result = InvUtils.findInHotbar(target);
        if (result.found()) {
            if (result.isOffhand()) {
                mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
                return true;
            }
            if (result.isMainHand()) {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                return true;
            }
            InvUtils.swap(result.slot(), true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
            return true;
        }

        // 背包主区找目标物品
        result = InvUtils.find(target);
        if (!result.found()) return false;

        int slotId = SlotUtils.indexToId(result.slot());
        int hotbarSlot = mc.player.getInventory().getSelectedSlot();
        int containerId = mc.player.containerMenu.containerId;
        int stateId = mc.player.containerMenu.getStateId();

        // 交换到手上
        sendSwap(containerId, stateId, slotId, hotbarSlot);
        // 使用
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        // 交换回去（服务器每处理一个点击包 stateId 递增，用 +1 的 stateId）
        sendSwap(containerId, stateId + 1, slotId, hotbarSlot);

        // 记录待确认换回：使用物品可能让服务器 stateId 变化导致换回包被拒，
        // 接下来几 tick 持续检查，未换回则用最新 stateId 重试
        pending = new PendingSwapBack(result.slot(), hotbarSlot, target);
        return true;
    }

    /**
     * 每 tick 检查换回（各调用模块的 onTick 中调用）：
     * 已换回（原槽恢复目标物品，或被消耗完变为空）则结束检查；
     * 否则用最新 stateId 再次尝试换回，直到窗口结束。
     */
    public static void tick() {
        if (pending == null || mc.player == null) return;
        PendingSwapBack p = pending;

        // 已换回：目标物品回到原槽；被消耗完（原槽为空）也视为完成
        ItemStack stack = mc.player.getInventory().getItem(p.invSlot);
        if (stack.isEmpty() || p.target.test(stack)) {
            pending = null;
            return;
        }

        p.ticksLeft--;
        if (p.ticksLeft <= 0) {
            // 窗口结束仍未换回，放弃
            pending = null;
            return;
        }

        // 用最新 stateId 再试一次换回
        sendSwap(mc.player.containerMenu.containerId, mc.player.containerMenu.getStateId(),
            SlotUtils.indexToId(p.invSlot), p.hotbarSlot);
    }

    /** 直发 SWAP 点击包：slotId 槽与热栏槽 hotbarSlot 互换 */
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
