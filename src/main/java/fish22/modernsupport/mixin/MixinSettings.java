package fish22.modernsupport.mixin;

import fish22.modernsupport.gui.ConfigSection;
import fish22.modernsupport.gui.PageConfigSection;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Settings.tick 注入：
 * Meteor 会在设置可见性变化时 clear() 整个设置列表再重建，这会把我们
 * 追加的"页面配置"/"配置设置"分组一并清掉。重建设置列表时同样追加
 * 这两个分组，保证区块始终存在于列表底部。
 *
 * <p>只处理 Config 主设置列表（{@link Config#get()}.settings），
 * 模块自己的设置界面（module.settings）不受影响。
 */
@Mixin(value = Settings.class, remap = false)
public abstract class MixinSettings {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;settings(Lmeteordevelopment/meteorclient/settings/Settings;)Lmeteordevelopment/meteorclient/gui/widgets/WWidget;"))
    private WWidget redirectTickSettings(GuiTheme theme, Settings settings) {
        WWidget widget = theme.settings(settings);
        // 只给 Config 主设置列表追加页面配置/配置设置分组（模块设置界面不动）
        if (settings == Config.get().settings && widget instanceof WContainer container) {
            PageConfigSection.addToSettings(container, theme);
            ConfigSection.addToSettings(container, theme);
        }
        return widget;
    }

    /**
     * 「鞘翅飞行」模块设置分块整理后的老配置迁移（在 {@code Settings.fromTag} 解析之前改写 tag）：
     *
     * <ul>
     *   <li>官方 General / Inventory / Autopilot 三块已合并进「简单控制」块，老 tag 的分组名换过去；</li>
     *   <li>老「合法平飞」块的设置现在一部分在「简单控制」、一部分（甲飞相关）在「甲飞」，
     *       所以这个 tag 复制成两份分别去匹配；</li>
     *   <li>老「悬停」块并进「简单控制」块；</li>
     *   <li>官方「模式」设置改名 simple-mode、老「甲飞方式」改名「甲飞模式」、
     *       老枚举显示名「合法平飞」改名「合法」。</li>
     * </ul>
     *
     * <p>名字对不上的设置会被 {@code SettingGroup.fromTag} 直接忽略，所以老配置里失效的项
     * 只会回到默认值，不会报错。
     */
    @Inject(method = "fromTag", at = @At("HEAD"))
    private void modernsupport$migrateElytraFlyTag(CompoundTag tag, CallbackInfoReturnable<Settings> cir) {
        try {
            // 只处理「鞘翅飞行」自己的设置列表（按改名后的模式设置识别）
            if (((Settings) (Object) this).get("simple-mode") == null) return;

            ListTag groups = tag.getListOrEmpty("groups");
            if (groups.isEmpty()) return;

            ListTag freshGroups = new ListTag();
            int legacyFlags = 0;
            for (Tag groupTagRaw : groups) {
                if (!(groupTagRaw instanceof CompoundTag groupTag)) continue;

                legacyFlags |= migrateSettingTags(groupTag);

                switch (groupTag.getStringOr("name", "")) {
                    case "General", "Inventory", "Autopilot", "悬停" -> {
                        groupTag.putString("name", "简单控制");
                        freshGroups.add(groupTag);
                    }
                    case "合法平飞" -> {
                        groupTag.putString("name", "简单控制");
                        freshGroups.add(groupTag);
                        // 老「合法平飞」里的甲飞设置（甲飞模式/换甲间隔/落地防摔…）去新的「甲飞」块
                        CompoundTag armorTag = groupTag.copy();
                        armorTag.putString("name", "甲飞");
                        freshGroups.add(armorTag);
                    }
                    default -> freshGroups.add(groupTag);
                }
            }

            // 老配置用的是已删掉的官方「甲飞」模式：模式本身回到原版，并给「甲飞」板块补上甲飞模式=普通
            if ((legacyFlags & LEGACY_ARMOR_MODE) != 0) addLegacyArmorMode(freshGroups);
            // 老配置用的是官方的「发包」平飞：现在归「无限鞘翅·发包」，给无限鞘翅板块补上模式=发包
            if ((legacyFlags & LEGACY_PACKET_MODE) != 0) addLegacyPacketMode(freshGroups);

            tag.put("groups", freshGroups);
        } catch (Exception ignored) {
            // 迁移失败只影响老配置读入，不影响正常加载
        }
    }

    /** migrateSettingTags 的返回值：老配置选的是已删掉的官方「甲飞」模式 */
    @Unique
    private static final int LEGACY_ARMOR_MODE = 1;

    /** migrateSettingTags 的返回值：老配置选的是官方的「发包」平飞（现在归无限鞘翅） */
    @Unique
    private static final int LEGACY_PACKET_MODE = 2;

    /**
     * 老 tag 里的设置名/枚举值改名。
     *
     * @return {@link #LEGACY_ARMOR_MODE} / {@link #LEGACY_PACKET_MODE} 的位或
     */
    @Unique
    private static int migrateSettingTags(CompoundTag groupTag) {
        int flags = 0;

        for (Tag settingTagRaw : groupTag.getListOrEmpty("settings")) {
            if (!(settingTagRaw instanceof CompoundTag settingTag)) continue;

            String name = settingTag.getStringOr("name", "");
            String value = settingTag.getStringOr("value", "");

            if (name.equals("mode")) {
                settingTag.putString("name", "simple-mode");
                if (value.equals("甲飞")) {
                    // 老「甲飞」模式 = 新「原版模式 + 甲飞模式=普通」
                    settingTag.putString("value", "原版");
                    flags |= LEGACY_ARMOR_MODE;
                } else if (value.equals("合法平飞")) {
                    // 老枚举显示名「合法平飞」→ 新显示名「合法」（配置按 toString 存）
                    settingTag.putString("value", "合法");
                } else if (value.equals("Vanilla")) {
                    // 官方原版模式的老显示名 → 我们的「原版」
                    settingTag.putString("value", "原版");
                } else if (value.equals("Packet")) {
                    // 官方「发包」平飞已经搬到「无限鞘翅·发包」，简单控制回到关闭
                    settingTag.putString("value", "关闭");
                    flags |= LEGACY_PACKET_MODE;
                }
            } else if (name.equals("甲飞方式")) {
                // 老甲飞板块的「甲飞方式」并进新的「甲飞模式」
                settingTag.putString("name", "甲飞模式");
            } else if (name.equals("无限鞘翅")) {
                // 老的「无限鞘翅」开关 → 新的「无限鞘翅模式」（开 = 换甲，关 = 关闭）
                settingTag.putString("name", "无限鞘翅模式");
                settingTag.putString("value", value.equals("true") ? "换甲" : "关闭");
            }
        }

        return flags;
    }

    /** 给「无限鞘翅」分组补一条 无限鞘翅模式=发包（老配置用官方发包平飞时调用） */
    @Unique
    private static void addLegacyPacketMode(ListTag groups) {
        for (Tag groupTagRaw : groups) {
            if (!(groupTagRaw instanceof CompoundTag groupTag)) continue;
            if (!groupTag.getStringOr("name", "").equals("无限鞘翅")) continue;

            ListTag settings = groupTag.getListOrEmpty("settings");
            for (Tag settingTagRaw : settings) {
                if (settingTagRaw instanceof CompoundTag settingTag
                    && settingTag.getStringOr("name", "").equals("无限鞘翅模式")) {
                    return; // 老配置里本来就有这一项（上面改过名），不覆盖
                }
            }

            CompoundTag modeTag = new CompoundTag();
            modeTag.putString("name", "无限鞘翅模式");
            modeTag.putString("value", "发包");
            settings.add(modeTag);
            groupTag.put("settings", settings);
            return;
        }
    }

    /** 给「甲飞」分组补一条 甲飞模式=普通（老配置用官方甲飞模式时调用） */
    @Unique
    private static void addLegacyArmorMode(ListTag groups) {
        for (Tag groupTagRaw : groups) {
            if (!(groupTagRaw instanceof CompoundTag groupTag)) continue;
            if (!groupTag.getStringOr("name", "").equals("甲飞")) continue;

            ListTag settings = groupTag.getListOrEmpty("settings");
            for (Tag settingTagRaw : settings) {
                if (settingTagRaw instanceof CompoundTag settingTag
                    && settingTag.getStringOr("name", "").equals("甲飞模式")) {
                    return; // 老配置里本来就有甲飞模式（合法平飞板块搬过来的）
                }
            }

            CompoundTag armorModeTag = new CompoundTag();
            armorModeTag.putString("name", "甲飞模式");
            armorModeTag.putString("value", "普通");
            settings.add(armorModeTag);
            groupTag.put("settings", settings);
            return;
        }
    }
}
