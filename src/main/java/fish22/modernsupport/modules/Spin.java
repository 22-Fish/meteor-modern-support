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

import fish22.modernsupport.utils.MovementCorrection;
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
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * 转圈（Derp）— 娱乐模块
 *
 * <p>参考 LiquidBounce ModuleDerp：不断改变服务器视角（客户端视角不动），
 * 看起来像在乱转头。偏航支持 静态/偏移/随机/抖动/旋转 五种模式，
 * 俯仰支持 静态/偏移/随机 三种模式。
 */
public class Spin extends Module {

    /** 偏航模式（显示中文） */
    public enum YawMode {
        STATIC("静态"),
        OFFSET("偏移"),
        RANDOM("随机"),
        JITTER("抖动"),
        SPIN("旋转");

        private final String displayName;

        YawMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 俯仰模式（显示中文） */
    public enum PitchMode {
        STATIC("静态"),
        OFFSET("偏移"),
        RANDOM("随机");

        private final String displayName;

        PitchMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // 移动矫正配置单独分组
    private final SettingGroup sgMovement = settings.createGroup("移动矫正");

    // 偏航
    private final Setting<YawMode> yawMode = sgGeneral.add(new EnumSetting.Builder<YawMode>()
        .name("偏航模式")
        .description("偏航（左右转头）的变化方式。")
        .defaultValue(YawMode.RANDOM)
        .build()
    );
    private final Setting<Double> yawStatic = sgGeneral.add(new DoubleSetting.Builder()
        .name("偏航角度")
        .description("静态模式下的固定偏航角度。")
        .defaultValue(0)
        .min(-180)
        .max(180)
        .build()
    );
    private final Setting<Double> yawOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("偏航偏移")
        .description("偏移模式下，相对玩家当前朝向的偏航偏移。")
        .defaultValue(0)
        .min(-180)
        .max(180)
        .build()
    );
    private final Setting<Integer> yawJitterForward = sgGeneral.add(new IntSetting.Builder()
        .name("朝前 tick")
        .description("保持当前朝向的 tick 数。")
        .defaultValue(2)
        .min(0)
        .max(100)
        .build()
    );
    private final Setting<Integer> yawJitterBackward = sgGeneral.add(new IntSetting.Builder()
        .name("朝后 tick")
        .description("转向 180° 后保持的 tick 数。")
        .defaultValue(2)
        .min(0)
        .max(100)
        .build()
    );
    private final Setting<Integer> yawSpinSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("旋转速度")
        .description("旋转模式下每 tick 转动的角度（°/tick）。")
        .defaultValue(50)
        .min(-70)
        .max(70)
        .build()
    );

    // 俯仰
    private final Setting<PitchMode> pitchMode = sgGeneral.add(new EnumSetting.Builder<PitchMode>()
        .name("俯仰模式")
        .description("俯仰的变化方式。")
        .defaultValue(PitchMode.RANDOM)
        .build()
    );
    private final Setting<Double> pitchStatic = sgGeneral.add(new DoubleSetting.Builder()
        .name("俯仰角度")
        .description("静态模式下的固定俯仰角度。")
        .defaultValue(-90)
        .min(-180)
        .max(180)
        .build()
    );
    private final Setting<Double> pitchOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("俯仰偏移")
        .description("偏移模式下，相对玩家当前俯仰的偏移。")
        .defaultValue(0)
        .min(-180)
        .max(180)
        .build()
    );

    // 其他
    private final Setting<MovementCorrection.Mode> movementCorrection = sgMovement.add(new EnumSetting.Builder<MovementCorrection.Mode>()
        .name("移动矫正")
        .description("移动矫正模式。严格：移动方向为真实旋转。静默：在严格基础上映射 WASD 按键,尝试让移动方向与视觉朝向一致。")
        .defaultValue(MovementCorrection.Mode.OFF)
        .build()
    );
    private final Setting<Boolean> smoothTurn = sgMovement.add(new BoolSetting.Builder()
        .name("平滑转头")
        .description("平滑旋转到目标角度。")
        .defaultValue(false)
        .build()
    );
    private final Setting<Integer> turnSpeed = sgMovement.add(new IntSetting.Builder()
        .name("转动速度")
        .description("平滑转头开启时，每 tick 最多转动的角度")
        .defaultValue(60)
        .min(1)
        .max(180)
        .visible(() -> smoothTurn.get())
        .build()
    );
    private final Setting<Boolean> safePitch = sgGeneral.add(new BoolSetting.Builder()
        .name("安全俯仰")
        .description("把俯仰限制在 -90° ~ 90° 之间，避免视角翻转。")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> notDuringSprint = sgGeneral.add(new BoolSetting.Builder()
        .name("疾跑时停止")
        .description("冲刺时不改变视角。")
        .defaultValue(true)
        .build()
    );

    private final Random random = new Random();

    // 抖动模式状态
    private int jitterTick = 0;
    private boolean jitterBackward = false;

    // 旋转模式状态
    private float spinYaw = 0;

    // 平滑转头状态（参考 LiquidBounce AngleSmooth）
    private float smoothYaw = 0;
    private float smoothPitch = 0;

    public Spin() {
        super(Categories.Misc, "转圈", "不断改变服务器视角（客户端视角不动），看起来像在乱转头。");
    }

    @Override
    public void onActivate() {
        jitterTick = 0;
        jitterBackward = false;
        spinYaw = mc.player != null ? mc.player.getYRot() : 0;
        smoothYaw = mc.player != null ? mc.player.getYRot() : 0;
        smoothPitch = mc.player != null ? mc.player.getXRot() : 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // 冲刺时不转
        if (notDuringSprint.get() && (mc.options.keySprint.isDown() || mc.player.isSprinting())) {
            return;
        }

        float yaw = switch (yawMode.get()) {
            case STATIC -> yawStatic.get().floatValue();
            case OFFSET -> mc.player.getYRot() + yawOffset.get().floatValue();
            case RANDOM -> random.nextFloat() * 360 - 180;
            case JITTER -> jitterYaw();
            case SPIN -> spinYaw += yawSpinSpeed.get();
        };

        float pitch = switch (pitchMode.get()) {
            case STATIC -> pitchStatic.get().floatValue();
            case OFFSET -> mc.player.getXRot() + pitchOffset.get().floatValue();
            case RANDOM -> random.nextFloat() * 360 - 180;
        };

        if (safePitch.get()) {
            pitch = Mth.clamp(pitch, -90, 90);
        }

        // 平滑转头（参考 LiquidBounce AngleSmooth）：开启时每 tick 朝目标最多转 turnSpeed 度，
        // 服务器视角呈连续轨迹；关闭时瞬间转到目标角度（默认，最快）
        if (smoothTurn.get()) {
            smoothYaw = approachAngle(smoothYaw, yaw, turnSpeed.get());
            smoothPitch = approachAngle(smoothPitch, pitch, turnSpeed.get());
        } else {
            smoothYaw = yaw;
            smoothPitch = pitch;
        }

        // 按模块的移动矫正设置旋转：严格/静默走移动矫正，其余回退原版静默旋转
        MovementCorrection.rotateWithMode(smoothYaw, smoothPitch, movementCorrection.get());
    }

    /** 角度平滑逼近（处理 ±180° 环绕），每 tick 最多变化 maxStep 度 */
    private float approachAngle(float current, float target, float maxStep) {
        float delta = Mth.wrapDegrees(target - current);
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.copySign(maxStep, delta);
    }

    /** 抖动模式：朝前 N tick、朝后 N tick 交替 */
    private float jitterYaw() {
        int ticks = jitterBackward ? yawJitterBackward.get() : yawJitterForward.get();
        if (jitterTick >= ticks) {
            jitterTick = 0;
            jitterBackward = !jitterBackward;
        }
        jitterTick++;
        return mc.player.getYRot() + (jitterBackward ? 180 : 0);
    }
}
