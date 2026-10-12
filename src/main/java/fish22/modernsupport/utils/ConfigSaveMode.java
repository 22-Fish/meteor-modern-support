package fish22.modernsupport.utils;

/**
 * 配置保存时机（Config 页面「配置保存」设置项的选项）。
 *
 * <p>设置界面按 {@link #toString()} 显示，配置里也按 {@link #toString()} 存取，
 * 所以直接把中文写在 toString 里。
 */
public enum ConfigSaveMode {
    /** 每次变化保存：设置/模块开关一变化就异步防抖写盘 */
    OnChange("每次变化保存"),
    /** 退出时保存：运行期间不写盘，只在退出游戏时写一次 */
    OnExit("退出时保存");

    private final String displayName;

    ConfigSaveMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
