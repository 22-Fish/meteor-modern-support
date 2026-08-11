package fish22.modernsupport.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.util.function.Function;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 通用文本输入界面：输入内容后点「保存」回调校验。
 * 回调返回 null 表示成功（自动关闭并刷新父界面）；返回非 null 字符串作为错误提示。
 * 用于新增配置/重命名配置/新增服务器。
 */
public class ConfigNameInputScreen extends WindowScreen {

    private final String initial;
    private final Function<String, String> onConfirm;

    private WLabel errorLabel;

    /**
     * @param title     窗口标题
     * @param initial   输入框初始内容（可为空）
     * @param onConfirm 校验回调：返回 null = 成功（自动关闭），返回错误消息 = 显示提示
     */
    public ConfigNameInputScreen(GuiTheme theme, String title, String initial, Function<String, String> onConfirm) {
        super(theme, title);
        this.initial = initial;
        this.onConfirm = onConfirm;
    }

    @Override
    public void initWidgets() {
        WTextBox textBox = add(theme.textBox(initial)).minWidth(200).expandX().widget();
        textBox.setFocused(true);

        WHorizontalList buttons = add(theme.horizontalList()).expandX().widget();

        WButton save = buttons.add(theme.button("保存")).widget();
        save.action = () -> {
            String error = onConfirm.apply(textBox.get().trim());
            if (error != null) {
                errorLabel.set(error);
                return;
            }
            // 成功：关闭输入框并刷新父界面（列表/内容变化即时显示）
            mc.setScreen(parent);
            if (parent instanceof WidgetScreen widgetScreen) widgetScreen.reload();
        };

        WButton cancel = buttons.add(theme.button("取消")).widget();
        cancel.action = () -> mc.setScreen(parent);

        errorLabel = add(theme.label("")).widget();
    }
}
