package fish22.modernsupport.gui;

import fish22.modernsupport.utils.ModuleConfigs;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 配置设置子页面（配置分块）：
 * 「自动应用配置」勾选（开启后显示服务器列表，连接匹配的服务器自动切换到本配置），
 * 服务器列表：新增服务器按钮 + 每行 [服务器 url 输入框][删除]。
 */
public class ConfigDetailScreen extends WindowScreen {

    private final String configName;

    /** 服务器列表本地副本：输入过程中只改内存，界面关闭时统一写盘（避免每敲一个字写一次 meta.nbt） */
    private final List<String> servers = new ArrayList<>();

    public ConfigDetailScreen(GuiTheme theme, String configName) {
        super(theme, "配置设置 - " + configName);
        this.configName = configName;
        servers.addAll(ModuleConfigs.servers(configName));
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
                servers.add(url);
                // 新增是低频操作，直接写盘（保证重进界面不丢）
                ModuleConfigs.setServers(configName, servers);
                return null;
            }));

            WTable table = add(theme.table()).expandX().widget();
            for (int i = 0; i < servers.size(); i++) {
                int idx = i;
                WTextBox box = table.add(theme.textBox(servers.get(i))).expandCellX().minWidth(300).widget();
                box.action = () -> {
                    // 输入变化只更新内存副本，界面关闭时统一写盘
                    servers.set(idx, box.get());
                };

                WButton del = table.add(theme.button("删除")).widget();
                del.action = () -> {
                    servers.remove(idx);
                    rebuild();
                };
                table.row();
            }
        }
    }

    /** 界面关闭时把服务器列表写盘（合并输入过程中的多次修改为一次写入） */
    @Override
    public void removed() {
        ModuleConfigs.setServers(configName, servers);
        super.removed();
    }

    /** 重建本页面（勾选状态/服务器列表变化后刷新） */
    private void rebuild() {
        clear();
        initWidgets();
    }
}
