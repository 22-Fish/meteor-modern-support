package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.SettingGroupRename;
import meteordevelopment.meteorclient.settings.SettingGroup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 分组名改写能力（name 是 final，靠 @Mutable 放开），
 * 给 MixinHandView 把官方「Arm」分组改名成「Main Arm」用
 */
@Mixin(value = SettingGroup.class, remap = false)
public abstract class MixinSettingGroup implements SettingGroupRename {
    @Shadow
    @Final
    @Mutable
    public String name;

    @Override
    @Unique
    public void setGroupName(String newName) {
        this.name = newName;
    }
}
