package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.ElytraFlySupport;
import fish22.modernsupport.utils.I18n;
import meteordevelopment.meteorclient.gui.DefaultSettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 枚举设置下拉框：DEFAULT 走 {@code theme.dropdown(value)}，它按
 * {@code value.getDeclaringClass().getEnumConstants()} 列出全部枚举常量。
 *
 * <p>「鞘翅飞行」的模式枚举里还留着官方的 俯仰40 / 弹跳（不能从 {@code $VALUES} 删，
 * 官方 switch 的 {@code $SwitchMap} 按 {@code values().length} 分配、按 ordinal 写入，
 * 数组变短会在类初始化时数组越界崩溃），但这两个模式已经拆成独立模块，
 * 所以这里在下拉框创建时把它们过滤掉，只显示 原版 / 发包 / 合法。
 *
 * <p>{@code require = 0}：万一 Meteor 改了 GUI 工厂的内部结构，注入失败也只是下拉里
 * 多出两个模式，不会让游戏起不来。
 */
@Mixin(value = DefaultSettingsWidgetFactory.class, remap = false)
public abstract class MixinDefaultSettingsWidgetFactory {

    /** 分组标题（General / Colors / ...）走翻译表 Group.Meteor.<名字> */
    @Redirect(
        method = "group",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;section(Ljava/lang/String;Z)Lmeteordevelopment/meteorclient/gui/widgets/containers/WSection;"
        ),
        require = 0
    )
    private WSection modernsupport$translateGroupTitle(GuiTheme theme, String name, boolean expanded) {
        return theme.section(I18n.groupName(name), expanded);
    }

    @Redirect(
        method = "enumW",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;dropdown(Ljava/lang/Enum;)Lmeteordevelopment/meteorclient/gui/widgets/input/WDropdown;"
        ),
        require = 0
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private WDropdown<?> modernsupport$filterElytraFlyModes(GuiTheme theme, Enum value) {
        if (value instanceof ElytraFlightModes) {
            return theme.dropdown(ElytraFlySupport.simpleControlModes(), (ElytraFlightModes) value);
        }
        return theme.dropdown(value);
    }
}
