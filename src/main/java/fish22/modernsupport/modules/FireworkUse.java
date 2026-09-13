package fish22.modernsupport.modules;

import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.ElytraFlySupport;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;

/**
 * 一键烟花（独立模块，杂项分类）
 *
 * <p>从「鞘翅飞行」模块的一键烟花设置拆出来的独立模块：按下快捷键释放一次烟花。
 *
 * <ul>
 *   <li>「鞘翅飞行」开着且处于 合法 / 甲飞：延后到滑翔窗口释放
 *       （甲飞 = 换装窗口结束后，合法 = 移动包发送后），否则服务器可能不认这次使用包；</li>
 *   <li>其余情况：立即释放（快捷栏/副手静默使用，勾了「背包烟花」则从背包换到手上用）。</li>
 * </ul>
 *
 * <p>背包烟花/背包使用模式由本模块自己提供，逻辑仍复用 {@link ElytraFlySupport}
 * （烟花等级挑选、延后释放都在那边）。
 */
public class FireworkUse extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 一键烟花快捷键 */
    private final Setting<Keybind> fireworkKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("一键烟花")
        .description("按下快捷键释放一次烟花")
        .defaultValue(Keybind.none())
        .action(ElytraFlySupport::fireworkOnce)
        .build()
    );

    /** 允许使用背包中的烟花 */
    private final Setting<Boolean> backpackFirework = sgGeneral.add(new BoolSetting.Builder()
        .name("背包烟花")
        .description("一键烟花允许使用背包中的烟花")
        .defaultValue(false)
        .build()
    );

    /** 背包烟花的交换发包模式 */
    private final Setting<BackpackUse.Mode> backpackMode = sgGeneral.add(new EnumSetting.Builder<BackpackUse.Mode>()
        .name("背包使用模式")
        .description("一键烟花背包烟花的交换发包模式。1p：SWAP 2包;2p：PICKUP 4 包。除特殊原因，请使用2p更稳定")
        .defaultValue(BackpackUse.Mode.PICKUP)
        .visible(backpackFirework::get)
        .build()
    );

    public FireworkUse() {
        super(Categories.Misc, "一键烟花",
            "按快捷键释放一次烟花（可勾选使用背包中的烟花）；鞘翅飞行处于甲飞/合法时延后到滑翔窗口释放");

        // 一键烟花的背包设置交给 ElytraFlySupport（延后释放时要用）
        ElytraFlySupport.oneKeyBackpackFirework = backpackFirework;
        ElytraFlySupport.oneKeyBackpackMode = backpackMode;
    }
}
