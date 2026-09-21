/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.modules;

import fish22.modernsupport.settings.WhiteListSetting;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动复读 — 白名单玩家在公屏发什么，就照着再发一条
 *
 * <p>按「公屏聊天格式」认聊天行（{name} 玩家名、{text} 聊天正文，写法参考 openAI / 珍珠点大管家），
 * 名字在「复读白名单」里的玩家一发公屏就复读一条原文。
 * 自己发的消息（含复读出去那条的服务器回显）不复读，不会自己叼自己。
 */
public class AutoRepeat extends Module {

    private final SettingGroup sgChat = settings.createGroup("聊天识别");
    private final SettingGroup sgEcho = settings.createGroup("复读");
    private final SettingGroup sgWhite = settings.createGroup("白名单");

    // ==================== 聊天识别 ====================

    public final Setting<String> publicChatFormat = sgChat.add(new StringSetting.Builder()
        .name("公屏聊天格式")
        .description("{name}为玩家名,{text}为聊天正文")
        .defaultValue("<{name}> {text}")
        .wide()
        .build()
    );

    public final Setting<Boolean> ignoreFormatting = sgChat.add(new BoolSetting.Builder()
        .name("忽略字体和颜色字符")
        .description("匹配前先把 § 颜色/字体代码去掉，方便填入服务器魔改过的消息格式")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> ignoreNamePrefix = sgChat.add(new BoolSetting.Builder()
        .name("忽略名字前的[]")
        .description("玩家名前面的方括号内容会被忽略（称号前缀、头像模组塞的标记等）")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> debugChat = sgChat.add(new BoolSetting.Builder()
        .name("调试：打印未识别消息")
        .description("打印认不出的消息原文，方便对着服务器实际显示改格式")
        .defaultValue(false)
        .build()
    );

    // ==================== 复读 ====================

    public final Setting<Boolean> convertColor = sgEcho.add(new BoolSetting.Builder()
        .name("颜色转换")
        .description("对方消息以>开头时复读成^开头，以^开头时复读成>开头")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> repeatDelay = sgEcho.add(new IntSetting.Builder()
        .name("复读延迟")
        .description("收到消息后隔这么多 tick 再复读，1 tick = 50 毫秒")
        .defaultValue(10)
        .min(0)
        .max(200)
        .sliderRange(0, 100)
        .build()
    );

    public final Setting<Boolean> randomDelay = sgEcho.add(new BoolSetting.Builder()
        .name("延迟抖动")
        .description("开启后每次复读的延迟在设置值上下随机浮动 1-10 tick")
        .defaultValue(true)
        .build()
    );

    // ==================== 白名单 ====================

    public final WhiteListSetting whiteList = sgWhite.add(new WhiteListSetting(
        "复读白名单",
        "只有名单里的玩家会被复读，选择和openAI的白名单一样。用量限制这一列这里用不到，随便填。",
        null
    ));

    /** 格式模板 → 正则缓存 */
    private final Map<String, Pattern> patternCache = new HashMap<>();

    /** 攒下来的调试提示：聊天事件里直接发聊天会把那条消息顶掉，等 tick 末再发 */
    private final Deque<String> pendingDebug = new ArrayDeque<>();

    /** 等着到点复读的消息 */
    private final Deque<PendingEcho> pendingEchoes = new ArrayDeque<>();

    private static final long TICK_MS = 50L;
    private static final Random RANDOM = new Random();

    private record ParsedChat(String name, String text) {}
    private record PendingEcho(String text, long dueAt) {}

    public AutoRepeat() {
        super(Categories.Misc, "自动复读", "白名单玩家在公屏发什么,就照着复读一条");
    }

    @Override
    public void onActivate() {
        synchronized (pendingDebug) {
            pendingDebug.clear();
        }
        synchronized (pendingEchoes) {
            pendingEchoes.clear();
        }
        if (whiteList.get().isEmpty()) info("白名单是空的，现在没人会被复读");
    }

    @Override
    public void onDeactivate() {
        synchronized (pendingDebug) {
            pendingDebug.clear();
        }
        synchronized (pendingEchoes) {
            pendingEchoes.clear();
        }
    }

    /** 收到一条聊天栏消息：认格式 → 查白名单 → 复读一条原文 */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive() || event.getMessage() == null) return;

        String raw = event.getMessage().getString();
        String text = ignoreFormatting.get() ? stripColor(raw) : raw;
        // 本模块自己打在聊天栏的提示不再当作聊天处理
        if (text.contains("[自动复读]")) return;

        ParsedChat parsed = parseLine(text);
        if (parsed == null) {
            if (debugChat.get()) {
                synchronized (pendingDebug) {
                    pendingDebug.addLast(text);
                    while (pendingDebug.size() > 8) pendingDebug.removeFirst();
                }
            }
            return;
        }
        if (parsed.name().isEmpty() || parsed.text().isEmpty()) return;

        // 自己的消息不复读：自己发的、复读出去那条的服务器回显，名字都是自己
        if (isSelfName(parsed.name())) return;
        if (!whiteList.contains(parsed.name())) return;

        String echo = convertColor.get() ? swapColorPrefix(parsed.text()) : parsed.text();

        // 都不立刻发：统一排到 tick 末再发，这样能先打「尝试复读」再发，也不会把收到的消息顶掉
        synchronized (pendingEchoes) {
            pendingEchoes.addLast(new PendingEcho(echo, System.currentTimeMillis() + nextDelayMs()));
            while (pendingEchoes.size() > 32) pendingEchoes.removeFirst();
        }
    }

    /** 下一条复读的延迟：设置值；开了「延迟抖动」就在它上下随机浮动 1-10 tick */
    private long nextDelayMs() {
        long base = repeatDelay.get() * TICK_MS;
        if (!randomDelay.get()) return base;

        long jitter = (1 + RANDOM.nextInt(10)) * TICK_MS;
        long delay = RANDOM.nextBoolean() ? base + jitter : base - jitter;
        return Math.max(0L, delay);
    }

    /** 每个 tick 末：到点的复读发出去，攒的调试提示打出来（带 % 的聊天不能当格式化字符串用） */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        long now = System.currentTimeMillis();
        while (true) {
            PendingEcho echo;
            synchronized (pendingEchoes) {
                PendingEcho head = pendingEchoes.peekFirst();
                if (head == null || head.dueAt() > now) break;
                echo = pendingEchoes.pollFirst();
            }
            info("%s", "尝试复读");
            ChatUtils.sendPlayerMsg(echo.text());
        }

        String text;
        synchronized (pendingDebug) {
            text = pendingDebug.pollFirst();
        }
        if (text != null) info("%s", "未识别: " + text);
    }

    /** 消息以 > 开头就换成 ^ 开头，以 ^ 开头就换成 > 开头，其它原样 */
    private static String swapColorPrefix(String text) {
        if (text.startsWith(">")) return "^" + text.substring(1);
        if (text.startsWith("^")) return ">" + text.substring(1);
        return text;
    }

    // ==================== 解析 ====================

    /** 先用填的格式匹配；行首带装饰（头像字符/称号）时去掉再试一次 */
    private ParsedChat parseLine(String text) {
        ParsedChat parsed = parse(publicChatFormat.get(), text);
        if (parsed != null) return parsed;

        String stripped = stripLeadingDecoration(text);
        if (!stripped.equals(text)) return parse(publicChatFormat.get(), stripped);
        return null;
    }

    private ParsedChat parse(String format, String text) {
        String template = format == null ? "" : format.trim();
        if (!template.contains("{name}") || !template.contains("{text}")) return null;

        Pattern pattern = patternCache.get(template);
        if (pattern == null) {
            pattern = buildPattern(template);
            patternCache.put(template, pattern);
        }

        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) return null;
        return new ParsedChat(cleanName(matcher.group(1)), matcher.group(2).trim());
    }

    /** 模板转正则：{name} → 玩家名（非空），{text} → 正文（可空），整行必须从开头匹配 */
    private static Pattern buildPattern(String format) {
        StringBuilder regex = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            if (format.startsWith("{name}", i)) {
                appendLiteral(regex, literal);
                regex.append("(.+?)");
                i += 6;
            } else if (format.startsWith("{text}", i)) {
                appendLiteral(regex, literal);
                regex.append("(.*)");
                i += 6;
            } else {
                literal.append(format.charAt(i));
                i++;
            }
        }
        appendLiteral(regex, literal);
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * 把一段字面量整段引用进正则：📨 这类 emoji 是两个 char，一个 char 一个 char 引用会被拆成半截字符
     */
    private static void appendLiteral(StringBuilder regex, StringBuilder literal) {
        if (literal.length() == 0) return;
        regex.append(Pattern.quote(literal.toString()));
        literal.setLength(0);
    }

    /** 去掉玩家名前面的装饰：头像模组的私有区/控制字符、[xxx] 称号（可叠多层） */
    private static String cleanName(String name) {
        String result = name == null ? "" : name.trim();
        for (int i = 0; i < 5; i++) {
            boolean changed = false;
            while (!result.isEmpty() && isDecorationChar(result.charAt(0))) {
                result = result.substring(1).trim();
                changed = true;
            }
            if (result.startsWith("[")) {
                int end = result.indexOf(']');
                if (end > 0) {
                    result = result.substring(end + 1).trim();
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return result;
    }

    /** 格式没匹配上时，去掉整行最前面的装饰再试 */
    private static String stripLeadingDecoration(String text) {
        String result = text;
        for (int i = 0; i < 5; i++) {
            boolean changed = false;
            while (!result.isEmpty() && isDecorationChar(result.charAt(0))) {
                result = result.substring(1);
                changed = true;
            }
            if (result.startsWith("[")) {
                int end = result.indexOf(']');
                if (end > 0) {
                    result = result.substring(end + 1);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return result;
    }

    /** 头像模组会用私有区字符，服务器可能塞控制字符 */
    private static boolean isDecorationChar(char c) {
        return c < 0x20 || (c >= 0xE000 && c <= 0xF8FF);
    }

    private static String stripColor(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    /** 这条消息是不是自己发的；服务器给名字加前缀（[VIP] 自己名字）时也算 */
    private boolean isSelfName(String name) {
        if (name == null || name.isEmpty()) return false;
        String self;
        try {
            self = mc.getUser().getName();
        } catch (Exception e) {
            return false;
        }
        if (self == null || self.isEmpty()) return false;
        if (name.equalsIgnoreCase(self)) return true;
        return name.length() > self.length() && name.toLowerCase().endsWith(self.toLowerCase());
    }
}
