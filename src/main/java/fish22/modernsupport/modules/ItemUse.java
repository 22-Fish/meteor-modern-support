package fish22.modernsupport.modules;

import fish22.modernsupport.settings.ItemUseListSetting;
import fish22.modernsupport.utils.BackpackUse;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 一键使用物品 — 杂项模块
 *
 * <p>在「物品列表」中配置物品与快捷键，按下快捷键一键使用对应物品：
 * <ul>
 *   <li>不勾选「背包使用」：快捷栏静默使用（副手优先，其次热栏静默切换使用后换回），
 *       等价于 Meteor 的静默使用方式</li>
 *   <li>勾选「背包使用」：同一 tick 先发「背包物品 ↔ 手上物品」交换包，
 *       再发使用包，再发交换回去的包，物品可放在背包任意位置</li>
 * </ul>
 * 物品不在背包/快捷栏时直接不触发，不弹任何提示。
 */
public class ItemUse extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final ItemUseListSetting itemList = sgGeneral.add(new ItemUseListSetting(
        "物品列表",
        "按快捷键一键使用对应物品。开启背包使用可以使用背包中的物品",
        null
    ));

    public ItemUse() {
        super(Categories.Misc, "一键使用物品", "配置物品与快捷键，按下快捷键一键使用对应物品（可勾选背包使用，自动从背包交换到手上）");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // 打开容器/界面时不触发，避免误操作
        if (mc.player.containerMenu.containerId != 0) return;

        // 背包交换使用后的换回确认（每 tick 检查，未换回则重试）
        BackpackUse.tick();

        for (ItemUseListSetting.ItemUseEntry entry : itemList.get()) {
            if (entry.item == null || entry.item == Items.AIR) continue;

            // 快捷键上升沿（按下瞬间触发一次，按住不重复触发）
            boolean pressed = entry.keybind.isPressed();
            if (pressed && !entry.prevPressed) {
                useItem(entry);
            }
            entry.prevPressed = pressed;
        }
    }

    private void useItem(ItemUseListSetting.ItemUseEntry entry) {
        if (entry.backpackUse) {
            // 背包使用：背包任意位置交换到手上使用（含换回确认重试）
            BackpackUse.use(stack -> stack.is(entry.item));
        } else {
            useFromHotbar(entry.item);
        }
    }

    /** 快捷栏静默使用：副手优先，其次热栏（静默切换使用后换回），找不到不触发 */
    private void useFromHotbar(Item item) {
        if (mc.player.getOffhandItem().is(item)) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            return;
        }

        FindItemResult result = InvUtils.findInHotbar(item);
        if (!result.found()) return;
        if (result.isMainHand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            return;
        }

        InvUtils.swap(result.slot(), true);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        InvUtils.swapBack();
    }
}
