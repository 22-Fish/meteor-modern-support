package fish22.modernsupport.mixin;

import fish22.modernsupport.modules.ElytraFlyPlus;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 关掉 Meteor 自带的「鞘翅飞行」（ElytraFly）
 *
 * <p>我们的鞘翅飞行是独立模块（{@link fish22.modernsupport.modules.ElytraFlyPlus}），启动时会把官方这个
 * 从模块列表里摘掉，但<b>实例必须留着</b>：meteor 自己的 mixin
 * （{@code LivingEntityMixin#isGlidingHook}、{@code EntityMixin#getPoseHook}、{@code LivingEntityMixin#recastOnLand}）
 * 会直接 {@code Modules.get().get(ElytraFly.class).xxx()}，实例没了就是空指针刷屏。
 * 所以这里把它的行为整个关掉：
 *
 * <ul>
 *   <li>激活入口直接取消：模块不会真的跑起来（设置界面里点开也会立刻回到关闭）；</li>
 *   <li>事件方法全部拦下：就算已经被激活（比如配置加载时），它一个包都不发、一次移动都不改；</li>
 *   <li>{@code canPacketEfly} 改成看我们自己的「无限鞘翅·发包」，让 meteor 那两个 hook 照旧能用
 *       （见 {@link ElytraFlyPlus#isPacketFlyActive()}）。</li>
 * </ul>
 *
 * <p>这是本模组<b>唯一</b>动官方 ElytraFly 的地方，别在这里加功能。
 */
@Mixin(value = ElytraFly.class, remap = false)
public abstract class MixinDisableMeteorElytraFly {

    @Inject(method = "onActivate", at = @At("HEAD"), cancellable = true)
    private void modernsupport$blockActivate(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "onDeactivate", at = @At("HEAD"), cancellable = true)
    private void modernsupport$blockDeactivate(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void modernsupport$blockPlayerMove(PlayerMoveEvent event, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "onPreTick", at = @At("HEAD"), cancellable = true)
    private void modernsupport$blockPreTick(TickEvent.Pre event, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void modernsupport$blockTick(TickEvent.Post event, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "onPacketSend", at = @At("HEAD"), cancellable = true)
    private void modernsupport$blockPacketSend(PacketEvent.Send event, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "onPacketReceive", at = @At("HEAD"), cancellable = true)
    private void modernsupport$blockPacketReceive(PacketEvent.Receive event, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "canPacketEfly", at = @At("HEAD"), cancellable = true)
    private void modernsupport$canPacketEfly(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(ElytraFlyPlus.isPacketFlyActive());
    }
}
