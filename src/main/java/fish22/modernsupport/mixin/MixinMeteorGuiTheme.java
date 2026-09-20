package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.ThemeAccessor;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 给 Meteor 主题的 General 分组追加 4 个 GUI 设置（模块名边距 / 高亮宽度 / 字符间距 / 圆角），
 * 默认值就是原本写死在代码里的值，所以默认状态下表现和原版完全一致。
 */
@Mixin(value = MeteorGuiTheme.class, remap = false)
public abstract class MixinMeteorGuiTheme implements ThemeAccessor {
    @Shadow @Final private SettingGroup sgGeneral;

    @Unique private Setting<Double> msModuleNamePadding;
    @Unique private Setting<Double> msHighlightWidth;
    @Unique private Setting<Double> msLetterSpacing;
    @Unique private Setting<Double> msCornerRadius;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void modernsupport$addSettings(CallbackInfo ci) {
        msModuleNamePadding = sgGeneral.add(new DoubleSetting.Builder()
            .name("module-name-padding")
            .description("Padding of module names.")
            .defaultValue(4).min(0).sliderRange(0, 10)
            .onChanged(v -> modernsupport$invalidate())
            .build()
        );

        msHighlightWidth = sgGeneral.add(new DoubleSetting.Builder()
            .name("highlight-width")
            .description("Width of the side highlight on active modules.")
            .defaultValue(2).min(0).sliderRange(0, 10)
            .onChanged(v -> modernsupport$invalidate())
            .build()
        );

        msLetterSpacing = sgGeneral.add(new DoubleSetting.Builder()
            .name("letter-spacing")
            .description("Spacing between module name characters.")
            .defaultValue(0).min(-4).sliderRange(-4, 8)
            .onChanged(v -> modernsupport$invalidate())
            .build()
        );

        msCornerRadius = sgGeneral.add(new DoubleSetting.Builder()
            .name("corner-radius")
            .description("Roundness of GUI window corners.")
            .defaultValue(0).min(0).sliderRange(0, 8)
            .onChanged(v -> modernsupport$invalidate())
            .build()
        );
    }

    @Unique
    private void modernsupport$invalidate() {
        if (mc.screen instanceof WidgetScreen screen) screen.invalidate();
    }

    @Override
    @Unique
    public Setting<Double> getModuleNamePadding() {
        return msModuleNamePadding;
    }

    @Override
    @Unique
    public Setting<Double> getHighlightWidth() {
        return msHighlightWidth;
    }

    @Override
    @Unique
    public Setting<Double> getLetterSpacing() {
        return msLetterSpacing;
    }

    @Override
    @Unique
    public Setting<Double> getCornerRadius() {
        return msCornerRadius;
    }
}
