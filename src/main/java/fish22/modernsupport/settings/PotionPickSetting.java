package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;
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

    /** 药水的效果和时长（比如「迅捷 I 3:00」，多种效果用「, 」隔开）；没效果（水瓶这种）返回空串 */
    public static String effectText(Potion potion) {
        StringBuilder sb = new StringBuilder();
        for (MobEffectInstance effect : effects(potion)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(effectName(effect));
        }
        return sb.toString();
    }

    /** 只报等级和时长（比如「8:00」「II 1:30」），用来区分同名的普通/延长/强化药水；没效果返回空串 */
    public static String durationText(Potion potion) {
        StringBuilder sb = new StringBuilder();
        for (MobEffectInstance effect : effects(potion)) {
            if (sb.length() > 0) sb.append('/');
            sb.append(levelDuration(effect));
        }
        return sb.toString();
    }

    /** 某种药水的全部效果（用喷溅药水的物品去取，和形态无关） */
    private static Iterable<MobEffectInstance> effects(Potion potion) {
        if (potion == null) return List.of();
        PotionContents contents = stack(potion, Items.SPLASH_POTION).get(DataComponents.POTION_CONTENTS);
        return contents == null ? List.of() : contents.getAllEffects();
    }

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /** 效果名 + 等级 + 时长 */
    private static String effectName(MobEffectInstance effect) {
        return effect.getEffect().value().getDisplayName().getString() + " " + levelDuration(effect);
    }

    /** 等级（I 不标）+ 时长 */
    private static String levelDuration(MobEffectInstance effect) {
        int level = effect.getAmplifier() + 1;
        String time = duration(effect);
        if (level <= 1) return time;
        return (level <= ROMAN.length ? ROMAN[level - 1] : String.valueOf(level)) + " " + time;
    }

    /** 时长：瞬间效果给「瞬间」，其余给「分:秒」 */
    private static String duration(MobEffectInstance effect) {
        int ticks = effect.getDuration();
        if (ticks <= 1) return "瞬间";
        int seconds = ticks / 20;
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
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
