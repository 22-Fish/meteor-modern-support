package fish22.modernsupport.gui;

import fish22.modernsupport.utils.ModuleConfigs;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 配置设置子页面（配置分块）：
 * 「自动应用配置」勾选（开启后显示服务器列表，连接匹配的服务器自动切换到本配置），
 * 服务器列表：新增服务器按钮 + 每行 [服务器 url 输入框][删除]。
 */
public class ConfigDetailScreen extends WindowScreen {

    private final String configName;

    public ConfigDetailScreen(GuiTheme theme, String configName) {
        super(theme, "配置设置 - " + configName);
        this.configName = configName;
    }

    @Override
    public void initWidgets() {
        // 自动应用配置勾选
        WHorizontalList autoRow = add(theme.horizontalList()).expandX().widget();
        autoRow.add(theme.label("自动应用配置"));
        WCheckbox autoCb = autoRow.add(theme.checkbox(ModuleConfigs.autoApplyEnabled(configName))).widget();
        autoCb.action = () -> {
            ModuleConfigs.setAutoApply(configName, autoCb.checked);
            rebuild();
        };
        add(theme.label("连接匹配的服务器时，自动将配置切换到此配置").color(theme.textSecondaryColor())).expandX();

        // 服务器列表（仅勾选自动应用配置时显示和生效）
        if (ModuleConfigs.autoApplyEnabled(configName)) {
            add(theme.horizontalSeparator()).expandX();

            WHorizontalList addRow = add(theme.horizontalList()).expandX().widget();
            addRow.add(theme.label("服务器"));
            WButton addServer = addRow.add(theme.button("新增服务器")).expandCellX().right().widget();
            addServer.action = () -> mc.setScreen(new ConfigNameInputScreen(theme, "新增服务器", "", url -> {
                if (url.isEmpty()) return "服务器地址不能为空";
                List<String> servers = ModuleConfigs.servers(configName);
                servers.add(url);
                ModuleConfigs.setServers(configName, servers);
                return null;
            }));

            WTable table = add(theme.table()).expandX().widget();
            List<String> servers = ModuleConfigs.servers(configName);
            for (int i = 0; i < servers.size(); i++) {
                int idx = i;
                WTextBox box = table.add(theme.textBox(servers.get(i))).expandCellX().minWidth(300).widget();
                box.action = () -> {
                    // 输入变化时同步保存
                    List<String> list = ModuleConfigs.servers(configName);
                    list.set(idx, box.get());
                    ModuleConfigs.setServers(configName, list);
                };

                WButton del = table.add(theme.button("删除")).widget();
                del.action = () -> {
                    List<String> list = ModuleConfigs.servers(configName);
                    list.remove(idx);
                    ModuleConfigs.setServers(configName, list);
                    rebuild();
                };
                table.row();
            }
        }
    }

    /** 重建本页面（勾选状态/服务器列表变化后刷新） */
    private void rebuild() {
        clear();
        initWidgets();
    }
}
