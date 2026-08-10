/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 fish22
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fish22.modernsupport.gui;

import fish22.modernsupport.utils.ModulePages;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 页面重命名界面：输入新名字，校验非空且不与其他页面重名。
 */
public class RenamePageScreen extends WindowScreen {
    private final int pageIdx;
    private WLabel errorLabel;

    public RenamePageScreen(GuiTheme theme, int pageIdx) {
        super(theme, "重命名页面");
        this.pageIdx = pageIdx;
    }

    @Override
    public void initWidgets() {
        WTextBox textBox = add(theme.textBox(ModulePages.get().getPage(pageIdx).name)).minWidth(200).expandX().widget();
        textBox.setFocused(true);

        WHorizontalList buttons = add(theme.horizontalList()).expandX().widget();

        WButton save = buttons.add(theme.button("保存")).widget();
        save.action = () -> {
            if (ModulePages.get().renamePage(pageIdx, textBox.get().trim())) {
                // 重命名后重建当前 Tab 界面（顶部栏按钮文字与页面配置列表同步更新）
                PageConfigSection.refreshTabScreen();
            } else {
                errorLabel.set("名称无效：不能为空或与其他页面重名");
            }
        };

        WButton cancel = buttons.add(theme.button("取消")).widget();
        cancel.action = () -> mc.setScreen(parent);

        errorLabel = add(theme.label("")).widget();
    }
}
