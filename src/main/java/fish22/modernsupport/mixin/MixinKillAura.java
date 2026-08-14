package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.MovementCorrection;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Shadow
    private Setting<Double> range;

    @Shadow
    private Setting<Boolean> tpsSync;

    @Shadow
    private SettingGroup sgTiming;

    @Unique
    private Setting<MovementCorrection.Mode> movementCorrectionMode;

    @Unique
    private Setting<Integer> onHitHoldTicks;

    @Unique
    private Setting<Boolean> aimAndRangeOptimization;

    @Unique
    private Setting<Boolean> rangeRender;

    @Unique
    private Setting<SettingColor> rangeColor;

    /** TPS 为 0 时聊天栏警告开关（插在 TPS-sync 下面） */
    @Unique
    private Setting<Boolean> chatWarn;

    /** 上次检测 TPS 是否为 0（边沿触发警告，避免刷屏） */
    @Unique
    private boolean lastTpsZero;

    /** 延迟到移动包发送后执行的目标（攻击包必须晚于旋转包发出，服务器视角到位后才能命中） */
    @Unique
    private final List<Entity> pendingAttacks = new ArrayList<>();

    /** entityCheck 当前正在判定的目标（范围判定改为眼位距离时用，见 redirectRangeCheck） */
    @Unique
    private Entity entityCheckTarget;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        KillAura self = (KillAura) (Object) this;

        SettingGroup sg = self.settings.createGroup("移动矫正");

        movementCorrectionMode = sg.add(new EnumSetting.Builder<MovementCorrection.Mode>()
            .name("移动矫正")
            .description("移动矫正模式。严格：移动方向为真实旋转。静默：在严格基础上映射 WASD 按键,尝试让移动方向与视觉朝向一致。")
            .defaultValue(MovementCorrection.Mode.OFF)
            .visible(() -> rotation.get() != KillAura.RotationMode.None)
            .build()
        );

        onHitHoldTicks = sg.add(new IntSetting.Builder()
            .name("转回延迟")
            .description("OnHit模式下，攻击后保持旋转的 tick 数")
            .defaultValue(1)
            .min(0)
            .max(20)
            .visible(() -> rotation.get() == KillAura.RotationMode.OnHit)
            .build()
        );

        // 瞄准点与范围优化：插到默认分组的「旋转」(rotate) 下面，改的是瞄准角度与范围判定
        aimAndRangeOptimization = new BoolSetting.Builder()
            .name("瞄准点与范围优化")
            .description("同时优化瞄准点与攻击范围：瞄准碰撞箱上最靠近玩家的点（而非中心），范围按眼睛到碰撞箱距离判定（对齐服务器）。")
            .defaultValue(true)
            .build();
        insertAfter(self.settings.getDefaultGroup(), "rotate", aimAndRangeOptimization);

        // 范围渲染 + 颜色：追加到默认分组底部
        rangeRender = self.settings.getDefaultGroup().add(new BoolSetting.Builder()
            .name("范围渲染")
            .description("以玩家为中心渲染一个球体，半径为设定的攻击范围（不穿墙范围）。")
            .defaultValue(false)
            .build()
        );

        rangeColor = self.settings.getDefaultGroup().add(new ColorSetting.Builder()
            .name("颜色")
            .description("范围渲染球体的颜色和透明度。")
            .defaultValue(new SettingColor(0, 255, 0, 50))
            .visible(rangeRender::get)
            .build()
        );

        // TPS 为 0 时聊天栏警告：插到官方「TPS-sync」下面，仅 TPS 同步开启时显示
        chatWarn = new BoolSetting.Builder()
            .name("聊天栏输出")
            .description("TPS为0时聊天栏警告")
            .defaultValue(true)
            .visible(tpsSync::get)
            .build();
        insertAfter(sgTiming, "TPS-sync", chatWarn);
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
            MovementCorrection.rotate(yaw, pitch, mode);
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
            MovementCorrection.rotate(yaw, pitch, mode);
            // 攻击后保持旋转 N tick 再转回原朝向
            MovementCorrection.setHoldTicks(onHitHoldTicks.get());
        } else {
            // 关闭 / 停止移动（未实现）：回退原版静默旋转
            Rotations.rotate(yaw, pitch);
        }
    }

    // ====== 旋转优化：瞄准点从目标中心改为碰撞箱上最靠近玩家的点 ======

    @Redirect(
        method = "onTick",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;getYaw(Lnet/minecraft/world/entity/Entity;)D"
        )
    )
    private double redirectGetYawTick(Entity entity) {
        return optimizedYaw(entity);
    }

    @Redirect(
        method = "onTick",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;getPitch(Lnet/minecraft/world/entity/Entity;Lmeteordevelopment/meteorclient/utils/entity/Target;)D"
        )
    )
    private double redirectGetPitchTick(Entity entity, Target target) {
        return optimizedPitch(entity, target);
    }

    @Redirect(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;getYaw(Lnet/minecraft/world/entity/Entity;)D"
        )
    )
    private double redirectGetYawAttack(Entity entity) {
        return optimizedYaw(entity);
    }

    @Redirect(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;getPitch(Lnet/minecraft/world/entity/Entity;Lmeteordevelopment/meteorclient/utils/entity/Target;)D"
        )
    )
    private double redirectGetPitchAttack(Entity entity, Target target) {
        return optimizedPitch(entity, target);
    }

    /** 瞄准点优化开启时，返回瞄准碰撞箱上最靠近玩家眼睛的点的 yaw；否则返回原版（目标中心） */
    @Unique
    private double optimizedYaw(Entity entity) {
        if (!aimAndRangeOptimization.get() || mc.player == null) return Rotations.getYaw(entity);
        return Rotations.getYaw(closestPointOnBox(mc.player.getEyePosition(), entity.getBoundingBox()));
    }

    /** 瞄准点优化开启时，返回瞄准碰撞箱上最靠近玩家眼睛的点的 pitch；否则返回原版（目标身体中心） */
    @Unique
    private double optimizedPitch(Entity entity, Target target) {
        if (!aimAndRangeOptimization.get() || mc.player == null) return Rotations.getPitch(entity, target);
        return Rotations.getPitch(closestPointOnBox(mc.player.getEyePosition(), entity.getBoundingBox()));
    }

    /** 计算碰撞箱上离 from 最近的点（参考 LiquidBounce getNearestPointBB） */
    @Unique
    private static Vec3 closestPointOnBox(Vec3 from, AABB box) {
        return new Vec3(
            Mth.clamp(from.x(), box.minX, box.maxX),
            Mth.clamp(from.y(), box.minY, box.maxY),
            Mth.clamp(from.z(), box.minZ, box.maxZ)
        );
    }

    /** 把设置插入到分组内指定名称的设置之后（找不到则追加到末尾） */
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

    // ====== swing 延迟：不立即发，等攻击包发出后统一挥动 ======
    //
    // 服务端 ServerPlayer.swing 会 resetAttackStrengthTicker（重置攻击冷却），
    // 若 swing 包先于攻击包到达，攻击包判定时冷却刚被清零 → 全部轻击/丢弃。
    // 原版顺序是攻击包 → swing，这里把 swing 一并延迟到 doPendingAttacks 里保证顺序。

    @Redirect(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V"
        )
    )
    private void redirectSwing(LocalPlayer player, InteractionHand hand) {
        // 挥动延迟到 doPendingAttacks（攻击包之后），不在这里立即发包
    }

    /** 执行延迟的攻击（在移动包发送完毕后被调用，此时服务器视角已到位） */
    @Unique
    private void doPendingAttacks() {
        if (mc == null || mc.player == null || pendingAttacks.isEmpty()) return;

        for (Entity target : pendingAttacks) {
            mc.gameMode.attack(mc.player, target);
            // 攻击包之后挥动：服务端先判定攻击（冷却满=重击），再处理 swing（重置冷却，开始积累下一击）
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        pendingAttacks.clear();
        hitTimer = 0;
    }

    // ====== TPS 为 0 时聊天栏警告 ======
    // 服务器 TPS 检测为 0（卡服/采样异常）时，在聊天栏输出一次警告，方便排查「杀戮光环不工作」。
    // 不改变 TPS 同步的原版行为：返回值原样透传，只在 TPS 从非 0 变 0 的边沿提示一次（避免刷屏）。

    @Redirect(
        method = "delayCheck",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/TickRate;getTickRate()F"
        )
    )
    private float redirectGetTickRate(TickRate tickRate) {
        float rate = tickRate.getTickRate();
        if (chatWarn.get()) {
            boolean zero = Float.isNaN(rate) || rate <= 0.0f;
            if (zero && !lastTpsZero) {
                ChatUtils.warning("【KillAura】: This server TPS == 0, module stopped working");
            }
            lastTpsZero = zero;
        } else {
            lastTpsZero = false;
        }
        return rate;
    }

    // ====== 范围判定改为眼位距离（对齐服务器攻击判定 / 渲染球） ======

    @Inject(method = "entityCheck", at = @At("HEAD"))
    private void onEntityCheckHead(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        entityCheckTarget = entity;
    }

    @Redirect(
        method = "entityCheck",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/PlayerUtils;isWithin(DDDD)Z"
        )
    )
    private boolean redirectRangeCheck(double x, double y, double z, double r) {
        // 范围优化未开启：回退原版（脚底坐标距离判定）
        if (!aimAndRangeOptimization.get()) {
            return PlayerUtils.isWithin(x, y, z, r);
        }
        Entity target = entityCheckTarget;
        if (target != null && mc.player != null) {
            // 眼睛到目标碰撞箱最近点的距离，与服务器 isWithinAttackRange 一致
            double distSq = target.getBoundingBox().distanceToSqr(mc.player.getEyePosition());
            return distSq <= r * r;
        }
        return PlayerUtils.isWithin(x, y, z, r);
    }

    // ====== 范围渲染：以玩家为中心渲染攻击范围球体（实心半透明，带深度遮挡） ======

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!rangeRender.get()) return;
        if (mc.player == null || mc.level == null) return;

        // getEyePosition(partialTick) 自带渲染插值，球体平滑跟随玩家移动
        Vec3 center = mc.player.getEyePosition(event.tickDelta);

        Color color = rangeColor.get();
        double radius = range.get();

        // 用 depthRenderer（带深度测试）渲染实心球，让球被实体/方块遮挡，便于判断距离
        drawSphere(event.depthRenderer, center.x(), center.y(), center.z(), radius, color);
    }

    /** 用经纬网格(UV sphere)画一个实心半透明球体（三角形面片） */
    @Unique
    private static void drawSphere(Renderer3D renderer, double cx, double cy, double cz, double radius, Color color) {
        int stacks = 16; // 纬线分段
        int slices = 32; // 经线分段

        for (int i = 0; i < stacks; i++) {
            double phi1 = Math.PI * i / stacks;
            double phi2 = Math.PI * (i + 1) / stacks;
            double sinPhi1 = Math.sin(phi1), cosPhi1 = Math.cos(phi1);
            double sinPhi2 = Math.sin(phi2), cosPhi2 = Math.cos(phi2);

            for (int j = 0; j < slices; j++) {
                double theta1 = 2 * Math.PI * j / slices;
                double theta2 = 2 * Math.PI * (j + 1) / slices;
                double sinT1 = Math.sin(theta1), cosT1 = Math.cos(theta1);
                double sinT2 = Math.sin(theta2), cosT2 = Math.cos(theta2);

                // 四个顶点围成一个四边形面片
                double x1 = cx + radius * sinPhi1 * cosT1;
                double y1 = cy + radius * cosPhi1;
                double z1 = cz + radius * sinPhi1 * sinT1;

                double x2 = cx + radius * sinPhi2 * cosT1;
                double y2 = cy + radius * cosPhi2;
                double z2 = cz + radius * sinPhi2 * sinT1;

                double x3 = cx + radius * sinPhi2 * cosT2;
                double y3 = cy + radius * cosPhi2;
                double z3 = cz + radius * sinPhi2 * sinT2;

                double x4 = cx + radius * sinPhi1 * cosT2;
                double y4 = cy + radius * cosPhi1;
                double z4 = cz + radius * sinPhi1 * sinT2;

                renderer.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, color);
            }
        }
    }
}
