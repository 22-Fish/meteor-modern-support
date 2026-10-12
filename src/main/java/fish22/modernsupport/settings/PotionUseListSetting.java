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
import net.minecraft.world.item.alchemy.Potion;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「一键药水」的药水列表设置：每个条目 = 药水 + 是否背包使用 + 快捷键。
 * GUI 由自定义 factory 渲染（顶部「新增药水」按钮 + 每行 [药水][背包使用][快捷键][删除]）。
 *
 * <p>形态（喷溅 / 滞留）是模块级设置，由调用方用 {@code form} 传进来拼显示名。
 * 快捷键录制（WKeybind）需要按键/鼠标事件驱动，本设置订阅事件统一转发
 * 给当前 GUI 中渲染的快捷键控件（同一键使用物品那套）。
 */
public class PotionUseListSetting extends Setting<List<PotionUseListSetting.Entry>> {

    /** 列表中的一个药水条目 */
    public static class Entry {
        public Potion potion;
        public boolean backpackUse;
        public Keybind keybind;

        /** 上一次按键状态（快捷键上升沿检测用，不保存） */
        public transient boolean prevPressed;

        public Entry(Potion potion, boolean backpackUse, Keybind keybind) {
            this.potion = potion;
            this.backpackUse = backpackUse;
            this.keybind = keybind;
        }
    }

    /** 形态对应的物品（喷溅药水 / 滞留药水），显示名字和图标用 */
    private final Supplier<Item> form;

    /** 当前 GUI 中渲染的快捷键控件（录制按键时转发事件） */
    private final List<WKeybind> keybindWidgets = new ArrayList<>();

    public PotionUseListSetting(String name, String description, Supplier<Item> form, IVisible visible) {
        super(name, description, new ArrayList<>(), null, null, visible);
        this.form = form;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public Item form() {
        return form.get();
    }

    /** 某个条目按钮上显示的名字（按当前形态拼，比如「喷溅型迅捷药水」） */
    public String displayName(Entry entry) {
        return PotionPickSetting.name(entry.potion, form.get());
    }

    /** 某个条目按钮上额外标的时长（方便区分同类同名的药水） */
    public String durationText(Entry entry) {
        return PotionPickSetting.durationText(entry.potion);
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
    protected List<Entry> parseImpl(String str) {
        return null;
    }

    @Override
    protected boolean isValueValid(List<Entry> value) {
        return true;
    }

    @Override
    protected CompoundTag save(CompoundTag tag) {
        ListTag listTag = new ListTag();
        for (Entry entry : value) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("potion", entry.potion == null ? "" : BuiltInRegistries.POTION.getKey(entry.potion).toString());
            entryTag.putBoolean("backpackUse", entry.backpackUse);
            entryTag.put("keybind", entry.keybind.toTag());
            listTag.add(entryTag);
        }
        tag.put("value", listTag);
        return tag;
    }

    @Override
    protected List<Entry> load(CompoundTag tag) {
        value.clear();
        for (Tag element : tag.getListOrEmpty("value")) {
            CompoundTag entryTag = (CompoundTag) element;
            Potion potion;
            try {
                potion = BuiltInRegistries.POTION.getOptional(Identifier.parse(entryTag.getStringOr("potion", ""))).orElse(null);
            } catch (Exception e) {
                // 配置损坏/药水 ID 非法：当成「任意」，不丢条目也不影响其余配置加载
                potion = null;
            }
            Entry entry = new Entry(potion, entryTag.getBooleanOr("backpackUse", false), Keybind.none());
            entry.keybind.fromTag(entryTag.getCompoundOrEmpty("keybind"));
            value.add(entry);
        }
        return value;
    }

    // ====== 快捷键录制事件转发（和「一键使用物品」同一套） ======

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
