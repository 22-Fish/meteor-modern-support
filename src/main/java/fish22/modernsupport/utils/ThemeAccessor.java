package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.settings.Setting;

/**
 * 由 MixinMeteorGuiTheme 实现：暴露本模组给 Meteor 主题追加的 GUI 设置。
 * 非 Meteor 主题或注入失败时 {@link #of(GuiTheme)} 返回 null，调用方按原版行为兜底。
 */
public interface ThemeAccessor {
    /** 模块名边距（默认 4，原版硬编码值） */
    Setting<Double> getModuleNamePadding();

    /** 启用模块左侧高亮的宽度（默认 2，原版硬编码值） */
    Setting<Double> getHighlightWidth();

    /** 模块名字符间距（默认 0 = 原版行为） */
    Setting<Double> getLetterSpacing();

    /** GUI 窗口圆角半径（默认 0 = 原版直角） */
    Setting<Double> getCornerRadius();

    static ThemeAccessor of(GuiTheme theme) {
        return theme instanceof ThemeAccessor accessor ? accessor : null;
    }
}
