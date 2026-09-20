package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.FallbackTextRenderer;
import fish22.modernsupport.font.StandardFont;
import fish22.modernsupport.font.StandardTextRenderer;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.systems.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 文字渲染器选择:
 *  自定义字体关闭 → 原版渲染器 (原版行为)
 *  自定义字体开启 + 标准字体渲染 → {@link StandardTextRenderer} (系统字体走原版 FreeType 管线, 桌面软件那样排版)
 *  自定义字体开启 → {@link FallbackTextRenderer} (Meteor 自定义字体渲染, 缺字自动回退原版字体, 中文不会变空白)
 * TextRenderer 是接口, mixin 必须声明为 interface
 */
@Mixin(value = TextRenderer.class, remap = false)
public interface MixinTextRenderer {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private static void onGet(CallbackInfoReturnable<TextRenderer> cir) {
        Config config = Config.get();

        if (config != null && config.customFont.get()) {
            if (StandardFont.isEnabled()) cir.setReturnValue(StandardTextRenderer.INSTANCE);
            else if (Fonts.RENDERER != null) cir.setReturnValue(FallbackTextRenderer.INSTANCE);
            else cir.setReturnValue(VanillaTextRenderer.INSTANCE);
            return;
        }

        cir.setReturnValue(VanillaTextRenderer.INSTANCE);
    }
}
