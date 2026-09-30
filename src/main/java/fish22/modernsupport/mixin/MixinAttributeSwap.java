package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.combat.AttributeSwap;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Meteor 官方「快切」(attribute-swap) 的背包切换增强，加两个设置：
 *
 * <ul>
 *   <li><b>自定义物品</b>：攻击时优先切到列表里的物品，按列表顺序，快捷栏里的那个格子优先；</li>
 *   <li><b>背包切换</b>：找物品时连主背包（9-35 格）一起翻，找到就把它换到手上用，
 *       用完按模块自己的「切换回延迟」放回原格；智能搜索的范围也一起扩到背包。</li>
 * </ul>
 *
 * <p>官方只能碰快捷栏 0-8：Simple 模式固定切到 target-slot，Smart 模式也只在快捷栏里挑评分最高的那件，
 * 所以背包里的武器一直用不上。
 *
 * <p>背包物品的换出 / 换回都走原版点击通道（{@code MultiPlayerGameMode#handleContainerInput}，
 * Meteor 的 {@code InvUtils.quickSwap()} 就是这么发的）：本地点完包就发，跟攻击包同一个 tick，
 * SWAP 点击在前、攻击包在后，服务端先把东西换到手上再结算这一下攻击，跟官方切快捷栏槽位的效果一致。
 * 换的是「当前手持那一格」，所以不用改选中槽位，换回就是把那两格再换一次。
 */
@Mixin(value = AttributeSwap.class, remap = false)
public abstract class MixinAttributeSwap {

    @Shadow
    @Final
    private Setting<Boolean> swapBack;

    @Shadow
    @Final
    private Setting<Integer> swapBackDelay;

    /** 模块自己的换回倒计时（快捷栏换法用它的换回逻辑，所以这里要写它） */
    @Shadow
    private int backTimer;

    /** 模块自己的等待换回标记 */
    @Shadow
    private boolean awaitingBack;

    /** 自定义物品（攻击时优先切到这些物品） */
    @Unique
    private Setting<List<Item>> customItems;

    /** 背包切换（快捷栏里没有就去主背包里找） */
    @Unique
    private Setting<Boolean> backpackSwap;

    /** 正在等换回的背包槽位，-1 表示没有 */
    @Unique
    private int backpackSlot = -1;

    /** 那件物品换到了哪个快捷栏格（换回时按它换回去，中途换过手也没关系） */
    @Unique
    private int backpackButton;

    /** 换出来的那件物品（换回前确认那一格里还是它，玩家自己动过手就不动了） */
    @Unique
    private Item backpackItem;

    /** 换回倒计时（tick） */
    @Unique
    private int backpackTimer;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ms$onInit(CallbackInfo ci) {
        AttributeSwap self = (AttributeSwap) (Object) this;

        SettingGroup sg = self.settings.createGroup("背包");

        customItems = sg.add(new ItemListSetting.Builder()
            .name("自定义物品")
            .description("攻击时优先切到列表里的物品，按列表顺序，快捷栏里有的优先，开着背包切换还会翻主背包")
            .build()
        );

        backpackSwap = sg.add(new BoolSetting.Builder()
            .name("背包切换")
            .description("快捷栏里找不到就翻主背包（9-35 格），找到的换到手上用，开着切换回就按延迟放回原格")
            .defaultValue(false)
            .build()
        );
    }

    /**
     * 换槽位这一步是两种模式（Simple 固定槽 / Smart 智能评分）的公共出口，所以拦在这里：
     * 自定义物品优先，其次智能模式挑中的背包格，都没有就让官方那套照旧跑。
     */
    @Inject(method = "doSwap", at = @At("HEAD"), cancellable = true)
    private void ms$onDoSwap(int slotIndex, CallbackInfo ci) {
        if (ms$trySwap(slotIndex)) ci.cancel();
    }

    @Unique
    private boolean ms$trySwap(int requestedSlot) {
        if (mc.player == null || mc.level == null) return false;

        // 官方自己的换回还没走完：什么都不做，让它原本的早退处理
        if (awaitingBack) return false;

        // 背包物品还在手上等换回：这一下不换（跟官方 awaitingBack 的挡法一致）
        if (backpackSlot != -1) return true;

        // 自定义物品优先
        List<Item> items = customItems.get();
        if (!items.isEmpty()) {
            for (Item item : items) {
                int slot = ms$findSlot(item);
                if (slot == -1) continue;
                ms$swapTo(slot);
                return true;
            }

            // 列表里的物品一件都没有：交给官方原本的逻辑
            return false;
        }

        // 智能模式把搜索范围扩到背包后，挑中的可能是主背包里的格子
        if (SlotUtils.isMain(requestedSlot)) {
            ms$swapTo(requestedSlot);
            return true;
        }

        return false;
    }

    /** 这件物品在哪一格：快捷栏 0-8 优先，开着背包切换再翻主背包 9-35，找不到返回 -1 */
    @Unique
    private int ms$findSlot(Item item) {
        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.HOTBAR_END; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }

        if (!backpackSwap.get()) return -1;

        for (int i = SlotUtils.MAIN_START; i <= SlotUtils.MAIN_END; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }

        return -1;
    }

    /**
     * 换到这一格：快捷栏格就切选中槽（官方那套，换回交给模块自己的倒计时），
     * 主背包格就跟当前手持格互换，自己记下来等换回。
     */
    @Unique
    private void ms$swapTo(int slot) {
        if (SlotUtils.isHotbar(slot)) {
            if (slot == mc.player.getInventory().getSelectedSlot()) return;
            if (!InvUtils.swap(slot, swapBack.get())) return;

            if (swapBack.get()) {
                awaitingBack = true;
                backTimer = swapBackDelay.get();
            }
            return;
        }

        // 当前菜单里认不出这个槽位（例如讲台这类没有玩家背包格的菜单）就不换
        if (SlotUtils.indexToId(slot) < 0) return;

        int held = mc.player.getInventory().getSelectedSlot();
        InvUtils.quickSwap().fromId(held).to(slot);

        if (!swapBack.get()) return;

        backpackSlot = slot;
        backpackButton = held;
        backpackItem = mc.player.getInventory().getItem(held).getItem();
        backpackTimer = swapBackDelay.get();
    }

    /** 换回倒计时：跟模块自己的「切换回延迟」一个算法（倒计时走完那一 tick 换回） */
    @Unique
    @EventHandler
    private void ms$onTick(TickEvent.Post event) {
        if (backpackSlot == -1) return;
        if (backpackTimer-- > 0) return;
        ms$restoreBackpack();
    }

    /** 模块关掉时把还在手上的背包物品放回原格 */
    @Inject(method = "onDeactivate", at = @At("HEAD"))
    private void ms$onDeactivate(CallbackInfo ci) {
        backpackTimer = 0;
        ms$restoreBackpack();
    }

    /** 把那件背包物品换回原来的格子（那一格里已经不是它了就不动） */
    @Unique
    private void ms$restoreBackpack() {
        int slot = backpackSlot;
        int button = backpackButton;
        Item item = backpackItem;
        backpackSlot = -1;

        if (slot == -1 || mc.player == null) return;
        if (!mc.player.getInventory().getItem(button).is(item)) return;

        InvUtils.quickSwap().fromId(button).to(slot);
    }

    /**
     * 智能搜索也翻背包：官方「智能切换」「长矛」两个查找循环都是 {@code for (int i = 0; i < 9; i++)}，
     * 开着背包切换就把上限改成 36（快捷栏 0-8 + 主背包 9-35），挑中的格子由 {@code doSwap} 那边换到手上。
     *
     * <p>{@code require = 0}：官方哪天改了循环写法，这条安静地不生效（智能搜索退回只翻快捷栏），不影响别的
     */
    @ModifyConstant(method = {"getSmartSlot", "getSmartSpearSlot"}, constant = @Constant(intValue = 9), require = 0)
    private int ms$extendSearch(int bound) {
        return backpackSwap.get() ? SlotUtils.MAIN_END + 1 : bound;
    }
}
