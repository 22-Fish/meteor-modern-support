package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.ElytraBounce;
import fish22.modernsupport.utils.ElytraLagSyncSupport;
import fish22.modernsupport.utils.ElytraFlySupport;
import fish22.modernsupport.utils.InfiniteElytraSupport;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 拦截客户端实体同步数据的应用（{@code SynchedEntityData.assignValues}）。
 *
 * <p>服务端通过 ClientboundSetEntityDataPacket 广播实体数据（滑翔标志、姿态等），
 * 客户端在 {@code assignValues} 里逐项应用到本地实体。无限鞘翅（
 * {@link InfiniteElytraSupport}）在脱鞘翅期间，服务端会广播「停止滑翔」
 * （FALL_FLYING 位清零 + 姿态变站立），这里把这两项改回滑翔状态，
 * 让客户端视觉上滑翔不闪断，同时服务端已完成 fallFlyTicks 归零。
 *
 * <p>甲飞「起飞限频绕过」（{@link ElytraLagSyncSupport}）同样：扣包窗口里本地的滑翔位要一直撑住
 * （史莱姆 {@code handleEntityDataUpdate} 那一手，注释里写着 may cause fake gliding），
 * 窗口一结束本地滑翔位就掉的话，客户端改用普通空中运算、服务端却已经认滑翔，两边位移就对不上
 */
@Mixin(SynchedEntityData.class)
public abstract class MixinSynchedEntityData {

    @Shadow
    @Final
    private SyncedDataHolder entity;

    @ModifyVariable(method = "assignValues", at = @At("HEAD"), argsOnly = true)
    private List<SynchedEntityData.DataValue<?>> modernsupport$rewriteStopGliding(List<SynchedEntityData.DataValue<?>> items) {
        // 先记下服务端这一包里的滑翔位（下面改写「停止滑翔」之前的那一份 = 服务端真实态度）：
        // 回弹自愈与起飞看门狗都靠它判断「服务端到底认不认滑翔」，本地标志不能当准
        if (this.entity instanceof LocalPlayer local && local == mc.player) {
            for (SynchedEntityData.DataValue<?> item : items) {
                if (item.id() == 0 && item.value() instanceof Byte data) {
                    ElytraFlySupport.noteServerGlideFlag((data & (1 << 7)) != 0);
                    break;
                }
            }
        }

        boolean infiniteElytra = InfiniteElytraSupport.isActive();
        boolean keepGlide = ElytraBounce.isKeepingGlide();
        boolean delaySync = ElytraLagSyncSupport.isKeepingGlide();

        // 只处理本地玩家
        if (!(this.entity instanceof LocalPlayer player) || player != mc.player) return items;

        if (!infiniteElytra && !keepGlide && !delaySync) return items;
        // 下面两个改写只针对「客户端本地仍在滑翔」的停滑广播
        if (!player.isFallFlying()) return items;

        List<SynchedEntityData.DataValue<?>> result = new ArrayList<>(items.size());
        for (SynchedEntityData.DataValue<?> item : items) {
            // 鞘翅弹跳的「落地维持滑翔」：落地那几 tick 把停滑广播改回滑翔，本地不闪断
            if (infiniteElytra) result.add(InfiniteElytraSupport.processDataValue(item));
            else if (delaySync) result.add(ElytraLagSyncSupport.keepGlideDataValue(item));
            else result.add(ElytraBounce.keepGlideDataValue(item));
        }
        return result;
    }

    @Inject(method = "assignValues", at = @At("TAIL"))
    private void modernsupport$flushDelayedFireworks(CallbackInfo ci) {
        if (!InfiniteElytraSupport.isActive()) return;
        if (!(this.entity instanceof LocalPlayer player) || player != mc.player) return;
        // 数据应用后（本地已恢复滑翔）再重发延迟烟花
        InfiniteElytraSupport.flushDelayedFireworks();
    }
}
