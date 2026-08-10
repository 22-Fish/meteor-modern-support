package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.MovementCorrection;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * KillAura 移动矫正集成 mixin
 *
 * 为 Meteor 的 KillAura 添加移动矫正模式设置项 "movement-correction"，
 * 并通过 {@code @Redirect} 精确拦截 {@link Rotations#rotate(double, double)}
 * 的两个调用点，替换为 {@link MovementCorrection#rotate(double, double, MovementCorrection.Mode)}。
 *
 * <p>SEVERE：旋转时客户端显示实际朝向（视角跟随）；NO_MOVE：旋转时客户端静默。
 * 设置项仅在 KillAura 旋转模式非 None 时可见。
 */
@Mixin(value = KillAura.class, remap = false)
public abstract class MixinKillAura {

    @Shadow
    private Setting<KillAura.RotationMode> rotation;

    @Shadow
    private int hitTimer;

    @Unique
    private Setting<MovementCorrection.Mode> movementCorrectionMode;

    @Unique
    private Setting<Integer> onHitHoldTicks;

    @Unique
    private Setting<Boolean> smoothLook;

    @Unique
    private Setting<Integer> smoothSpeed;

    @Unique
    private float smoothYaw;

    @Unique
    private float smoothPitch;

    /** 延迟到移动包发送后执行的目标（攻击包必须晚于旋转包发出，服务器视角到位后才能命中） */
    @Unique
    private final List<Entity> pendingAttacks = new ArrayList<>();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        KillAura self = (KillAura) (Object) this;

        SettingGroup sg = self.settings.createGroup("移动矫正");

        movementCorrectionMode = sg.add(new EnumSetting.Builder<MovementCorrection.Mode>()
            .name("移动矫正")
            .description("移动矫正模式。严格：真实旋转但客户端静默（服务器朝向正确，视角不动）。静默：在严格基础上映射 WASD 按键，移动方向与视觉朝向一致。停止移动暂未实现。")
            .defaultValue(MovementCorrection.Mode.OFF)
            .visible(() -> rotation.get() != KillAura.RotationMode.None)
            .build()
        );

        onHitHoldTicks = sg.add(new IntSetting.Builder()
            .name("转回延迟")
            .description("攻击时旋转（OnHit）模式下，攻击后保持旋转的 tick 数，然后转回原朝向。")
            .defaultValue(1)
            .min(0)
            .max(20)
            .visible(() -> rotation.get() == KillAura.RotationMode.OnHit)
            .build()
        );

        smoothLook = sg.add(new BoolSetting.Builder()
            .name("平滑转头")
            .description("平滑旋转到目标角度（服务器视角连续，反作弊友好，但转动耗时长）。关闭时瞬间转到目标角度（最快）。")
            .defaultValue(false)
            .visible(() -> rotation.get() != KillAura.RotationMode.None)
            .build()
        );

        smoothSpeed = sg.add(new IntSetting.Builder()
            .name("转动速度")
            .description("平滑转头开启时，每 tick 最多转动的角度（°/tick）。")
            .defaultValue(60)
            .min(1)
            .max(180)
            .visible(() -> rotation.get() != KillAura.RotationMode.None && smoothLook.get())
            .build()
        );
    }

    // ====== 激活时初始化平滑视角状态 ======

    @Inject(method = "onActivate", at = @At("TAIL"))
    private void onActivate(CallbackInfo ci) {
        // 平滑视角从玩家当前视角开始，避免从 0 开始逼近导致大跳变/绕远路
        smoothYaw = MeteorClient.mc.player != null ? MeteorClient.mc.player.getYRot() : 0;
        smoothPitch = MeteorClient.mc.player != null ? MeteorClient.mc.player.getXRot() : 0;
    }

    // ====== Always 模式：在 onTick 中拦截 Rotations.rotate(DD) ======

    @Redirect(
        method = "onTick",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DD)V"
        )
    )
    private void redirectRotateAlways(double yaw, double pitch) {
        MovementCorrection.Mode mode = movementCorrectionMode.get();
        if (mode == MovementCorrection.Mode.SEVERE || mode == MovementCorrection.Mode.QUIET) {
            applySmooth(yaw, pitch);
            MovementCorrection.rotate(smoothYaw, smoothPitch, mode);
        } else {
            // 关闭 / 停止移动（未实现）：回退原版静默旋转
            Rotations.rotate(yaw, pitch);
        }
    }

    // ====== OnHit 模式：在 attack 中拦截 Rotations.rotate(DD) ======

    @Redirect(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DD)V"
        )
    )
    private void redirectRotateOnHit(double yaw, double pitch) {
        MovementCorrection.Mode mode = movementCorrectionMode.get();
        if (mode == MovementCorrection.Mode.SEVERE || mode == MovementCorrection.Mode.QUIET) {
            applySmooth(yaw, pitch);
            MovementCorrection.rotate(smoothYaw, smoothPitch, mode);
            // 攻击后保持旋转 N tick 再转回原朝向
            MovementCorrection.setHoldTicks(onHitHoldTicks.get());
        } else {
            // 关闭 / 停止移动（未实现）：回退原版静默旋转
            Rotations.rotate(yaw, pitch);
        }
    }

    // ====== 攻击动作延迟：攻击包延后到移动包（含旋转）发送之后 ======

    @Redirect(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V"
        )
    )
    private void redirectGameModeAttack(MultiPlayerGameMode gameMode, Player player, Entity target) {
        // 不立即发包，记录目标，等移动包发送完毕后统一攻击
        pendingAttacks.add(target);
        MovementCorrection.runAfterSend(this::doPendingAttacks);
    }

    /** 执行延迟的攻击（在移动包发送完毕后被调用，此时服务器视角已到位） */
    @Unique
    private void doPendingAttacks() {
        if (mc == null || mc.player == null || pendingAttacks.isEmpty()) return;

        for (Entity target : pendingAttacks) {
            mc.gameMode.attack(mc.player, target);
        }
        pendingAttacks.clear();
        hitTimer = 0;
    }

    /** 按「平滑视角」设置处理旋转：开启时按速度渐进，关闭时直接设置目标角度（瞬间到位） */
    @Unique
    private void applySmooth(double yaw, double pitch) {
        if (smoothLook.get()) {
            smoothYaw = approachAngle(smoothYaw, (float) yaw, smoothSpeed.get());
            smoothPitch = approachAngle(smoothPitch, (float) pitch, smoothSpeed.get());
        } else {
            // 平滑关闭：直接设置目标角度，瞬间到位
            smoothYaw = (float) yaw;
            smoothPitch = (float) pitch;
        }
    }

    /** 角度平滑逼近（处理 ±180° 环绕），每 tick 最多变化 maxStep 度 */
    @Unique
    private static float approachAngle(float current, float target, float maxStep) {
        float delta = Mth.wrapDegrees(target - current);
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.copySign(maxStep, delta);
    }
}
