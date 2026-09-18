/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 * 由 AEBot (com.fakeplayer) 移植而来：把服务端的 AI 假人搬到客户端，做成一个模块。
 */

package fish22.modernsupport.modules;

import fish22.modernsupport.aibot.AI;
import fish22.modernsupport.aibot.AiChatHandler;
import fish22.modernsupport.aibot.AiConfig;
import fish22.modernsupport.aibot.AiContext;
import fish22.modernsupport.aibot.AiFiles;
import fish22.modernsupport.aibot.AiTasks;
import fish22.modernsupport.settings.ActionSetting;
import fish22.modernsupport.settings.ApiKeyRenderer;
import fish22.modernsupport.settings.ScheduledTaskListSetting;
import fish22.modernsupport.settings.WhiteListSetting;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * openAI — 启动一个openAI服务器
 *
 * <p>把 AEBot 那套「AI 在服务器聊天里跟玩家对话」搬到客户端：
 * 监听自己的聊天栏，按设置的格式认出公屏/私聊消息，命中触发词就调用 AI 接口，
 * 回复用公屏（公屏触发）或私聊命令（私聊触发）发出去。
 *
 * <p>工具调用提示、token 消耗、API 报错只在本地聊天栏显示，不会发到服务器。
 * 文件相关的工具（读/写/搜索）作用在 meteor-client/openai/ 目录下，可访问区域由 权限.json 配置。
 */
public class OpenAI extends Module {

    private static OpenAI INSTANCE;

    /** 当前模块实例（配置读取层 AiConfig / 调度线程用） */
    public static OpenAI get() { return INSTANCE; }

    private final SettingGroup sgApi = settings.createGroup("API");
    private final SettingGroup sgAgent = settings.createGroup("agent");
    private final SettingGroup sgAutoChat = settings.createGroup("自动聊天");
    private final SettingGroup sgChat = settings.createGroup("聊天识别");
    private final SettingGroup sgUser = settings.createGroup("白名单");
    private final SettingGroup sgTools = settings.createGroup("工具调用");
    private final SettingGroup sgToolSwitch = settings.createGroup("工具开关");

    // ==================== API ====================

    public final Setting<String> apiUrl = sgApi.add(new StringSetting.Builder()
        .name("URL")
        .description("AI提供商的接口地址")
        .defaultValue("https://api.deepseek.com")
        .wide()
        .build()
    );

    public final Setting<String> apiKey = sgApi.add(new StringSetting.Builder()
        .name("APIKEY")
        .description("AI的密钥")
        .defaultValue("")
        .renderer(ApiKeyRenderer.class)
        .wide()
        .build()
    );

    public final Setting<String> modelName = sgApi.add(new StringSetting.Builder()
        .name("模型")
        .description("调用的模型名")
        .defaultValue("deepseek-v4-pro")
        .build()
    );

    public final Setting<Boolean> stripThinking = sgApi.add(new BoolSetting.Builder()
        .name("无工具调用思考剔除")
        .description("不把没有工具调用的那几轮的思考内容传回服务器")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> thinkingMode = sgApi.add(new BoolSetting.Builder()
        .name("思考模式")
        .description("是否开启思考模式")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> reasoningEffort = sgApi.add(new StringSetting.Builder()
        .name("思考强度")
        .description("模型的思考强度")
        .defaultValue("low")
        .build()
    );

    public final Setting<Integer> refreshSystemPrompt = sgApi.add(new ActionSetting(
        "刷新系统提示词",
        "重新读取「提示词」文件夹里的内容，替换上下文最前面的系统提示词。"
            + "平时不会自动刷新，只有删减上下文之后、或者按这个按钮时才刷新",
        "刷新系统提示词",
        AiConfig::refreshSystemPromptFromGui,
        null
    ));

    // ==================== agent ====================

    public final Setting<Boolean> autoTrim = sgAgent.add(new BoolSetting.Builder()
        .name("上下文自动删减")
        .description("开启后按下面的阈值自动删减对话上下文")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> speculativeTrim = sgAgent.add(new IntSetting.Builder()
        .name("投机删除阈值")
        .description("上下文超过这个 token 数、且距离上次调用超过「忽略缓存时间」时，下次调用发送 API 前先删减上下文")
        .defaultValue(40000)
        .min(0)
        .noSlider()
        .build()
    );

    public final Setting<Integer> cacheIgnoreMinutes = sgAgent.add(new IntSetting.Builder()
        .name("忽略缓存时间")
        .description("单位分钟。距上次调用超过这么久缓存已经失效，这时删上下文不亏")
        .defaultValue(240)
        .min(1)
        .noSlider()
        .build()
    );

    public final Setting<Integer> trimKeep = sgAgent.add(new IntSetting.Builder()
        .name("上下文删除保留值")
        .description("删减后想保留的上下文 token 数")
        .defaultValue(10000)
        .min(1000)
        .noSlider()
        .build()
    );

    public final Setting<Integer> forceTrim = sgAgent.add(new IntSetting.Builder()
        .name("强制删除阈值")
        .description("上下文到达这个 token 数后，下次调用先删减再发送，不看缓存时间")
        .defaultValue(80000)
        .min(0)
        .noSlider()
        .build()
    );

    public final Setting<Integer> trimNow = sgAgent.add(new ActionSetting(
        "立即删减上下文",
        "手动删减一次上下文",
        "立即删减上下文",
        AiContext::trimNow,
        null
    ));

    public final Setting<Integer> forceTrigger = sgAgent.add(new ActionSetting(
        "强制触发",
        "强制触发一次AI：把公屏最近 10 条消息作为多条 user 消息传入",
        "强制触发",
        AiChatHandler::forceTriggerFromRecentChat,
        null
    ));

    // ==================== 自动聊天 ====================

    public final Setting<Boolean> autoChat = sgAutoChat.add(new BoolSetting.Builder()
        .name("自动聊天")
        .description("开启后，符合公屏/私聊格式的任意聊天都会进入聚合；不需要触发词，也不看白名单。谨慎开启")
        .defaultValue(false)
        .onChanged(AiChatHandler::onAutoChatSettingChanged)
        .build()
    );

    public final Setting<Integer> autoChatMaxDelay = sgAutoChat.add(new IntSetting.Builder()
        .name("自动聊天消息整合上限")
        .description("从第一条消息开始计时，最多等这么多秒；到时间就把期间的消息按多条 user 消息发给AI")
        .defaultValue(30)
        .min(1)
        .max(100)
        .sliderRange(1, 100)
        .build()
    );

    public final Setting<Integer> autoChatMinDelay = sgAutoChat.add(new IntSetting.Builder()
        .name("自动消息整合下限")
        .description("距离上一条消息超过这么多秒仍没有新消息时，直接把当前这批消息发给AI")
        .defaultValue(10)
        .min(1)
        .max(100)
        .sliderRange(1, 100)
        .build()
    );

    public final Setting<Boolean> autoChatRandomDelay = sgAutoChat.add(new BoolSetting.Builder()
        .name("随机整合时间")
        .description("开启后，每批自动聊天的整合上限和下限在设置值基础上随机上下浮动 1-10 tick")
        .defaultValue(true)
        .build()
    );

    // ==================== 聊天识别 ====================

    public final Setting<String> publicChatFormat = sgChat.add(new StringSetting.Builder()
        .name("公屏聊天格式")
        .description("{name}为玩家名,{text}为聊天正文")
        .defaultValue("<{name}> {text}")
        .wide()
        .build()
    );

    public final Setting<String> privateChatFormat = sgChat.add(new StringSetting.Builder()
        .name("私聊聊天格式")
        .description("{name}为玩家名,{text}为聊天正文")
        .defaultValue("{name} 悄悄对你说：{text}")
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

    public final Setting<String> namePrefixIgnores = sgChat.add(new StringSetting.Builder()
        .name("名字前缀忽略")
        .description("自定义忽略的名字前缀内容")
        .defaultValue("")
        .wide()
        .build()
    );

    public final Setting<Boolean> debugChat = sgChat.add(new BoolSetting.Builder()
        .name("调试：打印未识别消息")
        .description("打印所有未识别的消息原文，方便调试")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> privateCommand = sgChat.add(new StringSetting.Builder()
        .name("私聊命令")
        .description("私聊玩家的命令。{name}玩家名,{text}内容")
        .defaultValue("/tell {name} {text}")
        .build()
    );

    public final Setting<Integer> messageDelay = sgChat.add(new IntSetting.Builder()
        .name("消息频率")
        .description("分多条发消息时的间隔")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 100)
        .build()
    );

    public final Setting<Boolean> randomMessageDelay = sgChat.add(new BoolSetting.Builder()
        .name("随机消息间隔")
        .description("增加微小随机值浮动，方便绕过机器人识别")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> trigger = sgChat.add(new StringSetting.Builder()
        .name("消息触发词")
        .description("公屏消息里出现这个词才触发AI，不区分大小写，多个用逗号隔开。")
        .defaultValue("@ai")
        .build()
    );

    public final Setting<Boolean> privateNoTrigger = sgChat.add(new BoolSetting.Builder()
        .name("私聊无需触发词")
        .description("私聊给AI时不用写触发词，直接说话就行")
        .defaultValue(true)
        .build()
    );

    public final Setting<List<String>> excludeWords = sgChat.add(new StringListSetting.Builder()
        .name("排除词语")
        .description("聊天里包含这些词的消息一律不触发AI")
        .defaultValue("拉我")
        .build()
    );

    // ==================== 白名单 ====================

    public final WhiteListSetting whiteList = sgUser.add(new WhiteListSetting(
        "白名单用户",
        "只有名单里的玩家能触发AI。用量限制填 -1 表示无限调用；用量按本地日期每天重置。",
        null
    ));

    // ==================== 工具调用 ====================

    public final ScheduledTaskListSetting scheduledTasks = sgTools.add(new ScheduledTaskListSetting(
        "定时任务",
        "到点自动让AI说一次话。提示词里可以用 [[[time]]] 指代触发时间、[[[name]]] 指代任务名。",
        null
    ));

    // ---------- 每个工具的开关 ----------

    public final Setting<Boolean> toolGetTime = toolSwitch("查询时间", "get_time：查询现实时间");
    public final Setting<Boolean> toolCheckChat = toolSwitch("查看聊天记录", "check_chat：查看最近的公屏聊天记录");
    public final Setting<Boolean> toolListPlayers = toolSwitch("在线玩家列表", "list_players：查询当前在线玩家");
    public final Setting<Boolean> toolSendMessage = toolSwitch("发送消息", "send_message：让AI在公屏发送指定消息");
    public final Setting<Boolean> toolScheduledTask = toolSwitch("定时任务管理", "create_scheduled_task / delete_scheduled_task：增删定时任务");
    public final Setting<Boolean> toolListRoots = toolSwitch("文件区域列表", "list_roots：查看AI能访问的文件区域");
    public final Setting<Boolean> toolFileRead = toolSwitch("读取文件", "cd / ls / read：浏览与读取文件");
    public final Setting<Boolean> toolFileWrite = toolSwitch("写入文件", "write / edit / mkdir / move：新建、修改、移动文件");
    public final Setting<Boolean> toolFileDelete = toolSwitch("删除文件", "delete_file：删除文件（需要二次确认）");
    public final Setting<Boolean> toolSearch = toolSwitch("搜索文件", "search_name / search_content / search_file：搜索文件名与内容");

    private Setting<Boolean> toolSwitch(String name, String description) {
        return sgToolSwitch.add(new BoolSetting.Builder()
            .name(name)
            .description(description)
            .defaultValue(true)
            .build()
        );
    }

    public OpenAI() {
        super(Categories.Misc, "openAI", "启动一个openAI服务。测试中，不建议使用，坐标暴露等后果自负");
        INSTANCE = this;
    }

    // ==================== 生命周期 ====================

    @Override
    public void onActivate() {
        Path dir = MeteorClient.FOLDER.toPath().resolve(AI.FOLDER_NAME);
        AI.init(dir);
        AiConfig.loadPrompts();
        AiContext.load();
        // 上下文最前面固定一条系统提示词：恢复出来的上下文里没有（第一次用、老存档）才补
        AiConfig.ensureSystemPrompt();
        AiChatHandler.onModuleActivated();
        AiFiles.loadFilePermissions();
        AiTasks.start();

        if (AiConfig.getApiKey().isEmpty()) AI.LOG("还没填 APIKEY，AI 调用会失败（模块设置 → API → APIKEY）");
        if (whiteList.get().isEmpty()) AI.LOG("白名单是空的：现在没人能触发AI，请在模块设置的「白名单」里把自己加进去（用量限制填 -1 表示无限）");
        AI.LOG("openAI 服务已启动 | 触发词 " + String.join(", ", AiConfig.getTriggers())
            + " | 白名单 " + whiteList.get().size() + " 人");
    }

    @Override
    public void onDeactivate() {
        AiChatHandler.stop();
        AiTasks.stop();
        AI.LOG("openAI 服务已停止");
    }

    /** 每收到一条聊天消息就交给解析器（本地提示不会命中格式，所以不会自己触发自己） */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive() || event.getMessage() == null) return;
        String text = event.getMessage().getString();
        // 本地提示（[openAI] 开头）是模块自己打的，别再解析一遍，否则会自己喂自己
        if (AI.isLocalMessage(text)) return;
        if (isExcluded(text)) return;
        AiChatHandler.onMessage(text);
    }

    /** 自己在聊天框里发出的消息：不依赖服务器回显，一个人也能触发AI */
    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (!isActive()) return;
        if (isExcluded(event.message)) return;
        AiChatHandler.onOutgoingMessage(event.message);
    }

    /** 自动聊天的整合计时（第一条消息起算上限，最近一条消息起算空闲下限） */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;
        AiChatHandler.tickAutoChat();
    }

    /** 消息里出现「排除词语」里的任意一个就整条不触发（不区分大小写） */
    private boolean isExcluded(String text) {
        if (text == null || text.isEmpty()) return false;

        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : excludeWords.get()) {
            if (word == null || word.isEmpty()) continue;
            if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
        }

        return false;
    }
}
