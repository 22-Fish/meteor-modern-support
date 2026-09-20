package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.ElytraBounce;
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
 */
@Mixin(SynchedEntityData.class)
public abstract class MixinSynchedEntityData {

    @Shadow
    @Final
    private SyncedDataHolder entity;

    @ModifyVariable(method = "assignValues", at = @At("HEAD"), argsOnly = true)
    private List<SynchedEntityData.DataValue<?>> modernsupport$rewriteStopGliding(List<SynchedEntityData.DataValue<?>> items) {
        boolean infiniteElytra = InfiniteElytraSupport.isActive();
        boolean keepGlide = ElytraBounce.isKeepingGlide();

        // 只处理本地玩家
        if (!(this.entity instanceof LocalPlayer player) || player != mc.player) return items;

        if (!infiniteElytra && !keepGlide) return items;
        // 下面两个改写只针对「客户端本地仍在滑翔」的停滑广播
        if (!player.isFallFlying()) return items;

        List<SynchedEntityData.DataValue<?>> result = new ArrayList<>(items.size());
        for (SynchedEntityData.DataValue<?> item : items) {
            // 鞘翅弹跳的「落地维持滑翔」：落地那几 tick 把停滑广播改回滑翔，本地不闪断
            result.add(keepGlide && !infiniteElytra
                ? ElytraBounce.keepGlideDataValue(item)
                : InfiniteElytraSupport.processDataValue(item));
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
