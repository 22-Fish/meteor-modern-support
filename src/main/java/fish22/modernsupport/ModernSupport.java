package fish22.modernsupport;

import fish22.modernsupport.gui.ItemPickerScreen;
import fish22.modernsupport.aibot.AI;
import fish22.modernsupport.commands.PearlCommand;
import fish22.modernsupport.modules.AutoSugarcane;
import fish22.modernsupport.modules.ElytraBounce;
import fish22.modernsupport.modules.ElytraGrimAccelerate;
import fish22.modernsupport.modules.ElytraPitch40;
import fish22.modernsupport.modules.EntityList;
import fish22.modernsupport.modules.FireworkUse;
import fish22.modernsupport.modules.Freeze;
import fish22.modernsupport.modules.FireworkBoost;
import fish22.modernsupport.modules.GhostMine;
import fish22.modernsupport.modules.ItemUse;
import fish22.modernsupport.modules.LegalRotationConfig;
import fish22.modernsupport.modules.OpenAI;
import fish22.modernsupport.modules.PearlBot;
import fish22.modernsupport.modules.Printer;
import fish22.modernsupport.modules.SimplePlace;
import fish22.modernsupport.modules.SpearNoise;
import fish22.modernsupport.modules.Spin;
import fish22.modernsupport.settings.ActionSetting;
import fish22.modernsupport.settings.ItemUseListSetting;
import fish22.modernsupport.settings.PearlPointSetting;
import fish22.modernsupport.settings.ScheduledTaskListSetting;
import fish22.modernsupport.settings.WhiteListSetting;
import fish22.modernsupport.utils.AutoSave;
import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.I18n;
import fish22.modernsupport.utils.ModuleConfigs;
import fish22.modernsupport.utils.ModulePages;
import fish22.modernsupport.utils.LegalRotation;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.WKeybind;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.orbit.EventHandler;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * meteor现代化支持 — Meteor Client 扩展模组
 *
 * <p>为 Meteor 提供合法转头 API（{@link LegalRotation}）：
 * 在旋转的基础上修正 WASD 移动方向，附带 KillAura 集成；
 * 最佳放置角度查询接口（{@link fish22.modernsupport.utils.LegalPlace}）：
 * 传入目标位置和允许点击的面，算出在这个位置放方块的最佳角度（算不出来返回没有），
 * 只算角度、不发包；
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

            // 整组共用外层表格：这里只占一格，行和列在内层自己分
            // （外层表格的行末由 Meteor 自己调 row()，自定义 factory 不能碰）
            WTable itemTable = table.add(theme.table()).expandX().widget();

            // 新增物品按钮: 打开物品选择界面, 选完加入列表末尾
            WButton addBtn = itemTable.add(theme.button("新增物品")).expandCellX().widget();
            addBtn.action = () -> mc.setScreen(new ItemPickerScreen(theme, "选择物品", item -> {
                listSetting.get().add(new ItemUseListSetting.ItemUseEntry(item, false, Keybind.none()));
            }));

            itemTable.row();

            // 快捷键控件列表 (渲染时重建, 避免累积旧控件)
            List<WKeybind> keybindWidgets = listSetting.getKeybindWidgets();
            keybindWidgets.clear();

            // 每行: 物品按钮(点击更换) + 背包使用勾选 + 快捷键 + 删除
            List<ItemUseListSetting.ItemUseEntry> entries = listSetting.get();
            for (int i = 0; i < entries.size(); i++) {
                int idx = i;
                ItemUseListSetting.ItemUseEntry entry = entries.get(i);

                WButton itemBtn = itemTable.add(theme.button(Names.get(entry.item))).expandCellX().widget();
                itemBtn.action = () -> mc.setScreen(new ItemPickerScreen(theme, "选择物品", item -> {
                    entry.item = item;
                }));

                itemTable.add(theme.label("背包使用"));
                WCheckbox backpackCb = itemTable.add(theme.checkbox(entry.backpackUse)).widget();
                backpackCb.action = () -> entry.backpackUse = backpackCb.checked;

                WKeybind keybind = itemTable.add(theme.keybind(entry.keybind, Keybind.none())).widget();
                keybindWidgets.add(keybind);

                WButton delBtn = itemTable.add(theme.button("删除")).widget();
                delBtn.action = () -> {
                    entries.remove(idx);
                    reloadItemUseScreen();
                };

                itemTable.row();
            }
        });

        // 注册「openAI 白名单用户」列表设置的 GUI 渲染:
        // 顶部「新增用户」按钮 + 每个用户一行 [名称(撑满)][用量限制][输入框][删除]
        SettingsWidgetFactory.registerCustomFactory(WhiteListSetting.class, theme -> (table, setting) -> {
            WhiteListSetting listSetting = (WhiteListSetting) setting;

            WTable userTable = table.add(theme.table()).expandX().widget();

            WButton addBtn = userTable.add(theme.button("新增用户")).expandX().widget();
            addBtn.action = () -> {
                listSetting.get().add(new WhiteListSetting.Entry("玩家名", -1));
                reloadSettingScreen(listSetting);
            };
            userTable.row();

            List<WhiteListSetting.Entry> entries = listSetting.get();
            for (int i = 0; i < entries.size(); i++) {
                int idx = i;
                WhiteListSetting.Entry entry = entries.get(i);

                // 玩家名撑满剩余宽度
                WTextBox nameBox = userTable.add(theme.textBox(entry.name)).expandX().widget();
                nameBox.action = () -> entry.name = nameBox.get().trim();

                // 用量限制紧跟在玩家名后面 (-1 = 无限调用)
                userTable.add(theme.label("用量限制"));
                Cell<WTextBox> limitCell = userTable.add(theme.textBox(String.valueOf(entry.limit)));
                limitCell.minWidth(70);
                WTextBox limitBox = limitCell.widget();
                limitBox.action = () -> {
                    try {
                        entry.limit = Long.parseLong(limitBox.get().trim());
                    } catch (NumberFormatException ignored) {
                        // 输入不是数字时保留原值, 下次渲染会显示回来
                    }
                };

                WButton delBtn = userTable.add(theme.button("删除")).widget();
                delBtn.action = () -> {
                    entries.remove(idx);
                    reloadSettingScreen(listSetting);
                };
                userTable.row();
            }
        });

        // 注册「openAI 定时任务」列表设置的 GUI 渲染:
        // 顶部「新增」按钮 + 每个任务两行 [任务名(撑满)][时间][输入框][删除] / [提示词(撑满整行)]
        SettingsWidgetFactory.registerCustomFactory(ScheduledTaskListSetting.class, theme -> (table, setting) -> {
            ScheduledTaskListSetting listSetting = (ScheduledTaskListSetting) setting;

            WTable taskTable = table.add(theme.table()).expandX().widget();

            WButton addBtn = taskTable.add(theme.button("新增")).expandX().widget();
            addBtn.action = () -> {
                listSetting.get().add(new ScheduledTaskListSetting.Entry("任务名", "定时任务触发，该做点什么。", "00:00"));
                reloadSettingScreen(listSetting);
            };
            taskTable.row();

            List<ScheduledTaskListSetting.Entry> tasks = listSetting.get();
            for (int i = 0; i < tasks.size(); i++) {
                int idx = i;
                ScheduledTaskListSetting.Entry task = tasks.get(i);

                // 第一行: 任务名称 (撑满剩余宽度) + 触发时间 HH:mm + 删除
                WTextBox nameBox = taskTable.add(theme.textBox(task.name)).expandX().widget();
                nameBox.action = () -> task.name = nameBox.get().trim();

                taskTable.add(theme.label("时间"));
                Cell<WTextBox> timeCell = taskTable.add(theme.textBox(task.time));
                timeCell.minWidth(70);
                WTextBox timeBox = timeCell.widget();
                timeBox.action = () -> {
                    String input = timeBox.get().trim();
                    if (ScheduledTaskListSetting.isValidTime(input)) task.time = ScheduledTaskListSetting.normalizeTime(input);
                };

                WButton delBtn = taskTable.add(theme.button("删除")).right().widget();
                delBtn.action = () -> {
                    tasks.remove(idx);
                    reloadSettingScreen(listSetting);
                };
                taskTable.row();

                // 第二行: 配置提示词 (撑满整行)
                WTextBox promptBox = taskTable.add(theme.textBox(task.prompt)).expandX().widget();
                promptBox.action = () -> task.prompt = promptBox.get();
                taskTable.row();
            }
        });

        // 注册「珍珠点」设置的 GUI 渲染: 顶部「新增珍珠点」按钮 + 每行 [用户名][坐标][删除]
        //
        // 注意 Meteor 的 group() 是「整组共用一张 WTable」：它先加 [标签] 这一列，
        // 再调自定义 factory 填控件，最后**由 group 自己调用 table.row()**。
        // 所以自定义 factory 只能往后面的列里塞东西，绝对不能自己调 row()
        // （会把列结构搞乱，同一组里后面的设置项跟着错位）。
        // 这里套一层自己的小表格，在外面只占一格，行和列在内层随便分。
        SettingsWidgetFactory.registerCustomFactory(PearlPointSetting.class, theme -> (table, setting) -> {
            PearlPointSetting pointSetting = (PearlPointSetting) setting;

            WTable pointsTable = table.add(theme.table()).expandX().widget();

            WButton addPointBtn = pointsTable.add(theme.button("新增珍珠点")).expandX().widget();
            addPointBtn.action = () -> {
                pointSetting.get().put("玩家" + pointSetting.get().size(),
                    new PearlPointSetting.Point(0, 64, 0));
                reloadSettingScreen(pointSetting);
            };
            pointsTable.row();

            for (Map.Entry<String, PearlPointSetting.Point> entry : new ArrayList<>(pointSetting.get().entrySet())) {
                PearlPointSetting.Point point = entry.getValue();
                // 名字可能被就地改掉：用数组记当前 key，改完不重建界面，免得输入框失焦
                String[] currentName = { entry.getKey() };

                WTextBox nameBox = pointsTable.add(theme.textBox(currentName[0])).expandX().widget();
                // 用 actionOnUnfocused：action 是每敲一个字符都会触发，重建界面就会丢焦点
                nameBox.actionOnUnfocused = () -> {
                    String newName = nameBox.get().trim();
                    if (newName.isEmpty() || newName.equals(currentName[0])) return;
                    pointSetting.get().remove(currentName[0]);
                    pointSetting.get().put(newName, point);
                    currentName[0] = newName;
                };

                Cell<WTextBox> posCell = pointsTable.add(theme.textBox(point.x + " " + point.y + " " + point.z));
                posCell.minWidth(120);
                WTextBox posBox = posCell.widget();
                posBox.actionOnUnfocused = () -> {
                    String[] parts = posBox.get().trim().split("\\s+");
                    if (parts.length != 3) return;
                    try {
                        point.x = Integer.parseInt(parts[0]);
                        point.y = Integer.parseInt(parts[1]);
                        point.z = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {
                        // 输入不是三个整数时保留原值，下次渲染会显示回来
                    }
                };

                WButton delPointBtn = pointsTable.add(theme.button("删除")).widget();
                delPointBtn.action = () -> {
                    // 按对象删：名字可能已经被改过，按旧 key 删不掉
                    pointSetting.get().values().remove(point);
                    reloadSettingScreen(pointSetting);
                };
                pointsTable.row();
            }
        });

        // 初始化合法转头 API
        LegalRotation.init();

        // 初始化配置分块（meteor-client/config/ 下的模块配置快照）
        ModuleConfigs.init();

        // 订阅模块开关事件：开关状态变化时自动保存配置
        MeteorClient.EVENT_BUS.subscribe(ModernSupport.class);

        // 娱乐模块
        Modules.get().add(new Spin());
        // 长矛噪音（自动右键手里的长矛刷声音）
        Modules.get().add(new SpearNoise());

        // 杂项模块
        Modules.get().add(new ItemUse());
        // 一键烟花（从「鞘翅飞行」模块拆出来的独立模块，排在「一键使用物品」下面）
        Modules.get().add(new FireworkUse());
        Modules.get().add(new LegalRotationConfig());
        // openAI: 把 AEBot (服务端 AI 假人) 搬到客户端, 走自己的聊天栏收发
        Modules.get().add(new OpenAI());
        // 珍珠点大管家（从 meteor-miku 移植）：私聊关键字 → 自动走过去点开珍珠点
        Modules.get().add(new PearlBot());

        // 命令 .pearl <玩家名>：和私聊触发同样的效果，方便手动敲 / 别的程序调用
        Commands.add(new PearlCommand());

        // 移动模块
        Modules.get().add(new Freeze());
        Modules.get().add(new FireworkBoost());
        // 从官方「鞘翅飞行」拆出来的两个模式（移动分类）
        Modules.get().add(new ElytraPitch40());
        Modules.get().add(new ElytraBounce());
        // 鞘翅滑翔加速（从史莱姆 SlimefunHelper 的 ElytraGrimAcc 移植：压原版位置包 + 补假包触发拉回）
        Modules.get().add(new ElytraGrimAccelerate());
        // 鞘翅飞行增强已通过 MixinElytraFly 注入 Meteor 官方 ElytraFly 模块

        // 世界模块
        // 发包挖掘（从 meteor-miku 移植）
        Modules.get().add(new GhostMine());
        // 自动收甘蔗（由官方「核爆」简化：只收甘蔗/竹子，留下最下面一节）
        Modules.get().add(new AutoSugarcane());
        // 简单放置（配合投影模组使用）
        Modules.get().add(new SimplePlace());
        // 投影打印机（在范围内自动搭投影，放置逻辑用「简单放置」那一套）
        Modules.get().add(new Printer());

        // 渲染模块
        Modules.get().add(new EntityList());

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

    /** 背包使用：跨 tick 对账（换回没生效时用当前 stateId 幂等重发一次 SWAP） */
    @EventHandler
    private static void onTickPost(TickEvent.Post event) {
        BackpackUse.tick();
        // 本地聊天提示攒到 tick 末再发：在聊天事件里直接发会把那条聊天顶掉（还会变两条）
        AI.flushChatMessages();
    }

    /** 物品列表增删改后刷新当前设置界面 (重建控件, 列表变化即时显示) */
    private static void reloadItemUseScreen() {
        if (mc.screen instanceof WidgetScreen widgetScreen) widgetScreen.reload();
    }

    /**
     * 列表设置增删条目后刷新界面：先让 Meteor 重建设置控件 (invalidate),
     * 再重建当前界面。模块设置界面是 ModuleScreen（WidgetScreen 的子类），
     * 重载时会按最新列表重新渲染，新条目立刻出现。
     */
    private static void reloadSettingScreen(Setting<?> setting) {
        if (setting.module != null) setting.module.settings.invalidate();
        reloadItemUseScreen();
    }

    @Override
    public String getPackage() {
        return "fish22.modernsupport";
    }
}
