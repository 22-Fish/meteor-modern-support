package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.ElytraFlySupport;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 烟花物品 mixin —— 甲飞（穿胸甲假飞）下手动右键烟花不依赖本地滑翔状态
 *
 * <p>原版 {@link FireworkRocketItem#use} 只有玩家 isFallFlying 时才返回 SUCCESS
 * （客户端才发出使用包），否则返回 PASS（右键无反应）。甲飞穿胸甲时本地
 * isFallFlying 是服务器同步的闪烁状态、经常为 false，导致手动右键烟花发不出使用包。
 *
 * <p>甲飞模式下直接让 use() 返回 SUCCESS——客户端 use() 只负责发出使用包（不发射
 * 烟花、不消耗物品），服务器端收到包后按自己状态判断发射，所以提前返回 SUCCESS 无副作用。
 * 后续由 {@link ElytraFlySupport#onPacketSend} 拦截并延迟到「换鞘翅 + 起飞」的
 * 滑翔窗口再重发，保证服务器端也正常发射。
 *
 * <p>注意：用 @Inject HEAD + setReturnValue，而不是 @Redirect isFallFlying，
 * 避免与 ViaFabricPlus 对同一个 isFallFlying 调用点的 @Redirect 冲突。
 */
@Mixin(FireworkRocketItem.class)
public class MixinFireworkRocketItem {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void forceFireworkUse(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (ElytraFlySupport.isArmorFlyActive()) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}
