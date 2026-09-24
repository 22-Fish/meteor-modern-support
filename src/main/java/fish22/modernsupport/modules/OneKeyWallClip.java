package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.systems.modules.Categories;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * 一键卡墙 — 杂项模块
 *
 * <p>按下快捷键向前下方扔一颗末影珍珠，角度由「俯仰角」给（默认 85，越小越平）
 */
public class OneKeyWallClip extends ThrowModule {

    public OneKeyWallClip() {
        super(Categories.Misc, "一键卡墙", "按快捷键向前下方扔一颗末影珍珠", 85);
    }

    @Override
    protected Predicate<ItemStack> target() {
        return stack -> stack.is(Items.ENDER_PEARL);
    }
}
