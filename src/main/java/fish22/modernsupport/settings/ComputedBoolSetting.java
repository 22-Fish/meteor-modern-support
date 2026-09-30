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

package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.nbt.CompoundTag;

import java.util.function.Supplier;

/**
 * 值不存、每次问都现算的布尔设置。
 *
 * <p>给 mixin 顶替目标类里被重定向的 {@code xxx.get()} 读取用（那几处要的不是设置值，
 * 是按别的设置算出来的结果）。不进设置列表：不显示、不存档、不参与命令补全。
 */
public class ComputedBoolSetting extends Setting<Boolean> {

    private final Supplier<Boolean> supplier;

    public ComputedBoolSetting(String name, Supplier<Boolean> supplier) {
        super(name, "", false, null, null, null);
        this.supplier = supplier;
    }

    @Override
    public Boolean get() {
        return supplier.get();
    }

    @Override
    protected Boolean parseImpl(String str) {
        return null;
    }

    @Override
    protected boolean isValueValid(Boolean value) {
        return true;
    }

    @Override
    protected CompoundTag save(CompoundTag tag) {
        return tag;
    }

    @Override
    protected Boolean load(CompoundTag tag) {
        return get();
    }
}
