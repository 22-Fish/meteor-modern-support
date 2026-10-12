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

package fish22.modernsupport.utils;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.HandView;

/**
 * 手部视图「渲染副手」开关的传递。
 *
 * <p>选项本身是插进 meteor 官方 {@link HandView} 的一个设置（见 MixinHandView），
 * 但真正画手臂的是原版 ItemInHandRenderer（另一个 mixin），两个 mixin 互相看不到对方的字段，
 * 所以这里存一份设置引用给渲染那边读。
 */
public final class HandViewSupport {
    private static Setting<Boolean> renderOffhand;

    private HandViewSupport() {
    }

    /** MixinHandView 建好设置后登记进来 */
    public static void setRenderOffhandSetting(Setting<Boolean> setting) {
        renderOffhand = setting;
    }

    /** 手部视图开着 且 勾了「渲染副手」 */
    public static boolean renderOffhand() {
        if (renderOffhand == null || !renderOffhand.get()) return false;

        Modules modules = Modules.get();
        if (modules == null) return false;

        HandView handView = modules.get(HandView.class);
        return handView != null && handView.isActive();
    }
}
