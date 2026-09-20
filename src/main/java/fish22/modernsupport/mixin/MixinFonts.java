package fish22.modernsupport.mixin;

import fish22.modernsupport.ModernSupport;
import fish22.modernsupport.font.StandardFont;
import fish22.modernsupport.font.SystemFontScanner;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Meteor 字体列表刷新完之后:
 *  1. 补扫系统字体 (.otf / .ttc / Meteor 没读进来的 .ttf)
 *  2. 换了字体就标记标准字体重建
 */
@Mixin(value = Fonts.class, remap = false)
public class MixinFonts {
    @Inject(method = "refresh", at = @At("TAIL"))
    private static void onRefresh(CallbackInfo ci) {
        try {
            SystemFontScanner.scan();
        } catch (Throwable t) {
            ModernSupport.LOG.warn("扫描系统字体失败", t);
        }
    }

    @Inject(method = "load", at = @At("TAIL"))
    private static void onLoad(FontFace fontFace, CallbackInfo ci) {
        StandardFont.markDirty();
    }
}
