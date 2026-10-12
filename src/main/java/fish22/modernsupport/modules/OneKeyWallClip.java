package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.systems.modules.Categories;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * 一键穿墙 — 战斗模块
 *
 * <p>按下快捷键向前下方扔一颗末影珍珠，角度由「俯仰角」给（默认 85，越小越平）
 *
 * <p>按着移动键扔就是朝那个方向穿（A左 / D右 / S后 / W前），方便往侧面和后方穿墙
 */
public class OneKeyWallClip extends ThrowModule {

    public OneKeyWallClip() {
        super(Categories.Combat, "一键穿墙", "按快捷键向前下方扔一颗末影珍珠", 85);
    }

    @Override
    protected Predicate<ItemStack> target() {
        return stack -> stack.is(Items.ENDER_PEARL);
    }
}
