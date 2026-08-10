package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.MovementCorrection;
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
 * 移动矫正（严格/静默）旋转中，服务器朝向 ≠ 客户端视觉朝向：
 * 服务器端沿服务器朝向加速、客户端本地沿视觉朝向加速 → 两端速度方向分叉 →
 * 客户端本地位置与服务器模拟越拉越远，被服务器位置纠正（回弹）。
 *
 * <p>仅当本 tick 移动矫正 API 真实调用过 rotate（{@link MovementCorrection#wasActiveThisTick()}）时，
 * 才把加速方向替换为服务器朝向（目标旋转），两端方向一致不再回弹；
 * 移动矫正未启用时保持原版行为（视觉=服务器，方向本来就一致）。
 */
@Mixin(FireworkRocketEntity.class)
public class MixinFireworkRocketEntity {

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 useServerLookForBoost(LivingEntity entity) {
        if (entity == mc.player && MovementCorrection.wasActiveThisTick()) {
            // 移动矫正旋转中：用目标旋转（服务器朝向）计算加速方向
            return entity.calculateViewVector(
                MovementCorrection.getTargetPitch(),
                MovementCorrection.getTargetYaw()
            );
        }
        return entity.getLookAngle();
    }
}
