package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.MovementCorrection;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * applyInput 里的 bob（xBob/yBob）跟随目标替换为视觉朝向。
 *
 * <p>bob 是手部渲染的视角补偿数据源：ItemInHandRenderer 用
 * {@code (viewYRot - yBob) * 0.1} 做手的视角跟随。原版 applyInput 里
 * bob 每 tick 向当前 yRot 靠拢 50%，而移动矫正激活时 yRot 是旋转目标，
 * 会导致 bob 被带偏（手跟着目标转）；若在恢复时强制同步 bob 又会让 bob
 * 跳变（转视角时手抖动）。
 *
 * <p>这里把 bob 的跟随目标替换为视觉朝向（prevYaw/prevPitch），bob 始终
 * 平滑跟随视觉：手不随旋转目标偏，转视角时的手感与原版一致（不抖动）。
 * 非激活时返回原值，行为完全不变。
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayerApplyInput {

    @Redirect(method = "applyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float redirectBobYaw(LocalPlayer player) {
        if (MovementCorrection.isActive()) {
            return MovementCorrection.getVisualYaw();
        }
        return player.getYRot();
    }

    @Redirect(method = "applyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private float redirectBobPitch(LocalPlayer player) {
        if (MovementCorrection.isActive()) {
            return MovementCorrection.getVisualPitch();
        }
        return player.getXRot();
    }
}
