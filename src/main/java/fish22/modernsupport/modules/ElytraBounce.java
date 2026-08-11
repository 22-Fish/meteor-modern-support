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

import meteordevelopment.meteorclient.MeteorClient;
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
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 鞘翅弹跳 — 从「鞘翅飞行」（ElytraFly）分离出的 Bounce 弹跳功能
 *
 * <p>meteor模式：完整移植 Meteor 官方 ElytraFly Bounce 模式的逻辑
 * （俯仰/偏航锁定、自动跳跃、落地自动重飞 recast、防回弹重试等），行为与原版一致。
 *
 * <p>合法模式：穿着鞘翅时只要在空中（不在地面）就自动起飞，
 * 并可按需锁定俯仰（默认 90° 朝下俯冲加速）、锁定偏航、自动跳跃（落地自动跳起再飞）。
 * 起飞参考合法平飞：本地 tryToStartFallFlying 立即进入滑翔 + 发包，不等服务器广播。
 */
public class ElytraBounce extends Module {

    /** 弹跳模式 */
    public enum Mode {
        Meteor("meteor模式"),
        Legal("合法模式");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 偏航锁定方式（功能同 Meteor 官方 Rotation.LockMode，显示中文） */
    public enum YawLockMode {
        Smart("智能"),
        Simple("简单"),
        None("关闭");

        private final String displayName;

        YawLockMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ====== 模式 ======

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("模式")
        .description("meteor模式：原版弹跳（俯仰/偏航锁定、自动跳跃、落地重飞等）。合法模式：穿着鞘翅时只要在空中就自动起飞。")
        .defaultValue(Mode.Meteor)
        .build()
    );

    // ====== meteor模式设置（原版 Bounce 全套） ======

    private final Setting<Boolean> autoJump = sgGeneral.add(new BoolSetting.Builder()
        .name("自动跳跃")
        .description("自动为你按住跳跃键。")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Meteor)
        .build()
    );

    private final Setting<YawLockMode> yawLockMode = sgGeneral.add(new EnumSetting.Builder<YawLockMode>()
        .name("偏航锁定")
        .description("偏航锁定方式。智能：吸附到最近的 45° 倍数。简单：锁定为固定角度。关闭：不锁定。")
        .defaultValue(YawLockMode.Smart)
        .visible(() -> mode.get() == Mode.Meteor)
        .build()
    );

    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("偏航角度")
        .description("偏航锁定为「简单」时使用的固定偏航角度。")
        .defaultValue(0)
        .range(0, 360)
        .sliderRange(0, 360)
        .visible(() -> mode.get() == Mode.Meteor && yawLockMode.get() == YawLockMode.Simple)
        .build()
    );

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("俯仰锁定")
        .description("是否锁定俯仰角度。")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Meteor)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("俯仰角度")
        .description("俯仰锁定开启时使用的固定俯仰角度。")
        .defaultValue(85)
        .range(0, 90)
        .sliderRange(0, 90)
        .visible(() -> mode.get() == Mode.Meteor && lockPitch.get())
        .build()
    );

    private final Setting<Boolean> restart = sgGeneral.add(new BoolSetting.Builder()
        .name("防回弹重试")
        .description("被服务器回弹后自动重新起飞。")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Meteor)
        .build()
    );

    private final Setting<Integer> restartDelay = sgGeneral.add(new IntSetting.Builder()
        .name("重试延迟")
        .description("回弹后等待多少 tick 再重新起飞。")
        .defaultValue(7)
        .min(0)
        .sliderRange(0, 20)
        .visible(() -> mode.get() == Mode.Meteor && restart.get())
        .build()
    );

    private final Setting<Boolean> sprint = sgGeneral.add(new BoolSetting.Builder()
        .name("持续冲刺")
        .description("一直保持冲刺。关闭时只在地面冲刺（部分反作弊会因此回弹）。")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Meteor)
        .build()
    );

    private final Setting<Boolean> manualTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("手动起飞")
        .description("不自动起飞，需要按住跳跃键起飞。")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.Meteor)
        .build()
    );

    // ====== 合法模式设置（按需锁定视角） ======

    private final Setting<Boolean> legalLockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("俯仰锁定")
        .description("是否锁定俯仰角度（起飞后视角自动朝固定俯仰角）。")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Legal)
        .build()
    );

    private final Setting<Double> legalPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("俯仰角度")
        .description("俯仰锁定开启时使用的固定俯仰角度，默认 90° 朝下（垂直俯冲加速）。")
        .defaultValue(90)
        .min(-90)
        .max(90)
        .sliderRange(-90, 90)
        .visible(() -> mode.get() == Mode.Legal && legalLockPitch.get())
        .build()
    );

    private final Setting<YawLockMode> legalYawLockMode = sgGeneral.add(new EnumSetting.Builder<YawLockMode>()
        .name("偏航锁定")
        .description("偏航锁定方式（同 meteor 原版）。智能：吸附到最近的 45° 倍数。简单：锁定为固定角度。关闭：不锁定。")
        .defaultValue(YawLockMode.Smart)
        .visible(() -> mode.get() == Mode.Legal)
        .build()
    );

    private final Setting<Double> legalYaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("偏航角度")
        .description("偏航锁定为「简单」时使用的固定偏航角度。")
        .defaultValue(0)
        .range(0, 360)
        .sliderRange(0, 360)
        .visible(() -> mode.get() == Mode.Legal && legalYawLockMode.get() == YawLockMode.Simple)
        .build()
    );

    private final Setting<Boolean> legalAutoJump = sgGeneral.add(new BoolSetting.Builder()
        .name("自动跳跃")
        .description("自动为你按住跳跃键，落地瞬间自动跳起，配合自动起飞连续弹跳。")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Legal)
        .build()
    );

    // ====== meteor模式状态（原版 Bounce 逻辑） ======

    /** 是否被服务器回弹（收到位置纠正包） */
    private boolean rubberbanded = false;

    /** 回弹后重试倒计时 */
    private int tickDelay = 7;

    /** 进入模块前的 FOV 缩放值（退出时恢复） */
    private double prevFov = 0;

    // ====== 合法模式状态 ======

    /** 上一 tick 的滑翔状态（检测服务器取消起飞） */
    private boolean wasFlying = false;

    /**
     * 起飞节流倒计时：本地假滑翔后被服务器取消（tryToStartFallFlying 失败会
     * stopFallFlying 取消滑翔）时冷却几 tick 再重试，避免来回震荡
     */
    private int takeoffCooldown = 0;

    public ElytraBounce() {
        super(Categories.Movement, "鞘翅弹跳", "鞘翅飞行中的弹跳（Bounce）功能，已从「鞘翅飞行」模块分离。meteor模式：原版行为。合法模式：穿着鞘翅时只要在空中就自动起飞。");
    }

    /** 是否为 meteor 模式（供 recast mixin 判断） */
    public boolean isMeteorMode() {
        return mode.get() == Mode.Meteor;
    }

    @Override
    public String getInfoString() {
        return mode.get().toString();
    }

    @Override
    public void onActivate() {
        rubberbanded = false;
        tickDelay = restartDelay.get();
        wasFlying = false;
        takeoffCooldown = 0;
        if (mode.get() == Mode.Meteor) {
            prevFov = mc.options.fovEffectScale().get();
        }
    }

    @Override
    public void onDeactivate() {
        unpress();
        rubberbanded = false;
        tickDelay = restartDelay.get();
        takeoffCooldown = 0;
        if (mode.get() == Mode.Meteor && prevFov != 0 && !sprint.get()) {
            mc.options.fovEffectScale().set(prevFov);
        }
    }

    // ====== 事件 ======

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        if (mode.get() == Mode.Legal) {
            legalTick();
        } else {
            meteorTick();
        }
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null || mode.get() != Mode.Meteor) return;
        // 原版：持续冲刺开启时每 tick 强制冲刺
        if (checkConditions(mc.player) && sprint.get()) mc.player.setSprinting(true);
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mode.get() != Mode.Meteor) return;
        // 原版：非持续冲刺时，起飞瞬间强制冲刺
        if (event.packet instanceof ServerboundPlayerCommandPacket packet
            && packet.getAction().equals(ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
            && !sprint.get()) {
            mc.player.setSprinting(true);
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mode.get() != Mode.Meteor) return;
        // 原版：收到位置纠正（回弹）时标记，停止滑翔，延迟后自动重试起飞
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            rubberbanded = true;
            mc.player.stopFallFlying();
        }
    }

    // ====== 合法模式：穿着鞘翅，在空中就起飞 ======

    private void legalTick() {
        LocalPlayer p = mc.player;

        // 滑翔状态从 true 变 false（服务器取消起飞/落地）：起飞被拒时冷却几 tick 再试，
        // 避免「假滑翔 → 服务器取消 → 再假滑翔」来回震荡
        boolean flying = p.isFallFlying();
        if (wasFlying && !flying) {
            takeoffCooldown = 5;
        }
        wasFlying = flying;

        // 必须穿着鞘翅，否则不干预（不按跳跃、不锁视角）
        if (!LivingEntity.canGlideUsing(p.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST)) return;

        // 弹跳条件（非创造飞行/非乘客/非攀爬/非水中/无漂浮）：
        // 自动跳跃（落地瞬间自动跳起）+ 俯仰/偏航锁定
        if (checkConditions(p)) {
            if (legalAutoJump.get()) mc.options.keyJump.setDown(true);
            p.setYRot(getLegalYawDirection());
            if (legalLockPitch.get()) p.setXRot(legalPitch.get().floatValue());
        }

        if (flying) return;

        // 落地：重置冷却（重新离地立即起飞）
        if (p.onGround()) {
            takeoffCooldown = 0;
            return;
        }

        // 起飞节流：冷却期间不重发
        if (takeoffCooldown > 0) {
            takeoffCooldown--;
            return;
        }

        // 参考合法平飞：本地 tryToStartFallFlying 立即进入滑翔 + 发包。
        // 只发包等广播会受网络延迟影响（广播未到前重发会被服务器 stopFallFlying 取消），
        // 本地先滑翔则广播只是确认，不依赖延迟
        if (p.tryToStartFallFlying()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    // ====== meteor模式：原版 Bounce 逻辑（移植自 Meteor 官方 ElytraFly Bounce 模式） ======

    private void meteorTick() {
        LocalPlayer p = mc.player;

        // 按住跳跃键且未滑翔时手动起飞（开启「手动起飞」时跳过）
        if (mc.options.keyJump.isDown() && !p.isFallFlying() && !manualTakeoff.get()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }

        // 满足弹跳条件（非创造飞行、非乘客、非攀爬、非水中、无漂浮）
        if (checkConditions(p)) {
            if (!rubberbanded) {
                // 屏蔽 FOV 反复缩放
                if (prevFov != 0 && !sprint.get()) mc.options.fovEffectScale().set(0.0);
                // 自动跳跃 + 强制前进
                if (autoJump.get()) mc.options.keyJump.setDown(true);
                mc.options.keyUp.setDown(true);
                // 偏航/俯仰锁定
                p.setYRot(getYawDirection());
                if (lockPitch.get()) p.setXRot(pitch.get().floatValue());
            }

            if (!sprint.get()) {
                // 关闭持续冲刺时：飞行中只在地面冲刺（部分反作弊要求）
                if (p.isFallFlying()) p.setSprinting(p.onGround());
                else p.setSprinting(true);
            }

            // 回弹后延迟重试起飞
            if (rubberbanded && restart.get()) {
                if (tickDelay > 0) {
                    tickDelay--;
                } else {
                    mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    rubberbanded = false;
                    tickDelay = restartDelay.get();
                }
            }
        }
    }

    private void unpress() {
        mc.options.keyUp.setDown(false);
        if (autoJump.get()) mc.options.keyJump.setDown(false);
        if (legalAutoJump.get()) mc.options.keyJump.setDown(false);
    }

    /** 计算偏航锁定目标角度（智能/简单/关闭），meteor 模式 */
    private float getYawDirection() {
        return switch (yawLockMode.get()) {
            case None -> mc.player.getYRot();
            case Smart -> Math.round((mc.player.getYRot() + 1f) / 45f) * 45f;
            case Simple -> yaw.get().floatValue();
        };
    }

    /** 计算偏航锁定目标角度（智能/简单/关闭），合法模式 */
    private float getLegalYawDirection() {
        return switch (legalYawLockMode.get()) {
            case None -> mc.player.getYRot();
            case Smart -> Math.round((mc.player.getYRot() + 1f) / 45f) * 45f;
            case Simple -> legalYaw.get().floatValue();
        };
    }

    /** 原版弹跳条件：非创造飞行、非乘客、非攀爬、非水中、无漂浮 */
    public static boolean checkConditions(LocalPlayer player) {
        BlockState blockState = player.getInBlockState();
        boolean isClimbing = (blockState.is(BlockTags.CLIMBABLE) && !blockState.is(BlockTags.CAN_GLIDE_THROUGH));
        return (!player.getAbilities().flying && !player.isPassenger() && !isClimbing && !player.isInWater() && !player.hasEffect(MobEffects.LEVITATION));
    }

    /** 本地立即进入滑翔状态（不等服务器广播） */
    private static boolean startGliding(LocalPlayer player) {
        for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
            if (LivingEntity.canGlideUsing(player.getItemBySlot(equipmentSlot), equipmentSlot)) {
                MeteorClient.mc.executeIfPossible(player::startFallFlying);
                return true;
            }
        }
        return false;
    }

    /** 落地重飞（meteor模式）：条件满足时本地恢复滑翔并发送起飞包 */
    public static boolean recastElytra(LocalPlayer player) {
        if (checkConditions(player) && startGliding(player)) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            return true;
        }
        return false;
    }
}
