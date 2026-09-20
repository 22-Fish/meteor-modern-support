package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.GuiTweaks;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorWindow;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 窗口正文：圆角设置生效时只圆下面两个角（上面接标题栏） */
@Mixin(value = WMeteorWindow.class, remap = false)
public abstract class MixinWMeteorWindow {
    @Redirect(method = "onRender", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/renderer/GuiRenderer;quad(DDDDLmeteordevelopment/meteorclient/utils/render/color/Color;)V"), require = 0)
    private void modernsupport$roundBody(GuiRenderer renderer, double x, double y, double width, double height, Color color) {
        GuiTweaks.quad(renderer, ((WWidget) (Object) this).theme, x, y, width, height, false, false, true, true, color);
    }
}
