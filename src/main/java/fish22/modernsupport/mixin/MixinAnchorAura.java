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

import fish22.modernsupport.modules.GhostMine;
import fish22.modernsupport.utils.BackpackUse;
import fish22.modernsupport.utils.LegalCrystal;
import fish22.modernsupport.utils.LegalPlace;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「锚光环」（重生锚光环）增强 mixin
 *
 * <ul>
 *   <li><b>旋转拆成三个</b>：官方那个「旋转」总开关从界面上拿掉，换成放置旋转 / 充能旋转 / 爆炸旋转，
 *       各管自己那一段（放锚、放发光石充能、打爆炸锚）</li>
 *   <li><b>合法转头</b>：转头走 {@link LegalRotation}（朝向跟着移动包发出去，交互包排在它后面），
 *       角度用 {@link LegalPlace} 算（点得到那个面、且相对服务器当前角度差整数格鼠标灵敏度）。
 *       开着时一次都不会再走 Meteor 那套静默旋转：算不出合法角度的位置按官方那套挑个命中点
 *       算个角度、照样走合法转头（官方那次旋转有 4 tick 朝向滞留，还会和别的模块抢同一 tick 的移动包）</li>
 *   <li><b>仅合法位置</b>：选位置时就把「没有能点的支撑面」的位置跳过，不放上去</li>
 *   <li><b>忽略重挖点</b>：不在「发包挖掘」的重挖框那一格放锚（放上去会被它顺手挖掉）</li>
 *   <li><b>背包放置 / 背包充能</b>：重生锚、发光石在背包里也能用（走 {@link BackpackUse}）</li>
 * </ul>
 *
 * <p>新增的东西全按默认值（三个旋转开、其余关）时，除了「旋转拆成三个」以外行为和原版一致
 */
@Mixin(value = AnchorAura.class, remap = false)
public abstract class MixinAnchorAura {

    /**
     * 放置距离留的余量（格）
     *
     * <p>算角度用的是「这一 tick 走完之后」的预判眼睛，和服务器真正拿到的那一帧差一点点，
     * 贴着极限距离放，射线就差这零点几格打不到方块
     */
    @Unique
    private static final double REACH_MARGIN = 0.3;

    @Shadow
    private SettingGroup sgGeneral;

    @Shadow
    private SettingGroup sgPlace;

    @Shadow
    private SettingGroup sgBreak;

    @Shadow
    private Setting<Boolean> place;

    @Shadow
    private Setting<Boolean> swing;

    @Shadow
    private Setting<Boolean> swapBack;

    @Shadow
    private Setting<Double> placeRange;

    @Shadow
    private Setting<Double> breakRange;

    @Shadow
    private Setting<Integer> chargeDelay;

    @Shadow
    private Setting<Integer> breakDelay;

    @Shadow
    private BlockPos.MutableBlockPos bestBreakPos;

    @Shadow
    private BlockPos renderBlockPos;

    @Shadow
    private int chargeDelayLeft;

    @Shadow
    private int breakDelayLeft;

    /** 放锚时转头 */
    @Unique
    private Setting<Boolean> rotatePlace;

    /** 放发光石充能时转头 */
    @Unique
    private Setting<Boolean> rotateCharge;

    /** 打爆炸锚时转头 */
    @Unique
    private Setting<Boolean> rotateBreak;

    /** 合法转头模式（关闭＝用 Meteor 原版旋转） */
    @Unique
    private Setting<LegalRotation.Mode> legalRotation;

    /** 合法转头优先级 */
    @Unique
    private Setting<Integer> legalRotationPriority;

    /** 只在算得出合法放置角度（有能点的面）的位置放 */
    @Unique
    private Setting<Boolean> onlyLegalPosition;

    /** 不在发包挖掘的重挖框位置放锚 */
    @Unique
    private Setting<Boolean> ignoreRebreak;

    /** 重生锚在背包里也能放 */
    @Unique
    private Setting<Boolean> backpackPlace;

    /** 发光石在背包里也能充能 */
    @Unique
    private Setting<Boolean> backpackCharge;

    /** 仅有利：只选对自己伤害小于对敌人伤害的位置 */
    @Unique
    private Setting<Boolean> onlyProfitable;

    /** 官方那一步算出来的「对自己伤害」（顺手记下来，「仅有利」用它） */
    @Unique
    private float candidateSelfDamage;

    /** 调试输出：开着才在聊天栏打印放置判定（同一份判定不重复打） */
    @Unique
    private Setting<Boolean> debug;

    /** 上一次打过的判定（去重，免得刷屏） */
    @Unique
    private String lastDebugKey;

    /** 背包放置 / 背包充能共用的发包方式 */
    @Unique
    private Setting<BackpackUse.Mode> backpackMode;

    /** 背包放置 / 背包充能共用的目标槽位 */
    @Unique
    private Setting<BackpackUse.TargetSlot> backpackTarget;

    // ====== 设置 ======

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        AnchorAura self = (AnchorAura) (Object) this;

        // 官方那个「旋转」总开关从界面上拿掉，换成放置 / 充能 / 爆炸三个开关（值还在，只是不显示也不再用）
        removeSetting(sgGeneral, "rotate");

        // 仅有利：对自己伤害不低于对敌人伤害的位置直接跳过（挂在官方伤害设置旁边）
        onlyProfitable = new BoolSetting.Builder()
            .name("仅有利")
            .description("只选择对自己造成的伤害小于对敌人造成的伤害的位置")
            .defaultValue(false)
            .build();
        insertAfter(sgGeneral, "anti-suicide", onlyProfitable);

        rotatePlace = new BoolSetting.Builder()
            .name("放置旋转")
            .description("放重生锚时转头。关掉就不转，直接按当前视角放")
            .defaultValue(true)
            .visible(place::get)
            .build();
        insertAfter(sgPlace, "air-place", rotatePlace);

        onlyLegalPosition = new BoolSetting.Builder()
            .name("仅合法位置")
            .description("只放附近有点得到的面的位置，点不到的位置直接跳过")
            .defaultValue(false)
            .visible(place::get)
            .build();
        insertAfter(sgPlace, "放置旋转", onlyLegalPosition);

        ignoreRebreak = new BoolSetting.Builder()
            .name("忽略重挖点")
            .description("不在「发包挖掘」的重挖框那一格放重生锚（放上去会被它顺手挖掉）")
            .defaultValue(false)
            .visible(place::get)
            .build();
        insertAfter(sgPlace, "仅合法位置", ignoreRebreak);

        debug = new BoolSetting.Builder()
            .name("调试输出")
            .description("在聊天栏打印每一格的放置判定（选了哪格、算没算得出角度、走了哪条路）")
            .defaultValue(false)
            .visible(place::get)
            .build();
        insertAfter(sgPlace, "忽略重挖点", debug);

        rotateCharge = new BoolSetting.Builder()
            .name("充能旋转")
            .description("放发光石给锚充能时转头")
            .defaultValue(true)
            .build();
        insertAfter(sgBreak, "charge-delay", rotateCharge);

        rotateBreak = new BoolSetting.Builder()
            .name("爆炸旋转")
            .description("打爆炸锚时转头")
            .defaultValue(true)
            .build();
        insertAfter(sgBreak, "break-delay", rotateBreak);

        SettingGroup sgBackpack = self.settings.createGroup("背包");
        backpackPlace = sgBackpack.add(new BoolSetting.Builder()
            .name("背包放置")
            .description("重生锚在背包里也能放置")
            .defaultValue(false)
            .build()
        );
        backpackCharge = sgBackpack.add(new BoolSetting.Builder()
            .name("背包充能")
            .description("发光石在背包里也能充能")
            .defaultValue(false)
            .build()
        );
        backpackMode = sgBackpack.add(new EnumSetting.Builder<BackpackUse.Mode>()
            .name("背包使用发包")
            .description("背包放置和背包充能共用，SWAP：2 次 SWAP 点击；PICKUP：4 次 PICKUP 点击（走光标，背包满也能换）")
            .defaultValue(BackpackUse.Mode.SWAP)
            .visible(() -> backpackPlace.get() || backpackCharge.get())
            .build()
        );
        backpackTarget = sgBackpack.add(new EnumSetting.Builder<BackpackUse.TargetSlot>()
            .name("背包目标槽位")
            .description("背包放置和背包充能共用，背包物品换到哪一格使用")
            .defaultValue(BackpackUse.TargetSlot.OFFHAND)
            .visible(() -> backpackPlace.get() || backpackCharge.get())
            .build()
        );

        SettingGroup sgLegit = self.settings.createGroup("合法转头");
        legalRotation = sgLegit.add(new EnumSetting.Builder<LegalRotation.Mode>()
            .name("合法转头")
            .description("转头走合法转头（朝向跟着移动包发出，交互包排在它后面）。关闭＝用 Meteor 原版旋转")
            .defaultValue(LegalRotation.Mode.OFF)
            .build()
        );
        legalRotationPriority = sgLegit.add(new IntSetting.Builder()
            .name("合法转头优先级")
            .description("同一 tick 里和其它模块抢转向时的优先级，大的赢")
            .defaultValue(0)
            .sliderRange(-20, 20)
            .visible(this::isLegalRotationOn)
            .build()
        );
    }

    // ====== 选位置：重挖点 / 仅合法位置 ======

    /**
     * 官方算伤害那两步（先算对自己的、再算对目标的）：这里只加「仅有利」。
     *
     * <p>「对自己伤害」那一步照原样算，顺手把值记下来；「对目标伤害」那一步在「仅有利」开着、
     * 而自己挨的伤害不比敌人少时返回负伤害 —— 过不了官方那句「对敌人伤害 ≥ 最小伤害」，
     * 这一格就等于不存在（和水晶光环那边一个做法）。
     *
     * <p>{@code require = 0}：官方哪天换了这两处伤害调用，这条安静地不生效，「仅有利」也就不再过滤
     */
    @Redirect(
        method = "lambda$doAnchorAura$0(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/entity/DamageUtils;anchorDamage(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;)F",
            ordinal = 0
        ),
        require = 0
    )
    private float redirectSelfDamage(LivingEntity entity, Vec3 anchor) {
        return anchorDamageStep(entity, anchor);
    }

    /** 官方算「对目标伤害」那一步（「仅有利」在这一步把不划算的位置拦掉） */
    @Redirect(
        method = "lambda$doAnchorAura$0(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/entity/DamageUtils;anchorDamage(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;)F",
            ordinal = 1
        ),
        require = 0
    )
    private float redirectTargetDamage(LivingEntity entity, Vec3 anchor) {
        return anchorDamageStep(entity, anchor);
    }

    /**
     * 官方那两处伤害调用走这里：按「算的是谁」分辨是哪一步（不靠调用顺序），
     * 对自己的照原样返回并记下来，对目标的在「仅有利」下把不划算的位置变成负伤害
     */
    @Unique
    private float anchorDamageStep(LivingEntity entity, Vec3 anchor) {
        float damage = DamageUtils.anchorDamage(entity, anchor);

        if (entity == mc.player) {
            candidateSelfDamage = damage;
            return damage;
        }

        if (onlyProfitable.get() && candidateSelfDamage >= damage) return -1.0f;
        return damage;
    }

    /**
     * 官方扫附近方块那一步里「这一格能不能放」的检查（只有放置那一支会走）
     *
     * <p>「忽略重挖点」和「仅合法位置」都在这儿拦：位置不合适就当作不能放，
     * 官方会接着去看下一个位置，而不是整 tick 卡在同一个地方
     *
     * <p>{@code require = 0}：这是编译生成的 lambda 方法名，万一哪天换了名字，
     * 这一条安静地不生效：「忽略重挖点」还有 {@link #redirectPlaceAnchor} 兜底，
     * 「仅合法位置」在真正放的时候也还会再判一次
     */
    @Redirect(
        method = "lambda$doAnchorAura$0(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;canPlace(Lnet/minecraft/core/BlockPos;)Z"
        ),
        require = 0
    )
    private boolean redirectCanPlaceAnchor(BlockPos pos) {
        if (!BlockUtils.canPlace(pos)) return false;
        if (ignoreRebreak.get() && isRebreakPos(pos)) {
            dbg("rebreak@" + pos, "跳过 %s：这是「发包挖掘」的重挖框", pos.toShortString());
            return false;
        }
        if (onlyLegalPosition.get() && !hasLegalPlaceFace(pos)) {
            dbg("illegal@" + pos, "跳过 %s：算不出合法放置角度（没有点得到的支撑面）", pos.toShortString());
            return false;
        }
        return true;
    }

    /** 背包里找得到重生锚时，官方那句「快捷栏没有锚就不放」放行 */
    @Redirect(
        method = "lambda$doAnchorAura$1()V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/InvUtils;findInHotbar([Lnet/minecraft/world/item/Item;)Lmeteordevelopment/meteorclient/utils/player/FindItemResult;",
            ordinal = 0
        ),
        require = 0
    )
    private FindItemResult redirectFindAnchor(Item... items) {
        FindItemResult hotbar = InvUtils.findInHotbar(items);
        if (hotbar.found() || !backpackPlace.get()) return hotbar;

        FindItemResult inventory = InvUtils.find(Items.RESPAWN_ANCHOR);
        return inventory.found() ? inventory : hotbar;
    }

    /** 背包里找得到发光石时，官方「快捷栏没有发光石就不放锚」那条也放行 */
    @Redirect(
        method = "lambda$doAnchorAura$1()V",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/InvUtils;findInHotbar([Lnet/minecraft/world/item/Item;)Lmeteordevelopment/meteorclient/utils/player/FindItemResult;",
            ordinal = 1
        ),
        require = 0
    )
    private FindItemResult redirectFindGlowstone(Item... items) {
        FindItemResult hotbar = InvUtils.findInHotbar(items);
        if (hotbar.found() || !backpackCharge.get()) return hotbar;

        FindItemResult inventory = InvUtils.find(Items.GLOWSTONE);
        return inventory.found() ? inventory : hotbar;
    }

    // ====== 放置 ======

    /**
     * 官方挑完位置后放锚那一下
     *
     * <p>开了「仅合法位置 / 合法转头」就用 {@link LegalPlace} 算好的角度和命中点自己放
     * （优先 {@link LegalRotation}，没开就是 Meteor 原版旋转）；都没开就走官方
     * {@code BlockUtils.place}，只是「转不转」按新的「放置旋转」来
     *
     * <p>开了「合法转头」时算不出合法角度（空中放置那种六面都没支撑的）不再退回官方旋转：
     * 用 {@link #vanillaPlaceHit} 那套挑个命中点算个角度（量化到鼠标灵敏度格），照样走合法转头
     */
    @Redirect(
        method = "doPlace",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;place(Lnet/minecraft/core/BlockPos;Lmeteordevelopment/meteorclient/utils/player/FindItemResult;ZIZZZ)Z"
        )
    )
    private boolean redirectPlaceAnchor(BlockPos pos, FindItemResult item, boolean vanillaRotate, int priority,
                                        boolean swingHand, boolean checkEntities, boolean swapBackFlag) {
        // 重挖点兜底：官方挑中的就是重挖点（lambda 那条没生效时）也不放
        if (ignoreRebreak.get() && isRebreakPos(pos)) {
            dbg("place-rebreak@" + pos, "放弃 %s：这是「发包挖掘」的重挖框", pos.toShortString());
            return false;
        }

        boolean legal = isLegalRotationOn();
        LegalPlace.Aim aim = (legal || onlyLegalPosition.get()) ? findPlaceAim(pos) : null;

        // 仅合法位置：算不出合法角度就不放（宁可少放一下）
        if (onlyLegalPosition.get() && aim == null) {
            dbg("place-illegal@" + pos, "放弃 %s：「仅合法位置」开着，这格算不出合法角度", pos.toShortString());
            return false;
        }

        // 锚在背包里（不在快捷栏 / 副手）：官方那条路不认背包槽位，得自己发包
        boolean fromBackpack = backpackPlace.get() && item.found() && !item.isHotbar() && !item.isOffhand();

        // 没算角度、也不走背包、又没开合法转头 → 原版怎么放还怎么放，只是转不转看「放置旋转」
        // 开了合法转头就不走这条：算不出合法角度的位置见下面（按官方那套挑个命中点算角度，照样走合法转头）
        if (aim == null && !fromBackpack && !legal) {
            dbg("place-vanilla@" + pos, "放 %s：没算角度，走 meteor 原版放置（自己挑面）", pos.toShortString());
            return BlockUtils.place(pos, item, rotatePlace.get(), officialRotationPriority(priority), swingHand, checkEntities, swapBackFlag);
        }

        BlockHitResult hit = aim != null
            ? new BlockHitResult(aim.hitPos(), aim.face().getOpposite(), aim.clickedBlock(), false)
            : vanillaPlaceHit(pos);
        Runnable action = () -> sendPlacePacket(item, hit, swingHand);

        dbg("place-legal@" + pos, "放 %s：%s（%s）点 %s，背包=%s",
            pos.toShortString(), aim != null ? "用算好的合法角度" : "按官方命中点算角度",
            aim != null && aim.rotated() ? "要转" : "不用转",
            (aim != null ? aim.clickedBlock() : pos).toShortString(), fromBackpack);

        if (rotatePlace.get() && (aim == null || aim.rotated())) {
            double yaw = aim != null ? aim.yaw() : LegalPlace.snapYaw((float) Rotations.getYaw(hit.getLocation()));
            double pitch = aim != null ? aim.pitch() : LegalPlace.snapPitch((float) Rotations.getPitch(hit.getLocation()));

            // 合法转头开着：算不出合法角度也照样走合法转头，绝不再退回 Meteor 那套静默旋转
            // （官方那次旋转有 4 tick 朝向滞留、还会和别的模块抢同一 tick 的移动包）
            if (legal) {
                if (LegalRotation.rotate(yaw, pitch, legalRotation.get(), legalRotationPriority.get(), action)) {
                    return true;
                }

                // 这一份被更高优先级的旋转顶掉：这一次不放（退回官方会给这一 tick 多塞一个移动包）
                dbg("place-busy@" + pos, "跳过 %s：这一 tick 的转头被更高优先级占着", pos.toShortString());
                return true;
            }

            // 合法转头关着：走 Meteor 原版旋转
            Rotations.rotate(yaw, pitch, officialRotationPriority(priority), action);
            return true;
        }

        // 不用转（现在这个朝向本来就点得到）：排到移动包之后发，和原版「先走再交互」一个顺序
        LegalRotation.runAfterSend(action);
        return true;
    }

    /** 官方 {@code BlockUtils.place} 挑命中点那套：按「人在方块哪一侧」挑一个面 */
    @Unique
    private static BlockHitResult vanillaPlaceHit(BlockPos pos) {
        Vec3 hitPos = Vec3.atCenterOf(pos);
        Direction side = BlockUtils.getPlaceSide(pos);

        if (side == null) {
            return new BlockHitResult(hitPos, Direction.UP.getOpposite(), pos, false);
        }

        BlockPos neighbour = pos.relative(side);
        hitPos = hitPos.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        return new BlockHitResult(hitPos, side.getOpposite(), neighbour, false);
    }

    /** 真的把放置包发出去：背包放置开着就走 {@link BackpackUse}（快捷栏优先，其次背包） */
    @Unique
    private void sendPlacePacket(FindItemResult item, BlockHitResult hit, boolean swingHand) {
        if (backpackPlace.get()) {
            BackpackUse.place(stack -> stack.is(Items.RESPAWN_ANCHOR), hit,
                backpackMode.get(), backpackTarget.get(), swingHand);
            return;
        }

        if (!item.found()) return;

        boolean offhand = item.isOffhand();
        boolean swap = !offhand && !item.isMainHand();
        if (swap) InvUtils.swap(item.slot(), swapBack.get());

        BlockUtils.interact(hit, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, swingHand);

        if (swap && swapBack.get()) InvUtils.swapBack();
    }

    // ====== 充能 / 爆炸 ======

    /**
     * 官方破坏那一段整个换掉：充能和爆炸分开决定转不转，开了合法转头就先用合法角度转头、
     * 交互包排在带着这份朝向的移动包之后（和真人「先转过去再点」一个顺序）。
     * 算不出合法角度就按官方那套（瞄方块中心）算个角度，同样不再退回官方静默旋转
     */
    @Inject(method = "doBreak", at = @At("HEAD"), cancellable = true)
    private void onDoBreak(FindItemResult glowStone, CallbackInfo ci) {
        ci.cancel();

        renderBlockPos = bestBreakPos;

        if (mc.player == null || mc.level == null) return;

        boolean legal = isLegalRotationOn();
        boolean wantRotate = rotateCharge.get() || rotateBreak.get();

        // 充能和爆炸点的是同一个方块（锚自己），一次算一份合法角度就够
        LegalPlace.InteractAim aim = legal && wantRotate
            ? LegalPlace.computeInteract(bestBreakPos, List.of(Direction.values()), true, useReach(breakRange.get()))
            : null;
        BlockHitResult legalHit = aim == null
            ? null
            : new BlockHitResult(aim.hitPos(), aim.face(), bestBreakPos, false);

        // 需要转头：合法转头开 → 走合法转头（算不出合法角度就按官方那套算个角度）
        if (wantRotate && legal) {
            // 现在这个朝向本来就点得到：不转，直接交互
            if (aim != null && !aim.rotated()) {
                interactAnchor(glowStone, legalHit);
                return;
            }

            double yaw = aim != null ? aim.yaw() : LegalPlace.snapYaw((float) Rotations.getYaw(bestBreakPos));
            double pitch = aim != null ? aim.pitch() : LegalPlace.snapPitch((float) Rotations.getPitch(bestBreakPos));

            if (LegalRotation.rotate(yaw, pitch, legalRotation.get(), legalRotationPriority.get(),
                () -> interactAnchor(glowStone, legalHit))) {
                return;
            }

            // 这一份被更高优先级的旋转顶掉：这一次不打（退回官方会给这一 tick 多塞一个移动包）
            dbg("break-busy", "跳过：这一 tick 的转头被更高优先级占着");
            return;
        }

        // 合法转头关着、又要转 → 原版旋转 + 原版命中点
        if (wantRotate) {
            Rotations.rotate(Rotations.getYaw(bestBreakPos), Rotations.getPitch(bestBreakPos), 40,
                () -> interactAnchor(glowStone, null));
            return;
        }

        // 不用转：现在这个朝向本来就点得到（合法角度那份直接拿来用）
        interactAnchor(glowStone, legalHit);
    }

    /**
     * 官方 doInteract 那段：先充能（发光石），再爆炸（手上换个不是发光石的东西右键）
     *
     * <p>命中点可以是 {@link LegalPlace} 算好的那个合法点，也可以是官方原来按「人在方块哪一侧」
     * 挑的那个面
     */
    @Unique
    private void interactAnchor(FindItemResult glowStone, BlockHitResult hit) {
        BlockState state = mc.level.getBlockState(bestBreakPos);
        if (state.getBlock() != Blocks.RESPAWN_ANCHOR) return;

        Vec3 center = bestBreakPos.getCenter();
        BlockHitResult target = hit != null
            ? hit
            : new BlockHitResult(center, BlockUtils.getDirection(bestBreakPos), bestBreakPos, true);

        int charges = state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);

        // 充能
        if (charges == 0 && chargeDelayLeft++ >= chargeDelay.get()) {
            if (!chargeAnchor(glowStone, target)) return;
            chargeDelayLeft = 0;
            charges++;
        }

        // 爆炸
        if (charges > 0 && breakDelayLeft++ >= breakDelay.get()) {
            if (!explodeAnchor(target)) return;
            breakDelayLeft = 0;

            // 客户端本地也当它炸了，免得下一 tick 又对着这一格算放置
            mc.level.setBlock(bestBreakPos, mc.level.getFluidState(bestBreakPos).createLegacyBlock(), 0);
        }

        if (swapBack.get()) InvUtils.swapBack();
    }

    /** 放发光石充能：背包充能开着就走 {@link BackpackUse}（快捷栏优先，其次背包） */
    @Unique
    private boolean chargeAnchor(FindItemResult glowStone, BlockHitResult hit) {
        if (backpackCharge.get()) {
            return BackpackUse.place(stack -> stack.is(Items.GLOWSTONE), hit,
                backpackMode.get(), backpackTarget.get(), swing.get());
        }

        if (!glowStone.found()) return false;

        InvUtils.swap(glowStone.slot(), swapBack.get());
        BlockUtils.interact(hit, InteractionHand.MAIN_HAND, swing.get());
        return true;
    }

    /** 打爆炸锚：手上换个不是发光石的东西右键（原版就是随便找个不是发光石的槽位） */
    @Unique
    private boolean explodeAnchor(BlockHitResult hit) {
        FindItemResult item = InvUtils.findInHotbar(stack -> !stack.getItem().equals(Items.GLOWSTONE));
        if (!item.found()) return false;

        InvUtils.swap(item.slot(), swapBack.get());
        BlockUtils.interact(hit, InteractionHand.MAIN_HAND, swing.get());
        return true;
    }

    // ====== 工具方法 ======

    /** 合法转头开没开 */
    @Unique
    private boolean isLegalRotationOn() {
        return legalRotation != null && legalRotation.get() != LegalRotation.Mode.OFF;
    }

    /** 这个位置算最佳放置角度（点支撑方块的某个面），算不出来返回 null */
    @Unique
    private LegalPlace.Aim findPlaceAim(BlockPos target) {
        List<Direction> faces = LegalPlace.supportFaces(target);
        if (faces.isEmpty()) return null;

        return LegalPlace.compute(target, faces, true, 0.0, placeReach());
    }

    /**
     * 这个位置有没有「现在这个位置就点得到的支撑面」（选位置时的便宜版判定）
     *
     * <p>这里只做「有没有」这件事：面合法（不是空气 / 可替换方块）、在够得着的范围内、眼睛在这个面的外侧。
     * <b>不看中间有没有方块挡着</b>：挡着也照样点得到（射线能打进被点方块的碰撞箱就够了），
     * 也就是「穿墙放置」也算合法，只要求朝向点得到这个面。
     * 真正的角度留给 {@link #findPlaceAim} 去算（一个位置算一次，选位置时每个位置都算太贵）
     */
    @Unique
    private boolean hasLegalPlaceFace(BlockPos target) {
        if (mc.player == null || mc.level == null) return false;

        double reach = placeReach();
        if (reach <= 0.0) return false;

        // 和 findPlaceAim 用同一份眼睛（预判这一 tick 走完之后的位置），免得两边判得不一致
        Vec3 eye = LegalPlace.predictedEye();
        for (Direction face : LegalPlace.supportFaces(target)) {
            BlockPos clicked = target.relative(face);

            if (new AABB(clicked).distanceToSqr(eye) > reach * reach) continue;
            if (!eyeOnSide(eye, clicked, face.getOpposite())) continue;
            return true;
        }

        return false;
    }

    /** 眼睛在不在「点得着的那一面」外侧（面的外法线方向朝眼睛这边） */
    @Unique
    private static boolean eyeOnSide(Vec3 eye, BlockPos block, Direction face) {
        double epsilon = 1.0E-3;

        if (face == Direction.UP) return eye.y >= block.getY() + 1.0 - epsilon;
        if (face == Direction.DOWN) return eye.y <= block.getY() + epsilon;
        if (face == Direction.NORTH) return eye.z <= block.getZ() + epsilon;
        if (face == Direction.SOUTH) return eye.z >= block.getZ() + 1.0 - epsilon;
        if (face == Direction.WEST) return eye.x <= block.getX() + epsilon;
        return eye.x >= block.getX() + 1.0 - epsilon;
    }

    /** 算角度能用的距离：模块自己的范围和原版手长取小的，再退一点余量 */
    @Unique
    private static double useReach(double range) {
        double serverReach = mc.player == null ? 4.5 : mc.player.blockInteractionRange();
        return Math.max(Math.min(range, serverReach) - REACH_MARGIN, 0.0);
    }

    /**
     * 走官方旋转时用的优先级
     *
     * <p>官方给的是 {@link LegalCrystal#CRYSTAL_ROTATION_PRIORITY}，而 {@link MixinRotations}
     * 正好靠「优先级 50」认「这是水晶光环那次旋转」，
     * 锚光环也用 50 的话会被它当成水晶光环的旋转认领走（角度被换成水晶的、水晶那份还被消费掉），
     * 所以错开 1；Meteor 队列里这两个数只差一位，实际排序没有区别
     */
    @Unique
    private static int officialRotationPriority(int priority) {
        return priority == LegalCrystal.CRYSTAL_ROTATION_PRIORITY ? priority - 1 : priority;
    }

    /**
     * 放置算角度用的距离：原版手长退一点余量（不看模块自己的 place-range）
     *
     * <p>选位置那一步已经按 place-range / walls-range 挑过了，挑中的位置手长一定够得着；
     * 这里再拿 place-range 砍一刀的话，正好卡在范围边上的位置会「挑得出来、却算不出角度」，
     * 那就是明明能放却不放
     */
    @Unique
    private static double placeReach() {
        double serverReach = mc.player == null ? 4.5 : mc.player.blockInteractionRange();
        return Math.max(serverReach - REACH_MARGIN, 0.0);
    }

    /** 调试输出：同一份判定只打一次，免得每 tick 刷屏 */
    @Unique
    private void dbg(String key, String format, Object... args) {
        if (debug == null || !debug.get() || key.equals(lastDebugKey)) return;
        lastDebugKey = key;
        ((AnchorAura) (Object) this).info(format, args);
    }

    /** 这个位置是不是「发包挖掘」当前盯着的重挖框 */
    @Unique
    private static boolean isRebreakPos(BlockPos pos) {
        GhostMine ghostMine = GhostMine.getInstance();
        return ghostMine != null && ghostMine.isActive() && ghostMine.hasRebreakFrame(pos);
    }

    /** 把设置从分组里拿掉（官方那个旋转总开关不显示也不存档了） */
    @Unique
    private static void removeSetting(SettingGroup group, String name) {
        List<Setting<?>> settings = ((SettingGroupAccessor) (Object) group).getSettings();
        settings.removeIf(setting -> setting.name.equals(name));
    }

    /** 把设置插到分组内指定名字的设置之后（找不到就追加到末尾） */
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
