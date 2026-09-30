package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.AutoSave;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /** 我们的鞘翅飞行模块名（老配置里叫 elytra-fly，那是 Meteor 官方模块名） */
    private static final String NEW_ELYTRA_FLY_NAME = "鞘翅飞行";

    /** Meteor 官方鞘翅飞行的模块名 */
    private static final String OLD_ELYTRA_FLY_NAME = "elytra-fly";

    @Inject(method = "load(Ljava/io/File;)V", at = @At("HEAD"))
    private void onLoadHead(CallbackInfo ci) {
        AutoSave.setLoading(true);
    }

    @Inject(method = "load(Ljava/io/File;)V", at = @At("RETURN"))
    private void onLoadReturn(CallbackInfo ci) {
        AutoSave.setLoading(false);
    }

    /**
     * 老配置迁移：把 Meteor 官方鞘翅飞行的模块配置块改名成我们模块的名字
     *
     * <p>鞘翅飞行从「mixin 进官方模块」改成独立模块之后模块名也换了，老配置里那一块（设置、键位、
     * 开关状态）不看这一步就全丢。改名之后里面的设置名还是老名字，由 {@code MixinSettings}
     * 的迁移继续处理（简单控制模式、无限鞘翅、分组名那些）。
     *
     * <p>新名字的配置块已经存在时不改名（防止以后再把官方那个空块又搬过来）。
     */
    @Inject(method = "fromTag", at = @At("HEAD"))
    private void modernsupport$migrateElytraFlyModule(CompoundTag tag, CallbackInfoReturnable<Modules> cir) {
        try {
            ListTag modules = tag.getListOrEmpty("modules");
            if (modules.isEmpty()) return;

            for (Tag moduleTagRaw : modules) {
                if (moduleTagRaw instanceof CompoundTag moduleTag
                    && moduleTag.getStringOr("name", "").equals(NEW_ELYTRA_FLY_NAME)) {
                    return; // 已经是新配置了
                }
            }

            for (Tag moduleTagRaw : modules) {
                if (moduleTagRaw instanceof CompoundTag moduleTag
                    && moduleTag.getStringOr("name", "").equals(OLD_ELYTRA_FLY_NAME)) {
                    moduleTag.putString("name", NEW_ELYTRA_FLY_NAME);
                }
            }
        } catch (Exception ignored) {
            // 迁移失败只影响老配置读入，不影响正常加载
        }
    }
}
