package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.nbt.CompoundTag;

/**
 * 按钮设置: 点击执行动作, 不保存值 (重启不会自动触发)
 * GUI 渲染由 SettingsWidgetFactory.registerCustomFactory 注册
 */
public class ActionSetting extends Setting<Integer> {
    private final String buttonText;
    private final Runnable action;

    public ActionSetting(String name, String description, String buttonText, Runnable action, IVisible visible) {
        super(name, description, 0, null, null, visible);
        this.buttonText = buttonText;
        this.action = action;
    }

    /** 按钮显示文字 */
    public String getButtonText() {
        return buttonText;
    }

    /** 点击按钮时执行 */
    public void run() {
        action.run();
    }

    @Override
    protected Integer parseImpl(String str) {
        return null;
    }

    @Override
    protected boolean isValueValid(Integer value) {
        return true;
    }

    /** 不保存值, 只保留名字 */
    @Override
    protected CompoundTag save(CompoundTag tag) {
        return tag;
    }

    /** 不恢复值, 避免加载配置时自动触发动作 */
    @Override
    protected Integer load(CompoundTag tag) {
        return 0;
    }
}
