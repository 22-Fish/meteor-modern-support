package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.MovementCorrection;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移动计算前强制朝向 mixin
 *
 * <p>aiStep 内部（KeyboardInput 输入计算、游泳/滑翔转向等）可能把玩家朝向改回视觉值，
 * 导致本 tick 移动方向与移动矫正目标不一致（表现为"先朝旧方向飞一点再转向"）。
 * 在 {@link Player#travel} 入口（移动计算真正发生处）再强制一次目标朝向，
 * 保证移动方向一定跟随服务器朝向。
 */
@Mixin(Player.class)
public class MixinPlayerTravel {

    @Inject(method = "travel", at = @At("HEAD"))
    private void onTravel(Vec3 movementInput, CallbackInfo ci) {
        MovementCorrection.forceRotationBeforeMove((Player) (Object) this);
    }
}
