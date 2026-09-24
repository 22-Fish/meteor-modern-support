package fish22.modernsupport.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DeepSeek API 通信层：HTTP 请求、请求/响应模型、token 统计、工具定义生成
 *
 * 工具定义（Function Calling）按权限配置（AiFiles）动态生成：
 * - 可读区域列表注入 cd/ls/read/search 等工具描述
 * - 可写区域列表注入 write/edit/mkdir/move/delete_file 等工具描述
 * - list_roots 工具供 AI 查询当前可访问区域
 */
public class AiApiClient {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Gson GSON_FLAT = new GsonBuilder().create();

    /** 最近一次调用失败的原因（本地提示用） */
    private static String lastError = "";

    static String getApiKey() { return AiConfig.getApiKey(); }
    static String getApiUrl() { return AiConfig.getApiUrl(); }
    static String getLastError() { return lastError; }

    // ========== 请求/响应模型 ==========

    static class ChatRequest {
        String model;
        List<Map<String, Object>> messages;
        List<Map<String, Object>> tools;
        boolean stream = false;
        String reasoning_effort;
        Map<String, Object> thinking;
    }

    static class ChatResponse {
        List<Choice> choices;
        Map<String, Object> usage;
        static class Choice {
            Map<String, Object> message;
            String finish_reason;
        }
    }

    static class Message {
        final Map<String, Object> data;
        Message(Map<String, Object> data) { this.data = data; }
        String getRole() {
            Object r = data.get("role");
            return r != null ? r.toString() : "";
        }
    }

    /**
     * Token使用统计，工具循环中每次API调用累加输入和输出
     */
    static class TokenUsage {
        long inputTokens = 0;
        long cacheHitTokens = 0;
        long outputTokens = 0;

        @SuppressWarnings("unchecked")
        void add(Map<String, Object> usage) {
            if (usage != null) {
                inputTokens += ((Number) usage.getOrDefault("prompt_tokens", 0)).longValue();
                cacheHitTokens += ((Number) usage.getOrDefault("prompt_cache_hit_tokens", 0)).longValue();
                outputTokens += ((Number) usage.getOrDefault("completion_tokens", 0)).longValue();
            }
        }

        long totalTokens() {
            return inputTokens + outputTokens;
        }

        String format() {
            double hitRate = inputTokens > 0 ? (double) cacheHitTokens / inputTokens * 100.0 : 0;
            return String.format("§c[Token] 输入 %d(命中 %d %.1f%%) 输出 %d",
                inputTokens, cacheHitTokens, hitRate, outputTokens);
        }
    }

    // ========== 消息构造 ==========

    static Map<String, Object> systemMsg(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "system");
        m.put("content", content);
        return m;
    }

    static Map<String, Object> userMsg(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", content);
        return m;
    }

    static Map<String, Object> assistantMsg(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content);
        return m;
    }

    static Map<String, Object> toolMsg(String toolCallId, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId);
        m.put("content", content);
        return m;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> getToolCalls(Map<String, Object> msg) {
        Object tc = msg.get("tool_calls");
        if (tc instanceof List) {
            return (List<Map<String, Object>>) tc;
        }
        return null;
    }

    /**
     * 「无工具调用思考剔除」：assistant 且这一轮没有工具调用的消息，去掉 reasoning_content。
     * 把上一轮的思考原样传回服务器既费 token，部分服务商还会直接报错。
     */
    private static Map<String, Object> stripThinking(Map<String, Object> message) {
        if (!"assistant".equals(String.valueOf(message.get("role")))) return message;
        if (message.get("tool_calls") != null) return message;
        if (message.get("reasoning_content") == null) return message;

        Map<String, Object> copy = new LinkedHashMap<>(message);
        copy.remove("reasoning_content");
        return copy;
    }

    // ========== 工具定义（动态生成） ==========

    /**
     * 主对话工具定义（按当前权限配置动态生成）
     */
    public static List<Map<String, Object>> buildMainTools() {
        String json = mainToolsJsonTemplate()
                .replace("%ROOTS%", AiFiles.getReadableRootsDesc())
                .replace("%WRITABLE%", AiFiles.getWritableRootsDesc());
        //noinspection unchecked
        List<Map<String, Object>> tools = GSON.fromJson(json, List.class);

        // 新增工具：在公屏发消息
        tools.add(tool("send_message",
                "向服务器公屏发送一条消息，所有玩家都能看到。需要主动说话时用它，不要只说你已经发送了。",
                Map.of("message", Map.of("type", "string", "description", "要发送的消息内容")),
                List.of("message")));

        // 工具开关：关掉的工具直接从定义里去掉（子代理那批在 isToolEnabled 里一律返回 false）
        tools.removeIf(tool -> {
            Object function = tool.get("function");
            if (!(function instanceof Map<?, ?> map)) return true;
            return !AiConfig.isToolEnabled(String.valueOf(map.get("name")));
        });
        return tools;
    }

    /** 拼一个 Function Calling 工具定义 */
    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    /**
     * 子代理工具定义（只读检索 + 文件写入，权限受 AiFiles 区域配置约束）
     */
    public static List<Map<String, Object>> buildSubAgentTools() {
        String json = subToolsJsonTemplate()
                .replace("%ROOTS%", AiFiles.getReadableRootsDesc())
                .replace("%WRITABLE%", AiFiles.getWritableRootsDesc());
        //noinspection unchecked
        return GSON.fromJson(json, List.class);
    }

    private static String mainToolsJsonTemplate() {
        return """
[{"type":"function","function":{"name":"list_roots","description":"查询当前可访问的文件区域列表（含读写权限）。不确定某路径是否可用时，先调用此工具确认。","parameters":{"type":"object","properties":{},"required":[]}}},{"type":"function","function":{"name":"get_time","description":"查询当前的现实世界时间。","parameters":{"type":"object","properties":{},"required":[]}}},{"type":"function","function":{"name":"check_chat","description":"查看服务器公屏聊天记录（非AI对话上下文）。0=当前触发消息，1=前一条。check_chat(a,b)返回(a,b]范围。如check_chat(0,5)返回前面5条公屏消息。非必要勿调用，仅查看玩家公屏聊天时使用。","parameters":{"type":"object","properties":{"a":{"type":"integer","description":"起始距离（0=当前，较小值，不包含自身）"},"b":{"type":"integer","description":"结束距离（较大值，包含）"}},"required":["a","b"]}}},{"type":"function","function":{"name":"list_players","description":"查询当前在线玩家列表。","parameters":{"type":"object","properties":{},"required":[]}}},{"type":"function","function":{"name":"create_sub_agent","description":"创建一个新的子代理。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"子代理名称"}},"required":["name"]}}},{"type":"function","function":{"name":"ask_sub_agent","description":"向指定子代理提问，子代理会检索工具记忆中的知识库来回答。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"子代理名称"},"question":{"type":"string","description":"向子代理提出的问题"}},"required":["name","question"]}}},{"type":"function","function":{"name":"delete_sub_agent","description":"删除指定子代理及其所有上下文。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"子代理名称"}},"required":["name"]}}},{"type":"function","function":{"name":"list_sub_agents","description":"列出所有已创建的子代理。","parameters":{"type":"object","properties":{},"required":[]}}},{"type":"function","function":{"name":"get_sub_context","description":"查询指定子代理的上下文长度。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"子代理名称"}},"required":["name"]}}},{"type":"function","function":{"name":"create_scheduled_task","description":"创建一个定时任务，到指定时间自动触发AI（每天一次）。提示词中可用[[[time]]]指代当前时间，[[[name]]]指代任务名。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"任务名称"},"prompt":{"type":"string","description":"触发时发送给AI的提示词"},"time":{"type":"string","description":"触发时间，HH:mm格式，如 08:00"}},"required":["name","prompt","time"]}}},{"type":"function","function":{"name":"delete_scheduled_task","description":"删除一个定时任务。","parameters":{"type":"object","properties":{"name":{"type":"string","description":"任务名称"}},"required":["name"]}}},{"type":"function","function":{"name":"cd","description":"切换工作目录。支持 %ROOTS% ，/ 为总根。cd(\\\"/\\\")返回总根。仅本轮工具调用有效。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"目标路径，如 /提示词/建筑 或 /"}},"required":["path"]}}},{"type":"function","function":{"name":"ls","description":"列出目录内容，包含文件大小（字符数）和行数。不填 path 时列出当前目录；在总根 / 时列出全部可访问区域。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"目标路径，选填，不填则列出当前目录"}},"required":[]}}},{"type":"function","function":{"name":"read","description":"按行号读取文件（所有已授权区域均可读）。read(path,line=起始行,count=读取行数)。line默认1，count默认50(最大200)。配合search_file返回的行号精准定位。>1000行文件需confirm=true确认。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"文件路径"},"line":{"type":"integer","description":"起始行号(1-indexed，默认1)"},"count":{"type":"integer","description":"读取行数(默认50,最大200)"},"confirm":{"type":"boolean","description":">1000行文件需填true确认"}},"required":["path"]}}},{"type":"function","function":{"name":"search_name","description":"搜索文件名包含关键词的文件，返回匹配的虚拟路径列表。不区分大小写。path 不填则搜索全部已授权区域；结果过多(>100)时自动截断。","parameters":{"type":"object","properties":{"keyword":{"type":"string","description":"搜索关键词"},"path":{"type":"string","description":"搜索路径，选填（虚拟路径）；不填则搜索全部已授权区域"}},"required":["keyword"]}}},{"type":"function","function":{"name":"search_content","description":"分块检索文件内容，返回命中块：得分+虚拟路径+行号范围+内容片段，按相关度降序。keyword 支持空格分隔多词，默认所有词必须命中(AND)；min_hits=n 可放宽为只需命中 n 个词；strict=true 等价全命中；limit 控制返回块数(默认5,最大10)。path 支持目录或单个文件，不填则搜索全部已授权区域；命中过多会提示缩小范围。","parameters":{"type":"object","properties":{"keyword":{"type":"string","description":"搜索关键词"},"path":{"type":"string","description":"虚拟目录路径(选填)"},"confirm":{"type":"boolean","description":"大结果确认"}},"required":["keyword"]}}},{"type":"function","function":{"name":"search_file","description":"搜索指定文件内匹配关键词的所有行，返回行号和内容。最多200行匹配。","parameters":{"type":"object","properties":{"keyword":{"type":"string","description":"搜索关键词"},"path":{"type":"string","description":"文件路径"}},"required":["keyword","path"]}}},{"type":"function","function":{"name":"write","description":"新建或覆盖写入文件。仅 %WRITABLE% 可写，其余区域只读。自动创建父目录。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"目标文件虚拟路径，如 /提示词/我的笔记.md"},"content":{"type":"string","description":"要写入的完整内容（覆盖式写入）"}},"required":["path","content"]}}},{"type":"function","function":{"name":"edit","description":"精确修改文件内容。old_text 必须在文件中唯一出现，不唯一则拒绝修改。仅 %WRITABLE% 可写。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"文件虚拟路径"},"old_text":{"type":"string","description":"要替换的现有文本（必须与文件内容完全一致且唯一）"},"new_text":{"type":"string","description":"替换后的新文本"}},"required":["path","old_text","new_text"]}}},{"type":"function","function":{"name":"delete_file","description":"删除文件。第一次调用返回预览，再次调用并带 confirm=true 确认删除。仅 %WRITABLE% 可删。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"文件虚拟路径"},"confirm":{"type":"boolean","description":"二次确认标志"}},"required":["path"]}}},{"type":"function","function":{"name":"mkdir","description":"创建文件夹（支持嵌套，自动创建中间目录）。仅 %WRITABLE% 可写。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"要创建的文件夹虚拟路径，如 /提示词/2026-08"}},"required":["path"]}}},{"type":"function","function":{"name":"move","description":"移动或重命名文件/文件夹。源和目标都必须在可写区域内（%WRITABLE%）；目标已存在或移动到自身内部时拒绝。","parameters":{"type":"object","properties":{"source":{"type":"string","description":"源路径"},"target":{"type":"string","description":"目标路径"}},"required":["source","target"]}}}]""";
    }

    private static String subToolsJsonTemplate() {
        return """
[{"type":"function","function":{"name":"list_roots","description":"查询当前可访问的文件区域列表（含读写权限）。","parameters":{"type":"object","properties":{},"required":[]}}},{"type":"function","function":{"name":"get_time","description":"查询当前的现实世界时间。","parameters":{"type":"object","properties":{},"required":[]}}},{"type":"function","function":{"name":"check_chat","description":"查看服务器公屏聊天记录（非AI对话上下文）。0=当前触发消息，1=前一条。check_chat(a,b)返回(a,b]范围。非必要勿调用。","parameters":{"type":"object","properties":{"a":{"type":"integer","description":"起始距离（0=当前，较小值）"},"b":{"type":"integer","description":"结束距离（较大值）"}},"required":["a","b"]}}},{"type":"function","function":{"name":"list_players","description":"查询当前在线玩家列表。","parameters":{"type":"object","properties":{},"required":[]}}},{"type":"function","function":{"name":"cd","description":"切换工作目录。支持 %ROOTS% ，/ 为总根。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"目标路径"}},"required":["path"]}}},{"type":"function","function":{"name":"ls","description":"列出目录内容，含文件大小和行数。在总根时列出全部可访问区域。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"目标路径，选填"}},"required":[]}}},{"type":"function","function":{"name":"read","description":"按行号读取文件（所有已授权区域均可读）。read(path,line=行号,count=行数)。line默认1,count默认50(最大200)。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"文件路径"},"line":{"type":"integer","description":"起始行号(1-indexed)"},"count":{"type":"integer","description":"读取行数(最大200)"},"confirm":{"type":"boolean","description":">1000行文件确认"}},"required":["path"]}}},{"type":"function","function":{"name":"search_name","description":"搜索文件名包含关键词的文件，返回虚拟路径。不区分大小写。path 不填则搜索全部已授权区域；结果过多时自动截断。","parameters":{"type":"object","properties":{"keyword":{"type":"string","description":"搜索关键词"},"path":{"type":"string","description":"搜索路径，选填（虚拟路径）；不填则搜索全部已授权区域"}},"required":["keyword"]}}},{"type":"function","function":{"name":"search_content","description":"分块检索文件内容，返回命中块（得分+路径+行号+片段）。keyword 多词默认全部命中(AND)；min_hits=n 放宽；strict=true 全命中。path 支持目录或文件，不填则搜索全部已授权区域。","parameters":{"type":"object","properties":{"keyword":{"type":"string","description":"搜索关键词"},"path":{"type":"string","description":"虚拟目录路径(选填)"},"confirm":{"type":"boolean","description":"确认标志"}},"required":["keyword"]}}},{"type":"function","function":{"name":"search_file","description":"搜索文件内匹配关键词的所有行。最多200行。","parameters":{"type":"object","properties":{"keyword":{"type":"string","description":"搜索关键词"},"path":{"type":"string","description":"文件路径"}},"required":["keyword","path"]}}},{"type":"function","function":{"name":"write","description":"新建或覆盖写入文件。仅 %WRITABLE% 可写，其余区域只读。自动创建父目录。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"目标文件虚拟路径"},"content":{"type":"string","description":"要写入的完整内容（覆盖式写入）"}},"required":["path","content"]}}},{"type":"function","function":{"name":"edit","description":"精确修改文件内容。old_text 必须在文件中唯一出现，不唯一则拒绝修改。仅 %WRITABLE% 可写。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"文件虚拟路径"},"old_text":{"type":"string","description":"要替换的现有文本（必须与文件内容完全一致且唯一）"},"new_text":{"type":"string","description":"替换后的新文本"}},"required":["path","old_text","new_text"]}}},{"type":"function","function":{"name":"delete_file","description":"删除文件。第一次调用返回预览，再次调用并带 confirm=true 确认删除。仅 %WRITABLE% 可删。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"文件虚拟路径"},"confirm":{"type":"boolean","description":"二次确认标志"}},"required":["path"]}}},{"type":"function","function":{"name":"mkdir","description":"创建文件夹（支持嵌套，自动创建中间目录）。仅 %WRITABLE% 可写。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"要创建的文件夹虚拟路径"}},"required":["path"]}}},{"type":"function","function":{"name":"move","description":"移动或重命名文件/文件夹。源和目标都必须在可写区域内（%WRITABLE%）；目标已存在或移动到自身内部时拒绝。","parameters":{"type":"object","properties":{"source":{"type":"string","description":"源路径"},"target":{"type":"string","description":"目标路径"}},"required":["source","target"]}}}]""";
    }

    // ========== API 请求 ==========

    /**
     * 发送一次 API 请求并返回响应。
     *
     * <p>messages 里已经带着最前面那一条 system（系统提示词写在上下文最前面，见 AiContext），
     * 这里不再另外拼一条 system。
     */
    static ChatResponse sendApiRequest(List<Message> messages, List<Map<String, Object>> tools) {
        List<Map<String, Object>> requestMessages = new ArrayList<>();
        boolean stripThinking = AiConfig.isStripThinking();
        for (Message msg : messages) {
            requestMessages.add(stripThinking ? stripThinking(msg.data) : msg.data);
        }

        ChatRequest request = new ChatRequest();
        request.model = AiConfig.getModelName();
        request.messages = requestMessages;
        request.tools = tools;
        request.reasoning_effort = AiConfig.getReasoningEffort();
        if ("enabled".equals(AiConfig.getThinkingMode())) {
            Map<String, Object> thinkingObj = new LinkedHashMap<>();
            thinkingObj.put("type", "enabled");
            request.thinking = thinkingObj;
        }

        try {
            String json = GSON.toJson(request);
            byte[] postData = json.getBytes(StandardCharsets.UTF_8);
            // 调用过程不往聊天栏打字（URL/消息量/状态码这些属于调试信息），
            // 只留 token 用量：那部分在 AiToolExecutor.broadcastTokenUsage 里打。

            URL url = URI.create(getApiUrl()).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + getApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(120000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                String respBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                return GSON.fromJson(respBody, ChatResponse.class);
            } else {
                String errorBody = new String(conn.getErrorStream() != null ? conn.getErrorStream().readAllBytes() : new byte[0], StandardCharsets.UTF_8);
                lastError = "API错误 " + responseCode + ": " + errorBody;
                AI.LOG("§c" + lastError);
                conn.disconnect();
                return null;
            }
        } catch (Exception e) {
            lastError = "请求失败: " + e.getMessage();
            AI.LOG("§c" + lastError);
            return null;
        }
    }
}
