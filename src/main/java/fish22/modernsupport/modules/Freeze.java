package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/**
 * 冻结 (Freeze) 模块
 *
 * 冻结时完全移除移动运算（WASD、跳跃、击退、下坠全部不动），
 * 不发送位置移动包（旋转包照发，可正常转头），输入包中的
 * 移动/疾跑标志归零——服务器认为你完全静止，不会产生回弹。
 *
 * <p>不强制锁定位置，活塞推、末影珍珠、传送等服务端权威的
 * 位置更新仍然正常生效，不会和服务器较劲。
 *
 * <p>移植自 AEfish。
 */
public class Freeze extends Module {

    /** 冻结状态标志（travel/输入 mixin 检查用） */
    private static boolean frozen = false;

    /** 外部请求的冻结状态（如鞘翅飞行·合法平飞的悬停冻结），不依赖本模块开关 */
    private static boolean externalFrozen = false;

    /** 是否处于冻结状态（本模块开启 或 外部请求冻结） */
    public static boolean isFrozen() {
        return frozen || externalFrozen;
    }

    /** 设置外部冻结状态（合法平飞悬停=冻结时调用；取消时传 false） */
    public static void setExternalFrozen(boolean value) {
        externalFrozen = value;
    }

    /** 解冻时的动量处理方式 */
    public enum UnfreezeMotion {
        KeepAll,     // 保留所有动量（冻结前 + 冻结期间受到的）
        KeepFrozen,  // 保留冻结前的动量（丢弃期间受到的）
        Clear        // 动量清零
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> stopAll = sgGeneral.add(new BoolSetting.Builder()
        .name("停止所有")
        .description("冻结时停止发送全部数据包。")
        .defaultValue(false)
        .build()
    );

    private final Setting<UnfreezeMotion> unfreezeMotion = sgGeneral.add(new EnumSetting.Builder<UnfreezeMotion>()
        .name("解冻动量")
        .description("""
            解冻时的动量处理方式。
            KeepAll（保留全部）：保留受到的所有动量
            KeepFrozen（丢弃期间动量）：恢复冻结前的动量，冻结期间动量丢弃
            Clear（清零）：解冻后动量归零""")
        .defaultValue(UnfreezeMotion.KeepAll)
        .build()
    );

    /** 冻结前记录的动量（「保留冻结前」模式用于恢复） */
    private Vec3 frozenMotion;

    public Freeze() {
        super(Categories.Movement, "冻结",
            "冻结时完全移除移动运算并不发位置移动包；旋转照常。珍珠、传送等位置更新不受影响。关闭后恢复。");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        frozenMotion = mc.player.getDeltaMovement();
        frozen = true;
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) {
            switch (unfreezeMotion.get()) {
                case KeepFrozen -> {
                    // 恢复冻结前动量，丢弃期间受到的
                    if (frozenMotion != null) mc.player.setDeltaMovement(frozenMotion);
                }
                case Clear -> mc.player.setDeltaMovement(Vec3.ZERO);
                case KeepAll -> { /* 什么都不做，保留当前动量 */ }
            }
        }

        frozenMotion = null;
        frozen = false;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (mc.player == null) return;

        // 停止所有数据包
        if (stopAll.get()) {
            event.cancel();
            return;
        }

        // 冻结时拦截含位置的移动包，纯旋转包照常发送（转头正常）
        if (event.packet instanceof ServerboundMovePlayerPacket movePacket && movePacket.hasPosition()) {
            event.cancel();
        }
    }
}
