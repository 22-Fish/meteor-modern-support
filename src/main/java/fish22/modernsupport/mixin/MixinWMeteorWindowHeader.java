package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.GuiTweaks;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 窗口标题栏：圆角设置生效时只圆上面两个角 */
@Mixin(targets = "meteordevelopment.meteorclient.gui.themes.meteor.widgets.WMeteorWindow$WMeteorHeader", remap = false)
public abstract class MixinWMeteorWindowHeader {
    @Redirect(method = "onRender", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/renderer/GuiRenderer;quad(Lmeteordevelopment/meteorclient/gui/widgets/WWidget;Lmeteordevelopment/meteorclient/utils/render/color/Color;)V"), require = 0)
    private void modernsupport$roundHeader(GuiRenderer renderer, WWidget widget, Color color) {
        GuiTweaks.quad(renderer, widget.theme, widget.x, widget.y, widget.width, widget.height, true, true, false, false, color);
    }
}
