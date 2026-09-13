package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.player.Rotation;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅弹跳（独立模块，移动分类）
 *
 * <p>从 Meteor 官方「鞘翅飞行」的 Bounce 模式拆出来的独立模块（原逻辑来自 Elytra Recast，
 * 作者 Luna）：自动按住前进、锁定偏航与俯仰角持续滑翔，被反作弊回弹（收到位置纠正包）后
 * 按「重启延迟」自动重新起飞。
 *
 * <p>与「鞘翅飞行」互斥：两边都在控制滑翔，同时开只会互相打架。
 */
public class ElytraBounce extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 自动按住跳跃键（弹跳） */
    private final Setting<Boolean> autoJump = sgGeneral.add(new BoolSetting.Builder()
        .name("自动跳跃")
        .description("自动帮你按住跳跃键")
        .defaultValue(true)
        .build()
    );

    /** 偏航锁定方式 */
    private final Setting<Rotation.LockMode> yawLockMode = sgGeneral.add(new EnumSetting.Builder<Rotation.LockMode>()
        .name("偏航锁定")
        .description("锁定飞行偏航角的方式")
        .defaultValue(Rotation.LockMode.Smart)
        .build()
    );

    /** 简单锁定时的偏航角 */
    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("偏航角")
        .description("偏航锁定选「简单」时使用的角度")
        .defaultValue(0)
        .range(0, 360)
        .sliderRange(0, 360)
        .visible(() -> yawLockMode.get() == Rotation.LockMode.Simple)
        .build()
    );

    /** 锁定俯仰 */
    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("锁定俯仰")
        .description("锁定俯仰角")
        .defaultValue(true)
        .build()
    );

    /** 俯仰角 */
    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("俯仰角")
        .description("弹跳飞行时锁定的俯仰角")
        .defaultValue(85)
        .range(0, 90)
        .sliderRange(0, 90)
        .visible(lockPitch::get)
        .build()
    );

    /** 被回弹后自动重新起飞 */
    private final Setting<Boolean> restart = sgGeneral.add(new BoolSetting.Builder()
        .name("回弹重启")
        .description("被服务器回弹（位置纠正）后自动重新起飞")
        .defaultValue(true)
        .build()
    );

    /** 重启延迟 */
    private final Setting<Integer> restartDelay = sgGeneral.add(new IntSetting.Builder()
        .name("重启延迟")
        .description("被回弹后等多少 tick 再重新起飞")
        .defaultValue(7)
        .min(0)
        .sliderRange(0, 20)
        .visible(restart::get)
        .build()
    );

    /** 持续疾跑 */
    private final Setting<Boolean> sprint = sgGeneral.add(new BoolSetting.Builder()
        .name("持续疾跑")
        .description("一直保持疾跑")
        .defaultValue(true)
        .build()
    );

    /** 手动起飞：不自动起飞 */
    private final Setting<Boolean> manualTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("手动起飞")
        .description("不自动起飞")
        .defaultValue(false)
        .build()
    );

    /** 是否刚被回弹（等重启延迟） */
    private boolean rubberbanded = false;

    private int tickDelay;

    /** 关闭前保存的 FOV 效果缩放（持续疾跑关掉时会被改掉） */
    private double prevFov;

    public ElytraBounce() {
        super(Categories.Movement, "鞘翅弹跳",
            "弹跳：自动按住前进 + 锁定偏航/俯仰持续滑翔");
    }

    @Override
    public void onActivate() {
        // 两边都在控制滑翔，同时开只会互相打架：开这个就把「鞘翅飞行」关掉
        Module elytraFly = Modules.get().get(ElytraFly.class);
        if (elytraFly != null && elytraFly.isActive()) elytraFly.toggle();

        prevFov = mc.options.fovEffectScale().get();
        rubberbanded = false;
        tickDelay = restartDelay.get();
    }

    @Override
    public void onDeactivate() {
        unpress();
        rubberbanded = false;
        if (prevFov != 0 && !sprint.get()) mc.options.fovEffectScale().set(prevFov);
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (checkConditions() && sprint.get()) mc.player.setSprinting(true);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // 按着跳跃但没在滑翔：手动补一发起飞包（官方行为）
        if (mc.options.keyJump.isDown() && !mc.player.isFallFlying() && !manualTakeoff.get()) {
            sendStartFlying();
        }

        if (!checkConditions()) return;

        if (!rubberbanded) {
            if (prevFov != 0 && !sprint.get()) mc.options.fovEffectScale().set(0.0);
            if (autoJump.get()) mc.options.keyJump.setDown(true);
            mc.options.keyUp.setDown(true);
            mc.player.setYRot(getYawDirection());
            if (lockPitch.get()) mc.player.setXRot(pitch.get().floatValue());
        }

        if (!sprint.get()) {
            // 一直疾跑在部分反作弊上会被回弹，关掉后只在地面疾跑
            mc.player.setSprinting(mc.player.isFallFlying() ? mc.player.onGround() : true);
        }

        // 回弹：等「重启延迟」后重新起飞
        if (rubberbanded && restart.get()) {
            if (tickDelay > 0) {
                tickDelay--;
            } else {
                sendStartFlying();
                rubberbanded = false;
                tickDelay = restartDelay.get();
            }
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            rubberbanded = true;
            mc.player.stopFallFlying();
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (event.packet instanceof ServerboundPlayerCommandPacket command
            && command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            && !sprint.get()) {
            mc.player.setSprinting(true);
        }
    }

    /** 松开自动按下的前进/跳跃键 */
    private void unpress() {
        mc.options.keyUp.setDown(false);
        if (autoJump.get()) mc.options.keyJump.setDown(false);
    }

    private void sendStartFlying() {
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    /** 当前是否满足弹跳飞行的条件（官方 checkConditions） */
    private boolean checkConditions() {
        if (mc.player == null) return false;
        BlockState blockState = mc.player.getInBlockState();
        boolean isClimbing = blockState.is(BlockTags.CLIMBABLE) && !blockState.is(BlockTags.CAN_GLIDE_THROUGH);
        return !mc.player.getAbilities().flying
            && !mc.player.isPassenger()
            && !isClimbing
            && !mc.player.isInWater()
            && !mc.player.hasEffect(MobEffects.LEVITATION);
    }

    private float getYawDirection() {
        return switch (yawLockMode.get()) {
            case None -> mc.player.getYRot();
            case Smart -> Math.round((mc.player.getYRot() + 1f) / 45f) * 45f;
            case Simple -> yaw.get().floatValue();
        };
    }
}
