package fish22.modernsupport.mixin;

import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 读原版 FontManager 的字体集表
 * 标准字体渲染缺字时, 从 minecraft:default 实时取一份字形源兜底
 * (资源重载后原版会换成新的 FontSet, 所以每次现查, 不缓存)
 */
@Mixin(value = FontManager.class, remap = false)
public interface FontManagerAccessor {
    @Accessor("fontSets")
    Map<Identifier, FontSet> meteor$fontSets();
}
