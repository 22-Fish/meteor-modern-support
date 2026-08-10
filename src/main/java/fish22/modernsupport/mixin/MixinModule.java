package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.I18n;
import fish22.modernsupport.utils.ModuleAccessor;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 模块构造完成后, 用语言文件替换 title/description (若当前语言有对应翻译)
 * title/description 是 final 字段, 通过 @Shadow @Final @Mutable 重写
 */
@Mixin(value = Module.class, remap = false, priority = 999)
public abstract class MixinModule implements ModuleAccessor {
    @Shadow @Final @Mutable public String title;
    @Shadow @Final @Mutable public String description;

    /** 保存未被翻译前的原始值, 语言文件缺失对应键时回退到原始文字 */
    @Unique private String originalTitle;
    @Unique private String originalDescription;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        if (originalTitle == null) originalTitle = title;
        if (originalDescription == null) originalDescription = description;
        I18n.applyModule((Module) (Object) this);
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
