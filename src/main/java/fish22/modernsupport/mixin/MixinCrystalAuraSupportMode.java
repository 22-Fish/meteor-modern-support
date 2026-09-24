/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 22_Fish
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * 给水晶光环的「支持块模式」追加一个「仅合法」
 *
 * <p>做法和 {@link MixinCriticalsMode} / {@link MixinNoFallMode} 一样：在枚举
 * {@code <clinit>} 末尾用 Unsafe 把常量追加进 {@code $VALUES}。官方设置构造的时候
 * {@code getEnumConstants()} 就能拿到它，下拉框、命令补全、配置读写全都能直接用，
 * 官方原来那个设置对象也照旧（不用替换，旧配置里存的值也不受影响）。
 *
 * <p>官方那边对这个枚举只有两处判断（是不是 Disabled、是不是 Fast），没有 switch，
 * 所以多一个常量不会走到什么空分支：它和「精确」走同一条路，
 * 区别在 {@link MixinCrystalAura} 里（放支撑块时用合法转头，算不出合法角度就不放）。
 */
@Mixin(value = CrystalAura.SupportMode.class, remap = false)
public abstract class MixinCrystalAuraSupportMode {

    /** 追加的枚举常量名（下拉框显示的就是它，配置里也按这个名字存） */
    @Unique
    private static final String ONLY_LEGIT = "OnlyLegit";

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void onClinit(CallbackInfo ci) {
        try {
            CrystalAura.SupportMode[] original = readValues();
            CrystalAura.SupportMode onlyLegit = createConstant(ONLY_LEGIT, original.length);

            CrystalAura.SupportMode[] freshValues = Arrays.copyOf(original, original.length + 1);
            freshValues[original.length] = onlyLegit;
            writeValues(freshValues);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add enum constant " + ONLY_LEGIT + " to CrystalAura.SupportMode", e);
        }
    }

    /** 当前枚举的 $VALUES（= {@code values()} 的内容） */
    @Unique
    private static CrystalAura.SupportMode[] readValues() throws Exception {
        return (CrystalAura.SupportMode[]) valuesField().get(null);
    }

    /** 用 Unsafe 创建枚举常量（绕过构造器与 final 限制） */
    @Unique
    private static CrystalAura.SupportMode createConstant(String name, int ordinal) throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        CrystalAura.SupportMode constant = (CrystalAura.SupportMode) unsafe.allocateInstance(CrystalAura.SupportMode.class);
        // name 是 String 引用字段用 putObject；ordinal 是 int 字段必须用 putInt（用 putObject 会写坏内存）
        unsafe.putObject(constant, unsafe.objectFieldOffset(Enum.class.getDeclaredField("name")), name);
        unsafe.putInt(constant, unsafe.objectFieldOffset(Enum.class.getDeclaredField("ordinal")), ordinal);
        return constant;
    }

    /** 写回 $VALUES：反射 set 在 Java 17+ 禁止修改 static final 字段，改用 Unsafe 直接写 */
    @Unique
    private static void writeValues(CrystalAura.SupportMode[] values) throws Exception {
        Field valuesField = valuesField();
        sun.misc.Unsafe unsafe = getUnsafe();
        unsafe.putObject(unsafe.staticFieldBase(valuesField), unsafe.staticFieldOffset(valuesField), values);
    }

    @Unique
    private static Field valuesField() throws Exception {
        Field field = CrystalAura.SupportMode.class.getDeclaredField("$VALUES");
        field.setAccessible(true);
        return field;
    }

    @Unique
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
