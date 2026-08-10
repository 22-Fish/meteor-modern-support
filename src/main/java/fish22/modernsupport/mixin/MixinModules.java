package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.AutoSave;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/**
 * 配置加载期间禁用自动保存 mixin
 *
 * <p>Meteor 加载模块配置时会对所有设置调用 reset()、触发模块开关事件，
 * 若此时自动保存会把加载中的默认值覆盖到磁盘，导致配置丢失。
 * 在 {@link Modules#load} 期间标记 loading，暂停自动保存。
 */
@Mixin(value = Modules.class, remap = false)
public abstract class MixinModules {

    @Inject(method = "load(Ljava/io/File;)V", at = @At("HEAD"))
    private void onLoadHead(CallbackInfo ci) {
        AutoSave.setLoading(true);
    }

    @Inject(method = "load(Ljava/io/File;)V", at = @At("RETURN"))
    private void onLoadReturn(CallbackInfo ci) {
        AutoSave.setLoading(false);
    }
}
