package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.SettingAccessor;
import meteordevelopment.meteorclient.settings.Setting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 设置项构造完成后保存原始值 (构造时不知道所属模块, 不做翻译;
 * 由 MixinModule 构造/语言切换时通过 I18n.applySetting 按模块归属翻译)
 */
@Mixin(value = Setting.class, remap = false, priority = 999)
public abstract class MixinSetting implements SettingAccessor {
    @Shadow @Final @Mutable public String title;
    @Shadow @Final @Mutable public String description;

    @Unique private String originalTitle;
    @Unique private String originalDescription;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        originalTitle = title;
        originalDescription = description;
    }

    @Override
    @Unique
    public void setTitle(String t) {
        this.title = t;
    }

    @Override
    @Unique
    public void setDescription(String d) {
        this.description = d;
    }

    @Override
    @Unique
    public String getOriginalTitle() {
        return originalTitle != null ? originalTitle : title;
    }

    @Override
    @Unique
    public String getOriginalDescription() {
        return originalDescription != null ? originalDescription : description;
    }
}
