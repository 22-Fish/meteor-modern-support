package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * 给 Meteor 官方 ElytraFlightModes 枚举追加「合法」「关闭」两个模式
 *
 * <p>「甲飞」已改成 ElytraFly 模块里的独立设置（「甲飞模式」），
 * 「俯仰40 / 弹跳」已拆成独立模块（{@code 鞘翅Pitch40 / 鞘翅弹跳}），
 * 所以这里只在静态初始化末尾用 Unsafe 反射追加两个常量：
 * {@code Legal}（显示名「合法」）与 {@code Off}（显示名「关闭」，简单控制默认值），
 * ordinal 依次追加在官方值之后。
 *
 * <p>新常量的 ordinal 取原数组长度往后排（不与官方值重叠）：官方 {@code onModeChanged}
 * 的 switch 落到 default 分支无操作，新模式的逻辑由 {@link MixinElytraFly} 按枚举 name 判断接管，
 * 不会破坏官方模式。
 *
 * <p><b>注意</b>：不要把官方常量从 {@code $VALUES} 里剔除来「隐藏」它们 ——
 * 官方 switch 用的 {@code ElytraFly$1.$SwitchMap} 是按 {@code ElytraFlightModes.values().length}
 * 分配、按各常量 ordinal 写入的，数组变短会在类初始化时直接数组越界崩溃。
 * 隐藏俯仰40/弹跳由 {@link MixinElytraFly}（收敛设置候选值）与
 * {@link MixinDefaultSettingsWidgetFactory}（下拉只显示 原版/发包/合法）负责。
 */
@Mixin(value = ElytraFlightModes.class, remap = false)
public abstract class MixinElytraFlightModes {

    /** 追加的枚举常量名（= 合法平飞改名后的「合法」） */
    private static final String EXTRA_LEGAL = "Legal";

    /** 追加的枚举常量名（简单控制「关闭」：模块不做任何飞行控制，官方逻辑也不跑） */
    private static final String EXTRA_OFF = "Off";

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void onClinit(CallbackInfo ci) {
        try {
            ElytraFlightModes[] original = readValues();

            // 新常量：ordinal 取原长度往后排，避开官方 switch 的 tableswitch 范围
            ElytraFlightModes legal = createConstant(EXTRA_LEGAL, original.length);
            ElytraFlightModes off = createConstant(EXTRA_OFF, original.length + 1);

            ElytraFlightModes[] freshValues = Arrays.copyOf(original, original.length + 2);
            freshValues[original.length] = legal;
            freshValues[original.length + 1] = off;
            writeValues(freshValues);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to add enum constants " + EXTRA_LEGAL + "/" + EXTRA_OFF + " to ElytraFlightModes", e);
        }
    }

    /** 合并到目标类：模式显示名（合法 / 关闭 中文化，官方模式保持原名） */
    public String toString() {
        String n = ((Enum<?>) (Object) this).name();
        if (n.equals(EXTRA_LEGAL)) return "合法";
        if (n.equals(EXTRA_OFF)) return "关闭";
        return n;
    }

    /**
     * 当前枚举的 $VALUES（= {@code values()} 的内容）。
     *
     * <p><b>方法名不能叫 values</b>：Mixin 会把本类的方法并进目标类，
     * 而枚举自带的 {@code public static values()} 是 PUBLIC 的，
     * 用 private 的同名同参方法去覆盖会直接抛
     * {@code InvalidMixinException: cannot reduce visibility of PUBLIC target method}。
     */
    private static ElytraFlightModes[] readValues() throws Exception {
        return (ElytraFlightModes[]) valuesField().get(null);
    }

    /** 用 Unsafe 反射创建枚举常量（绕过构造器与 final 限制） */
    private static ElytraFlightModes createConstant(String name, int ordinal) throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        ElytraFlightModes constant = (ElytraFlightModes) unsafe.allocateInstance(ElytraFlightModes.class);
        // name 是 String 引用字段用 putObject；ordinal 是 int 字段必须用 putInt（用 putObject 会写坏内存）
        unsafe.putObject(constant, unsafe.objectFieldOffset(Enum.class.getDeclaredField("name")), name);
        unsafe.putInt(constant, unsafe.objectFieldOffset(Enum.class.getDeclaredField("ordinal")), ordinal);
        return constant;
    }

    /** 写回 $VALUES：反射 set 在 Java 17+ 禁止修改 static final 字段，改用 Unsafe 直接写 */
    private static void writeValues(ElytraFlightModes[] values) throws Exception {
        Field valuesField = valuesField();
        sun.misc.Unsafe unsafe = getUnsafe();
        unsafe.putObject(unsafe.staticFieldBase(valuesField), unsafe.staticFieldOffset(valuesField), values);
    }

    private static Field valuesField() throws Exception {
        Field field = ElytraFlightModes.class.getDeclaredField("$VALUES");
        field.setAccessible(true);
        return field;
    }

    private static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            return sun.misc.Unsafe.getUnsafe();
        }
    }
}
