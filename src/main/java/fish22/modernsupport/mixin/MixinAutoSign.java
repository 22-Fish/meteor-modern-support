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

import fish22.modernsupport.utils.AutoSignMode;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.world.AutoSign;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「自动签名」（AutoSign）增强：两种模式 + 四行预设模板 + 修 IMBlocker 崩溃。
 *
 * <p><b>两种模式</b>（设置「模式」）：
 * <ul>
 *   <li><b>复制</b>：照旧——第一块告示牌手写，写完的内容抄给后面每一块。</li>
 *   <li><b>预设</b>：每块都写「第一行~第四行」这四行模板，支持占位符：
 *       {@code %name} 玩家名，{@code %Y %M %D %H %m %S} 年月日时分秒（月日时分秒补两位）。</li>
 * </ul>
 *
 * <p><b>为什么在包上拦</b>：官方原逻辑是「打开编辑界面的事件里取消界面」，界面对象已经 new 出来
 * 但没走初始化（输入框是 null）。装了「输入法冲突修复」（IMBlocker）后，它每 tick 会读一遍被它
 * 记下的界面的输入框位置，一读就空指针崩游戏——右键告示牌必崩。现在改成拦「服务器让客户端打开
 * 告示牌编辑界面」的包：要自动写的界面压根不创建，直接排队发写字包（照旧吃官方 delay 队列），
 * 雷就没了。只有复制模式还没抄到内容时才放行这个包，让玩家正常手写第一块。
 */
@Mixin(value = AutoSign.class, remap = false)
public abstract class MixinAutoSign {

    /** 玩家名占位符 */
    @Unique
    private static final String NAME_TOKEN = "%name";

    /** 官方的写字包发送队列（它的 onTick 会按 delay 一发一发地发），直接接着用 */
    @Shadow
    @Final
    private Queue<ServerboundSignUpdatePacket> queue;

    /** 官方抄下来的第一块手写内容（复制模式用） */
    @Shadow
    private String[] text;

    @Unique
    private Setting<AutoSignMode> mode;

    @Unique
    private Setting<String> line1;

    @Unique
    private Setting<String> line2;

    @Unique
    private Setting<String> line3;

    @Unique
    private Setting<String> line4;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        AutoSign self = (AutoSign) (Object) this;

        mode = new EnumSetting.Builder<AutoSignMode>()
            .name("模式")
            .description("复制：抄第一块手写告示牌的内容。预设：每块都写下面的四行模板")
            .defaultValue(AutoSignMode.Copy)
            .build();

        line1 = line("第一行", "%name到此一游");
        line2 = line("第二行", "%Y.%M.%D");
        line3 = line("第三行", "");
        line4 = line("第四行", "");

        SettingGroup group = self.settings.getDefaultGroup();
        insertAfter(group, "delay", mode);
        group.add(line1);
        group.add(line2);
        group.add(line3);
        group.add(line4);
    }

    /** 预设模式的一行模板（只有预设模式才显示） */
    @Unique
    private Setting<String> line(String name, String defaultValue) {
        return new StringSetting.Builder()
            .name(name)
            .description("支持 %name %Y %M %D %H %m %S")
            .defaultValue(defaultValue)
            .visible(() -> mode.get() == AutoSignMode.Preset)
            .build();
    }

    /**
     * 拦「服务器让客户端打开告示牌编辑界面」的包：要自动写就直接排队发写字包，界面不放出来。
     *
     * <p>官方 onSendPacket 照旧抄玩家手写内容（复制模式第一块），官方 onTick 照旧按 delay 发队列。
     */
    @Unique
    @EventHandler
    private void modernsupport$onOpenSign(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundOpenSignEditorPacket packet)) return;

        boolean preset = mode.get() == AutoSignMode.Preset;
        String[] copied = text;

        // 复制模式还没抄到内容：放行，让玩家照常手写第一块
        if (!preset && copied == null) return;

        event.cancel();

        // 包事件在网络线程，写字包进队列挪回主线程（发送队列只有主线程动）
        mc.execute(() -> {
            String[] lines = preset ? presetLines() : copied;
            queue.add(new ServerboundSignUpdatePacket(packet.getPos(), packet.isFrontText(), lines[0], lines[1], lines[2], lines[3]));
        });
    }

    /**
     * 官方「界面一打开就取消并抄字」那条路整个停用：取消界面会踩 IMBlocker 的空指针雷，
     * 抄字/写字全改在包层做（见 {@link #modernsupport$onOpenSign}）。
     */
    @Inject(method = "onOpenScreen", at = @At("HEAD"), cancellable = true)
    private void modernsupport$disableScreenCancel(OpenScreenEvent event, CallbackInfo ci) {
        ci.cancel();
    }

    /** 预设模式写进告示牌的四行 */
    @Unique
    private String[] presetLines() {
        return new String[]{
            expand(line1.get()),
            expand(line2.get()),
            expand(line3.get()),
            expand(line4.get())
        };
    }

    /**
     * 模板替换：{@code %name} 玩家名，{@code %Y %M %D %H %m %S} 年月日时分秒
     * （月日时分秒补两位），不认识的占位符原样留着。
     */
    @Unique
    private static String expand(String template) {
        if (template == null || template.isEmpty()) return "";

        LocalDateTime now = LocalDateTime.now();
        String name = mc.player != null ? mc.player.getName().getString() : "";
        StringBuilder out = new StringBuilder(template.length() + 16);

        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c != '%' || i + 1 >= template.length()) {
                out.append(c);
                continue;
            }
            if (template.startsWith(NAME_TOKEN, i)) {
                out.append(name);
                i += NAME_TOKEN.length() - 1;
                continue;
            }

            char token = template.charAt(i + 1);
            switch (token) {
                case 'Y' -> out.append(now.getYear());
                case 'M' -> out.append(pad(now.getMonthValue()));
                case 'D' -> out.append(pad(now.getDayOfMonth()));
                case 'H' -> out.append(pad(now.getHour()));
                case 'm' -> out.append(pad(now.getMinute()));
                case 'S' -> out.append(pad(now.getSecond()));
                default -> out.append(c).append(token);
            }
            i++;
        }

        return out.toString();
    }

    @Unique
    private static String pad(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    /** 插到指定设置下面（Meteor 只能末尾追加，得自己按位置插，同 MixinSpeedMine） */
    @Unique
    private static void insertAfter(SettingGroup group, String afterName, Setting<?> setting) {
        List<Setting<?>> settings = ((SettingGroupAccessor) (Object) group).getSettings();
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).name.equals(afterName)) {
                settings.add(i + 1, setting);
                return;
            }
        }
        group.add(setting);
    }
}
