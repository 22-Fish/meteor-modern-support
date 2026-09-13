package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 鞘翅俯仰40（独立模块，移动分类）
 *
 * <p>从 Meteor 官方「鞘翅飞行」的 Pitch40 模式拆出来的独立模块：滑翔时自动控制俯仰角，
 * 下降时保持 37.72°（最高效的下降角），降到「下界」开始抬头（每 tick 抬头速度，最高到 -54.77°），
 * 升到「上界」再慢慢压回 37.72°，如此循环，实现自动爬升/下降。
 *
 * <p>和官方一样：只改俯仰角，不干预水平移动（水平方向还是自己控制），
 * 开启时必须同时满足「在上界之上」且「比下界高至少 40 格」，否则开不起来（官方限制）。
 */
public class ElytraPitch40 extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 下界：低于该高度开始抬头 */
    private final Setting<Double> lowerBounds = sgGeneral.add(new DoubleSetting.Builder()
        .name("下界")
        .description("低于这个高度就开始抬头。开启模块时必须比这个高度高至少 40 格")
        .defaultValue(180)
        .min(-128)
        .sliderMax(360)
        .build()
    );

    /** 上界：高于该高度开始压回下降角 */
    private final Setting<Double> upperBounds = sgGeneral.add(new DoubleSetting.Builder()
        .name("上界")
        .description("高于这个高度就压回下降角。开启模块时必须在这个高度之上")
        .defaultValue(220)
        .min(-128)
        .sliderMax(360)
        .build()
    );

    /** 抬头速度（度/tick） */
    private final Setting<Double> rotationSpeedUp = sgGeneral.add(new DoubleSetting.Builder()
        .name("抬头速度")
        .description("每次抬头每 tick 转多少度")
        .defaultValue(5.45)
        .min(1)
        .sliderMax(20)
        .build()
    );

    /** 低头速度（度/tick） */
    private final Setting<Double> rotationSpeedDown = sgGeneral.add(new DoubleSetting.Builder()
        .name("低头速度")
        .description("每次压回下降角每 tick 转多少度")
        .defaultValue(0.90)
        .min(0.5)
        .sliderMax(2)
        .build()
    );

    /** 当前是否在下压阶段（true = 往下降角靠，false = 抬头） */
    private boolean pitchingDown = true;

    private float pitch;

    public ElytraPitch40() {
        super(Categories.Movement, "鞘翅Pitch40",
            "俯仰40：滑翔时自动维持俯仰角（下降看 37.72°，降到下界后抬头到 -54.77° 再慢慢压回），自动爬升与下降");
    }

    @Override
    public void onActivate() {
        // 两边都在控制滑翔，同时开只会互相打架：开这个就把「鞘翅飞行」关掉
        Module elytraFly = Modules.get().get(ElytraFly.class);
        if (elytraFly != null && elytraFly.isActive()) elytraFly.toggle();

        if (mc.player == null) return;

        if (mc.player.getY() < upperBounds.get()) {
            error("玩家必须在「上界」之上！");
            toggle();
            return;
        }
        if (mc.player.getY() - 40 < lowerBounds.get()) {
            error("玩家必须比「下界」高至少 40 格！");
            toggle();
            return;
        }

        pitchingDown = true;
        pitch = 37.72F;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || !mc.player.isFallFlying()) return;

        // 上下界切换
        if (pitchingDown && mc.player.getY() <= lowerBounds.get()) {
            pitchingDown = false;
        } else if (!pitchingDown && mc.player.getY() >= upperBounds.get()) {
            pitchingDown = true;
        }

        if (!pitchingDown) {
            // 抬头
            pitch -= randPitch(rotationSpeedUp.get().floatValue(), 1.0F);
            if (pitch < -54.77F) {
                pitch = -54.77F;
                pitchingDown = true;
            }
        } else if (pitch < 37.72F) {
            // 压回下降角
            pitch += randPitch(rotationSpeedDown.get().floatValue(), 0.50F);
        }

        mc.player.setXRot(pitch);
    }

    /** 在角度上叠加 ±bound/2 的随机浮动（原版逻辑） */
    private float randPitch(float pitch, float bound) {
        return (float) (pitch + (bound * (Math.random() - 0.5)));
    }
}
