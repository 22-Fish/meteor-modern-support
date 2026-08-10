package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.Freeze;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 冻结 mixin — 冻结时屏蔽 WASD 与疾跑输入
 *
 * 冻结期间将 W/S/A/D 和疾跑输入归零（跳跃、潜行保留）。
 * 客户端输入完全无法施加移动动量（WASD 移动量为 0），
 * 服务端施加的动量（击退等）不受影响，正常生效。
 * 同时防止疾跑状态激活产生粒子、输入包携带移动标志
 * 被服务器用于移动模拟导致回弹。
 */
@Mixin(KeyboardInput.class)
public abstract class MixinFreezeKeyboardInput extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickTail(CallbackInfo ci) {
        // 冻结时屏蔽 WASD 与疾跑输入，保留跳跃/潜行
        if (!Freeze.isFrozen()) return;

        keyPresses = new Input(
            false,               // forward
            false,               // backward
            false,               // left
            false,               // right
            keyPresses.jump(),   // jump 保留
            keyPresses.shift(),  // shift 保留
            false                // sprint 归零（防止疾跑状态激活）
        );
        moveVector = Vec2.ZERO;
    }
}
