package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.ElytraBounce;
import fish22.modernsupport.utils.ElytraFlySupport;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 甲飞「空中屏蔽空格」mixin —— 空中把跳跃键当作没按
 *
 * <p>在 {@code KeyboardInput#tick} 末尾把跳跃键抹掉，客户端随后发出去的输入包
 * （{@code ServerboundPlayerInputPacket}）就是 jump=false，服务器不知道你按着空格。
 * 直接改本地输入而不是拦包重发：不产生同 tick 的重复输入包（Grim BadPacketsZ），
 * 也不需要自己维护「服务器那边现在以为空格是按下还是松开」。
 *
 * <p>时机：{@code KeyboardInput#tick} 在 {@code LocalPlayer.aiStep} 内部、跳跃真正
 * 施加之前调用，所以这里的 {@code onGround()} 还是上一 tick 的结果——
 * <b>地面起跳的那一下不会被屏蔽</b>（那次跳跃必须让服务器看到，否则预测对不上），
 * 真正在空中按住空格才屏蔽。
 *
 * <p>与 {@link MixinFreezeKeyboardInput}（冻结）和 {@link MixinKeyboardInput}
 * （静默转头按键映射）同注入点（TAIL）：本 mixin 只改跳跃键，其它按键原样保留，
 * 三个混入谁先谁后结果都一致。
 */
@Mixin(KeyboardInput.class)
public abstract class MixinElytraKeyboardInput extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickTail(CallbackInfo ci) {
        // 兼容 grim 输入检测：发过起飞包的那一 tick 让输入包带 jump=true
        // （按下包排在起飞包后面，正好是原版顺序）
        // 鞘翅弹跳的「兼容 grim 输入检测」同理：起飞那一 tick 按下，其余空中 tick 松开
        boolean pressJump = ElytraFlySupport.shouldPressJumpInput()
            || ElytraBounce.shouldPressJumpInput();

        // 每 tick 都必须推进「服务端已看到松开」状态：它同时是「兼容 grim 输入检测」里
        // 下一次起飞包的放行条件（skipStartThisTick）。放行状态跟本地有没有按着空格无关，
        // 所以这里不能先判 keyPresses.jump() —— 那样不按空格时状态永远推进不了，
        // 换装窗口只在按着/狂按空格的那些 tick 才开，表现为「按一下空格正常几 tick 就停飞」。
        boolean hideJump = pressJump
            || ElytraFlySupport.shouldHideJumpInput()
            || ElytraBounce.shouldHideJumpInput();

        // 甲飞换装期间屏蔽移动/疾跑键：Grim MultiActionsC/D（移动中点击背包 / 关闭背包）
        // 会把换装的容器点击包直接取消，换装随之落空（「甲飞不换甲」）。
        boolean hideMove = ElytraFlySupport.shouldHideMoveInput();

        boolean forward = keyPresses.forward() && !hideMove;
        boolean backward = keyPresses.backward() && !hideMove;
        boolean left = keyPresses.left() && !hideMove;
        boolean right = keyPresses.right() && !hideMove;
        boolean jump = pressJump || (keyPresses.jump() && !hideJump);
        boolean sprint = keyPresses.sprint() && !hideMove;

        // 鞘翅弹跳的兼容模式靠这个状态判断「服务端最后看到的跳跃键」是不是松开
        ElytraBounce.recordJumpInput(jump);

        // 没有任何要改的：保持原样（避免每 tick 都重建 Input 记录）
        if (forward == keyPresses.forward() && backward == keyPresses.backward()
            && left == keyPresses.left() && right == keyPresses.right()
            && jump == keyPresses.jump() && sprint == keyPresses.sprint()) {
            return;
        }

        keyPresses = new Input(forward, backward, left, right, jump, keyPresses.shift(), sprint);
    }
}
