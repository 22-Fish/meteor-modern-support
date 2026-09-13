package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.ElytraFlySupport;
import fish22.modernsupport.utils.LegalRotation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 烟花火箭 mixin —— 鞘翅滑翔加速方向对齐服务器朝向
 *
 * <p>滑翔时使用烟花，烟花实体以附着模式生成（attachedToEntity = 使用者），
 * 每 tick 沿使用者 look 方向给玩家加速（{@link FireworkRocketEntity#tick}，
 * 客户端/服务器端都会执行这段逻辑，没有 isClientSide 判断）。
 * 合法转头（严格/静默）旋转中，服务器朝向 ≠ 客户端视觉朝向：
 * 服务器端沿服务器朝向加速、客户端本地沿视觉朝向加速 → 两端速度方向分叉 →
 * 客户端本地位置与服务器模拟越拉越远，被服务器位置纠正（回弹）。
 *
 * <p>所以本地玩家这里一律用 {@link LegalRotation#getServerLook(net.minecraft.world.entity.Entity)}
 * ——「服务器此刻认为的朝向」：合法转头激活时就是旋转目标，没激活时它就等于玩家视角，
 * 行为与原版完全一致。两端方向一致，不再回弹。
 *
 * <p>还有一处：原版加速前会判断 {@code attachedToEntity.isFallFlying()}。甲飞时本地这个
 * 标志位由服务器同步的滑翔 bit 反复覆盖（换装窗口开/关），会出现 false 的 tick；
 * 这些 tick 客户端本地完全不加速，服务端却照常加速 → 本地越落越远被服务端拉回。
 * 所以飞行窗口内把这个判断强制为 true，让两端「有没有烟花加速」一致。
 */
@Mixin(FireworkRocketEntity.class)
public class MixinFireworkRocketEntity {

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 useServerLookForBoost(LivingEntity entity) {
        // 本地玩家：用「服务器此刻认为的朝向」（合法转头没激活时它就等于玩家视角）
        if (entity == mc.player) return LegalRotation.getServerLook(entity);
        return entity.getLookAngle();
    }

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isFallFlying()Z")
    )
    private boolean keepGlidingForBoost(LivingEntity entity) {
        if (entity == mc.player && ElytraFlySupport.shouldAlignFireworkBoostWithServer()) {
            // 服务器认这一 tick 在滑翔（合法平飞·甲飞窗口），本地也照滑翔算烟花加速
            return true;
        }
        return entity.isFallFlying();
    }
}
