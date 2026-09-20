package fish22.modernsupport.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import fish22.modernsupport.font.StandardFont;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 字形灰度后处理
 *
 * <p>原版把 FreeType 渲染出来的灰度字形拷进图集时, 我们在这里过一遍灰阶查表,
 * 让低分辨率下的小字更实 (细节见 FontSharpness)。只对自己建的字脸生效,
 * 原版字体和别的模组的字体都不碰。
 */
@Mixin(value = NativeImage.class, remap = false)
public abstract class MixinNativeImage {
    @Shadow
    private long pixels;

    @Inject(method = "copyFromFont", at = @At("TAIL"))
    private void onCopyFromFont(FT_Face face, int index, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;

        int[] lut = StandardFont.sharpnessLut(face);
        if (lut == null) return;

        NativeImage image = (NativeImage) (Object) this;
        int count = image.getWidth() * image.getHeight();

        for (int i = 0; i < count; i++) {
            long address = pixels + i;
            MemoryUtil.memPutByte(address, (byte) lut[MemoryUtil.memGetByte(address) & 0xFF]);
        }
    }
}
