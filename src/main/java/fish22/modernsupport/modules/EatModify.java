package fish22.modernsupport.modules;

import fish22.modernsupport.utils.SilentSwitch;
import meteordevelopment.meteorclient.events.entity.player.DoItemUseEvent;
import meteordevelopment.meteorclient.events.entity.player.FinishUsingItemEvent;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IMultiPlayerGameMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 进食修改 — 杂项模块
 *
 * <ul>
 *   <li><b>进食进度</b>：吃东西 / 喝药水的时候，把当前选中的快捷栏格子整格从下往上填满，
 *       颜色从「初始颜色」慢慢过渡到「结束颜色」</li>
 *   <li><b>长按仅进食一个</b>：按住右键吃完一份就不再吃，得松开重新按右键</li>
 *   <li><b>工具自动进食</b>：手里拿着工具右键时，自动从快捷栏或背包里拿一份食物过来吃，
 *       吃完不管右键还按着没有都换回原样。快捷栏里有食物时可以开「静默切换」：
 *       本地选中槽位一动不动，只让服务端拿着食物那一格</li>
 *   <li><b>单次点击进食</b>：右键点一下就自动把这一份吃完，切槽位 / 被别的模块打断 / 吃完就停</li>
 * </ul>
 *
 * <p>做法上没碰原版的进食流程：进食本身还是原版那套（鼠标按下开始、服务器计时完成），
 * 模块只是在「原版准备再吃一口」时拦一下（{@link DoItemUseEvent}），
 * 在「原版要停掉进食」时拦一下（见 {@code MixinMultiPlayerGameModeEat}），
 * 以及吃完了换回工具（{@link FinishUsingItemEvent}）。
 */
public class EatModify extends Module {

    /** 当前实例（Mixin 要问「这一下该不该拦住」，不方便走 Modules.get 查） */
    private static EatModify INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgProgress = settings.createGroup("进度条");
    private final SettingGroup sgTool = settings.createGroup("工具自动进食");

    // ==================== 常规 ====================

    private final Setting<Boolean> holdOne = sgGeneral.add(new BoolSetting.Builder()
        .name("长按仅进食一个")
        .description("长按右键进食成功一次就停止进食，需要松开重新右键才继续")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clickOnce = sgGeneral.add(new BoolSetting.Builder()
        .name("单次点击进食")
        .description("进食只需要单次右键点击，自动继续把这一份吃完。再次右键取消进食")
        .defaultValue(true)
        .build()
    );

    // ==================== 进度条 ====================

    private final Setting<Boolean> progressBar = sgProgress.add(new BoolSetting.Builder()
        .name("进食进度")
        .description("进食食物或者饮用药水时，当前快捷栏那一格整格从下往上填满")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> startColor = sgProgress.add(new ColorSetting.Builder()
        .name("初始颜色")
        .description("刚开始吃（进度 0%）时的颜色")
        .defaultValue(new SettingColor(255, 0, 0, 120))
        .visible(progressBar::get)
        .build()
    );

    private final Setting<SettingColor> endColor = sgProgress.add(new ColorSetting.Builder()
        .name("结束颜色")
        .description("快吃完（进度 100%）时的颜色")
        .defaultValue(new SettingColor(0, 255, 0, 120))
        .visible(progressBar::get)
        .build()
    );

    /**
     * 结束这次自动进食（再点一次右键 / 玩家自己打断）
     *
     * <p>先把「不再拦住原版停用」的标记打上，再真发停用包把这一份断掉
     * （不然会被 {@link #keepUsing} 拦下来）；工具进食顺手把手上的工具换回去
     */
    private void cancelEating() {
        autoEating = false;
        autoSlot = -1;
        waitRelease = true;     // 这一下之后要松开右键才会再吃

        releasing = true;
        try {
            if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        } finally {
            releasing = false;
        }

        endToolEat();
    }

    // ==================== 工具自动进食 ====================

    private final Setting<Boolean> toolEat = sgTool.add(new BoolSetting.Builder()
        .name("工具自动进食")
        .description("手持工具右键时自动从背包或快捷栏切换一个食物过来，吃完自动换回去")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> tools = sgTool.add(new ItemListSetting.Builder()
        .name("可选工具")
        .description("手里拿着这些物品右键时触发自动进食")
        // 物品标签要等游戏起来才就绪，这里不能提前算默认值，等开模块时再填（见 fillToolDefaults）
        .onModuleActivated(setting -> fillToolDefaults())
        .visible(toolEat::get)
        .build()
    );

    private final Setting<List<Item>> foods = sgTool.add(new ItemListSetting.Builder()
        .name("可选食物")
        .description("自动进食要找的食物，列表里有金苹果时金苹果优先")
        .defaultValue(Items.GOLDEN_CARROT, Items.GOLDEN_APPLE)
        .visible(toolEat::get)
        .build()
    );

    private final Setting<Boolean> silentSwitch = sgTool.add(new BoolSetting.Builder()
        .name("静默切换")
        .description("快捷栏里拿食物时不切本地槽位，只让服务端拿着食物那一格（本地选中槽位和画面都不动）")
        .defaultValue(true)
        .visible(toolEat::get)
        .build()
    );

    // ==================== 运行状态 ====================

    /** 吃完一份后要松开右键才能再吃（长按仅进食一个） */
    private boolean waitRelease;

    /** 单次点击进食：这次右键按下的续吃会话 */
    private boolean autoEating;

    /** 续吃会话开始时选中的快捷栏槽位（换槽位就结束） */
    private int autoSlot = -1;

    /** 模块自己正在真停进食（这一刻不能拦原版的停用） */
    private boolean releasing;

    /** 工具自动进食：正在吃换过来的那一份 */
    private boolean toolEating;

    /** 食物原本所在的背包槽（主背包区），-1 = 走快捷栏没换物品 */
    private int srcSlot = -1;

    /** 换过去的那一格（快捷栏索引 0-8） */
    private int swapButton = -1;

    /** 快捷栏吃着的时候，我们切到了哪一格 */
    private int foodHotbarSlot = -1;

    /** 快捷栏吃着的时候，吃完要切回哪一格 */
    private int restoreSlot = -1;

    /** 工具自动进食是不是走的静默切换（走静默的话本地槽位从头到尾没动过） */
    private boolean silentEating;

    /** 换回对账用的槽位（主背包区） */
    private int verifySrc = -1;

    /** 换回对账用的快捷栏格（0-8） */
    private int verifyButton = -1;

    /** 换回对账剩余 tick */
    private int verifyTicks;

    public EatModify() {
        super(Categories.Misc, "进食修改", "进食进度条 / 长按只吃一个 / 工具自动进食 / 单次点击进食");
        INSTANCE = this;
    }

    /**
     * 「单次点击进食」在跑：原版要停掉进食时先拦住（Mixin 调）。
     * 拦住这一次停用（不发停用包），服务器那边还在计时，这一份就能正常吃完。
     */
    public static boolean keepUsing(Player player) {
        EatModify module = INSTANCE;
        if (module == null || player == null || player != MeteorClient.mc.player || !module.isActive()) return false;
        if (module.releasing) return false;

        // 单次点击进食 / 工具自动进食：点一下就把这一份吃完，不按着右键也不撒手
        return module.autoEating || (module.toolEating && module.clickOnce.get());
    }

    @Override
    public void onActivate() {
        resetState();
        SilentSwitch.start();
    }

    @Override
    public void onDeactivate() {
        // 关模块前把手上的工具换回去，别把工具留在背包里
        endToolEat();
        SilentSwitch.stop();
        autoEating = false;
        autoSlot = -1;
        waitRelease = false;
    }

    // ==================== 进食流程 ====================

    /**
     * 原版每次要「用一下手上的东西」都会先跑这里（在发使用包、点方块之前）
     *
     * <p>不取消事件 = 原版接着正常用；这里只在两种情况下插手：
     * 换一份食物到手上（换完原版自己会拿它进食），或者按住不放时拦住再吃一口
     */
    @EventHandler
    private void onDoItemUse(DoItemUseEvent event) {
        if (mc.player == null) return;

        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();

        // 吃完一份了，还按着右键就不再吃（松开右键这里就放行）
        if (waitRelease) {
            if (isConsumable(mainHand) || isConsumable(offHand)) event.cancel();
            return;
        }

        // 单次点击进食：记下这次右键，之后松开也接着把这一份吃完
        // 主手副手都算：副手拿食物时原版也是右键就开吃（主手优先，原版自己决定用哪只手）
        if (clickOnce.get() && !autoEating && (isConsumable(mainHand) || isConsumable(offHand))) {
            autoEating = true;
            autoSlot = mc.player.getInventory().getSelectedSlot();
            return;
        }

        // 工具自动进食：手里是工具，就拿一份食物过来（换好之后原版会拿它进食）
        if (toolEat.get() && !toolEating && isAllowedTool(mainHand.getItem())) {
            startToolEat();
        }
    }

    /** 这一份吃完了（服务器发「用完物品」事件，客户端本地也走一遍收尾） */
    @EventHandler
    private void onFinishUsingItem(FinishUsingItemEvent event) {
        if (mc.player == null || !isConsumable(event.itemStack)) return;

        // 单次点击进食：吃完了，会话结束
        autoEating = false;
        autoSlot = -1;

        // 工具自动进食：吃完顺手把手上的工具换回来
        endToolEat();

        // 长按仅进食一个：还按着右键，就锁到松开为止
        if (holdOne.get() && mc.options.keyUse.isDown()) waitRelease = true;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            resetState();
            return;
        }

        // 工具自动进食（静默切换）：每 tick 续租一次，服务端一直拿着食物那一格
        if (toolEating && silentEating && foodHotbarSlot >= 0) SilentSwitch.keep(foodHotbarSlot);

        // 右键松开了，「只吃一个」的锁解除
        if (waitRelease && !mc.options.keyUse.isDown()) waitRelease = false;

        // 单次点击进食 / 工具自动进食期间又点了一次右键 → 结束进食（这一下吃掉，不再接着吃）
        if (autoEating && mc.options.keyUse.consumeClick()) {
            cancelEating();
            return;
        }

        // 单次点击进食：切了槽位 / 被别的模块提前停了 → 结束（正常吃完走 onFinishUsingItem）
        if (autoEating && (mc.player.getInventory().getSelectedSlot() != autoSlot || !mc.player.isUsingItem())) {
            autoEating = false;
            autoSlot = -1;
        }

        // 保险：早就过了该吃完的时间还挂在「正在使用」上（服务器没回包 / 这一口被切槽打断之类）→ 真停掉，别卡住
        // 长按右键也照停：按着不放正好是这个状态的高发场景，要求松手才肯停的话，一按就是一直卡着
        if ((autoEating || toolEating) && mc.player.isUsingItem() && mc.player.getUseItemRemainingTicks() < -40) {
            autoEating = false;
            autoSlot = -1;
            if (holdOne.get() && mc.options.keyUse.isDown()) waitRelease = true;

            // 放行这一下：不然会被 keepUsing 拦掉（autoEating 刚清掉、toolEating + 单次点击还在），
            // 客户端就一直挂在「正在使用」，下面换回工具那一步永远走不到
            releasing = true;
            try {
                mc.gameMode.releaseUsingItem(mc.player);
            } finally {
                releasing = false;
            }
        }

        // 工具自动进食：进食停了（吃完 / 被打断 / 松开右键）→ 换回工具
        if (toolEating && !mc.player.isUsingItem()) endToolEat();

        tickVerify();
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!progressBar.get() || mc.player == null || !mc.player.isUsingItem()) return;

        ItemStack use = mc.player.getUseItem();
        if (!isConsumable(use)) return;

        int total = use.getUseDuration(mc.player);
        if (total <= 0) return;

        // 原版剩余时间从 total 往下减，所以进度 = (总量 - 剩余) / 总量
        float progress = (float) (total - mc.player.getUseItemRemainingTicks()) / total;
        progress = Math.max(0f, Math.min(1f, progress));

        // 原版快捷栏格子：左边 = 屏幕中心 - 91 + 槽位*20，上边 = 屏幕高 - 22
        // 物品是画在格子左上角 +3 处的 16x16，也就是物品中心在格子 +11 处，
        // 所以「框」是绕着物品中心的 22x22（左右上下各比物品多 3 像素），这里就按这个来盖
        // 进度条画在「服务端认的那一格」：静默切换进食时食物在那一格，本地选中槽位没动
        int slot = Math.max(0, SilentSwitch.slot());
        int left = event.screenWidth / 2 - 91 + slot * 20;
        int bottom = event.screenHeight - 22 + 22;

        int filled = Math.round(22 * progress);
        if (filled <= 0) return;

        event.graphics.fill(left, bottom - filled, left + 22, bottom,
            packed(startColor.get(), endColor.get(), progress));
    }

    // ==================== 工具自动进食 ====================

    /** 找一份食物：金苹果优先，快捷栏优先（切过去），其次主背包（换到手持格） */
    private void startToolEat() {
        if (mc.player.containerMenu.containerId != 0) return;

        List<Item> order = priorityFoods();

        // 1. 快捷栏里就有：切到那一格，吃完切回来
        for (Item food : order) {
            FindItemResult result = InvUtils.findInHotbar(food);
            if (!result.found() || result.isMainHand() || result.isOffhand()) continue;

            foodHotbarSlot = result.slot();

            // 静默切换：本地选中槽位一个字都不动，只让服务端拿着食物那一格
            if (silentSwitch.get()) {
                // 记一份回来用的槽位：收尾要明确把服务端钉回这格（本地槽位真被带跑了也能切回来）
                restoreSlot = mc.player.getInventory().getSelectedSlot();
                silentEating = true;
                SilentSwitch.keep(foodHotbarSlot);
            } else {
                restoreSlot = mc.player.getInventory().getSelectedSlot();
                selectSlot(foodHotbarSlot);
            }
            beginToolEating();
            return;
        }

        // 2. 主背包里找：换到当前手持格，吃完换回来
        for (Item food : order) {
            FindItemResult result = InvUtils.find(stack -> stack.is(food), SlotUtils.MAIN_START, SlotUtils.MAIN_END);
            if (!result.found()) continue;

            int slotId = SlotUtils.indexToId(result.slot());
            if (slotId < 0) return;

            swapButton = mc.player.getInventory().getSelectedSlot();
            clickSwap(slotId, swapButton);
            srcSlot = result.slot();
            beginToolEating();
            return;
        }
    }

    /**
     * 工具进食会话开始（食物已经换到手上）
     *
     * <p>「单次点击进食」开着的时候顺手接上同一套：右键点一下就行，不用一直按着
     * （松开右键那一下由 {@link #keepUsing} 拦住，这一份照常吃完）
     */
    private void beginToolEating() {
        toolEating = true;

        if (clickOnce.get()) {
            autoEating = true;
            autoSlot = mc.player.getInventory().getSelectedSlot();
        }
    }

    /** 结束工具自动进食：把手上的工具换回去（吃完了 / 被打断 / 关模块都走这里） */
    private void endToolEat() {
        if (!toolEating) return;
        toolEating = false;

        // 静默切换吃的：放开接管，服务端切回原来那格（工具本来就还在手上）
        if (silentEating) {
            silentEating = false;
            silentToolBack();
        }

        // 主背包换过来的：换回原槽位（换回去之后原手持格又是工具）
        if (srcSlot >= 0 && swapButton >= 0 && mc.player != null) {
            verifySrc = srcSlot;
            verifyButton = swapButton;
            verifyTicks = 4;

            // 开着容器时不能点背包格，留给对账等容器关了再换回去
            if (mc.player.containerMenu.containerId == 0) {
                int slotId = SlotUtils.indexToId(srcSlot);
                if (slotId >= 0) clickSwap(slotId, swapButton);
            }
        }

        // 快捷栏吃着的时候：切回原来那一格（玩家自己换过格就不动他）
        if (restoreSlot >= 0 && mc.player != null && mc.player.getInventory().getSelectedSlot() == foodHotbarSlot) {
            selectSlot(restoreSlot);
        }

        // 还按着右键就把「只吃一个」的锁打上：这一口要是没走到「用完物品」事件（被切槽打断之类），
        // 长按就会立刻重吃下一份、食物格一直被按住、工具一直切不回来
        if (holdOne.get() && mc.options.keyUse.isDown()) waitRelease = true;

        srcSlot = -1;
        swapButton = -1;
        foodHotbarSlot = -1;
        restoreSlot = -1;
    }

    /**
     * 静默接管收尾：保证服务端手持物切回原来那格
     * <p>
     * 不能只调 {@link SilentSwitch#release()}：静默期间只要有别人发过换手包（插件把那一下同步回来了、
     * 玩家自己切了格），记账就被作废成「没接管」，release() 那一份就不发包了 —— 服务端会一直停在
     * 食物那格，本地槽位一动不动，看起来就是「切不回去」
     * <p>
     * 所以先把原来那格明确交给服务端（记账没了/拿着别的都会真发这一包），再放开接管
     */
    private void silentToolBack() {
        int back = restoreSlot;
        if (back < 0 && mc.player != null) back = mc.player.getInventory().getSelectedSlot();

        if (back >= 0 && back <= 8) SilentSwitch.keep(back);
        SilentSwitch.release();
    }

    /** 食物优先级：列表里有金苹果就先金苹果，其余按列表顺序 */
    private List<Item> priorityFoods() {
        List<Item> order = new ArrayList<>();
        if (foods.get().contains(Items.GOLDEN_APPLE)) order.add(Items.GOLDEN_APPLE);

        for (Item item : foods.get()) {
            if (item == null || item == Items.AIR || item == Items.GOLDEN_APPLE) continue;
            order.add(item);
        }
        return order;
    }

    /**
     * 换回对账：交换用的那一格里还是食物 → 说明换回没生效，用当前 stateId 补一次 SWAP。
     * 换回成功后手持格又是工具（不是食物了），直接收工
     */
    private void tickVerify() {
        if (verifySrc < 0 || mc.player == null || mc.level == null) return;

        // 手持格里已经不是食物（换回来了 / 吃光了）→ 收工
        ItemStack hand = mc.player.getInventory().getItem(verifyButton);
        if (hand.isEmpty() || !foods.get().contains(hand.getItem())) {
            verifySrc = -1;
            return;
        }

        // 开着容器（点的是容器菜单）先等着，别把点击发到别的菜单里
        if (mc.player.containerMenu.containerId != 0) {
            return;
        }

        if (--verifyTicks <= 0) {
            verifySrc = -1;
            return;
        }

        int slotId = SlotUtils.indexToId(verifySrc);
        if (slotId < 0) {
            verifySrc = -1;
            return;
        }
        clickSwap(slotId, verifyButton);
    }

    // ==================== 小工具 ====================

    /** 一次原版 SWAP 点击：背包槽 ↔ 快捷栏格互换（本地同步 + 带预测信息的包，服务端也照做） */
    private void clickSwap(int slotId, int button) {
        if (slotId < 0) return;
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotId, button,
            ContainerInput.SWAP, mc.player);
    }

    /** 切换选中的快捷栏格，并把这次换手告诉服务器 */
    private void selectSlot(int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        ((IMultiPlayerGameMode) mc.gameMode).meteor$syncSelected();
    }

    private void resetState() {
        // 静默切换吃着的时候被打断（换世界、掉线之类）：先把服务端放回本地槽位
        if (silentEating) {
            silentEating = false;
            silentToolBack();
        }
        waitRelease = false;
        autoEating = false;
        autoSlot = -1;
        releasing = false;
        toolEating = false;
        srcSlot = -1;
        swapButton = -1;
        foodHotbarSlot = -1;
        restoreSlot = -1;
        verifySrc = -1;
        verifyButton = -1;
        verifyTicks = 0;
    }

    /** 能吃 / 能喝的物品（食物、药水） */
    private static boolean isConsumable(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (stack.has(DataComponents.FOOD) || stack.getItem() instanceof PotionItem);
    }

    /** 工具判断：带 TOOL 组件，或剑 / 镐 / 斧 / 锹 / 锄 */
    private static boolean isTool(ItemStack stack) {
        return !stack.isEmpty() && (stack.has(DataComponents.TOOL)
            || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS)
            || stack.is(ItemTags.HOES) || stack.is(ItemTags.SWORDS));
    }

    /**
     * 这个物品算不算「可选工具」：列表空 = 全选（默认）
     *
     * <p>列表默认是空的（模块构造那会儿物品标签还没就绪，算不了工具），
     * 第一次开模块会填成全部工具，见 {@link #fillToolDefaults()}
     */
    private boolean isAllowedTool(Item item) {
        if (item == null || item == Items.AIR) return false;

        List<Item> list = tools.get();
        if (list.isEmpty()) return isTool(new ItemStack(item));
        return list.contains(item);
    }

    /** 「可选工具」默认全选：模块构造时算不了，等开模块时把注册表里所有工具填进去 */
    private void fillToolDefaults() {
        List<Item> list = tools.get();
        if (!list.isEmpty()) return;

        try {
            List<Item> all = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                if (isTool(new ItemStack(item))) all.add(item);
            }
            list.addAll(all);
        } catch (Exception ignored) {
            // 物品 / 标签这时还没就绪：这次不填，下次开模块再说
        }
    }

    /** 初始色 → 结束色 按进度线性过渡，转成原版 GUI 用的 ARGB */
    private static int packed(Color from, Color to, float t) {
        int r = (int) (from.r + (to.r - from.r) * t);
        int g = (int) (from.g + (to.g - from.g) * t);
        int b = (int) (from.b + (to.b - from.b) * t);
        int a = (int) (from.a + (to.a - from.a) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
