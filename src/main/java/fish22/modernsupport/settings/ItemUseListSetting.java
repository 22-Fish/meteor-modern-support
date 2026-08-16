package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.gui.widgets.WKeybind;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 「一键使用物品」的列表设置：每个条目 = 物品 + 是否背包使用 + 快捷键。
 * GUI 由自定义 factory 渲染（顶部「新增物品」按钮 + 每行 [物品][背包使用][快捷键][删除]）。
 *
 * <p>快捷键录制（WKeybind）需要按键/鼠标事件驱动，本设置订阅事件统一转发
 * 给当前 GUI 中渲染的快捷键控件（参考 Meteor KeybindSetting 的做法）。
 */
public class ItemUseListSetting extends Setting<List<ItemUseListSetting.ItemUseEntry>> {

    /** 列表中的一个物品条目 */
    public static class ItemUseEntry {
        public Item item;
        public boolean backpackUse;
        public Keybind keybind;

        /** 上一次按键状态（快捷键上升沿检测用，不保存） */
        public transient boolean prevPressed;

        public ItemUseEntry(Item item, boolean backpackUse, Keybind keybind) {
            this.item = item;
            this.backpackUse = backpackUse;
            this.keybind = keybind;
        }
    }

    /** 当前 GUI 中渲染的快捷键控件（录制按键时转发事件） */
    private final List<WKeybind> keybindWidgets = new ArrayList<>();

    public ItemUseListSetting(String name, String description, IVisible visible) {
        super(name, description, new ArrayList<>(), null, null, visible);
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    /**
     * value 始终为独立列表（与 defaultValue 分离），
     * 直接修改 value（增删条目）时配置才能正确对比保存。
     */
    @Override
    protected void resetImpl() {
        if (value == null) value = new ArrayList<>();
        else value.clear();
    }

    public List<WKeybind> getKeybindWidgets() {
        return keybindWidgets;
    }

    @Override
    protected List<ItemUseEntry> parseImpl(String str) {
        return null;
    }

    @Override
    protected boolean isValueValid(List<ItemUseEntry> value) {
        return true;
    }

    @Override
    protected CompoundTag save(CompoundTag tag) {
        ListTag listTag = new ListTag();
        for (ItemUseEntry entry : value) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("item", BuiltInRegistries.ITEM.getKey(entry.item).toString());
            entryTag.putBoolean("backpackUse", entry.backpackUse);
            entryTag.put("keybind", entry.keybind.toTag());
            listTag.add(entryTag);
        }
        tag.put("value", listTag);
        return tag;
    }

    @Override
    protected List<ItemUseEntry> load(CompoundTag tag) {
        value.clear();
        for (Tag element : tag.getListOrEmpty("value")) {
            CompoundTag entryTag = (CompoundTag) element;
            Item item;
            try {
                item = BuiltInRegistries.ITEM.getValue(Identifier.parse(entryTag.getStringOr("item", "")));
            } catch (Exception e) {
                // 配置损坏/物品 ID 非法：该条目重置为默认值（空气），
                // 不丢条目也不影响其余配置加载（界面里可重新选择物品）
                item = Items.AIR;
            }
            ItemUseEntry entry = new ItemUseEntry(item, entryTag.getBooleanOr("backpackUse", false), Keybind.none());
            entry.keybind.fromTag(entryTag.getCompoundOrEmpty("keybind"));
            value.add(entry);
        }
        return value;
    }

    // ====== 快捷键录制事件转发（参考 KeybindSetting） ======

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onKeyBinding(KeyInputEvent event) {
        for (WKeybind widget : keybindWidgets) {
            if (event.action == KeyAction.Press && event.key() == GLFW.GLFW_KEY_ESCAPE && widget.onClear()) {
                event.cancel();
            } else if (event.action == KeyAction.Release && widget.onAction(true, event.key(), event.modifiers())) {
                event.cancel();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMouseClickBinding(MouseClickEvent event) {
        for (WKeybind widget : keybindWidgets) {
            if (event.action == KeyAction.Press && widget.onAction(false, event.button(), 0)) {
                event.cancel();
            }
        }
    }
}
