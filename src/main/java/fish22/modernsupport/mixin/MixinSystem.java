package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/**
 * 阻止 Meteor 再往自带的 modules.nbt 写模块设置
 *
 * <p>模块设置现在只由 ModuleConfigs 存到 config/&lt;配置名&gt;.nbt，
 * modules.nbt 写的地方有三个：退出钩子、离开世界 Systems.onGameLeft、我们自己的 AutoSave，
 * 全部走 System.save 的默认目录重载，在这里一次拦掉
 *
 * <p>只拦「默认目录 + Modules」：profiles 功能用的是带 folder 的重载
 * (profiles/&lt;名字&gt;/modules.nbt)，不拦，保持原样可用
 */
@Mixin(value = System.class, remap = false)
public abstract class MixinSystem {

    @Inject(method = "save(Ljava/io/File;)V", at = @At("HEAD"), cancellable = true)
    private void modernsupport$blockModulesNbt(File folder, CallbackInfo ci) {
        // this 在 mixin 里编译期不认识继承关系，先当 Object 再判断
        if (folder == null && ((Object) this) instanceof Modules) ci.cancel();
    }
}
