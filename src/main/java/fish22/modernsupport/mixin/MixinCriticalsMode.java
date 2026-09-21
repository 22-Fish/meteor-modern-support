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

import meteordevelopment.meteorclient.systems.modules.combat.Criticals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * 给 Meteor 官方「刀刀暴击」（Criticals）的 Mode 枚举追加一个「Grim」模式
 *
 * <p>做法和 {@link MixinNoFallMode} 一模一样：在 {@code <clinit>} 末尾用 Unsafe 把常量追加进
 * {@code $VALUES}，官方构造设置时 {@code getEnumConstants()} 拿到的候选值里就多一个 Grim，
 * 设置界面下拉 / 命令补全 / 配置读写都能直接用
 *
 * <p>官方那边的 switch 里没有 Grim，所以 {@link MixinCriticals} 在攻击包一进来就把它拦掉，
 * 不让它走到 switch；grim 的那套逻辑在 {@link fish22.modernsupport.utils.GrimCriticalsSupport}
 */
@Mixin(value = Criticals.Mode.class, remap = false)
public abstract class MixinCriticalsMode {

    /** 追加的枚举常量名（显示名就是它，配置里也按这个名字存） */
    @Unique
    private static final String GRIM_MODE = "Grim";

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void onClinit(CallbackInfo ci) {
        try {
            Criticals.Mode[] original = readValues();
            Criticals.Mode grim = createConstant(GRIM_MODE, original.length);

            Criticals.Mode[] freshValues = Arrays.copyOf(original, original.length + 1);
            freshValues[original.length] = grim;
            writeValues(freshValues);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add enum constant " + GRIM_MODE + " to Criticals.Mode", e);
        }
    }

    /** 当前枚举的 $VALUES（= {@code values()} 的内容） */
    @Unique
    private static Criticals.Mode[] readValues() throws Exception {
        return (Criticals.Mode[]) valuesField().get(null);
    }

    /** 用 Unsafe 创建枚举常量（绕过构造器与 final 限制） */
    @Unique
    private static Criticals.Mode createConstant(String name, int ordinal) throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        Criticals.Mode constant = (Criticals.Mode) unsafe.allocateInstance(Criticals.Mode.class);
        // name 是 String 引用字段用 putObject；ordinal 是 int 字段必须用 putInt（用 putObject 会写坏内存）
        unsafe.putObject(constant, unsafe.objectFieldOffset(Enum.class.getDeclaredField("name")), name);
        unsafe.putInt(constant, unsafe.objectFieldOffset(Enum.class.getDeclaredField("ordinal")), ordinal);
        return constant;
    }

    /** 写回 $VALUES：反射 set 在 Java 17+ 禁止修改 static final 字段，改用 Unsafe 直接写 */
    @Unique
    private static void writeValues(Criticals.Mode[] values) throws Exception {
        Field valuesField = valuesField();
        sun.misc.Unsafe unsafe = getUnsafe();
        unsafe.putObject(unsafe.staticFieldBase(valuesField), unsafe.staticFieldOffset(valuesField), values);
    }

    @Unique
    private static Field valuesField() throws Exception {
        Field field = Criticals.Mode.class.getDeclaredField("$VALUES");
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
