package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.I18n;
import meteordevelopment.meteorclient.gui.DefaultSettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 设置分组标题翻译：分组标题（General / Colors / ...）走翻译表 Group.Meteor.&lt;名字&gt;
 *
 * <p>{@code require = 0}：万一 Meteor 改了 GUI 工厂的内部结构，注入失败也只是分组标题
 * 不翻译，不会让游戏起不来。
 */
@Mixin(value = DefaultSettingsWidgetFactory.class, remap = false)
public abstract class MixinDefaultSettingsWidgetFactory {

    @Redirect(
        method = "group",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;section(Ljava/lang/String;Z)Lmeteordevelopment/meteorclient/gui/widgets/containers/WSection;"
        ),
        require = 0
    )
    private WSection modernsupport$translateGroupTitle(GuiTheme theme, String name, boolean expanded) {
        return theme.section(I18n.groupName(name), expanded);
    }
}
