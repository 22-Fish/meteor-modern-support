package fish22.modernsupport.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 生物滑翔运算访问器 —— 调用原版滑翔速度运算（{@code updateFallFlyingMovement}）
 *
 * <p>原版 {@code travelFallFlying} 就是三段：
 * <pre>
 *   setDeltaMovement(updateFallFlyingMovement(getDeltaMovement()));
 *   move(SELF, getDeltaMovement());
 *   （撞墙伤害只在服务端结算）
 * </pre>
 *
 * <p>甲飞（穿胸甲假飞）本地大多时候不是滑翔状态，需要在「不伪造 isFallFlying」的前提下
 * 复用同一套运算，所以这里只借原版的速度运算，move 与状态处理由调用方按原版顺序做
 * （见 {@link fish22.modernsupport.utils.ElytraFlySupport#travelAsElytra}）。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityGlideInvoker {

    /** 原版滑翔速度运算：重力 + 滑翔抬升 + 俯冲加速 + 视线对齐 + 0.99/0.98/0.99 阻力 */
    @Invoker("updateFallFlyingMovement")
    Vec3 meteor$updateFallFlyingMovement(Vec3 movement);
}
