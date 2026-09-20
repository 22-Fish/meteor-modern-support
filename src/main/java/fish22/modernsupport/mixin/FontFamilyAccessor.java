package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** 读 Meteor 字体家族里已有的字体 (扫描系统字体时用来跳过已经加载过的文件) */
@Mixin(value = FontFamily.class, remap = false)
public interface FontFamilyAccessor {
    @Accessor("fonts")
    List<FontFace> meteor$fonts();
}
