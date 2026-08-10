package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.Freeze;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 冻结 mixin — travel 入口
 *
 * Player 重写了 {@link Player#travel}，本地玩家的实际移动
 * 运算走的是 Player.travel（而非 LivingEntity.travel），
 * 所以必须注入到 Player。
 *
 * 冻结时完全移除移动运算（WASD、跳跃、击退、下坠全部不执行），
 * 动量保留（解冻时按「解冻动量」设置处理）。
 *
 * <p>不干预位置本身，活塞推、末影珍珠、传送等服务端权威的
 * 位置更新仍然正常生效，不会和服务器较劲。
 */
@Mixin(Player.class)
public class MixinFreezeTravel {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void onTravel(Vec3 movementInput, CallbackInfo ci) {
        // 只对本地玩家生效，不影响其他实体
        if (!((Object) this instanceof LocalPlayer)) return;

        // 冻结：完全移除移动运算，动量保留
        if (Freeze.isFrozen()) {
            ci.cancel();
        }
    }
}
