package fish22.modernsupport.modules;

import fish22.modernsupport.settings.PotionUseListSetting;
import fish22.modernsupport.utils.BackpackUse;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 一键药水 — 杂项模块
 *
 * <p>和「一键使用物品」一样是列表：每个条目自己挑药水、自己绑快捷键，
 * 按下快捷键把对应药水往正下方（俯仰角默认 90，水平方向无所谓）扔出去
 *
 * <p>「形态」是模块级的，决定列表里用的是喷溅药水还是滞留药水；
 * 条目勾了「背包使用」就去背包里换（发包方式和目标槽位见 {@link BackpackUse}）
 */
public class OneKeyPotion extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 形态：决定用哪一种物品扔 */
    public enum Form {
        SPLASH("喷溅药水", Items.SPLASH_POTION),
        LINGERING("滞留药水", Items.LINGERING_POTION);

        private final String displayName;
        private final Item item;

        Form(String displayName, Item item) {
            this.displayName = displayName;
            this.item = item;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final Setting<Form> form = sgGeneral.add(new EnumSetting.Builder<Form>()
        .name("形态")
        .description("列表里用喷溅药水还是滞留药水")
        .defaultValue(Form.SPLASH)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("俯仰角")
        .description("90 = 正下方，0 = 水平，-90 = 正上方")
        .defaultValue(90)
        .range(-90, 90)
        .sliderRange(-90, 90)
        .build()
    );

    private final PotionUseListSetting potionList = sgGeneral.add(new PotionUseListSetting(
        "药水列表",
        "每个条目挑一种药水、绑一个快捷键，按快捷键扔对应药水。勾「背包使用」可以用背包里的",
        () -> form.get().item,
        null
    ));

    private final Setting<BackpackUse.Mode> mode = sgGeneral.add(new EnumSetting.Builder<BackpackUse.Mode>()
        .name("背包使用发包")
        .description("2次SWAP点击；4 次PICKUP点击")
        .defaultValue(BackpackUse.Mode.SWAP)
        .visible(() -> potionList.get().stream().anyMatch(e -> e.backpackUse))
        .build()
    );

    private final Setting<BackpackUse.TargetSlot> targetSlot = sgGeneral.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
        .name("目标槽位")
        .description("背包物品换到哪一格使用")
        .defaultValue(BackpackUse.TargetSlot.OFFHAND)
        .visible(() -> potionList.get().stream().anyMatch(e -> e.backpackUse))
        .build()
    );

    public OneKeyPotion() {
        super(Categories.Misc, "一键药水", "配置药水与快捷键，按下快捷键一键扔对应药水");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // 打开容器/界面时不触发，避免误操作
        if (mc.player.containerMenu.containerId != 0) return;

        for (PotionUseListSetting.Entry entry : potionList.get()) {
            // 快捷键上升沿（按下瞬间触发一次，按住不重复触发）
            boolean pressed = entry.keybind.isPressed();
            if (pressed && !entry.prevPressed) throwPotion(entry);
            entry.prevPressed = pressed;
        }
    }

    /** 扔一次：俯仰角用设置给的，水平方向就用当前朝向 */
    private void throwPotion(PotionUseListSetting.Entry entry) {
        float yaw = mc.player.getYRot();
        float throwPitch = pitch.get().floatValue();
        Predicate<ItemStack> target = target(entry);

        if (entry.backpackUse) {
            BackpackUse.useAngled(target, mode.get(), targetSlot.get(), yaw, throwPitch);
        } else {
            BackpackUse.fromHotbar(target, hand -> BackpackUse.useAngled(hand, yaw, throwPitch));
        }
    }

    /** 这个条目认哪种物品：「形态」对上，且药水种类对上（条目选「任意」就只看形态） */
    private Predicate<ItemStack> target(PotionUseListSetting.Entry entry) {
        return stack -> {
            if (!stack.is(form.get().item)) return false;

            // 「任意」：形态对上就行
            if (entry.potion == null) return true;

            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            return contents != null && contents.is(BuiltInRegistries.POTION.wrapAsHolder(entry.potion));
        };
    }
}
