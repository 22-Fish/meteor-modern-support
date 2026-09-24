package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.EatModify;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「进食修改」的「单次点击进食」用
 *
 * <p>原版每 tick 会检查一次「右键松开了没有」，松开就调这里停用物品
 * （发停用包 + 客户端停止使用）。单次点击进食要的是「点一下就接着把这一份吃完」，
 * 所以这一下停用先拦住：服务器那边没收到停用包，还在自己计时，这一份照常吃完，
 * 客户端因为服务器同步的「正在使用」标记也一直保持进食状态
 * （进食动画和进度条都不会断）。
 *
 * <p>只在模块开着并且确实在等这一份吃完的时候拦（{@link EatModify#keepUsing}），
 * 其它情况一律原样放行
 */
@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameModeEat {
    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void onReleaseUsingItem(Player player, CallbackInfo ci) {
        if (EatModify.keepUsing(player)) ci.cancel();
    }
}
