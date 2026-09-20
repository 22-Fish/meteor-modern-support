package fish22.modernsupport.mixin;

import fish22.modernsupport.settings.ActionSetting;
import fish22.modernsupport.font.FontSharpness;
import fish22.modernsupport.font.StandardFont;
import fish22.modernsupport.utils.I18n;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.ProvidedStringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

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

        // 刷新按钮: 修改 meteor-lang 下的语言文件后, 点击重新加载并立即生效。
        // 重新翻译后 GUI 文本不会实时重建, 自动关闭界面, 重开即可看到新文本
        sgLanguage.add(new ActionSetting(
            "reload-languages",
            "Reload: re-read language files from meteor-lang/ and re-apply translations immediately.",
            "Reload",
            () -> {
                I18n.reloadAndApply();
                mc.setScreen(null);
            },
            null
        ));

        // 字体组: 放在设置页最上面, 并把 Meteor 自带的「自定义字体 / 字体选择」一起并进来
        SettingGroup sgFont = config.settings.createGroup("Font");

        SettingGroup sgVisual = config.settings.getGroup("Visual");
        if (sgVisual != null) {
            List<Setting<?>> visualSettings = ((SettingGroupAccessor) sgVisual).getSettings();
            visualSettings.remove(config.customFont);
            visualSettings.remove(config.font);
        }

        sgFont.add(config.customFont);
        sgFont.add(config.font);

        StandardFont.enabled = sgFont.add(new BoolSetting.Builder()
            .name("standard-font")
            .description("Render the font selected in Meteor's font setting through the vanilla FreeType pipeline: font size is the em size, glyphs are rasterized on demand, missing characters fall back to the vanilla font.")
            .defaultValue(false)
            .visible(() -> config.customFont.get())
            .onChanged(v -> StandardFont.markDirty())
            .build()
        );

        StandardFont.size = sgFont.add(new DoubleSetting.Builder()
            .name("standard-font-size")
            .description("Font size in em pixels. The same size looks the same in every font.")
            .defaultValue(9)
            .range(4, 32)
            .sliderMax(16)
            .visible(() -> config.customFont.get() && StandardFont.isEnabled())
            .onChanged(v -> StandardFont.markDirty())
            .build()
        );

        StandardFont.sharpness = sgFont.add(new EnumSetting.Builder<FontSharpness>()
            .name("standard-font-sharpness")
            .description("Compress the antialiasing grey edges so small text reads as solid rather than washed out. Off keeps the raw rendering; the further right, the more solid the strokes.")
            .defaultValue(FontSharpness.Light)
            .visible(() -> config.customFont.get() && StandardFont.isEnabled())
            .onChanged(v -> StandardFont.markDirty())
            .build()
        );

        // 挪到第一个: 设置页最顶上就是「字体」
        List<SettingGroup> groups = config.settings.groups;
        groups.remove(sgFont);
        groups.add(0, sgFont);
    }
}
