package fish22.modernsupport.modules;

import fish22.modernsupport.settings.PotionPickSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.function.Predicate;

/**
 * 一键药水 — 杂项模块
 *
 * <p>按下快捷键往正下方（俯仰角默认 90）扔一瓶药水
 *
 * <p>扔哪种药水由两个设置决定：「形态」选喷溅药水 / 滞留药水，
 * 「药水」选具体种类（迅捷药水、力量药水、迅捷药水 II 各算一种，默认「任意」）
 */
public class OneKeyPotion extends ThrowModule {

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
        .description("扔喷溅药水还是滞留药水")
        .defaultValue(Form.SPLASH)
        .build()
    );

    private final Setting<Potion> potion = sgGeneral.add(new PotionPickSetting(
        "药水",
        "扔哪一种药水，点开选（任意 = 背包里哪种能扔的药水都行）",
        null,
        () -> form.get().item,
        null
    ));

    public OneKeyPotion() {
        super(Categories.Misc, "一键药水", "按快捷键往正下方扔一瓶药水", 90);
    }

    @Override
    protected Predicate<ItemStack> target() {
        return stack -> {
            if (!stack.is(form.get().item)) return false;

            // 「任意」：形态对上就行
            Potion selected = potion.get();
            if (selected == null) return true;

            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            return contents != null && contents.is(BuiltInRegistries.POTION.wrapAsHolder(selected));
        };
    }
}
