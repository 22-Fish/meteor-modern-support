package fish22.modernsupport.modules;

import fish22.modernsupport.utils.BackpackUse;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Random;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 长矛噪音 — 娱乐模块
 *
 * <p>长矛（26.1 新增武器）右键会发声：右键时原版会进入「正在使用」状态并播放一次
 * SPEAR_USE 声音，连点就是连着响，附近玩家能听到（服务端放给自己以外的玩家）。
 * 本模块就是自动帮玩家「连点」手里的长矛，用来刷声音。
 *
 * <p>运行节奏（都在设置界面里配）：
 * <ul>
 *   <li>持续时间：一段噪音持续多少个 tick，两个滑块里取随机值；</li>
 *   <li>停止：勾上后两段噪音之间插入一段停歇，停歇时长同样在两个滑块里取随机值，
 *       不勾就是一段接一段一直响；</li>
 *   <li>每tick次数：一段里每 tick 右键几次，两个滑块里取随机值（1-10 次/tick）。</li>
 * </ul>
 *
 * <p>用哪把长矛：手上（主手/副手）优先，长矛在别的快捷栏格时静默切过去用完换回；
 * 勾了「背包使用」还可以用背包（主区）里的长矛，交换方式/目标槽位见 {@link BackpackUse}。
 * 附近没有长矛时这一段只是空响，不弹提示。
 *
 * <p>一 tick 连点多次时物品只<b>移动一次</b>：先换到位，再把这一 tick 的所有使用包一次性发完，
 * 最后换回一次（不是每发一次使用包就换进换出一次）。这样同一 tick 的背包点击包少很多，
 * 也不容易和换装（甲飞换鞘翅、一键烟花等）抢背包状态。
 *
 * <p>连点完同 tick 会「松手」一次：不松手的话服务端会一直认为玩家在蓄力，
 * 长矛的突刺判定每 tick 都在跑（会真的捅到人、也会让别的模块认为玩家在「使用物品中」）。
 * 玩家自己在吃/喝等使用别的物品时不会去松手。
 *
 * <p>勾上「静音」后连点不再在自己这边播放长矛的使用音（只有附近玩家听得到），
 * 只掐本模块自己的连点，不影响别人的长矛声。
 *
 * <p>勾上「手持长矛暂停」后，主手拿着长矛时本模块完全不动（也不去点它），
 * 方便自己手动用长矛；换成别的物品后接着按原来的进度跑。
 *
 * <p>勾上「使用物品时暂停」（默认开）后，玩家自己正在用别的物品（吃、喝、拉弓、举盾…）时
 * 本模块完全不动（也不去点它）：连点会把玩家的使用状态顶掉（比如吃到一半被顶成蓄力），
 * 所以默认不去打断；用完后接着按原来的进度跑。
 * 「正在使用长矛」不算玩家自己的使用（本模块自己连点出来的状态也长这样），要手动蓄力长矛
 * 请开「手持长矛暂停」或先关掉本模块。
 */
public class SpearNoise extends Module {

    /** 阶段：噪音段 / 停止段 */
    private enum Phase {
        NOISE,
        STOP
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 频率（每 tick 连点次数）单独一组 */
    private final SettingGroup sgFrequency = settings.createGroup("频率");

    /** 使用（背包使用相关）单独一组 */
    private final SettingGroup sgUse = settings.createGroup("使用");

    // ---------------- 持续时间 ----------------

    /** 一段噪音的持续时间下限 */
    private final Setting<Integer> durationMin = sgGeneral.add(new IntSetting.Builder()
        .name("持续时间1")
        .description("一段噪音持续时间的随机下限（tick，20 tick = 1 秒）。")
        .defaultValue(20)
        .range(1, 200)
        .sliderRange(1, 200)
        .build()
    );

    /** 一段噪音的持续时间上限 */
    private final Setting<Integer> durationMax = sgGeneral.add(new IntSetting.Builder()
        .name("持续时间2")
        .description("一段噪音持续时间的随机上限（tick，20 tick = 1 秒）。")
        .defaultValue(60)
        .range(1, 200)
        .sliderRange(1, 200)
        .build()
    );

    /** 是否在两段噪音之间停歇 */
    private final Setting<Boolean> stop = sgGeneral.add(new BoolSetting.Builder()
        .name("停止")
        .description("两段噪音之间是否插入一段停歇，停歇时长在下面的两个滑块里取随机值。")
        .defaultValue(true)
        .build()
    );

    // ---------------- 停止时间（和持续时间一样，取随机值） ----------------

    /** 停歇时长下限 */
    private final Setting<Integer> stopMin = sgGeneral.add(new IntSetting.Builder()
        .name("停止时间1")
        .description("一段停歇的随机下限（tick，20 tick = 1 秒）。")
        .defaultValue(20)
        .range(1, 200)
        .sliderRange(1, 200)
        .visible(stop::get)
        .build()
    );

    /** 停歇时长上限 */
    private final Setting<Integer> stopMax = sgGeneral.add(new IntSetting.Builder()
        .name("停止时间2")
        .description("一段停歇的随机上限（tick，20 tick = 1 秒）。")
        .defaultValue(60)
        .range(1, 200)
        .sliderRange(1, 200)
        .visible(stop::get)
        .build()
    );

    /** 连点时不在自己这边播放长矛的声音 */
    private final Setting<Boolean> mute = sgGeneral.add(new BoolSetting.Builder()
        .name("静音")
        .description("连点时不播放长矛的使用音（只有附近玩家听得到）。只掐本模块自己的连点，不屏蔽别人的长矛声。")
        .defaultValue(false)
        .build()
    );

    /** 主手拿着长矛时暂停本模块 */
    private final Setting<Boolean> pauseOnHold = sgGeneral.add(new BoolSetting.Builder()
        .name("手持长矛暂停")
        .description("主手拿着长矛时暂停：不会去自动点它（方便自己手动用长矛），换成别的物品后接着跑。")
        .defaultValue(false)
        .build()
    );

    /** 玩家自己在用物品时暂停本模块 */
    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("使用物品时暂停")
        .description("玩家自己正在使用别的物品（吃、喝、拉弓、举盾…）时暂停：不会自动点长矛去打断它，计时也一起停住，用完后接着跑。（手动蓄力长矛请用「手持长矛暂停」）")
        .defaultValue(true)
        .build()
    );

    // ---------------- 频率 ----------------

    /** 每 tick 右键次数下限 */
    private final Setting<Integer> clicksMin = sgFrequency.add(new IntSetting.Builder()
        .name("每tick次数1")
        .description("每 tick 右键次数的随机下限（1-10 次/tick）。")
        .defaultValue(1)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    /** 每 tick 右键次数上限 */
    private final Setting<Integer> clicksMax = sgFrequency.add(new IntSetting.Builder()
        .name("每tick次数2")
        .description("每 tick 右键次数的随机上限（1-10 次/tick）。")
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    // ---------------- 使用（背包使用相关） ----------------

    /** 允许使用背包里的长矛 */
    private final Setting<Boolean> backpackUse = sgUse.add(new BoolSetting.Builder()
        .name("背包使用")
        .description("允许使用背包（主区）里的长矛：每 tick 只换一次——换到目标槽位，把这一 tick 的使用包发完，再换回。")
        .defaultValue(false)
        .build()
    );

    /** 背包使用的交换发包方式 */
    private final Setting<BackpackUse.Mode> mode = sgUse.add(new EnumSetting.Builder<BackpackUse.Mode>()
        .name("背包使用发包")
        .description("背包使用的发包方式。SWAP：2 次 SWAP 点击（背包槽与目标格互换）；PICKUP：4 次 PICKUP 点击（走光标，背包满也能换）")
        .defaultValue(BackpackUse.Mode.SWAP)
        .visible(backpackUse::get)
        .build()
    );

    /** 背包使用换到哪一格使用 */
    private final Setting<BackpackUse.TargetSlot> targetSlot = sgUse.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
        .name("目标槽位")
        .description("背包物品换到哪一格使用。副手：换到副手使用（不碰手上那一格）；主手：换到手持那一格；快捷栏：换到除手持那一格以外的一个快捷栏格（空手 > 工具 > 方块 > 物品）")
        .defaultValue(BackpackUse.TargetSlot.OFFHAND)
        .visible(backpackUse::get)
        .build()
    );

    private final Random random = new Random();

    /** 当前阶段 */
    private Phase phase = Phase.NOISE;
    /** 当前阶段剩余 tick */
    private int ticksLeft;
    /** 静音窗口：为 true 时（本模块正在连点）掐掉长矛的使用音 */
    private boolean muteSound;

    public SpearNoise() {
        super(Categories.Misc, "长矛噪音",
            "自动右键手里的长矛刷声音");
    }

    @Override
    public void onActivate() {
        phase = Phase.NOISE;
        ticksLeft = randomBetween(durationMin.get(), durationMax.get());
    }

    @Override
    public void onDeactivate() {
        stopUsing();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // 打开容器（箱子等）时不动作，避免把点击发到容器里
        if (mc.player.containerMenu.containerId != 0) return;

        // 手持长矛暂停：主手拿着长矛就不动它（计时也一起停住，切走物品后接着跑）
        if (pauseOnHold.get() && isSpear(mc.player.getMainHandItem())) return;

        // 使用物品时暂停：玩家自己在用别的物品（吃/喝/拉弓/举盾…）就不动它，免得连点把玩家顶出
        // 使用状态（计时也一起停住，用完后接着跑）。
        // 注意不能直接看 isUsingItem()：26.1 的它是客户端本地状态，本模块自己连点长矛、以及服务端
        // 回声都会把它点亮，直接当玩家使用会让模块把自己永久停住（详见 usingStateIsOurs()）
        if (pauseOnUse.get() && !usingStateIsOurs()) return;

        // 当前阶段结束 → 切到下一个阶段并抽新的随机时长
        if (ticksLeft <= 0) nextPhase();

        if (phase == Phase.NOISE) {
            int clicks = randomBetween(clicksMin.get(), clicksMax.get());

            boolean silent = mute.get();
            if (silent) muteSound = true;
            boolean used;
            try {
                used = useTimes(clicks);
            } finally {
                if (silent) muteSound = false;
            }

            // 连点完就松手，不让玩家一直停在长矛的「正在使用」（蓄力）状态
            if (used) stopUsing();
        }

        ticksLeft--;
    }

    /** 阶段结束：有停歇就进停止段，否则直接开下一段噪音 */
    private void nextPhase() {
        if (phase == Phase.NOISE && stop.get()) {
            phase = Phase.STOP;
            ticksLeft = randomBetween(stopMin.get(), stopMax.get());
            return;
        }

        phase = Phase.NOISE;
        ticksLeft = randomBetween(durationMin.get(), durationMax.get());
    }

    /**
     * 本 tick 连点 {@code times} 次长矛：物品只「移动一次」，这一 tick 的所有使用包一次性发完，
     * 最后再「移动一次」放回原位。
     *
     * <p>一 tick 里每用一次就换进换出一次是没必要的：那样会让背包点击包成倍增加
     * （背包使用 SWAP 模式 2 包/次、PICKUP 模式 4 包/次），本地镜像和服务端菜单更容易分叉，
     * 还会和同一 tick 的换装（甲飞换鞘翅、一键烟花）抢同一份背包状态 —— 频率越高越容易出现
     * 「物品留在错误的格子里」，表现就是别的模块（例如一键烟花）突然找不到自己的物品。
     * 所以这里按「换一次 → 连发 → 换回一次」来做。
     *
     * @return 是否真的发出了使用包（附近没有长矛时返回 false）
     */
    private boolean useTimes(int times) {
        if (mc.player == null) return false;

        // 背包使用：快捷栏/副手优先，其次背包任意位置（同一 tick 内交换 → 连发使用包 → 换回）
        if (backpackUse.get()) {
            return BackpackUse.use(SpearNoise::isSpear, mode.get(), targetSlot.get(), times);
        }

        // 不勾选背包使用：主手 / 副手直接使用
        if (isSpear(mc.player.getMainHandItem())) {
            useRepeated(InteractionHand.MAIN_HAND, times);
            return true;
        }
        if (isSpear(mc.player.getOffhandItem())) {
            useRepeated(InteractionHand.OFF_HAND, times);
            return true;
        }

        // 长矛在别的快捷栏格：静默切过去一次、连发完这一 tick 的使用包、再切回来一次
        FindItemResult result = InvUtils.findInHotbar(SpearNoise::isSpear);
        if (!result.found()) return false;
        if (result.isOffhand()) {
            // 正常到不了这里（副手上面已经处理过），真到了也照常连点，别把使用包发到手上别的物品
            useRepeated(InteractionHand.OFF_HAND, times);
            return true;
        }

        InvUtils.swap(result.slot(), true);
        try {
            useRepeated(InteractionHand.MAIN_HAND, times);
        } finally {
            InvUtils.swapBack();
        }
        return true;
    }

    /** 同一 tick 内连发 {@code times} 个使用包（不移动任何物品） */
    private void useRepeated(InteractionHand hand, int times) {
        for (int i = 0; i < times; i++) {
            mc.gameMode.useItem(mc.player, hand);
        }
    }

    /**
     * 松手（退出「正在使用」状态）。
     *
     * <p>玩家自己正在用别的物品（吃/喝/拉弓/举盾…）时让路，其余一律松手。
     *
     * <p>为什么不能只看手上那个物品是不是长矛：26.1 起客户端的「正在使用物品」是本地状态
     * （{@code LocalPlayer#isUsingItem()} 读的是 {@code startedUsingItem}），原版右键
     * （{@code Minecraft#handleKeybinds} 里的 {@code keyUse.isDown() && rightClickDelay == 0
     * && !player.isUsingItem()}）就卡在这个状态上——一旦它被点亮又没人清，右键使用、右键交互、
     * 挥手动画会全部失灵。背包使用是「换到手上 → 用 → 换回」，松手时服务端回声点亮的那个
     * 「正在使用」用的可能是换回来的物品（比如副手图腾），手上已经不是长矛了，这时也必须清掉，
     * 否则这一发使用就再也松不开手。
     */
    private void stopUsing() {
        if (mc.player == null) return;

        // 玩家自己在用别的物品：不打断
        if (!usingStateIsOurs()) return;

        mc.gameMode.releaseUsingItem(mc.player);
    }

    /**
     * 这个「正在使用物品」状态是不是本模块自己弄出来的（连点长矛 / 它的服务端回声 / 残留）。
     *
     * <p>26.1 的 {@code LocalPlayer#isUsingItem()} 只是客户端本地状态（{@code startedUsingItem}）：
     * 本模块自己连点长矛就会把它点亮（服务端回声也会点亮，而且回声点亮时取的是
     * <b>手上此刻的物品</b>，换回之后可能已经不是长矛了）。
     *
     * <p>判断成"本模块自己的"，就要由本模块自己松手；判断成玩家自己的，模块让路（暂停 / 不松手）：
     * <ul>
     *   <li><b>长矛</b>：本模块就是个自动点长矛的模块，这个状态一定是它自己点出来的（回声、残留同理），
     *       所以交给模块松手；要手动蓄力长矛请开「手持长矛暂停」（主手）或先关掉模块；</li>
     *   <li><b>别的根本没有使用时长（{@code useDuration <= 0}）的物品</b>（图腾、方块、普通武器…）：
     *       它们不可能一直被"使用中"，被点亮只可能是回声/残留点错了物品，也归模块清掉；</li>
     *   <li>剩下的（食物、药水、弓、盾等真的能一直用的物品）才可能是玩家自己在用，模块让路。</li>
     * </ul>
     */
    private boolean usingStateIsOurs() {
        if (!mc.player.isUsingItem()) return true;

        ItemStack used = mc.player.getUseItem();
        if (isSpear(used)) return true;

        return used.getUseDuration(mc.player) <= 0;
    }

    /**
     * 静音：掐掉本模块连点自己发出的长矛使用音。
     *
     * <p>客户端只会播自己造成的那些声音（{@code ClientLevel#playSeededSound} 里会判 «除自己以外»），
     * 而声音实例就是在「使用」调用里同步播出来的，所以 {@link #muteSound} 只在自己连点的那一小段里
     * 为 true，附近玩家的长矛声不会受影响。
     */
    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!muteSound) return;
        if (!isSpearUseSound(event.sound.getIdentifier())) return;

        event.cancel();
    }

    /** 长矛的使用音：普通长矛 {@code item.spear.use}，木长矛单独一个音 */
    private static boolean isSpearUseSound(Identifier id) {
        return id.equals(SoundEvents.SPEAR_USE.value().location())
            || id.equals(SoundEvents.SPEAR_WOOD_USE.value().location());
    }

    /** 长矛判断：物品带 spears 标签（木/石/铜/铁/金/钻/合金 七把长矛都在这个标签里） */
    private static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.SPEARS);
    }

    /** 在两个滑块之间取随机值（两个滑块顺序反了也能用） */
    private int randomBetween(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo + random.nextInt(hi - lo + 1);
    }
}
