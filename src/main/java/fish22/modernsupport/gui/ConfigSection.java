package fish22.modernsupport.gui;

import fish22.modernsupport.utils.ModuleConfigs;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 配置设置区块（模块配置分块）：追加到 Config 设置列表末尾。
 * 「新增配置」按钮 + 每行 [勾选(单选)][配置名][设置][重命名][删除]（仅一个配置时删除无效）。
 * 勾选即应用：先保存当前配置，再加载目标配置（同时只能勾选一个）。
 */
public class ConfigSection extends WVerticalList {

    /** 在设置列表（WSettings）末尾追加"配置设置"分组 */
    public static void addToSettings(WContainer settingsContainer, GuiTheme theme) {
        WSection section = settingsContainer.add(theme.section("配置设置", true)).expandX().widget();
        section.add(new ConfigSection()).expandX();
    }

    @Override
    public void init() {
        rebuild();
    }

    private void rebuild() {
        clear();

        // 顶部：当前勾选配置 + 新增配置按钮
        WHorizontalList top = add(theme.horizontalList()).expandX().widget();
        top.add(theme.label("当前配置: " + (ModuleConfigs.selected() != null ? ModuleConfigs.selected() : "未勾选")));
        WButton addBtn = top.add(theme.button("新增配置")).expandCellX().right().widget();
        addBtn.action = () -> mc.setScreen(new ConfigNameInputScreen(theme, "新增配置", "", name -> {
            if (name.isEmpty()) return "配置名不能为空";
            if (ModuleConfigs.list().contains(name)) return "已存在同名配置";
            ModuleConfigs.create(name);
            return null;
        }));

        // 配置行：勾选(单选) + 配置名 + 设置 + 重命名 + 删除
        List<String> configs = ModuleConfigs.list();
        if (configs.isEmpty()) return;

        WTable table = add(theme.table()).expandX().widget();
        for (int i = 0; i < configs.size(); i++) {
            String name = configs.get(i);
            boolean isSelected = name.equals(ModuleConfigs.selected());

            // 勾选框：勾选即应用该配置（单选，同时只能勾选一个）
            WCheckbox cb = table.add(theme.checkbox(isSelected)).widget();
            cb.action = () -> {
                if (cb.checked && !isSelected) {
                    ModuleConfigs.select(name);
                    refreshTab();
                } else if (!cb.checked) {
                    // 取消勾选无效：必须有一个配置被勾选
                    cb.checked = true;
                }
            };

            table.add(theme.label(name)).expandCellX();

            WButton settingsBtn = table.add(theme.button("设置")).widget();
            settingsBtn.action = () -> mc.setScreen(new ConfigDetailScreen(theme, name));

            WButton renameBtn = table.add(theme.button("重命名")).widget();
            renameBtn.action = () -> mc.setScreen(new ConfigNameInputScreen(theme, "重命名配置", name, newName -> {
                if (newName.isEmpty()) return "配置名不能为空";
                if (!newName.equals(name) && ModuleConfigs.list().contains(newName)) return "已存在同名配置";
                ModuleConfigs.rename(name, newName);
                return null;
            }));

            WButton delBtn = table.add(theme.button("删除")).widget();
            delBtn.action = () -> {
                // 仅有一个配置时无法删除
                if (ModuleConfigs.list().size() > 1) {
                    ModuleConfigs.delete(name);
                    refreshTab();
                }
            };

            table.row();
        }
    }

    /** 配置勾选/增删改后重建当前 Tab 界面 */
    private void refreshTab() {
        PageConfigSection.refreshTabScreen();
    }
}
