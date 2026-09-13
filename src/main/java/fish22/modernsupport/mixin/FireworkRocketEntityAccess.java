package fish22.modernsupport.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.OptionalInt;

/**
 * 烟花火箭同步数据访问器。
 *
 * <p>暴露原版私有 {@code DATA_ATTACHED_TO_TARGET}：客户端用它精确判断烟花是否附着在
 * 自己身上。史莱姆 mod 的烟花加速也正是在该同步字段等于玩家实体 id 时开始生效。
 */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccess {

    @Accessor("DATA_ATTACHED_TO_TARGET")
    static EntityDataAccessor<OptionalInt> meteor$getDataAttachedToTarget() {
        throw new AssertionError();
    }
}
