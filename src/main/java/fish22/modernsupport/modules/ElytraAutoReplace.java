package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅自动替换（独立模块，世界分类）
 *
 * <p>滑翔中，胸甲槽上的鞘翅剩余耐久掉到「替换阈值」以下时，
 * 自动换成背包（快捷栏 + 主背包 + 副手）里剩余耐久最高的那件鞘翅。
 *
 * <h3>为什么不会把滑翔打断</h3>
 * 换装是「拿起背包那件 → 放胸甲槽 → 把换下来的放回原槽」三个点击包，
 * 在同一个 tick 里一次发完（本地背包同步改完）。
 * 服务端要处理完整批包才会检查一次滑翔条件，那时胸甲槽上还是鞘翅，滑翔照旧。
 *
 * <h3>几个安全判断</h3>
 * <ul>
 *   <li>只在滑翔中（{@code isFallFlying}）处理，站着/走路不动背包；</li>
 *   <li>只在玩家自己的背包菜单里换（背包界面开着也行、光标上拖着东西也行）；
 *       箱子/熔炉这类容器的菜单里没有护甲槽，点不到，这种时候不换；</li>
 *   <li>换过去的鞘翅必须比身上这件更耐用，换上耐久更低的没意义，
 *       也避免「背包里剩的全是低耐久鞘翅」时反复倒腾；</li>
 *   <li>不可破坏的鞘翅（没有耐久条）不参与，也不当目标。</li>
 * </ul>
 */
public class ElytraAutoReplace extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 替换阈值：剩余耐久低于或等于这个值时换鞘翅 */
    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
        .name("替换阈值")
        .description("胸甲槽上的鞘翅剩余耐久低于或等于这个值时，自动换成背包里耐久最高的鞘翅")
        .defaultValue(10)
        .range(1, 432)
        .sliderRange(1, 432)
        .build()
    );

    public ElytraAutoReplace() {
        super(Categories.World, "鞘翅自动替换", "滑翔中鞘翅快坏了就自动换成背包里耐久最高的鞘翅");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // 只在滑翔中处理
        if (!mc.player.isFallFlying()) return;

        // 开着界面也照常换：只要当前菜单是玩家自己的背包（背包界面 / 创造背包 / 聊天栏），
        // 胸甲槽就在这个菜单里，点得到；箱子/熔炉这类容器的菜单里没有护甲槽，点不到，跳过。
        // 玩家正拖着物品（光标非空）也没关系：三个点击走完光标里的东西原样留着
        if (!(mc.player.containerMenu instanceof InventoryMenu)) return;

        // 胸甲槽上得是一件有耐久的鞘翅
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!isElytra(chest) || !chest.isDamageableItem()) return;

        int remaining = remainingDurability(chest);
        if (remaining > threshold.get()) return;

        int best = findBestElytra(remaining);
        if (best == -1) return;

        swapWithChest(best);
    }

    /** 这件物品能不能当滑翔翼（原版鞘翅，或带「滑翔」组件的东西） */
    private static boolean isElytra(ItemStack stack) {
        return stack.is(Items.ELYTRA) || stack.has(DataComponents.GLIDER);
    }

    /** 剩余耐久（能承受的剩余伤害次数） */
    private static int remainingDurability(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    /**
     * 在快捷栏 + 主背包 + 副手找剩余耐久最高的鞘翅。
     *
     * <p>只找比身上这件（{@code currentRemaining}）更耐用的：一件都没有时返回 -1，什么也不做。
     *
     * @return 背包槽位索引（快捷栏 0-8 / 主背包 9-35 / 副手 40），找不到返回 -1
     */
    private int findBestElytra(int currentRemaining) {
        int bestSlot = -1;
        int bestRemaining = currentRemaining;

        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.MAIN_END; i++) {
            int slot = betterElytraSlot(i, bestRemaining);
            if (slot == -1) continue;

            bestSlot = slot;
            bestRemaining = remainingDurability(mc.player.getInventory().getItem(slot));
        }

        int offhand = betterElytraSlot(SlotUtils.OFFHAND, bestRemaining);
        if (offhand != -1) bestSlot = offhand;

        return bestSlot;
    }

    /** 这一格是「比 minRemaining 更耐用的鞘翅」就返回该槽位，否则返回 -1 */
    private int betterElytraSlot(int slot, int minRemaining) {
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (!isElytra(stack) || !stack.isDamageableItem()) return -1;

        return remainingDurability(stack) > minRemaining ? slot : -1;
    }

    /**
     * 背包槽与胸甲槽互换（三击：拿起背包那件 → 放进胸甲槽 → 换下来的放回原槽）。
     * 三个点击在本地同步执行、同 tick 一起发出，光标内容原样保留。
     *
     * @param invIndex 背包槽位索引（{@link #findBestElytra} 的返回值）
     */
    private void swapWithChest(int invIndex) {
        InvUtils.click().slot(invIndex);     // 1. 拿起背包里的鞘翅（与光标互换）
        InvUtils.click().slotArmor(2);       // 2. 放进胸甲槽（原鞘翅进光标）
        InvUtils.click().slot(invIndex);     // 3. 原鞘翅放回背包原槽（光标恢复原样）
    }
}
