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

package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 简单放置（世界分类）— 配合投影模组（Litematica）使用的轻松放置：按住快捷键，准星指哪放哪。
 *
 * <p>放置的设置项和放置逻辑都在父类 {@link PlaceModule} 里（「范围 / 穿墙范围」「精准放置协议」
 * 「合法转头」「grim非法朝向绕过」「背包放置」等），这个类只多两样：本模块的快捷键和放置间隔，
 * 以及「按住快捷键 → 每几 tick 试一次准星那一格」的驱动。
 *
 * <p>和「投影打印机」共用同一份放置实现，但设置各存各的（两个模块可以配成不一样）。
 */
public class SimplePlace extends PlaceModule {

    private final Setting<Keybind> placeKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("简单放置快捷键")
        .description("简单放置的快捷键")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("放置间隔")
        .description("放置尝试的间隔")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    /** 距离下一次尝试还剩几 tick */
    private int cooldown;

    public SimplePlace() {
        super("简单放置", "投影简单放置+，需要安装投影模组");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.screen != null) {
            abortEngine();
            return;
        }

        // 上一 tick 已经发了 Grim 能通过的合法朝向；这一 tick 发原版状态需要的朝向并放置
        if (tickPendingPlacement()) return;

        // 这个 tick 已经有放置模块动过手（绕过第一 / 第二 tick）：不再发起第二次放置
        if (engineBusy()) return;

        if (!placeKey.get().isSet() || !placeKey.get().isPressed()) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = Math.max(1, interval.get()) - 1;

        placeAtCrosshair();
    }
}
