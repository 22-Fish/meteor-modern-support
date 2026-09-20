package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.ThemeAccessor;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorModule;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 模块按钮的三个可调项：
 * 模块名边距（原本写死 scale(4)）、左侧高亮宽度（原本写死 scale(2)）、模块名字符间距（原本 0）。
 */
@Mixin(value = WMeteorModule.class, remap = false)
public abstract class MixinWMeteorModule {
    @Shadow @Final private String title;
    @Shadow private double titleWidth;

    @Unique private double msLastSpacing;
    @Unique private boolean msSpacingKnown;

    /** 模块名边距 */
    @Inject(method = "pad", at = @At("HEAD"), cancellable = true)
    private void modernsupport$pad(CallbackInfoReturnable<Double> cir) {
        MeteorGuiTheme theme = modernsupport$theme();
        ThemeAccessor accessor = ThemeAccessor.of(theme);
        if (accessor == null) return;

        cir.setReturnValue(theme.scale(accessor.getModuleNamePadding().get()));
    }

    /** 字符间距要算进模块宽度里 */
    @Inject(method = "onCalculateSize", at = @At("TAIL"))
    private void modernsupport$letterSpacing(CallbackInfo ci) {
        GuiTheme theme = ((WWidget) (Object) this).theme;
        double spacing = modernsupport$spacing();

        if (msSpacingKnown && msLastSpacing == spacing && titleWidth != 0) return;
        msSpacingKnown = true;
        msLastSpacing = spacing;

        titleWidth = theme.textWidth(title) + spacing * Math.max(0, title.length() - 1);
        ((WWidget) (Object) this).width = ((WMeteorModule) (Object) this).pad() * 2 + titleWidth;
    }

    /** 按字符间距绘制模块名 */
    @Redirect(method = "onRender", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/renderer/GuiRenderer;text(Ljava/lang/String;DDLmeteordevelopment/meteorclient/utils/render/color/Color;Z)V"), require = 0)
    private void modernsupport$spacedText(GuiRenderer renderer, String text, double x, double y, Color color, boolean titleStyle) {
        double spacing = modernsupport$spacing();
        if (spacing == 0) {
            renderer.text(text, x, y, color, titleStyle);
            return;
        }

        GuiTheme theme = ((WWidget) (Object) this).theme;
        for (int i = 0; i < text.length(); i++) {
            String character = String.valueOf(text.charAt(i));
            renderer.text(character, x, y, color, titleStyle);
            x += theme.textWidth(character) + spacing;
        }
    }

    /** 左侧高亮宽度 */
    @Redirect(method = "onRender", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/themes/meteor/MeteorGuiTheme;scale(D)D"), require = 0)
    private double modernsupport$highlightWidth(MeteorGuiTheme theme, double value) {
        ThemeAccessor accessor = ThemeAccessor.of(theme);
        if (accessor != null && value == 2.0) return theme.scale(accessor.getHighlightWidth().get());

        return theme.scale(value);
    }

    @Unique
    private MeteorGuiTheme modernsupport$theme() {
        return (MeteorGuiTheme) ((WWidget) (Object) this).theme;
    }

    @Unique
    private double modernsupport$spacing() {
        ThemeAccessor accessor = ThemeAccessor.of(modernsupport$theme());
        if (accessor == null) return 0;

        return accessor.getLetterSpacing().get();
    }
}
