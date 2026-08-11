package fish22.modernsupport;

import fish22.modernsupport.gui.ItemPickerScreen;
import fish22.modernsupport.modules.ElytraBounce;
import fish22.modernsupport.modules.Freeze;
import fish22.modernsupport.modules.ItemUse;
import fish22.modernsupport.modules.Spin;
import fish22.modernsupport.settings.ActionSetting;
import fish22.modernsupport.settings.ItemUseListSetting;
import fish22.modernsupport.utils.AutoSave;
import fish22.modernsupport.utils.I18n;
import fish22.modernsupport.utils.ModuleConfigs;
import fish22.modernsupport.utils.ModulePages;
import fish22.modernsupport.utils.MovementCorrection;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.WKeybind;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.orbit.EventHandler;
import org.slf4j.Logger;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

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

        // 注册「一键使用物品」物品列表设置的 GUI 渲染:
        // 顶部「新增物品」按钮 + 每行 [物品][背包使用][快捷键][删除]
        SettingsWidgetFactory.registerCustomFactory(ItemUseListSetting.class, theme -> (table, setting) -> {
            ItemUseListSetting listSetting = (ItemUseListSetting) setting;

            // 新增物品按钮: 打开物品选择界面, 选完加入列表末尾
            WButton addBtn = table.add(theme.button("新增物品")).expandCellX().widget();
            addBtn.action = () -> mc.setScreen(new ItemPickerScreen(theme, "选择物品", item -> {
                listSetting.get().add(new ItemUseListSetting.ItemUseEntry(item, false, Keybind.none()));
            }));

            table.row();

            // 快捷键控件列表 (渲染时重建, 避免累积旧控件)
            List<WKeybind> keybindWidgets = listSetting.getKeybindWidgets();
            keybindWidgets.clear();

            // 每行: 物品按钮(点击更换) + 背包使用勾选 + 快捷键 + 删除
            List<ItemUseListSetting.ItemUseEntry> entries = listSetting.get();
            for (int i = 0; i < entries.size(); i++) {
                int idx = i;
                ItemUseListSetting.ItemUseEntry entry = entries.get(i);

                WButton itemBtn = table.add(theme.button(Names.get(entry.item))).expandCellX().widget();
                itemBtn.action = () -> mc.setScreen(new ItemPickerScreen(theme, "选择物品", item -> {
                    entry.item = item;
                }));

                table.add(theme.label("背包使用"));
                WCheckbox backpackCb = table.add(theme.checkbox(entry.backpackUse)).widget();
                backpackCb.action = () -> entry.backpackUse = backpackCb.checked;

                WKeybind keybind = table.add(theme.keybind(entry.keybind, Keybind.none())).widget();
                keybindWidgets.add(keybind);

                WButton delBtn = table.add(theme.button("删除")).widget();
                delBtn.action = () -> {
                    entries.remove(idx);
                    reloadItemUseScreen();
                };

                table.row();
            }
        });

        // 初始化移动矫正 API
        MovementCorrection.init();

        // 初始化配置分块（meteor-client/config/ 下的模块配置快照）
        ModuleConfigs.init();

        // 订阅模块开关事件：开关状态变化时自动保存配置
        MeteorClient.EVENT_BUS.subscribe(ModernSupport.class);

        // 娱乐模块
        Modules.get().add(new Spin());

        // 杂项模块
        Modules.get().add(new ItemUse());

        // 移动模块
        Modules.get().add(new Freeze());
        // 鞘翅弹跳（从 ElytraFly Bounce 模式分离的独立模块）
        Modules.get().add(new ElytraBounce());
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

    /** 进入世界/服务器：配置分块按服务器自动应用 */
    @EventHandler
    private static void onGameJoined(GameJoinedEvent event) {
        ModuleConfigs.onGameJoin();
    }

    /** 物品列表增删改后刷新当前设置界面 (重建控件, 列表变化即时显示) */
    private static void reloadItemUseScreen() {
        if (mc.screen instanceof WidgetScreen widgetScreen) widgetScreen.reload();
    }

    @Override
    public String getPackage() {
        return "fish22.modernsupport";
    }
}
