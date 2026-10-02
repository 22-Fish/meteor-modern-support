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

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 静默切换：把「服务端认为你手上拿的槽位」和本地选中的槽位分开
 * <p>
 * 用法：想让服务端按住某个热栏槽位，就每 tick 调一次 {@link #keep(int)}；哪一 tick 不调了，
 * 下一 tick 自动把服务端切回本地槽位。本地槽位和画面一概不动，所以客户端看不到切换（水影那种静默切换）
 * <p>
 * 接管期间玩家手动切物品只改本地槽位和画面：原版每 tick 那次「把本地槽位同步给服务端」会被拦下来，
 * 手动切换就不影响正在按着的槽位（别的模块自己的切换不走这条路，照常发给服务端）；
 * 切回时（{@link #release()}）再把本地槽位发给服务端
 * <p>
 * 模块里判断「手上拿的是哪把」一律用 {@link #slot()}（没接管时就是本地选中的那个），不要看本地选中槽位
 */
public final class SilentSwitch {
    /** 服务端被按住的槽位（-1 = 没接管，服务端和本地一致） */
    private static int serverSlot = -1;
    /** 本 tick 的续租（-1 = 这一 tick 没人要） */
    private static int request = -1;
    /** 自己刚发出去那个包的槽位（事件里用它跟别人的包分开），每 tick 开头清掉 */
    private static int selfSlot = -1;

    private static final Listener LISTENER = new Listener();

    private SilentSwitch() {
    }

    /** 模块激活：开始看发出去的换手包 */
    public static void start() {
        MeteorClient.EVENT_BUS.subscribe(LISTENER);
        reset();
    }

    /** 模块关闭：立刻把服务端切回本地槽位 */
    public static void stop() {
        MeteorClient.EVENT_BUS.unsubscribe(LISTENER);
        release();
    }

    /** 现在是不是接管着服务端的手持物 */
    public static boolean active() {
        return serverSlot != -1;
    }

    /**
     * 原版每 tick 那个「把本地槽位同步给服务端」的包要不要拦（mixin 里问，只拦手动切物品这一条路）
     * <p>
     * 只有接管着、而且玩家本地拿的不是我们按着的那格时才拦：手动切物品就只改本地槽位和画面
     */
    public static boolean blockClientSync() {
        return serverSlot != -1 && mc.player != null && mc.player.getInventory().getSelectedSlot() != serverSlot;
    }

    /** 服务端此刻认的槽位：没接管时就是本地选中的那个 */
    public static int slot() {
        if (serverSlot != -1) return serverSlot;
        return mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
    }

    /**
     * 本 tick 想让服务端拿这个热栏槽位
     * <p>
     * 每 tick 调一次续租。要的槽位和服务端现在拿着的不一样就当场补一个换手包，本地槽位不动
     */
    public static void keep(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        request = slot;

        int local = mc.player.getInventory().getSelectedSlot();
        if (slot == local) {
            // 客户端本来就拿着这格：不用接管；之前按着别的槽位就先放回去
            if (serverSlot != -1) send(local);
            serverSlot = -1;
            return;
        }

        if (slot != serverSlot) send(slot);
        serverSlot = slot;
    }

    /** 每 tick 开头调一次：上一 tick 没人续租就把服务端切回本地槽位 */
    public static void tick() {
        selfSlot = -1;

        if (mc.player == null) {
            reset();
            return;
        }

        if (request == -1) {
            release();
            return;
        }
        request = -1;
    }

    /** 现在就切回本地槽位、放开接管（不等下一 tick） */
    public static void release() {
        if (serverSlot != -1 && mc.player != null) send(mc.player.getInventory().getSelectedSlot());
        reset();
    }

    private static void reset() {
        serverSlot = -1;
        request = -1;
        selfSlot = -1;
    }

    /** 只给服务端发一个换手包（本地槽位不动，客户端画面不切） */
    private static void send(int slot) {
        if (mc.getConnection() == null) return;
        selfSlot = slot;
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    /**
     * 看收发两边的换手包
     * <p>
     * 发出去的：自己按着的时候有别人（别的模块切物品）发的包，说明服务端的手持物交给它了，记账作废 ——
     * 模块照常切，我们这边下一 tick 再按需重新按住
     * （手动切物品那一路在 {@code MultiPlayerGameMode.tick()} 里就被拦了，见 MixinMultiPlayerGameModeSilentSwitch）
     * <p>
     * 收到的：服务端自己把手持物换走了（插件之类）就把账作废
     */
    private static class Listener {
        @EventHandler
        private void onPacketSend(PacketEvent.Send event) {
            if (!(event.packet instanceof ServerboundSetCarriedItemPacket packet)) return;

            if (packet.getSlot() == selfSlot) {   // 刚自己发的那个
                selfSlot = -1;
                return;
            }

            if (packet.getSlot() != serverSlot) reset();
        }

        @EventHandler
        private void onPacketReceive(PacketEvent.Receive event) {
            if (serverSlot == -1) return;
            if (event.packet instanceof ClientboundSetHeldSlotPacket packet && packet.slot() != serverSlot) reset();
        }
    }
}
