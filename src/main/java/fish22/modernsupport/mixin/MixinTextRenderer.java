package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 强制 Meteor 使用原版文字渲染器 (支持中文等非 ASCII 字符, 否则中文显示为方框/乱码)
 * TextRenderer 是接口, mixin 必须声明为 interface
 */
@Mixin(value = TextRenderer.class, remap = false)
public interface MixinTextRenderer {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private static void onGet(CallbackInfoReturnable<TextRenderer> cir) {
        cir.setReturnValue(VanillaTextRenderer.INSTANCE);
    }
}
