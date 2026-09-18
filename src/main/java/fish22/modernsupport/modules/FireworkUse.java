package fish22.modernsupport.modules;

import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.ElytraFlySupport;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;

/**
 * 一键烟花（独立模块，杂项分类）
 *
 * <p>从「鞘翅飞行」模块的一键烟花设置拆出来的独立模块：<b>按下</b>快捷键释放一次烟花
 * （按下瞬间触发一次，按住不重复；不是 Meteor 按键设置默认的「松开触发」，见 {@link #onKeyInput}）。
 *
 * <ul>
 *   <li>「鞘翅飞行」开着且处于 合法 / 甲飞：延后到滑翔窗口释放
 *       （甲飞 = 换装窗口结束后，合法 = 移动包发送后），否则服务器可能不认这次使用包；</li>
 *   <li>其余情况：立即释放（快捷栏/副手静默使用，勾了「背包烟花」则从背包换到手上用）。</li>
 * </ul>
 *
 * <p>背包烟花/背包使用设置由本模块自己提供，逻辑仍复用 {@link ElytraFlySupport}
 * （烟花等级挑选、延后释放都在那边）。
 */
public class FireworkUse extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 一键烟花快捷键 */
    private final Setting<Keybind> fireworkKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("一键烟花")
        .description("按下快捷键释放一次烟花")
        .defaultValue(Keybind.none())
        .build()
    );

    /** 允许使用背包中的烟花 */
    private final Setting<Boolean> backpackFirework = sgGeneral.add(new BoolSetting.Builder()
        .name("背包烟花")
        .description("一键烟花允许使用背包中的烟花")
        .defaultValue(false)
        .build()
    );

    /** 背包烟花的交换发包方式 */
    private final Setting<BackpackUse.Mode> backpackMode = sgGeneral.add(new EnumSetting.Builder<BackpackUse.Mode>()
        .name("背包使用发包")
        .description("2次SWAP点击；4 次PICKUP点击")
        .defaultValue(BackpackUse.Mode.SWAP)
        .visible(backpackFirework::get)
        .build()
    );

    /** 背包烟花换到哪一格使用 */
    private final Setting<BackpackUse.TargetSlot> backpackTarget = sgGeneral.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
        .name("背包使用目标槽位")
        .description("背包烟花换到哪一格使用")
        .defaultValue(BackpackUse.TargetSlot.OFFHAND)
        .visible(backpackFirework::get)
        .build()
    );

    public FireworkUse() {
        super(Categories.Misc, "一键烟花",
            "按快捷键释放一次烟花");

        // 一键烟花的背包设置交给 ElytraFlySupport（延后释放时要用）
        ElytraFlySupport.oneKeyBackpackFirework = backpackFirework;
        ElytraFlySupport.oneKeyBackpackMode = backpackMode;
        ElytraFlySupport.oneKeyBackpackTarget = backpackTarget;
    }

    /**
     * 快捷键按下（键盘键）：释放一次烟花。
     *
     * <p>Meteor 的按键设置（{@link KeybindSetting}）只在 <b>松开</b> 时回调 {@code action}，
     * 所以这里不用它的 action，改成自己接按键事件、在按下（{@link KeyAction#Press}）那一刻触发。
     * 只认按下：按住不会重复触发（Repeat 不处理），松开不会有任何动作。
     *
     * <p>界面开着时照常触发（和 Meteor 按键设置原来的行为一致）：背包交换的点击走的是
     * <b>当前打开的那个菜单</b>，{@code SlotUtils#indexToId} 会把玩家背包索引换算成该菜单里的
     * 槽位号，所以开着背包 / 容器 / Meteor 界面按也能换到手上用。
     */
    @EventHandler
    private void onKeyInput(KeyInputEvent event) {
        if (event.action != KeyAction.Press) return;
        if (!fireworkKey.get().matches(event.input)) return;
        pressFirework();
    }

    /** 快捷键按下（鼠标按键）：与键盘同理（Keybind 绑的是鼠标键时走这里） */
    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press) return;
        if (!fireworkKey.get().matches(event.input)) return;
        pressFirework();
    }

    /** 真正的一次按键触发（键盘 / 鼠标共用）：不在世界里时什么都不做 */
    private void pressFirework() {
        if (mc.player == null) return;
        ElytraFlySupport.fireworkOnce();
    }
}
