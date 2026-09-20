package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.ElytraBounce;
import fish22.modernsupport.utils.ElytraFlySupport;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 甲飞视角高度锁定 mixin —— 本地不认服务器同步过来的滑翔姿势
 *
 * <p>甲飞本地大部分时间穿胸甲，服务器只有换装窗口那一两 tick 认滑翔；而服务器会把玩家自己的
 * 标志位（滑翔 bit）与姿势同步回客户端（{@code ServerEntity#sendChanges} →
 * {@code sendToTrackingPlayersAndSelf}），窗口开/关来回同步 → 本地 {@code isFallFlying()}
 * 一 tick 真一 tick 假 → 姿势在 FALL_FLYING（视高 0.4）与 STANDING（1.62）之间来回切，
 * 而 {@code Camera#tick} 每 tick 按 50% 去追这个视高，于是「视角高度一直上下抖」。
 *
 * <p>这里在 {@code Player#getDesiredPose} 入口把甲飞时的滑翔姿势拦掉，姿势保持站立/潜行
 * （判定与后续原版分支一致），视高、碰撞箱稳定，也和服务器结算完那一 tick 的姿势一致。
 *
 * <p><b>只改本地姿势</b>：不动 {@code isFallFlying} 标志位、不发包，
 * 滑翔运算（{@link fish22.modernsupport.mixin.MixinElytraTravel}）、换装时序、
 * 烟花逻辑都不受影响；判断条件见 {@link ElytraFlySupport#shouldLockPose(Player)}。
 */
@Mixin(Player.class)
public abstract class MixinElytraPoseLock {

    @Inject(method = "getDesiredPose", at = @At("HEAD"), cancellable = true)
    private void meteor$lockViewHeight(CallbackInfoReturnable<Pose> cir) {
        Player self = (Player) (Object) this;
        if (!ElytraFlySupport.shouldLockPose(self) && !ElytraBounce.shouldLockPose(self)) return;

        boolean crouching = self.isShiftKeyDown() && !self.getAbilities().flying;
        cir.setReturnValue(crouching ? Pose.CROUCHING : Pose.STANDING);
    }
}
