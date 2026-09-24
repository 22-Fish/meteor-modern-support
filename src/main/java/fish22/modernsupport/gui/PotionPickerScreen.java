package fish22.modernsupport.gui;

import fish22.modernsupport.settings.PotionPickSetting;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WItemWithLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import org.apache.commons.lang3.Strings;

import java.util.function.Consumer;

/**
 * 药水选择界面：搜索框 + 药水列表，列出注册表里的每一种药水
 * （名字按当前语言，比如「喷溅型迅捷药水」「滞留型迅捷药水 II」），点击「选择」回调选中的药水
 *
 * <p>列表第一行是「任意」：不挑药水种类，背包里哪种能扔的药水都能用
 *
 * <p>形态（喷溅 / 滞留）由调用方给，列表里的图标和名字按形态拼
 */
public class PotionPickerScreen extends WindowScreen {

    private final Consumer<Potion> onSelect;
    private final Item form;

    private WTable table;
    private WTextBox filter;
    private String filterText = "";

    /**
     * @param form 形态对应的物品（喷溅药水 / 滞留药水），用来生成列表里的图标和名字
     */
    public PotionPickerScreen(GuiTheme theme, String title, Consumer<Potion> onSelect, Item form) {
        super(theme, title);
        this.onSelect = onSelect;
        this.form = form;
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
        // 「任意」：不挑药水种类
        addRow(null);

        for (Potion potion : BuiltInRegistries.POTION) addRow(potion);
    }

    /** 一行：[药水图标 + 名字][选择] */
    private void addRow(Potion potion) {
        ItemStack stack = PotionPickSetting.stack(potion, form);
        String name = PotionPickSetting.name(potion, form);
        if (!filterText.isEmpty() && !Strings.CI.contains(name, filterText)) return;

        WItemWithLabel itemLabel = theme.itemWithLabel(stack, name);
        table.add(itemLabel);

        WButton select = table.add(theme.button("选择")).expandCellX().right().widget();
        select.action = () -> {
            onSelect.accept(potion);
            onClose();
            // 关闭后刷新原界面（按钮上的药水名即时生效）
            if (parent instanceof WidgetScreen widgetScreen) widgetScreen.reload();
        };

        table.row();
    }
}
