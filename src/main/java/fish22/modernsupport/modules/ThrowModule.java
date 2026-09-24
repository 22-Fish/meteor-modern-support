package fish22.modernsupport.modules;

import fish22.modernsupport.utils.BackpackUse;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 一键投掷（一键药水 / 一键卡墙）的公共部分
 *
 * <p>按下快捷键把物品投出去一次，物品从哪来：
 * <ul>
 *   <li>勾了「背包使用」：背包任意位置换到目标槽位用，用完换回（复用 {@link BackpackUse}）</li>
 *   <li>没勾：只认副手 / 快捷栏，背包里有也不换</li>
 * </ul>
 *
 * <p>投掷方向 = 当前的朝向 +「水平偏移」，俯仰角由设置给（90 = 正下方，0 = 水平）。
 * 角度是跟着使用包一起发出去的，客户端不用真的转头，见 {@link BackpackUse#useAngled}。
 */
public abstract class ThrowModule extends Module {

    protected final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> keybind;
    private final Setting<Double> pitch;
    private final Setting<Double> yawOffset;
    private final Setting<Boolean> backpackUse;
    private final Setting<BackpackUse.Mode> backpackMode;
    private final Setting<BackpackUse.TargetSlot> backpackTarget;

    protected ThrowModule(Category category, String name, String description, double defaultPitch) {
        super(category, name, description);

        keybind = sgGeneral.add(new KeybindSetting.Builder()
            .name("快捷键")
            .description("按下快捷键投一次")
            .defaultValue(Keybind.none())
            .build()
        );

        pitch = sgGeneral.add(new DoubleSetting.Builder()
            .name("俯仰角")
            .description("90 = 正下方，0 = 水平，-90 = 正上方")
            .defaultValue(defaultPitch)
            .range(-90, 90)
            .sliderRange(-90, 90)
            .build()
        );

        yawOffset = sgGeneral.add(new DoubleSetting.Builder()
            .name("水平偏移")
            .description("在朝向的基础上左右偏多少度")
            .defaultValue(0.0)
            .range(-180, 180)
            .sliderRange(-180, 180)
            .build()
        );

        backpackUse = sgGeneral.add(new BoolSetting.Builder()
            .name("背包使用")
            .description("物品在背包里时也换出来投")
            .defaultValue(false)
            .build()
        );

        backpackMode = sgGeneral.add(new EnumSetting.Builder<BackpackUse.Mode>()
            .name("背包使用发包")
            .description("2次SWAP点击；4 次PICKUP点击")
            .defaultValue(BackpackUse.Mode.SWAP)
            .visible(backpackUse::get)
            .build()
        );

        backpackTarget = sgGeneral.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
            .name("背包使用目标槽位")
            .description("背包物品换到哪一格使用")
            .defaultValue(BackpackUse.TargetSlot.OFFHAND)
            .visible(backpackUse::get)
            .build()
        );
    }

    /** 要投出去的物品 */
    protected abstract Predicate<ItemStack> target();

    /** 快捷键按下（键盘键） */
    @EventHandler
    private void onKeyInput(KeyInputEvent event) {
        if (event.action != KeyAction.Press) return;
        if (!keybind.get().matches(event.input)) return;
        throwOnce();
    }

    /** 快捷键按下（鼠标键） */
    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press) return;
        if (!keybind.get().matches(event.input)) return;
        throwOnce();
    }

    /** 触发一次：算好角度，把物品从快捷栏或背包拿出来投出去 */
    private void throwOnce() {
        if (mc.player == null) return;

        // 开着容器（箱子之类）时不触发：背包交换的点击会点到容器菜单上
        if (mc.player.containerMenu.containerId != 0) return;

        float yaw = mc.player.getYRot() + yawOffset.get().floatValue();
        float throwPitch = pitch.get().floatValue();

        if (backpackUse.get()) {
            BackpackUse.useAngled(target(), backpackMode.get(), backpackTarget.get(), yaw, throwPitch);
        } else {
            BackpackUse.fromHotbar(target(), hand -> BackpackUse.useAngled(hand, yaw, throwPitch));
        }
    }
}
