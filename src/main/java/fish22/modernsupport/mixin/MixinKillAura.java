package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
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
import meteordevelopment.orbit.EventPriority;
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
 * KillAura 合法转头集成 mixin
 *
 * 为 Meteor 的 KillAura 添加合法转头模式设置项 "legal-rotation"，
 * 并通过 {@code @Redirect} 精确拦截 {@link Rotations#rotate(double, double)}
 * 的两个调用点，替换为 {@link LegalRotation#rotate(double, double, LegalRotation.Mode)}。
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

    /** 穿墙（看不见目标）时的攻击范围 */
    @Shadow
    private Setting<Double> wallsRange;

    @Shadow
    private Setting<Boolean> tpsSync;

    @Shadow
    private SettingGroup sgTiming;

    @Unique
    private Setting<LegalRotation.Mode> legalRotationMode;

    @Unique
    private Setting<Integer> legalRotationPriority;

    /** 原版攻击伪造：攻击/挥手包排到移动包之前（原版顺序）。默认关闭 */
    @Unique
    private Setting<Boolean> vanillaAttackSpoof;

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

    /** 原版攻击伪造：扣到下一 tick 开头（移动包之前）再发的目标 */
    @Unique
    private final List<Entity> spoofPendingAttacks = new ArrayList<>();

    /** 原版攻击伪造：tick 计数与「本 tick 刚排进来的攻击」的 tick，用来保证至少隔一 tick 再发 */
    @Unique
    private int spoofTickCounter;

    @Unique
    private int spoofPendingTick = -1;

    /** entityCheck 当前正在判定的目标（范围判定改为眼位距离时用，见 redirectRangeCheck） */
    @Unique
    private Entity entityCheckTarget;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        KillAura self = (KillAura) (Object) this;

        SettingGroup sg = self.settings.createGroup("合法转头");

        legalRotationMode = sg.add(new EnumSetting.Builder<LegalRotation.Mode>()
            .name("合法转头")
            .description("合法转头模式。严格：移动方向为真实旋转。静默：在严格基础上映射 WASD 按键,尝试让移动方向与视觉朝向一致")
            .defaultValue(LegalRotation.Mode.OFF)
            .visible(() -> rotation.get() != KillAura.RotationMode.None)
            .build()
        );

        legalRotationPriority = sg.add(new IntSetting.Builder()
            .name("合法转头优先级")
            .description("合法转头的优先级")
            .defaultValue(0)
            .sliderRange(-20, 20)
            .visible(() -> rotation.get() != KillAura.RotationMode.None
                && (legalRotationMode.get() == LegalRotation.Mode.SEVERE || legalRotationMode.get() == LegalRotation.Mode.QUIET))
            .build()
        );

        vanillaAttackSpoof = sg.add(new BoolSetting.Builder()
            .name("原版攻击伪造")
            .description("攻击与挥手包排到移动包之前发（原版顺序：先攻击、后移动）。这一 tick 只转身、下一 tick 开头才打，所以换目标的第一击可能空")
            .defaultValue(false)
            .visible(() -> rotation.get() != KillAura.RotationMode.None)
            .build()
        );

        // 瞄准点与范围优化：插到默认分组的「旋转」(rotate) 下面，改的是瞄准角度与范围判定
        aimAndRangeOptimization = new BoolSetting.Builder()
            .name("瞄准点与范围优化")
            .description("同时优化瞄准点与攻击范围：瞄准碰撞箱上最靠近玩家的点（而非中心），范围按眼睛到碰撞箱距离判定，穿墙时的墙壁范围判定也一样")
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
            .description("范围渲染球体的颜色和透明度")
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
        LegalRotation.Mode mode = legalRotationMode.get();
        if (mode == LegalRotation.Mode.SEVERE || mode == LegalRotation.Mode.QUIET) {
            LegalRotation.rotate(yaw, pitch, mode, legalRotationPriority.get());
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
        LegalRotation.Mode mode = legalRotationMode.get();
        if (mode == LegalRotation.Mode.SEVERE || mode == LegalRotation.Mode.QUIET) {
            LegalRotation.rotate(yaw, pitch, mode, legalRotationPriority.get());
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
        // 原版攻击伪造：这一 tick 只转身，攻击先扣下，下一 tick 开头（移动包之前）再和挥手一起发
        if (vanillaAttackSpoof.get()) {
            spoofPendingAttacks.add(target);
            spoofPendingTick = spoofTickCounter;
            return;
        }

        // 不立即发包，记录目标，等移动包发送完毕后统一攻击
        pendingAttacks.add(target);
        LegalRotation.runAfterSend(this::doPendingAttacks);
    }

    // ====== 原版攻击伪造：攻击/挥手排到移动包之前发 ======
    //
    // 原版玩家左键是这样发的：handleKeybinds（tick 最前面，比移动包早）里发攻击包 + 挥手包，
    // 之后才 tick 玩家、才把移动包（带朝向）发出去，即 攻击 → 挥手 → 移动包。
    //
    // 我们这份挂在 TickEvent.Pre（Minecraft.tick 的开头），优先级比模块自己的 onTick 高，
    // 所以是「上一 tick 排进来的攻击」在这里发：上一 tick 的朝向已经随那一 tick 的移动包
    // 到服务器了，服务器视角到位、攻击包又排在本 tick 移动包之前，顺序就和原版一样。

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTickSpoofAttacks(TickEvent.Pre event) {
        spoofTickCounter++;

        if (mc.player == null) {
            spoofPendingAttacks.clear();
            spoofPendingTick = -1;
            return;
        }
        // 关掉这个选项时把扣下的攻击丢掉，免得下次打开时补出一发过期的
        if (!vanillaAttackSpoof.get()) {
            spoofPendingAttacks.clear();
            return;
        }
        if (spoofPendingAttacks.isEmpty()) return;

        // 保险：同一 tick 里刚排进来的先不发（发送要晚于「那一 tick 的朝向随移动包发出」）
        if (spoofPendingTick == spoofTickCounter) return;

        List<Entity> attacks = new ArrayList<>(spoofPendingAttacks);
        spoofPendingAttacks.clear();

        for (Entity target : attacks) {
            if (!canSpoofAttack(target)) continue;

            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** 原版攻击伪造：隔了一 tick 才补这一击，目标没了 / 走出范围就丢掉这一击 */
    @Unique
    private boolean canSpoofAttack(Entity target) {
        if (mc.player == null || target == null || target.isRemoved() || !target.isAlive()) return false;

        double r = range.get();
        AABB box = target.getBoundingBox();

        // 和 entityCheck 一样：开了瞄准点与范围优化按眼睛到碰撞箱的距离，否则按脚底坐标距离
        if (aimAndRangeOptimization.get()) {
            double distSq = eyeToBoxDistSq(target);
            if (distSq > r * r) return false;
            // 看不见目标时还要过墙壁范围这一关（和 entityCheck 用同一把尺子）
            return PlayerUtils.canSeeEntity(target) || distSq <= wallsRange.get() * wallsRange.get();
        }

        return PlayerUtils.isWithin(
            Mth.clamp(mc.player.getX(), box.minX, box.maxX),
            Mth.clamp(mc.player.getY(), box.minY, box.maxY),
            Mth.clamp(mc.player.getZ(), box.minZ, box.maxZ),
            r
        );
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
            return eyeToBoxDistSq(target) <= r * r;
        }
        return PlayerUtils.isWithin(x, y, z, r);
    }

    /** 穿墙（看不见目标）时的墙壁范围判定：同样按眼睛到碰撞箱的距离 */
    @Redirect(
        method = "entityCheck",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/PlayerUtils;isWithin(Lnet/minecraft/world/entity/Entity;D)Z"
        )
    )
    private boolean redirectWallsRangeCheck(Entity entity, double r) {
        // 范围优化未开启：回退原版（玩家到实体坐标点的距离）
        if (!aimAndRangeOptimization.get() || mc.player == null) {
            return PlayerUtils.isWithin(entity, r);
        }
        // 原版这里量的是玩家到实体坐标点的距离，不走优化：
        // 恶魂这类大碰撞箱实体「箱边进了范围、坐标点还在外面」就被误判超范围不打，
        // 玩家卡在方块里时视线全被挡、所有目标都走这一关，看起来就像范围优化失效
        return eyeToBoxDistSq(entity) <= r * r;
    }

    /** 眼睛到目标碰撞箱最近点的距离平方（与服务器 isWithinAttackRange 一致） */
    @Unique
    private static double eyeToBoxDistSq(Entity target) {
        return target.getBoundingBox().distanceToSqr(mc.player.getEyePosition());
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
