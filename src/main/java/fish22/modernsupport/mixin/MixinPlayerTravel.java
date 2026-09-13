package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.FireworkBoost;
import fish22.modernsupport.utils.LegalRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 玩家移动 mixin
 *
 * <p>两件事：
 * <ul>
 *   <li>{@link FireworkBoost}：烟花加速要在移动运算之前套用（独立模块，见该类说明）；</li>
 *   <li>游泳 / 创造飞行推进方向用的是 {@code getLookAngle()}，这里替换成合法转头的
 *       真实角度（和 moveRelative 一样，只在这一次调用里生效，不动玩家自己的视角）。</li>
 * </ul>
 */
@Mixin(Player.class)
public class MixinPlayerTravel {

    @Inject(method = "travel", at = @At("HEAD"))
    private void onTravel(Vec3 movementInput, CallbackInfo ci) {
        FireworkBoost.beforeMove((Player) (Object) this);
    }

    @Redirect(
        method = "travel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getLookAngle()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 redirectLookAngle(Player player) {
        if (player == mc.player) return LegalRotation.getServerLook(player);
        return player.getLookAngle();
    }
}
