package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * 给 Meteor 官方 ElytraFlightModes 枚举追加「甲飞 / 合法平飞」两个模式
 *
 * <p>在枚举静态初始化末尾用 Unsafe 反射创建并注册两个新常量（追加到 $VALUES 末尾），
 * 官方 ElytraFly 的模式设置（flightMode）会自动多出这两项；显示名由合并的
 * {@link #toString()} 提供中文。
 *
 * <p>官方 {@code onModeChanged} 的 switch 编译为 tableswitch（只覆盖原 4 个值），
 * 新值 ordinal 落在 default 分支无操作，因此新模式的逻辑由
 * {@link MixinElytraFly} 按枚举 name 判断接管，不会破坏官方模式。
 */
@Mixin(value = ElytraFlightModes.class, remap = false)
public abstract class MixinElytraFlightModes {

    /** 追加的枚举常量名（ordinal 追加在官方值之后） */
    private static final String EXTRA_ARMOR = "Armor";
    private static final String EXTRA_LEGAL = "Legal";

    /** 已分离到独立模块「鞘翅弹跳」的官方枚举值（从 ElytraFly 模式列表移除） */
    private static final String REMOVED_BOUNCE = "Bounce";

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void onClinit(CallbackInfo ci) {
        addEnumConstant(EXTRA_ARMOR);
        addEnumConstant(EXTRA_LEGAL);
        removeEnumConstant(REMOVED_BOUNCE);
    }

    /** 合并到目标类：模式显示名（甲飞/合法平飞，官方模式保持原名） */
    public String toString() {
        String n = ((Enum<?>) (Object) this).name();
        return switch (n) {
            case EXTRA_ARMOR -> "甲飞";
            case EXTRA_LEGAL -> "合法平飞";
            default -> n;
        };
    }

    /**
     * 用 Unsafe 从 $VALUES 移除指定枚举常量（弹跳已分离为独立模块，不再出现在模式列表）。
     *
     * <p>EnumSetting 的选项来自 getEnumConstants()（反射读 $VALUES），移除后模式列表
     * 不再显示 Bounce，旧配置里的 "Bounce" 解析不到会回退默认值；
     * 官方代码中对 ElytraFlightModes.Bounce 的引用仍是合法的静态字段，永不命中，安全。
     */
    private static void removeEnumConstant(String name) {
        try {
            Field valuesField = ElytraFlightModes.class.getDeclaredField("$VALUES");
            valuesField.setAccessible(true);
            ElytraFlightModes[] oldValues = (ElytraFlightModes[]) valuesField.get(null);

            ElytraFlightModes[] filtered = Arrays.stream(oldValues)
                .filter(v -> !v.name().equals(name))
                .toArray(ElytraFlightModes[]::new);

            // 没找到目标常量，无需处理
            if (filtered.length == oldValues.length) return;

            Object base = getUnsafe().staticFieldBase(valuesField);
            long offset = getUnsafe().staticFieldOffset(valuesField);
            getUnsafe().putObject(base, offset, filtered);
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove enum constant " + name + " from ElytraFlightModes", e);
        }
    }

    /** 用 Unsafe 反射创建枚举常量并追加到 $VALUES（绕过构造器与 final 限制） */
    private static void addEnumConstant(String name) {
        try {
            Field valuesField = ElytraFlightModes.class.getDeclaredField("$VALUES");
            valuesField.setAccessible(true);
            ElytraFlightModes[] oldValues = (ElytraFlightModes[]) valuesField.get(null);

            sun.misc.Unsafe unsafe = getUnsafe();
            ElytraFlightModes constant = (ElytraFlightModes) unsafe.allocateInstance(ElytraFlightModes.class);
            // name 是 String 引用字段用 putObject；ordinal 是 int 字段必须用 putInt（用 putObject 会写坏内存）
            unsafe.putObject(constant, unsafe.objectFieldOffset(Enum.class.getDeclaredField("name")), name);
            unsafe.putInt(constant, unsafe.objectFieldOffset(Enum.class.getDeclaredField("ordinal")), oldValues.length);

            ElytraFlightModes[] freshValues = Arrays.copyOf(oldValues, oldValues.length + 1);
            freshValues[oldValues.length] = constant;
            // 反射 set 在 Java 17+ 禁止修改 static final 字段，改用 Unsafe 直接写（绕过 final/模块检查）
            Object base = unsafe.staticFieldBase(valuesField);
            long offset = unsafe.staticFieldOffset(valuesField);
            unsafe.putObject(base, offset, freshValues);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add enum constant " + name + " to ElytraFlightModes", e);
        }
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
