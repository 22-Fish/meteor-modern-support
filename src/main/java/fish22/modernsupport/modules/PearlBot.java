/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 从 meteor-miku 的「珍珠点大管家」移植并适配 26.1（Mojmap）。
 */

package fish22.modernsupport.modules;

import fish22.modernsupport.settings.PearlPointSetting;
import fish22.modernsupport.utils.LegalPlace;
import fish22.modernsupport.utils.LegalRotation;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.NopPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static meteordevelopment.meteorclient.MeteorClient.mc;


public class PearlBot extends Module {

    /** 聊天格式模板里的占位符：{name} = 发送者，{text} = 内容 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(name|text)}");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> keyword = sgGeneral.add(new StringSetting.Builder()
        .name("关键字")
        .description("私聊内容里包含这个关键字就执行")
        .defaultValue("拉我")
        .build()
    );

    private final Setting<Boolean> privateChat = sgGeneral.add(new BoolSetting.Builder()
        .name("接收私聊")
        .description("解析私聊消息里的关键字")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> privateFormat = sgGeneral.add(new StringSetting.Builder()
        .name("私聊格式")
        .description("{name} 代表发送者、{text} 代表内容，其它字符按原样匹配。默认是 3C 服的私聊格式")
        .defaultValue("📨 {name} ➡ {text}")
        .visible(privateChat::get)
        .build()
    );

    private final Setting<Boolean> publicChat = sgGeneral.add(new BoolSetting.Builder()
        .name("接收公屏")
        .description("解析公屏消息里的关键字")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> publicFormat = sgGeneral.add(new StringSetting.Builder()
        .name("公屏格式")
        .description("{name} 代表发送者、{text} 代表内容。默认是原版的 <名字> 内容")
        .defaultValue("«{name}» {text}")
        .visible(publicChat::get)
        .build()
    );

    private final Setting<Boolean> stripNamePrefix = sgGeneral.add(new BoolSetting.Builder()
        .name("忽略名字前缀")
        .description("忽略玩家名前面 [] 及里面的内容")
        .defaultValue(true)
        .build()
    );

    private final PearlPointSetting points = sgGeneral.add(
        new PearlPointSetting("珍珠点", "左边用户名，右边是珍珠点方块坐标（x y z）。", () -> true)
    );

    private final Setting<Boolean> setup = sgGeneral.add(new BoolSetting.Builder()
        .name("添加珍珠点模式")
        .description("开启后右键目标方块把它记录成珍珠点")
        .defaultValue(false)
        .onChanged(value -> {
            if (value && isActive()) info("右键珍珠点的方块来添加");
        })
        .build()
    );

    private final Setting<Boolean> onlyInteractive = sgGeneral.add(new BoolSetting.Builder()
        .name("只记录可交互方块")
        .description("添加时只接受可交互方块")
        .defaultValue(true)
        .visible(setup::get)
        .build()
    );

    private final Setting<Boolean> searchSigns = sgGeneral.add(new BoolSetting.Builder()
        .name("智能识别告示牌")
        .description("若未找到珍珠记录，则尝试从附近告示牌获取珍珠主人的名字（取告示牌下方一格）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("搜索告示牌半径")
        .description("搜索告示牌的范围半径。")
        .defaultValue(64)
        .min(8)
        .sliderRange(8, 128)
        .visible(searchSigns::get)
        .build()
    );

    private final Setting<Integer> timeout = sgGeneral.add(new IntSetting.Builder()
        .name("赶路超时时间")
        .description("允许赶路 / 返回的最大时间（tick，20 tick = 1 秒）。")
        .defaultValue(600)
        .min(20)
        .sliderRange(20, 20000)
        .build()
    );

    private final Setting<Boolean> distanceCheck = sgGeneral.add(new BoolSetting.Builder()
        .name("太远的不拉")
        .description("当与珍珠点距离过远时自动拒绝请求。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxDistance = sgGeneral.add(new IntSetting.Builder()
        .name("最大距离")
        .description("允许接受请求的最大距离（格）。")
        .defaultValue(120)
        .min(10)
        .sliderRange(10, 300)
        .visible(distanceCheck::get)
        .build()
    );

    private final Setting<Boolean> legalRotate = sgGeneral.add(new BoolSetting.Builder()
        .name("交互时合法转头")
        .description("调用合法转头 API 转向目标方块后再交互：先转头发移动包、后发交互包，能过 Grim。关掉就用当前朝向直接点（非 Grim 服 / 调试用）")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> returnToStart = sgGeneral.add(new BoolSetting.Builder()
        .name("使用后返回出发点")
        .description("交互完成后自己走回出发的位置。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoCloseScreen = sgGeneral.add(new BoolSetting.Builder()
        .name("自动关掉交互界面")
        .description("点到箱子这类会开界面的方块时，自动把界面关掉")
        .defaultValue(true)
        .build()
    );

    // ====== 运行时状态 ======

    /** 格式模板 → 编译好的正则（模板不变就不重复编译） */
    private final Map<String, Pattern> templateCache = new HashMap<>();

    /** 正在前往 / 交互的方块 */
    private BlockPos taskPos;
    private String taskName = "";
    private int taskTicks;
    /** 这一 tick 已经排了「转头 → 交互」，等移动包发完后的回调 */
    private boolean interacting;
    /** 等回调的兜底计时（正常情况下一 tick 内就执行完了） */
    private int interactWaitTicks;

    private BlockPos returnPos;
    private boolean returning;
    /** 交互后自动关界面的倒计时 */
    private int screenCloseTicks;

    public PearlBot() {
        super(Categories.Misc, "珍珠点大管家", "自动管理上百人的珍珠点都可以，随时私聊拉");
    }

    @Override
    public void onActivate() {
        super.onActivate();
        resetTask();

        if (setup.get()) info("请精确右键珍珠点的方块来添加");
        if (PathManagers.get() instanceof NopPathManager) {
            warning("没检测到 Baritone，自动赶路不可用");
        }
        if (privateChat.get() && compileTemplate(privateFormat.get()) == null) {
            warning("私聊格式必须同时包含 {name} 和 {text}");
        }
        if (publicChat.get() && compileTemplate(publicFormat.get()) == null) {
            warning("公屏格式必须同时包含 {name} 和 {text}");
        }
    }

    @Override
    public void onDeactivate() {
        PathManagers.get().stop();
        resetTask();
        super.onDeactivate();
    }

    // ====== 建档 ======

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (!setup.get() || mc.level == null) return;

        BlockPos pos = event.result.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;

        if (onlyInteractive.get() && !LegalPlace.isInteractive(state.getBlock())) {
            warning("这个方块不是可交互方块");
            return;
        }

        String id = "玩家" + points.get().size();
        points.get().put(id, new PearlPointSetting.Point(pos.getX(), pos.getY(), pos.getZ()));
        info("已记录珍珠点 " + id + " -> " + pos.toShortString() + " ，可以到设置里把名字改成对方ID");
        event.cancel();
    }

    // ====== 接单 ======

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive() || mc.player == null || mc.level == null || event.getMessage() == null) return;

        String chatText = event.getMessage().getString();
        // 模块自己打的本地提示也会走聊天事件，别解析自己
        if (isLocalMessage(chatText)) return;

        ParsedMessage parsed = parseMessage(chatText);
        if (parsed == null) return;
        if (!parsed.content().contains(keyword.get())) return;

        // 显示名可能带前缀（[头颅]Steve），查表和回消息都用去掉前缀的真名
        String rawName = parsed.username();
        String username = cleanName(rawName);
        info("收到来自 " + rawName + " 的请求。");

        String reason = pullFor(username);
        if (reason != null) whisper(username, reason);
    }

    /**
     * 给指定玩家拉珍珠点。聊天私聊/公屏触发、Meteor 命令（{@code .pearl}）都走这里。
     *
     * @param username 玩家名，带 {@code []} 前缀也行（内部按「忽略名字前缀」设置清洗）
     * @return {@code null} = 已经出发；否则是失败原因，调用方拿去提示（私聊回去或打在本地）
     */
    public String pullFor(String username) {
        if (!isActive()) return "珍珠点大管家没开启";
        if (mc.player == null || mc.level == null) return "玩家未在线";

        String name = cleanName(username);
        if (name.isEmpty()) return "没写玩家名";
        if (busy()) return "我正忙着呢，稍等一下";

        BlockPos pearl = findPearl(name);
        if (pearl == null && searchSigns.get()) {
            pearl = findPearlBySign(name);
            if (pearl != null) {
                points.get().put(name, new PearlPointSetting.Point(pearl.getX(), pearl.getY(), pearl.getZ()));
                info("已通过告示牌记录珍珠点: " + pearl.toShortString());
            }
        }

        if (pearl == null) return "没找到你的珍珠啊 " + name;

        double distance = Math.sqrt(distanceSqr(mc.player.blockPosition(), pearl));
        if (distanceCheck.get() && distance > maxDistance.get()) {
            return "我现在离你的珍珠太远，距离 " + (int) distance;
        }

        BlockState state = mc.level.getBlockState(pearl);
        if (state.isAir() || state.canBeReplaced()) return "你的珍珠点方块不见了";

        startTask(pearl, name);
        return null;
    }

    // ====== 驱动 ======

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            PathManagers.get().stop();
            resetTask();
            return;
        }

        // 点到会开界面的方块时（箱子这些）自动关掉，别卡着任务
        if (screenCloseTicks > 0) {
            screenCloseTicks--;
            if (autoCloseScreen.get() && mc.screen != null) mc.setScreen(null);
        }

        // 任务计时 / 超时：不管开没开界面都要走，免得卡在「正忙」出不来
        if (taskPos != null || returning) {
            taskTicks++;
            if (taskTicks > timeout.get()) {
                PathManagers.get().stop();
                warning("任务超时（" + timeout.get() + " tick），已取消。");
                resetTask();
                return;
            }
        }

        if (returning) {
            tickReturn();
            return;
        }

        if (taskPos == null) return;

        // 这一 tick 已经排好「转头 → 交互」，等移动包发完后的回调
        if (interacting) {
            if (++interactWaitTicks > 20) {
                interacting = false;
                interactWaitTicks = 0;
                warning("转头回调没有执行，重新尝试。");
            }
            return;
        }

        // 够得着就交互（内部会安排转头 + 回调发包），够不着就继续赶路
        if (tryInteract(taskPos)) return;

        if (!PathManagers.get().isPathing()) {
            PathManagers.get().moveTo(taskPos, false);
        }
    }

    /**
     * 尝试交互目标方块。
     *
     * @return true = 这一 tick 已经安排好了（或已经发出）交互，调用方不要再动
     */
    private boolean tryInteract(BlockPos pos) {
        LegalPlace.InteractAim aim = LegalPlace.computeInteract(pos);
        if (aim == null) return false;

        if (legalRotate.get() && aim.rotated()) {
            // 先转头：交互包排在这一份旋转随移动包发出之后，
            // 服务器看到的顺序就是「移动包（带目标朝向）→ 交互包」，和真人一致
            if (!LegalRotation.rotate(aim.yaw(), aim.pitch(), () -> sendInteract(pos, aim))) return false;
            interacting = true;
            return true;
        }

        sendInteract(pos, aim);
        return true;
    }

    /** 真的发交互包（合法转头模式下是在移动包发出之后的回调里执行） */
    private void sendInteract(BlockPos pos, LegalPlace.InteractAim aim) {
        if (mc.player == null || mc.level == null) return;

        PathManagers.get().stop();
        interacting = false;
        interactWaitTicks = 0;

        BlockUtils.interact(
            new BlockHitResult(aim.hitPos(), aim.face(), pos, false),
            InteractionHand.MAIN_HAND,
            true
        );

        info("已触发 " + taskName + " 的珍珠点，坐标 " + pos.toShortString());
        screenCloseTicks = 10;

        taskPos = null;
        taskTicks = 0;

        if (returnToStart.get() && returnPos != null) {
            returning = true;
            taskTicks = 0;
            PathManagers.get().moveTo(returnPos, false);
        } else {
            taskName = "";
        }
    }

    private void tickReturn() {
        if (distanceSqr(mc.player.blockPosition(), returnPos) <= 4.0) {
            PathManagers.get().stop();
            returning = false;
            returnPos = null;
            info("已返回出发点。");
            return;
        }

        if (!PathManagers.get().isPathing()) {
            PathManagers.get().moveTo(returnPos, false);
        }
    }

    private void startTask(BlockPos pos, String name) {
        returnPos = mc.player != null ? mc.player.blockPosition() : null;
        returning = false;
        taskPos = pos;
        taskName = name;
        taskTicks = 0;
        interacting = false;
        interactWaitTicks = 0;

        info("开始处理 " + name + " 的珍珠点任务");
        whisper(name, "正在路上");
    }

    private void resetTask() {
        taskPos = null;
        taskName = "";
        taskTicks = 0;
        interacting = false;
        interactWaitTicks = 0;
        returning = false;
        returnPos = null;
        screenCloseTicks = 0;
    }

    private boolean busy() {
        return taskPos != null || returning;
    }

    // ====== 查找 ======

    private BlockPos findPearl(String username) {
        PearlPointSetting.Point point = points.find(username);
        return point == null ? null : point.blockPos();
    }

    /** 扫附近的告示牌，找写着这个人名字的，取告示牌下方一格（和原版一样只看正面） */
    private BlockPos findPearlBySign(String username) {
        BlockPos origin = mc.player.blockPosition();
        int radius = searchRadius.get();
        String needle = username.toLowerCase(Locale.ROOT);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.isLoaded(pos)) continue;
                    if (!(mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) continue;

                    for (Component line : sign.getFrontText().getMessages(false)) {
                        if (line.getString().toLowerCase(Locale.ROOT).contains(needle)) {
                            info("找到包含名字的告示牌: " + pos.toShortString());
                            return pos.below();
                        }
                    }
                }
            }
        }

        return null;
    }

    // ====== 聊天 ======

    /**
     * 按设置的格式模板解析一条聊天消息。私聊和公屏各试一次，谁先匹配上算谁的。
     *
     * @return 解析结果；格式不对返回 {@code null}
     */
    private ParsedMessage parseMessage(String message) {
        if (privateChat.get()) {
            ParsedMessage parsed = match(privateFormat.get(), message);
            if (parsed != null) return parsed;
        }

        if (publicChat.get()) {
            ParsedMessage parsed = match(publicFormat.get(), message);
            if (parsed != null) return parsed;
        }

        return null;
    }

    /** 用某个模板去匹配一条消息 */
    private ParsedMessage match(String template, String message) {
        if (template == null || template.isEmpty()) return null;

        Pattern pattern = templateCache.computeIfAbsent(template, PearlBot::compileTemplate);
        if (pattern == null) return null;

        Matcher matcher = pattern.matcher(message);
        if (!matcher.matches()) return null;

        return new ParsedMessage(matcher.group("name").trim(), matcher.group("text").trim());
    }

    /**
     * 把格式模板编译成正则：{@code {name}} / {@code {text}} 变成命名捕获组，
     * 其它字符全部按字面量转义。模板里两个占位符必须都有，否则返回 {@code null}。
     *
     * <p>例：{@code 📨 {name} ➡ {text}} 能匹配 {@code 📨 Steve ➡ 拉我}。
     */
    private static Pattern compileTemplate(String template) {
        StringBuilder regex = new StringBuilder("^");
        Matcher matcher = PLACEHOLDER.matcher(template);
        boolean hasName = false;
        boolean hasText = false;
        int last = 0;

        while (matcher.find()) {
            regex.append(escapeLiteral(template.substring(last, matcher.start())));
            if (matcher.group(1).equals("name")) {
                regex.append("(?<name>.+?)");
                hasName = true;
            } else {
                regex.append("(?<text>.+?)");
                hasText = true;
            }
            last = matcher.end();
        }

        regex.append(escapeLiteral(template.substring(last))).append('$');
        if (!hasName || !hasText) return null;

        try {
            return Pattern.compile(regex.toString());
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * 模板里的普通文字转成正则字面量。
     *
     * <p>空格单独处理成 {@code \s+}：服务器回显出来的空白数量经常和客户端不一样
     * （比如多打一个空格），按原样匹配很容易漏消息。
     */
    private static String escapeLiteral(String text) {
        StringBuilder escaped = new StringBuilder();
        boolean pendingSpace = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                pendingSpace = true;
                continue;
            }

            if (pendingSpace) {
                escaped.append("\\s+");
                pendingSpace = false;
            }

            if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }

        if (pendingSpace) escaped.append("\\s+");
        return escaped.toString();
    }

    /** 这条聊天栏消息是不是本模块自己打的本地提示（Meteor 加的本地消息也会走聊天事件） */
    private static boolean isLocalMessage(String message) {
        return message != null && (message.contains("[珍珠点大管家]") || message.contains("[Meteor]"));
    }

    /**
     * 把聊天里显示的名字还原成真正的玩家名：去掉开头的 {@code [xxx]}
     * （玩家头颅显示之类的模组会在名字前面挂一段 {@code [方块]}）。
     *
     * <p>例：{@code [头颅]Steve} → {@code Steve}。
     */
    private String cleanName(String username) {
        if (username == null) return "";

        String name = username.trim();
        if (!stripNamePrefix.get()) return name;

        while (name.startsWith("[")) {
            int close = name.indexOf(']');
            if (close < 0) break;
            name = name.substring(close + 1).trim();
        }

        return name;
    }

    private void whisper(String username, String message) {
        if (mc.player == null) return;
        // 只走私聊命令，内容里也不含任何坐标
        mc.player.connection.sendCommand("msg " + cleanName(username) + " " + message);
    }

    private static double distanceSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private record ParsedMessage(String username, String content) {
    }
}
