package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 访问 {@link SettingGroup} 内部的设置列表，用于把新增设置插入到指定位置
 * （Meteor 只提供末尾追加，没有按位置插入的能力）。
 */
@Mixin(value = SettingGroup.class, remap = false)
public interface SettingGroupAccessor {
    @Accessor("settings")
    List<Setting<?>> getSettings();
}
