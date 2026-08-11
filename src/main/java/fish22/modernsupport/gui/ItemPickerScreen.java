package fish22.modernsupport.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WItemWithLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.render.DisplayItemUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.apache.commons.lang3.Strings;

import java.util.function.Consumer;

/**
 * 物品选择界面（参考 Meteor ItemSettingScreen）：搜索框 + 物品列表，
 * 点击「选择」回调选中的物品，关闭后刷新原界面（物品名/列表变化即时生效）。
 * 用于「一键使用物品」列表的新增/更换物品。
 */
public class ItemPickerScreen extends WindowScreen {

    private final Consumer<Item> onSelect;

    private WTable table;
    private WTextBox filter;
    private String filterText = "";

    public ItemPickerScreen(GuiTheme theme, String title, Consumer<Item> onSelect) {
        super(theme, title);
        this.onSelect = onSelect;
    }

    @Override
    public void initWidgets() {
        filter = add(theme.textBox("")).minWidth(400).expandX().widget();
        filter.setFocused(true);
        filter.action = () -> {
            filterText = filter.get().trim();

            table.clear();
            initTable();
        };

        table = add(theme.table()).expandX().widget();
        initTable();
    }

    private void initTable() {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;

            WItemWithLabel itemLabel = theme.itemWithLabel(DisplayItemUtils.toStack(item), Names.get(item));
            if (!filterText.isEmpty() && !Strings.CI.contains(itemLabel.getLabelText(), filterText)) continue;
            table.add(itemLabel);

            WButton select = table.add(theme.button("选择")).expandCellX().right().widget();
            select.action = () -> {
                onSelect.accept(item);
                onClose();
                // 关闭后刷新原界面（列表/物品名变化即时生效）
                if (parent instanceof WidgetScreen widgetScreen) widgetScreen.reload();
            };

            table.row();
        }
    }
}
