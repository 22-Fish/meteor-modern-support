package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.LegalCrystal;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.player.Rotations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
 * 整个调用丢掉，<b>不放行官方那次旋转</b>：官方那条会把角度记成「上一次旋转」，
 * 随后几 tick 继续把它塞进移动包（客户端那几 tick 是按视角走的 → 服务端预测分叉 → 拉回）
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
            // 顶掉了就整包丢掉：宁可这一下不动手，也不要让官方那套把后面的移动包带跑
            LegalCrystal.log("转头: 被更高优先级顶掉，这一下不动手");
            ci.cancel();
        }
    }

    /**
     * 水晶光环「合法转头」开着时，把 Meteor 自带的「保持上一次朝向」时长当成 0
     *
     * <p>那一套会把上一次经过官方 {@code Rotations} 的角度继续塞进后面几 tick 的移动包，
     * 客户端那几 tick 是按视角走的 → 服务端移动预测分叉 → 拉回（卡脚）。
     * 只有水晶光环（合法转头开着）保持激活时才这么做，模块关掉就恢复原样。
     */
    @Redirect(
        method = "onSendMovementPacketsPre(Lmeteordevelopment/meteorclient/events/entity/player/SendMovementPacketsEvent$Pre;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/settings/Setting;get()Ljava/lang/Object;"
        )
    )
    private static Object meteor$suppressRotationHold(Setting<?> setting) {
        Object value = setting.get();

        Config config = Config.get();
        if (LegalCrystal.suppressRotationHold && config != null && setting == config.rotationHoldTicks) return 0;

        return value;
    }
}
