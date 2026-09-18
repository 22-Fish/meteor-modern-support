package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 背包交换工具：把物品从背包任意位置换到「目标槽位」使用、放置，用完换回。
 *
 * <p>两个设置（由调用方模块提供）：
 * <ul>
 *   <li>发包方式 {@link Mode}：
 *       {@link Mode#SWAP} = 2 次 SWAP 点击（背包槽 ↔ 目标格互换 × 2，换过去 → 操作 → 换回）；
 *       {@link Mode#PICKUP} = 4 个 PICKUP 包（参考原版手动点击，本地同步执行，
 *       拿起 → 互换 → 操作 → 换回 → 放回，中间不需要空位，背包满也能换）</li>
 *   <li>目标槽位 {@link TargetSlot}：
 *       {@link TargetSlot#OFFHAND} = 换到副手使用，<b>不碰手上那一格</b>
 *       （攻击中手上那一格的内容一直在变，走手上路线时最容易被搅乱）；
 *       {@link TargetSlot#MAIN_HAND} = 换到当前手持的那一快捷栏格，用主手操作；
 *       {@link TargetSlot#HOTBAR} = 换到「除手持那一格以外」的一个快捷栏格，切过去操作完再切回来
 *       （选格优先级：空手 &gt; 工具 &gt; 方块 &gt; 物品）</li>
 * </ul>
 * 包 + 操作都在同一 tick 内按顺序发完，不跨 tick。
 *
 * <p><b>两种方式都走原版点击通道</b>（{@code MultiPlayerGameMode#handleContainerInput}，
 * 见 {@link #clickSwap}）：本地同步 + 包里带真实 stateId、本次改动过的槽位清单与光标物品哈希。
 * 服务端拿这份清单更新它记的「客户端看到的背包」，点完对账时就不会认为客户端还看着旧状态，
 * 也就不会把中间态补发回来（手拼包交不出清单，服务端每点一次都要回推槽位、并且每次回推都让
 * 菜单 stateId +1，紧接着的第二次点击就会因 stateId 对不上触发整份菜单全量重发）。
 *
 * <p>SWAP 类交换换回后还会跨 tick 对账（{@link #tick()}）：一旦发现交换用的那一格
 * （手持热栏 / 副手 / 快捷栏模式换过去的那一格）里还是目标物品，说明换回没生效，用当前 stateId
 * 幂等补一次同样的 SWAP 换回去。PICKUP 走光标，状态错位时长这样不是单发 SWAP 能修的，
 * 所以不登记对账。
 *
 * <p>「一键使用物品」「一键烟花」「鞘翅飞行背包烟花」「防止生成背包放置」共用。
 */
public class BackpackUse {

    /** 发包方式：SWAP = 2 次原版 SWAP 点击（背包槽 ↔ 目标格互换），PICKUP = 4 次原版 PICKUP 点击（走光标，背包满也能换） */
    public enum Mode {
        SWAP("SWAP模式"),
        PICKUP("PICKUP模式");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 目标槽位：背包物品换到哪一格使用 */
    public enum TargetSlot {
        OFFHAND("副手"),
        MAIN_HAND("主手"),
        HOTBAR("快捷栏");

        private final String displayName;

        TargetSlot(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 换回对账窗口（tick）：窗口内每 tick 检查一次，发现没换回就用当前 stateId 补一次 SWAP */
    private static final int VERIFY_TICKS = 3;

    /** 待对账的换回记录（一次 SWAP 类背包交换对应一条；PICKUP 不登记） */
    private static Pending pending;

    private static final class Pending {
        /** 目标物品判断 */
        final Predicate<ItemStack> target;
        /** 目标物品原本所在的背包槽（inventory 索引） */
        final int targetSlot;
        /** 换回用的 SWAP 按钮：热栏索引 0-8 或 {@link SlotUtils#OFFHAND} */
        final int button;
        /** 剩余检查 tick */
        int ticksLeft = VERIFY_TICKS;

        Pending(Predicate<ItemStack> target, int targetSlot, int button) {
            this.target = target;
            this.targetSlot = targetSlot;
            this.button = button;
        }
    }

    private BackpackUse() {
    }

    /**
     * 交换使用：快捷栏（副手 / 主手 / 热栏）里已有目标物品则按快捷栏静默方案使用；
     * 否则从背包换到「目标槽位」使用（按 mode 选 SWAP 或 PICKUP）。
     *
     * @return 是否触发了使用
     */
    public static boolean use(Predicate<ItemStack> target, Mode mode, TargetSlot targetSlot) {
        return use(target, mode, targetSlot, 1);
    }

    /**
     * 交换使用（连用多次）：物品只「移动一次」—— 换到目标槽位后，同一 tick 内把 {@code times} 个
     * 使用包一次性发完，最后再换回一次。
     *
     * <p>每用一次就换进换出一次是没有必要的：那样一 tick 会发成倍的背包点击包
     * （SWAP 模式 2 包/次、PICKUP 模式 4 包/次），本地镜像与服务端菜单也更容易分叉，
     * 和同 tick 的其它换装（甲飞换鞘翅、一键烟花）抢同一份背包状态。
     *
     * @param times 这一 tick 连用几次（小于 1 按 1 次算）
     * @return 是否触发了使用
     */
    public static boolean use(Predicate<ItemStack> target, Mode mode, TargetSlot targetSlot, int times) {
        return operate(target, mode, targetSlot, times, hand -> mc.gameMode.useItem(mc.player, hand));
    }

    /**
     * 交换放置：逻辑同 {@link #use}，只是把"使用"换成"放置"（右键方块）。
     *
     * @param hitResult 放置目标（点击位置/面/方块），由调用方计算
     * @return 是否触发了放置
     */
    public static boolean place(Predicate<ItemStack> target, BlockHitResult hitResult, Mode mode, TargetSlot targetSlot) {
        return place(target, hitResult, mode, targetSlot, false);
    }

    /**
     * 交换放置（带挥手开关）。
     *
     * @param swing 放置后要不要跟着挥手（和 {@code BlockUtils#interact} 的挥手参数同义）
     */
    public static boolean place(Predicate<ItemStack> target, BlockHitResult hitResult, Mode mode, TargetSlot targetSlot,
                                boolean swing) {
        return operate(target, mode, targetSlot, 1, hand -> {
            mc.gameMode.useItemOn(mc.player, hand, hitResult);
            if (swing) mc.player.swing(hand);
        });
    }

    /** 统一的交换操作：快捷栏静默方案，或背包交换（同一 tick 内发完，操作连做 times 次） */
    private static boolean operate(Predicate<ItemStack> target, Mode mode, TargetSlot targetSlot,
                                   int times, Consumer<InteractionHand> action) {
        if (mc.player == null) return false;

        int count = Math.max(1, times);

        // 快捷栏（副手/主手/热栏）：静默切换操作后换回，优先于背包交换
        FindItemResult result = InvUtils.findInHotbar(target);
        if (result.found()) {
            if (result.isOffhand()) {
                act(action, InteractionHand.OFF_HAND, count);
                return true;
            }
            if (result.isMainHand()) {
                act(action, InteractionHand.MAIN_HAND, count);
                return true;
            }
            InvUtils.swap(result.slot(), true);
            try {
                act(action, InteractionHand.MAIN_HAND, count);
            } finally {
                InvUtils.swapBack();
            }
            return true;
        }

        // 背包主区找目标物品
        result = InvUtils.find(target);
        if (!result.found()) return false;

        int invSlot = result.slot();

        switch (targetSlot) {
            // 副手：换到副手操作（只动副手和那个背包槽，不碰手上那一格）
            case OFFHAND -> swapAndAct(target, mode, invSlot, SlotUtils.OFFHAND, false,
                count, InteractionHand.OFF_HAND, action);

            // 主手：换到当前手持的那一快捷栏格操作
            case MAIN_HAND -> swapAndAct(target, mode, invSlot, mc.player.getInventory().getSelectedSlot(), false,
                count, InteractionHand.MAIN_HAND, action);

            // 快捷栏：换到「除手持那一格以外」的一格，切过去操作完再切回来
            case HOTBAR -> {
                int hotbarSlot = pickHotbarSlot();
                if (hotbarSlot < 0) return false;
                swapAndAct(target, mode, invSlot, hotbarSlot, true, count, InteractionHand.MAIN_HAND, action);
            }
        }

        return true;
    }

    /** 连做 count 次操作（同一 tick 内一次性发完所有使用/放置包） */
    private static void act(Consumer<InteractionHand> action, InteractionHand hand, int count) {
        for (int i = 0; i < count; i++) action.accept(hand);
    }

    /**
     * 背包交换本体：目标槽 ↔ 指定格换过去 → 操作（连做 times 次） → 换回。
     *
     * <p>SWAP：两次点击都走原版通道 {@link #clickSwap}（本地同步 + 带预测信息的包），
     * 结束后登记换回对账（见 {@link #tick()}）。
     * PICKUP：4 个 PICKUP 包，本地同步由 {@code MultiPlayerGameMode#handleContainerInput} 完成。
     *
     * @param button     换到哪一格：热栏索引 0-8 或 {@link SlotUtils#OFFHAND}（副手）
     * @param selectSlot 换过去后是否把这个热栏格切成选中槽（快捷栏模式要切过去才算"手上拿着它"；
     *                   副手 / 手持格不用切）
     * @param hand       操作用的那只手（主手格 = MAIN_HAND，副手 = OFF_HAND）
     */
    private static void swapAndAct(Predicate<ItemStack> target, Mode mode, int invSlot, int button,
                                   boolean selectSlot, int times, InteractionHand hand,
                                   Consumer<InteractionHand> action) {
        if (mode == Mode.PICKUP) {
            InvUtils.click().slot(invSlot);                     // 1. 拿起目标物品（进光标，原槽空）
            InvUtils.click().slot(button);                      // 2. 目标物品 ↔ 目标格互换
            if (selectSlot) InvUtils.swap(button, true);        // 快捷栏模式：切到那一格
            act(action, hand, times);                           // 使用 / 放置（这一 tick 连做几次）
            if (selectSlot) InvUtils.swapBack();                // 切回原来那一格
            InvUtils.click().slot(button);                      // 3. 目标格的东西回手，目标物品进光标
            InvUtils.click().slot(invSlot);                     // 4. 目标物品放回原槽
            return;
        }

        int slotId = SlotUtils.indexToId(invSlot);
        if (slotId < 0) return;

        clickSwap(slotId, button);                             // 换到目标格（本地同步 + 发包）
        if (selectSlot) InvUtils.swap(button, true);           // 快捷栏模式：切到那一格
        act(action, hand, times);                              // 使用 / 放置（这一 tick 连做几次）
        if (selectSlot) InvUtils.swapBack();                   // 切回原来那一格
        clickSwap(slotId, button);                             // 换回（stateId 在包发出时现读，不猜）

        pending = new Pending(target, invSlot, button);
    }

    /**
     * 选一个「除手持那一格以外」的快捷栏格（快捷栏模式用）。
     *
     * <p>优先级：空手（空格最干净，什么都不用挪）&gt; 工具 &gt; 方块 &gt; 物品。
     * 9 格热栏里排除手持那一格后必定还有别的格，正常不会返回 -1。
     */
    private static int pickHotbarSlot() {
        int selected = mc.player.getInventory().getSelectedSlot();
        int empty = -1, tool = -1, block = -1, item = -1;

        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.HOTBAR_END; i++) {
            if (i == selected) continue;

            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                if (empty == -1) empty = i;
            } else if (isTool(stack)) {
                if (tool == -1) tool = i;
            } else if (stack.getItem() instanceof BlockItem) {
                if (block == -1) block = i;
            } else if (item == -1) {
                item = i;
            }
        }

        if (empty != -1) return empty;
        if (tool != -1) return tool;
        if (block != -1) return block;
        return item;
    }

    /** 工具判断：带 TOOL 组件的物品，或镐 / 斧 / 锹 / 锄 / 剑 */
    private static boolean isTool(ItemStack stack) {
        return stack.has(DataComponents.TOOL)
            || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS)
            || stack.is(ItemTags.HOES) || stack.is(ItemTags.SWORDS);
    }

    /**
     * 换回对账（本模组每 tick 调用，见 ModernSupport）：
     * 交换用的那一格里还是目标物品 → 说明换回没生效，用当前 stateId 幂等重发一次同样的 SWAP
     * （单发 SWAP 自逆：只会把它换回去，或本来就没问题时不产生变化）。
     *
     * <p>攻击中手上那一格的内容一直在变、或与自动图腾之类的库存点击抢同一格时，都可能出现换回
     * 没生效；副手模式下这一格是副手，图腾/盾会被一起换走，所以发现没换回就立刻补一发换回来。
     * 交换用的那一格已经换成别的东西（例如自动图腾刚补了个图腾进副手）时不动它。
     */
    public static void tick() {
        if (pending == null) return;
        if (mc.player == null || mc.level == null) {
            pending = null;
            return;
        }

        Pending p = pending;

        // 已经换回（交换用的那一格不再是目标物品）→ 收工
        if (!p.target.test(mc.player.getInventory().getItem(p.button))) {
            pending = null;
            return;
        }

        // 窗口结束仍未换回 → 放弃，避免一直动玩家的背包
        if (--p.ticksLeft <= 0) {
            pending = null;
            return;
        }

        // 只在玩家背包界面（没开箱子等容器）里补发，避免点到别的菜单
        if (mc.player.containerMenu.containerId != 0) return;

        int slotId = SlotUtils.indexToId(p.targetSlot);
        if (slotId < 0) {
            pending = null;
            return;
        }

        clickSwap(slotId, p.button);
    }

    /**
     * 一次 SWAP 点击：slotId 槽与 button 指的格子互换（热栏索引 0-8 / 副手 40）。
     *
     * <p>走原版通道 {@code MultiPlayerGameMode#handleContainerInput}（Meteor 的
     * {@code InvUtils.quickSwap()} 也是这么发的），它一次做完两件事，缺一个都不行：
     *
     * <ul>
     *   <li><b>本地同步</b>：先按同样的点击改本地菜单。紧接着的
     *       {@code gameMode.useItem / useItemOn} 是按<b>本地手上那个物品</b>做客户端预测的
     *       —— 手持末影珍珠去一键使用背包里的东西时，珍珠会亮起冷却动画（服务端收到的点击包里
     *       手上已经是目标物品，珍珠并没有真的扔出去，只是客户端预测用错了物品）；</li>
     *   <li><b>带预测信息的包</b>：真实 stateId + 本次点击改动过的槽位清单 + 光标物品哈希。
     *       服务端用这份清单更新它记的「客户端看到的背包」。手拼包给不出这份清单时，服务端会
     *       认为客户端还看着旧状态，于是把改动过的槽位补发回来（补发一次菜单 stateId 就 +1），
     *       下一次点击的 stateId 就对不上 → 服务端改发整份菜单全量重同步。那些补发的是服务端
     *       那一瞬间的<b>中间态</b>（烟花在手上、原手上物品在背包里），会盖掉客户端的本地预测，
     *       也正是 SWAP 之前不如 PICKUP 稳的原因。</li>
     * </ul>
     *
     * <p>stateId 由原版通道在发包那一刻现读，不再需要调用方猜（原先写死的 {@code stateId + 1}
     * 只有在「第一次点击恰好只改一个槽位」时才碰巧对，正常一次交换改两个槽位，所以基本每次都会
     * 撞上整份菜单重同步）。
     */
    private static void clickSwap(int slotId, int button) {
        if (slotId < 0) return;
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotId, button,
            ContainerInput.SWAP, mc.player);
    }
}
