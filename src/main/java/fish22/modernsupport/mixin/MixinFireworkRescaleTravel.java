package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.ElytraFlySupport;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 烟花加速 V2 重缩放的滑翔速度覆盖点。
 *
 * <p>史莱姆 mod 的 V2 不直接改玩家速度，而是把限制后的速度暂存起来，在
 * {@code travelGliding} 的滑翔速度计算返回处替换。对应到 26.1 就是原版私有方法
 * {@code updateFallFlyingMovement} 的返回值。
 */
@Mixin(LivingEntity.class)
public class MixinFireworkRescaleTravel {

    @Inject(method = "updateFallFlyingMovement", at = @At("RETURN"), cancellable = true)
    private void onUpdateFallFlyingMovement(Vec3 oldVelocity, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this != mc.player) return;
        Vec3 override = ElytraFlySupport.consumeFireworkRescaleOverride();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
