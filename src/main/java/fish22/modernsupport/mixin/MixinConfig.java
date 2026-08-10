package fish22.modernsupport.mixin;

import fish22.modernsupport.settings.ActionSetting;
import fish22.modernsupport.utils.I18n;
import meteordevelopment.meteorclient.settings.ProvidedStringSetting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Meteor 设置主界面 (Config) 添加"语言"设置 (下拉列表):
 *  选项自动拉取游戏目录 meteor-lang/ 下所有文件夹 (文件夹名 = 语言代码)
 *  默认值: 首次启动自动选择与 Minecraft 语言匹配的语言, 无匹配用英语
 *  语言文件位于游戏目录 meteor-lang/<语言代码>/*.json, 也可用 mod 内置翻译
 */
@Mixin(value = Config.class, remap = false)
public abstract class MixinConfig {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        Config config = (Config) (Object) this;
        SettingGroup sgLanguage = config.settings.createGroup("Language");

        sgLanguage.add(new ProvidedStringSetting.Builder()
            .name("language")
            .description("Language. On first launch, automatically picks the language matching Minecraft's (falls back to English). Switching the language applies immediately; after editing files in meteor-lang/<language-name>/ (e.g. 简体中文, English), switch away and back to reload them. The folder name is the language name, and all JSON files inside a folder are merged.")
            .defaultValue(I18n.suggestDefaultLang())
            .supplier(() -> I18n.availableLangs().toArray(new String[0]))
            .onChanged(I18n::setLang)
            .build()
        );

        // 刷新按钮: 修改 meteor-lang 下的语言文件后, 点击重新加载并立即生效
        sgLanguage.add(new ActionSetting(
            "reload-languages",
            "Reload: re-read language files from meteor-lang/ and re-apply translations immediately.",
            "Reload",
            I18n::reloadAndApply,
            null
        ));
    }
}
