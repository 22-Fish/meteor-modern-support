package fish22.modernsupport.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读原版 FontManager (标准字体渲染要拿原版字体集做缺字兜底) */
@Mixin(value = Minecraft.class, remap = false)
public interface MinecraftAccessor {
    @Accessor("fontManager")
    FontManager meteor$fontManager();
}
