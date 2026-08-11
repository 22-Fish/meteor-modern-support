package fish22.modernsupport.mixin;

import fish22.modernsupport.gui.ConfigSection;
import fish22.modernsupport.gui.PageConfigSection;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Settings.tick 注入：
 * Meteor 会在设置可见性变化时 clear() 整个设置列表再重建，这会把我们
 * 追加的"页面配置"/"配置设置"分组一并清掉。重建设置列表时同样追加
 * 这两个分组，保证区块始终存在于列表底部。
 *
 * <p>只处理 Config 主设置列表（{@link Config#get()}.settings），
 * 模块自己的设置界面（module.settings）不受影响。
 */
@Mixin(value = Settings.class, remap = false)
public abstract class MixinSettings {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;settings(Lmeteordevelopment/meteorclient/settings/Settings;)Lmeteordevelopment/meteorclient/gui/widgets/WWidget;"))
    private WWidget redirectTickSettings(GuiTheme theme, Settings settings) {
        WWidget widget = theme.settings(settings);
        // 只给 Config 主设置列表追加页面配置/配置设置分组（模块设置界面不动）
        if (settings == Config.get().settings && widget instanceof WContainer container) {
            PageConfigSection.addToSettings(container, theme);
            ConfigSection.addToSettings(container, theme);
        }
        return widget;
    }
}
