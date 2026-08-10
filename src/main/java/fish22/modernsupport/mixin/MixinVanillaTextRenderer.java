package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版渲染器逐字符独立缩放, 保证中文等宽文字显示正确
 */
@Mixin(value = VanillaTextRenderer.class, remap = false)
public abstract class MixinVanillaTextRenderer {
    @Shadow
    public boolean scaleIndividually;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(String text, double x, double y, Color color, boolean shadow, CallbackInfoReturnable<Double> cir) {
        this.scaleIndividually = true;
    }
}
