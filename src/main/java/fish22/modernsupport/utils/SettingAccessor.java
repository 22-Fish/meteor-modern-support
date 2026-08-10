package fish22.modernsupport.utils;

/**
 * 由 MixinSetting 实现, 提供修改 final title/description 及读取原始值的能力
 */
public interface SettingAccessor {
    void setTitle(String title);
    void setDescription(String description);
    String getOriginalTitle();
    String getOriginalDescription();
}
