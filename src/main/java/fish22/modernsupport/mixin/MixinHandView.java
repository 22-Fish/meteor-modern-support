/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 22_Fish
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fish22.modernsupport.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import meteordevelopment.meteorclient.events.render.HeldItemRendererEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Vector3dSetting;
import meteordevelopment.meteorclient.systems.modules.render.HandView;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.Camera;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemUseAnimation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「手部视图」新增一个设置：「进食动画优化」。
 *
 * <p>手部视图的「位置」偏移是加在原版姿势之后的（原版把要吃东西的那只手送到嘴边的动作早就做完了），
 * 所以位置偏移一大，进食用具/食物就停在半路、送不到嘴边。开了这个设置之后，吃东西（或喝东西）的那只
 * 手暂时不加「位置」偏移，只保留「缩放」和「旋转」；吃完了这一帧条件不成立，位置偏移自动回来。
 *
 * <p>顺带修掉缩放的偏心：缩放本来是围绕手部姿势原点（手腕，画面右下角）做的，比例一调小，
 * 手里的东西就整块往右上角缩，进食的时候看着偏右。开了这个设置之后，缩放改成横向围绕屏幕中间那条竖线、
 * 纵向围绕手腕的高度做，东西只往画面中间收、往下收，不再往右上角跑；
 * 位置还是不满意可以用「进食位置补偿」手调一点。
 */
@Mixin(value = HandView.class, remap = false)
public abstract class MixinHandView {

    @Shadow
    @Final
    private Setting<Boolean> followRotations;

    @Shadow
    @Final
    private Setting<Vector3d> rotMain;

    @Shadow
    @Final
    private Setting<Vector3d> scaleMain;

    @Shadow
    @Final
    private Setting<Vector3d> rotOff;

    @Shadow
    @Final
    private Setting<Vector3d> scaleOff;

    @Shadow
    private void rotate(PoseStack matrix, Vector3d rotation) { throw new AssertionError(); }

    @Shadow
    private void scale(PoseStack matrix, Vector3d scale) { throw new AssertionError(); }

    @Shadow
    private void applyServerRotations(PoseStack matrix) { throw new AssertionError(); }

    @Unique
    private Setting<Boolean> eatOptimize;

    @Unique
    private Setting<Vector3d> eatCenterCompensation;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void meteorsupport$onInit(CallbackInfo ci) {
        HandView self = (HandView) (Object) this;

        eatOptimize = new BoolSetting.Builder()
            .name("进食动画优化")
            .description("进食/饮用时不加位置偏移, 缩放改成围绕屏幕中间那条竖线和手腕高度做(不往右上飘也不挡准星), 旋转照常, 吃完自动恢复")
            .defaultValue(false)
            .build();

        insertAfter(self.settings.getDefaultGroup(), "disable-eating-animation", eatOptimize);

        eatCenterCompensation = new Vector3dSetting.Builder()
            .name("进食位置补偿")
            .description("位置还要微调时用, 按屏幕方向: X 右 / Y 上 / Z 前 (想再往下就填负的 Y)")
            .defaultValue(0, 0, 0)
            .sliderRange(-1, 1)
            .decimalPlaces(2)
            .visible(() -> eatOptimize != null && eatOptimize.get())
            .build();

        insertAfter(self.settings.getDefaultGroup(), "进食动画优化", eatCenterCompensation);
    }

    /** 进食动画优化: 「位置」偏移那一步跳过, 缩放围绕屏幕中心做, 旋转照常（原版姿势保持不变） */
    @Inject(method = "onHeldItemRender", at = @At("HEAD"), cancellable = true)
    private void meteorsupport$onHeldItemRender(HeldItemRendererEvent event, CallbackInfo ci) {
        if (eatOptimize == null || eatCenterCompensation == null) return;
        if (!eatOptimize.get()) return;
        if (!meteorsupport$isEating(event.hand)) return;

        if (Rotations.rotating && followRotations.get()) applyServerRotations(event.matrix);

        boolean main = event.hand == InteractionHand.MAIN_HAND;
        Vector3d rotation = main ? rotMain.get() : rotOff.get();
        Vector3d scaling = main ? scaleMain.get() : scaleOff.get();

        meteorsupport$centerScale(event.matrix, scaling, eatCenterCompensation.get());
        rotate(event.matrix, rotation);
        scale(event.matrix, scaling);

        ci.cancel();
    }

    /**
     * 往姿势矩阵上补一位平移, 让接下来的缩放变成「横向围绕屏幕中间那条竖线、高度围绕手腕」,
     * 而不是原来的「整块围绕手腕那个原点」。
     *
     * <p>「围绕某个点 p 缩放」= 先平移 (1 - 缩放) * (p - 手腕) 再缩放。这里选的 p 和手腕同高、同深度，
     * 只是横向搬到了屏幕中间那条竖线上：所以横向只朝屏幕中间收（不再往右上角偏），
     * 纵向朝手腕那条高度收（往下走，不挡准星），缩完的「看起来大小」也正好是设置里的比例。
     *
     * <p>注意 26.1 这套手部姿势矩阵是「摄像机旋转的逆 × 手部变换」，也就是轴跟世界对齐、原点在摄像机，
     * 所以「屏幕左右」这个方向不能拿矩阵自己的 x 轴，得用摄像机自己的左右轴，
     * 否则一转头算出来的左右方向就是错的、位置还会突然跳。
     */
    @Unique
    private static void meteorsupport$centerScale(PoseStack matrix, Vector3d scaling, Vector3d manual) {
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;

        // 姿势矩阵后面会被 translate/rotate/scale 就地改掉, 先拷一份
        Matrix4f pose = new Matrix4f(matrix.last().pose());
        Vector3f origin = new Vector3f(pose.m30(), pose.m31(), pose.m32());   // 手腕在这套坐标里的位置

        // 手腕 → 屏幕中间那条竖线，高度和深度不动（左右轴取左还是取右都一样，差一个正负号而已）
        Vector3fc left = camera.leftVector();
        Vector3f shift = new Vector3f(left).mul(-origin.dot(left));

        if (manual.x != 0 || manual.y != 0 || manual.z != 0) {
            // 手调的那份按屏幕方向给: X 右 / Y 上 / Z 前
            shift.fma((float) -manual.x, camera.leftVector());
            shift.fma((float) manual.y, camera.upVector());
            shift.fma((float) manual.z, camera.forwardVector());
        }

        // 换算成手部本地坐标, 再乘 (1 - 缩放): 缩放是 1 的时候本来就不用补
        new Matrix3f(pose).invert().transform(shift);

        matrix.translate(
            (float) ((1 - scaling.x) * shift.x),
            (float) ((1 - scaling.y) * shift.y),
            (float) ((1 - scaling.z) * shift.z)
        );
    }

    /** 这只手正在吃东西/喝东西（用的就是原版进食动画的两种: EAT / DRINK） */
    @Unique
    private static boolean meteorsupport$isEating(InteractionHand hand) {
        if (mc.player == null) return false;
        if (!mc.player.isUsingItem()) return false;
        if (mc.player.getUsedItemHand() != hand) return false;
        if (mc.player.getUseItemRemainingTicks() <= 0) return false;

        ItemUseAnimation animation = mc.player.getUseItem().getUseAnimation();
        return animation == ItemUseAnimation.EAT || animation == ItemUseAnimation.DRINK;
    }

    /** 插到指定设置下面（Meteor 只能末尾追加, 得自己按位置插） */
    @Unique
    private static void insertAfter(SettingGroup group, String afterName, Setting<?> setting) {
        List<Setting<?>> settings = ((SettingGroupAccessor) (Object) group).getSettings();
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).name.equals(afterName)) {
                settings.add(i + 1, setting);
                return;
            }
        }
        group.add(setting);
    }
}
