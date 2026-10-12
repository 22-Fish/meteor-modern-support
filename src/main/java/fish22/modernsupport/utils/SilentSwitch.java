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

import fish22.modernsupport.modules.SilentSwitchConfig;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 静默切换：把「服务端认为你手上拿的槽位」和本地选中的槽位分开（水影那套的做法）
 * <p>
 * 用法：想让服务端按住某个热栏槽位，就每 tick 调一次 {@link #keep(int)}；哪一 tick 不调了，
 * 下一 tick 自动把服务端切回本地槽位。本地槽位和画面一概不动，所以客户端看不到切换
 * <p>
 * 接管期间玩家手动切物品（数字键、滚轮）不拦：本地槽位照原版切，紧接着静默切换就地让位
 * （{@link #onManualSelect()}），服务端跟着玩家切到那一格 —— 手动切的那一下以玩家为准，
 * 谁还想按住谁再 {@link #keep(int)}
 * （见 MixinMinecraftSilentSwitch / MixinMouseHandlerSilentSwitch）
 * <p>
 * 服务端手持物由我们自己直接发换手包控制（{@link #sync()}），原版那条「把手上的槽位同步给服务端」的
 * 路一个字都不改：它记的账一直是本地槽位，本地槽位没动时它什么都不发，服务端就一直拿着我们按的
 * 那一格；本地槽位一旦真的被谁切了（玩家手动、模块切槽、别的插件），原版那次同步就会把新槽位发出去，
 * 服务端跟着切，静默切换自动让位（记账由 {@link Listener} 作废，下一 tick 谁要按再 {@link #keep(int)}）
 * <p>
 * 客户端也当自己拿着的是 {@link #slot()} 那一格（见 MixinInventorySilentSwitch）：
 * 挖得快不快、挖不挖得动、手上是什么、别的模块问「主手拿的啥」，全和服务端一致。
 * 只有「本地选中槽位」这个字段本身不动 —— 热栏高亮、背包界面、手动切物品照旧
 * <p>
 * 模块里判断「手上拿的是哪把」一律用 {@link #slot()}（没接管时就是本地选中的那个），不要看本地选中槽位
 */
public final class SilentSwitch {
    /** 服务端被按住的槽位（-1 = 没接管，服务端和本地一致） */
    private static int serverSlot = -1;
    /** 本 tick 的续租（-1 = 这一 tick 没人要） */
    private static int request = -1;
    /** 放开接管之后还要把「换手」算进接管里的 tick 数（原版结账比放开晚一点，留两 tick 兜住） */
    private static int releaseGrace;
    /** 上一 tick 手部渲染有没有被 {@link #freezeHandRender} 钉住 */
    private static boolean handFrozen;
    /** 钉住的时候画面用的是哪件（用来判断玩家自己换没换手上的东西） */
    private static ItemStack frozenHandItem = ItemStack.EMPTY;

    private static final Listener LISTENER = new Listener();

    /** 有几个模块在用（GhostMine、进食修改可以同时开着，谁先谁后开关都不影响对方） */
    private static int users;

    private SilentSwitch() {
    }

    /** 模块激活：开始看发出去的换手包 */
    public static void start() {
        if (users++ == 0) {
            MeteorClient.EVENT_BUS.subscribe(LISTENER);
            reset();
        }
    }

    /** 模块关闭：立刻把服务端切回本地槽位 */
    public static void stop() {
        if (users == 0 || --users > 0) return;
        release();
        MeteorClient.EVENT_BUS.unsubscribe(LISTENER);
        reset();
    }

    /** 现在是不是接管着服务端的手持物 */
    public static boolean active() {
        return serverSlot != -1;
    }

    /** 服务端此刻认的槽位：没接管时就是本地选中的那个 */
    public static int slot() {
        if (serverSlot != -1) return serverSlot;
        return mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
    }

    /**
     * 接管期间玩家手动切槽（数字键、滚轮）：不拦，就在这里让位
     * <p>
     * 本地槽位已经由原版那条路切好了（调用方放行之后调这里），所以直接把服务端手持交出去、
     * 按新槽位同步一次 —— 手动切槽以玩家为准，之后谁还想按住谁再 {@link #keep(int)}
     */
    public static void onManualSelect() {
        if (serverSlot == -1) return;
        release();
    }

    /** 热栏高亮该画哪一格：手动切槽照切，所以就是本地选中槽位 */
    public static int displaySlot() {
        return mc.player == null ? 0 : mc.player.getInventory().getSelectedSlot();
    }

    /**
     * 手部渲染该拿哪一件主手物品
     * <p>
     * 开了「静默切换固定手部物品渲染」（默认关）时，接管期间返回「画面选中那一格」的东西
     * （{@link #displaySlot()}，就是本地选中槽位）—— 手上不会跟着静默切换换成服务端那格；
     * 没开就照旧返回服务端认的那一格
     *
     * @param serverItem 原本（服务端那格）要渲染的物品
     */
    public static ItemStack handRenderItem(ItemStack serverItem) {
        if (serverSlot == -1 || mc.player == null) return serverItem;
        if (!SilentSwitchConfig.isFixedHandItemRender()) return serverItem;
        return fixedHandItem();
    }

    /** 勾了「静默切换固定手部物品渲染」时画面该显示的那件主手物品（本地选中槽位那件） */
    public static ItemStack fixedHandItem() {
        if (mc.player == null) return ItemStack.EMPTY;
        return mc.player.getInventory().getItem(displaySlot());
    }

    /**
     * 手部渲染这一 tick 要不要钉住不播「换手动画」（{@link #handRenderItem} 的配套）
     * <p>
     * 原版手部那个高度是跟着「服务端认的那件东西」走的：服务端手持物每换一次
     * （静默切换每次按下/放开、每换一把工具都算），原版就把换手动画的进度清零
     * （见 {@link #holding()}），动画从头播 —— 画面固定成本地那件之后，画面上没换东西
     * 却一直往下沉，就是这个来的
     * <p>
     * 接管期间钉住；放开之后只要画面还是原来那件（玩家没自己切槽）也接着钉住，
     * 免得放开那一下又沉一回；玩家自己切了槽（画面换东西了）就不钉，原版那套照常
     *
     * @param shown 这一 tick 画面要用的主手物品（{@link #fixedHandItem()}）
     */
    public static boolean freezeHandRender(ItemStack shown) {
        boolean freeze = serverSlot != -1 || (handFrozen && ItemStack.matches(frozenHandItem, shown));
        handFrozen = freeze;
        frozenHandItem = freeze ? shown : ItemStack.EMPTY;
        return freeze;
    }

    /**
     * 服务端手持物现在（或者上一两 tick 刚）被我们按着
     * <p>
     * 放开那一 tick 原版还会按「手换了一件东西」结一次账，所以刚放开这两 tick 也算在内，
     * 免得漏掉放开那一下的清零
     */
    public static boolean holding() {
        return serverSlot != -1 || releaseGrace > 0;
    }

    /**
     * 本 tick 想让服务端拿这个热栏槽位
     * <p>
     * 每 tick 调一次续租。要的槽位和服务端现在拿着的不一样就当场同步一次，本地槽位不动；
     * 切过去这件事要赶在同 tick 的挖掘/使用包前面，服务端才会拿新工具算
     */
    public static void keep(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        request = slot;

        if (slot == serverSlot) return;
        serverSlot = slot;
        sync();
    }

    /** 每 tick 开头调一次：上一 tick 没人续租就把服务端切回本地槽位 */
    public static void tick() {
        if (mc.player == null) {
            reset();
            return;
        }

        if (releaseGrace > 0) releaseGrace--;

        if (request == -1) release();
        request = -1;
    }

    /** 现在就切回本地槽位、放开接管（不等下一 tick） */
    public static void release() {
        request = -1;
        if (serverSlot == -1) return;
        serverSlot = -1;
        // 放开这一 tick 原版还会按「手换了一件东西」结一次账，标记一下让它也算在被接管的那段时间里
        releaseGrace = 2;
        sync();
    }

    private static void reset() {
        serverSlot = -1;
        request = -1;
        releaseGrace = 0;
    }

    /**
     * 把我们按住的那一格（没接管时就是本地槽位）直接发给服务端
     * <p>
     * 手发换手包，不碰原版那本账（它记的一直是本地槽位）：本地槽位没动的时候原版各处调同步都
     * 什么都不发，服务端就一直拿着我们按的这格；本地槽位真被切了，原版就会把新槽位发出去，
     * 服务端跟玩家走，静默切换自动让位
     */
    private static void sync() {
        if (mc.player == null || mc.getConnection() == null) return;
        int slot = slot();
        if (slot < 0) return;
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    /**
     * 看收发两边的换手包
     * <p>
     * 发出去的：我们按着的时候有别人（别的模块直接发换手包）发的、槽位又跟我们按着的不一样，
     * 说明服务端的手持物交给它了，记账作废 —— 模块照常切，我们这边下一 tick 再按需重新按住
     * <p>
     * 收到的：服务端自己把手持物换走了（插件之类）就把账作废
     */
    private static class Listener {
        @EventHandler
        private void onPacketSend(PacketEvent.Send event) {
            if (serverSlot == -1) return;
            if (event.packet instanceof ServerboundSetCarriedItemPacket packet && packet.getSlot() != serverSlot) reset();
        }

        @EventHandler
        private void onPacketReceive(PacketEvent.Receive event) {
            if (serverSlot == -1) return;
            if (event.packet instanceof ClientboundSetHeldSlotPacket packet && packet.slot() != serverSlot) reset();
        }
    }
}
