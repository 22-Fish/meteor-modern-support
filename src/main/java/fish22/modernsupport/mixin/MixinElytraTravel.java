package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.ElytraFlySupport;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 甲飞移动运算 mixin —— 空中始终按原版滑翔运算移动
 *
 * <p><b>只替换移动运算，不伪造滑翔状态</b>：甲飞在客户端是「胸甲 + 服务器滑翔
 * 状态闪烁」，本地 {@code isFallFlying()} 大部分时间是 false，原版这时会退回普通
 * 空中移动运算（WASD 加速 + 0.91 空气阻力 + 满重力），和服务器（Grim 按滑翔运算
 * 预测：忽略输入、0.99 阻力、滑翔抬升）对不上：横移被服务器回弹，烟花给的动量
 * 也会被空气阻力几 tick 吃掉（表现为放得出烟花但不加速）。
 *
 * <p>这里在移动运算入口（{@link Player#travel}）统一按滑翔运算移动：
 * 本地是滑翔状态时原版本来就走滑翔运算，本地不是滑翔状态时由本 mixin 补上，
 * 两者用的是同一个原版方法，行为完全一致。
 * {@code isFallFlying} 标志、换装时序、烟花延迟重发机制一律不动。
 *
 * <p>注意与 {@link MixinPlayerTravel}（合法转头）和 {@link MixinFreezeTravel}
 * （冻结）同注入点：本 mixin 不依赖注入顺序，转向自己补一次，冻结时直接放行。
 */
@Mixin(Player.class)
public class MixinElytraTravel {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void onTravel(Vec3 input, CallbackInfo ci) {
        if (ElytraFlySupport.travelAsElytra((Player) (Object) this)) ci.cancel();
    }
}
