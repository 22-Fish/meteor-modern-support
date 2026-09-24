package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.LegalCrystal;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.utils.player.Rotations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把「官方静默旋转」换成「合法转头」的拦截点。
 *
 * <p>只对排过队的那些调用生效：{@link LegalCrystal} 里有一份待用角度时才接管，
 * 否则一个字段判空就返回，全游戏其它模块的 {@code Rotations.rotate} 完全不受影响。
 *
 * <p>接管后：角度换成我们算好的合法角度，模式走 {@link LegalRotation}（朝向随移动包发出、
 * 回调排在移动包之后），时序跟官方那个「先旋转、后回调」的重载一致，但一 tick 只有
 * 一个移动类包。抢不过更高优先级的旋转时（{@link LegalRotation#rotate} 返回 false）
 * 直接放行，让官方那次旋转照原样执行。
 */
@Mixin(value = Rotations.class, remap = false)
public class MixinRotations {

    @Inject(method = "rotate(DDILjava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
    private static void meteor$rotateLegit(double yaw, double pitch, int priority, Runnable callback, CallbackInfo ci) {
        LegalCrystal.Pending pending = LegalCrystal.peek();
        if (pending == null || priority != LegalCrystal.CRYSTAL_ROTATION_PRIORITY) return;
        LegalCrystal.take();

        if (LegalRotation.rotate(pending.yaw(), pending.pitch(), pending.mode(), pending.priority(), callback)) {
            LegalCrystal.markApplied();
            LegalCrystal.log("转头: 走合法转头API yaw=%.1f pitch=%.1f 模式=%s", pending.yaw(), pending.pitch(), pending.mode());
            ci.cancel();
        } else {
            LegalCrystal.log("转头: 被更高优先级顶掉，回退官方旋转");
        }
    }
}
