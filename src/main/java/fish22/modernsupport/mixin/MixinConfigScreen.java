package fish22.modernsupport.mixin;

import fish22.modernsupport.gui.ConfigSection;
import fish22.modernsupport.gui.PageConfigSection;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.builtin.ConfigTab;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.settings.Settings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Config 页注入：创建设置列表时在末尾追加"页面配置"（模块分页管理）和"配置设置"（配置分块）。
 *
 * <p>设置列表首次渲染在这里追加；Settings.tick 因可见性变化重建列表时
 * 由 {@link MixinSettings} 同样补回（两个区块始终存在于列表底部）。
 */
@Mixin(value = ConfigTab.ConfigScreen.class, remap = false)
public abstract class MixinConfigScreen {

    @Redirect(method = "initWidgets", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;settings(Lmeteordevelopment/meteorclient/settings/Settings;)Lmeteordevelopment/meteorclient/gui/widgets/WWidget;"))
    private WWidget redirectSettings(GuiTheme theme, Settings settings) {
        WWidget widget = theme.settings(settings);
        if (widget instanceof WContainer container) {
            PageConfigSection.addToSettings(container, theme);
            ConfigSection.addToSettings(container, theme);
        }
        return widget;
    }
}
