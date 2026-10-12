package fish22.modernsupport.utils;

/**
 * 由 MixinSettingGroup 实现, 提供改分组名的能力 (SettingGroup.name 是 final)
 */
public interface SettingGroupRename {
    void setGroupName(String newName);
}
