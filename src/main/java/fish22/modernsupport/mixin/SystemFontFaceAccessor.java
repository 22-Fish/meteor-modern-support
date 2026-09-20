package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.renderer.text.SystemFontFace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

/** 读系统字体文件路径 */
@Mixin(value = SystemFontFace.class, remap = false)
public interface SystemFontFaceAccessor {
    @Accessor("path")
    Path meteor$path();
}
