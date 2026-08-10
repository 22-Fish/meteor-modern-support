package fish22.modernsupport.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 实体旋转旧值访问器：同步 yRotO/xRotO 用。
 *
 * <p>渲染按 {@code rotLerp(partialTick, yRotO, yRot)} 插值转向，
 * 移动矫正只改 yRot/xRot 时，渲染会从旧值平滑转过去（1 tick 延迟 + 平滑转头）。
 * 设置/恢复朝向时同步旧值，消除插值，瞬间转到目标角度。
 */
@Mixin(Entity.class)
public interface EntityRotationAccessor {

    @Accessor("yRotO")
    void setYRotO(float value);

    @Accessor("xRotO")
    void setXRotO(float value);
}
