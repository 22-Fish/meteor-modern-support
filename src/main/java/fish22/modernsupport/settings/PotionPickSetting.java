package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.function.Supplier;

/**
 * 药水种类选择设置：UI 和一键使用物品同一套（按钮显示药水名，点开 mod 自己的「选择药水」界面）
 *
 * <p>值 = 注册表里的一种药水（迅捷药水、力量药水、迅捷药水 II 这种各算一个），
 * {@code null} = 任意（背包里哪种能扔的药水都能用）
 *
 * <p>按钮上的名字按「形态」拼出来（喷溅型迅捷药水 / 滞留型迅捷药水），
 * 形态对应的物品由调用方通过 {@code form} 给
 */
public class PotionPickSetting extends Setting<Potion> {

    private final Supplier<Item> form;

    public PotionPickSetting(String name, String description, Potion defaultValue, Supplier<Item> form, IVisible visible) {
        super(name, description, defaultValue, null, null, visible);
        this.form = form;
    }

    /** 形态对应的物品（喷溅药水 / 滞留药水） */
    public Item form() {
        return form.get();
    }

    /** 按钮上显示的名字 */
    public String displayName() {
        return name(value, form());
    }

    /** 某种形态 + 某种药水的物品（potion 为 null 时只带形态，用在「任意」那一行） */
    public static ItemStack stack(Potion potion, Item form) {
        if (potion == null) return new ItemStack(form);
        return PotionContents.createItemStack(form, BuiltInRegistries.POTION.wrapAsHolder(potion));
    }

    /** 药水在游戏里的名字（喷溅型迅捷药水这种），按当前语言 */
    public static String name(Potion potion, Item form) {
        if (potion == null) return "任意";
        return stack(potion, form).getHoverName().getString();
    }

    @Override
    protected Potion parseImpl(String str) {
        return null;
    }

    @Override
    protected boolean isValueValid(Potion value) {
        return true;
    }

    @Override
    protected CompoundTag save(CompoundTag tag) {
        if (value != null) tag.putString("potion", BuiltInRegistries.POTION.getKey(value).toString());
        return tag;
    }

    @Override
    protected Potion load(CompoundTag tag) {
        Potion loaded = null;
        try {
            loaded = BuiltInRegistries.POTION.getOptional(Identifier.parse(tag.getStringOr("potion", ""))).orElse(null);
        } catch (Exception ignored) {
            // 配置里的药水 ID 非法：当成「任意」，不影响其余配置加载
        }

        // meteor 的 load 要自己把值写回去（fromTag 只拿返回值丢掉，不会赋值）
        set(loaded);
        return get();
    }
}
