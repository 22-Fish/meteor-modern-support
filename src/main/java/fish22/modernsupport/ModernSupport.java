package fish22.modernsupport;

import fish22.modernsupport.modules.Freeze;
import fish22.modernsupport.modules.Spin;
import fish22.modernsupport.settings.ActionSetting;
import fish22.modernsupport.utils.AutoSave;
import fish22.modernsupport.utils.I18n;
import fish22.modernsupport.utils.ModulePages;
import fish22.modernsupport.utils.MovementCorrection;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import org.slf4j.Logger;

/**
 * meteor现代化支持 — Meteor Client 扩展模组
 *
 * <p>为 Meteor 提供移动矫正 API（{@link MovementCorrection}）：
 * 在旋转的基础上修正 WASD 移动方向，附带 KillAura 集成；
 * 以及配置自动保存（防止强退丢配置）。
 */
public class ModernSupport extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing meteor现代化支持");

        // 初始化语言支持 (内置翻译 + 游戏目录 meteor-lang/ 动态加载)
        I18n.init();

        // 注册按钮设置 (ActionSetting) 的 GUI 渲染: 点击执行动作
        SettingsWidgetFactory.registerCustomFactory(ActionSetting.class, theme -> (table, setting) -> {
            ActionSetting actionSetting = (ActionSetting) setting;
            WButton button = table.add(theme.button(actionSetting.getButtonText())).expandCellX().widget();
            button.action = actionSetting::run;
        });

        // 初始化移动矫正 API
        MovementCorrection.init();

        // 订阅模块开关事件：开关状态变化时自动保存配置
        MeteorClient.EVENT_BUS.subscribe(ModernSupport.class);

        // 娱乐模块
        Modules.get().add(new Spin());

        // 移动模块
        Modules.get().add(new Freeze());
        // 鞘翅飞行增强已通过 MixinElytraFly 注入 Meteor 官方 ElytraFly 模块

        // 模块分页系统：注册 + 加载存档 + 启动登记检查（新分类自动进主界面）
        // 放在本 mod 模块注册之后，确保 Spin/Freeze 所在分类也被登记
        ModulePages pages = new ModulePages();
        Systems.add(pages);
        pages.load();
        pages.checkNewCategories();

        // 全量翻译: 模块标题在构造时已自动翻译, 这里补模块设置 (构造时设置项还未创建)
        // 和 Meteor 设置主界面 (Config) 的设置
        I18n.applyAll();
    }

    @EventHandler
    private static void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        AutoSave.onChanged();
    }

    @Override
    public String getPackage() {
        return "fish22.modernsupport";
    }
}
