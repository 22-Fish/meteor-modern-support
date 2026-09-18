package fish22.modernsupport.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件沙箱：AI 可操作区域管理（可配置）+ 文件工具执行
 *
 * 区域配置：config/aebot/权限.json
 * [
 *   {"路径": "./提示词", "权限": "读写删"},   // 写权限自动包含创建和移动权限
 *   {"路径": "./资料库", "权限": "读"}
 * ]
 *  - 路径：相对 config/aebot 的目录（支持嵌套，如 "./资料库/建筑"），对应虚拟路径 /提示词、/资料库/建筑
 *  - 权限："读" / "读写" / "读写删"
 *
 * 启动时只自动创建 提示词 文件夹（系统提示词与 AI 记忆所在），其余区域由用户按需配置。
 * 所有路径解析必须落在已配置区域内，normalize 后越界一律拒绝（SecurityException）。
 */
public class AiFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 一个可访问区域
     */
    static class RootEntry {
        String virtualName;   // 虚拟根名（相对 config/aebot 的路径，正斜杠，如 "提示词" 或 "资料库/建筑"）
        Path realPath;        // 真实路径
        boolean canWrite;     // 是否可写（创建/编辑/移动）
        boolean canDelete;    // 是否可删

        RootEntry(String virtualName, Path realPath, boolean canWrite, boolean canDelete) {
            this.virtualName = virtualName;
            this.realPath = realPath;
            this.canWrite = canWrite;
            this.canDelete = canDelete;
        }
    }

    private static final List<RootEntry> roots = new ArrayList<>();

    // 资料库沙箱：当前工作目录（相对虚拟路径，空字符串=总根）
    private static String currentDbPath = "";
    // 待确认删除的文件：虚拟路径 -> 真实路径
    private static final Map<String, Path> pendingFileDeletes = new HashMap<>();

    // ==================== 区域配置加载 ====================

    /**
     * 加载权限配置（config/aebot/权限.json）。
     * 自动创建 提示词 文件夹；权限.json 不存在时生成默认（仅提示词 读写删）。
     * 配置的区域文件夹不自动创建（用户自己建，或 AI 用 mkdir 创建）。
     */
    public static void loadFilePermissions() {
        roots.clear();
        try {
            Files.createDirectories(AI.getConfigDir());
            // 只自动创建提示词文件夹（AI 记忆等文件所在），其余区域由用户配置
            Files.createDirectories(AI.getConfigDir().resolve("提示词"));

            Path permFile = AI.getConfigDir().resolve("权限.json");
            if (!Files.exists(permFile)) {
                List<Map<String, Object>> defaults = new ArrayList<>();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("路径", "./提示词");
                entry.put("权限", "读写删");
                defaults.add(entry);
                Files.writeString(permFile, GSON.toJson(defaults));
                LOG("§b[AEBot] §e已创建默认权限配置文件: " + permFile + "（默认仅 /提示词 可访问，请按需添加区域）");
            }

            String json = Files.readString(permFile).trim();
            if (json.isEmpty()) {
                LOG("§c[AEBot] 权限.json 为空，AI 无任何可访问区域");
                return;
            }
            Object parsed = GSON.fromJson(json, Object.class);
            if (!(parsed instanceof List)) {
                LOG("§c[AEBot] 权限.json 格式错误：应为区域数组");
                return;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawList = (List<Map<String, Object>>) parsed;
            for (Map<String, Object> raw : rawList) {
                if (raw == null) continue;
                String pathStr = raw.get("路径") != null ? raw.get("路径").toString().trim() : "";
                String permStr = raw.get("权限") != null ? raw.get("权限").toString().trim() : "读";
                if (pathStr.isEmpty()) continue;
                // 规范化路径：去掉 ./ 或 . 前缀，统一正斜杠
                String rel = pathStr;
                while (rel.startsWith("./")) rel = rel.substring(2);
                if (rel.startsWith(".")) rel = rel.substring(1);
                rel = rel.replace('\\', '/');
                while (rel.startsWith("/")) rel = rel.substring(1);
                while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                if (rel.isEmpty()) continue;

                // 解析权限：包含"写"→可写；包含"删"→可删；否则只读
                boolean canWrite = permStr.contains("写");
                boolean canDelete = permStr.contains("删");

                Path realPath = AI.getConfigDir().resolve(rel).normalize();
                // 防御：区域路径必须仍在 config/aebot 内
                if (!realPath.startsWith(AI.getConfigDir().normalize())) {
                    LOG("§c[AEBot] 权限.json 区域越界，已跳过: " + pathStr);
                    continue;
                }
                roots.add(new RootEntry(rel, realPath, canWrite, canDelete));
            }
            LOG("§b[AEBot] §a已加载 " + roots.size() + " 个可访问区域: " + getRootsDesc());
        } catch (IOException e) {
            LOG("§c[AEBot] 加载权限配置失败: " + e.getMessage());
        }
    }

    // ==================== 区域查询 ====================

    /**
     * 所有区域（含权限）描述，供 list_roots 工具返回
     */
    public static String getRootsDesc() {
        if (roots.isEmpty()) return "（无任何可访问区域，请联系管理员在 权限.json 中配置）";
        StringBuilder sb = new StringBuilder("可访问区域:\n");
        for (RootEntry e : roots) {
            String perm = e.canDelete ? "读写删" : (e.canWrite ? "读写" : "读");
            sb.append("/").append(e.virtualName).append(" (").append(perm).append(")");
            if (!Files.exists(e.realPath)) sb.append(" [未创建]");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 可读区域列表描述（用于工具定义），如 "/提示词 /资料库"
     */
    public static String getReadableRootsDesc() {
        if (roots.isEmpty()) return "（无）";
        return roots.stream().map(e -> "/" + e.virtualName).collect(Collectors.joining(" "));
    }

    /**
     * 可写区域列表描述（用于工具定义），如 "/提示词 /备忘录"
     */
    public static String getWritableRootsDesc() {
        return roots.stream().filter(e -> e.canWrite).map(e -> "/" + e.virtualName).collect(Collectors.joining(" "));
    }

    /**
     * 查找包含某真实路径的最深（最长虚拟名）区域
     */
    private static RootEntry findRoot(Path real) {
        RootEntry best = null;
        for (RootEntry e : roots) {
            if (real.startsWith(e.realPath) && (best == null || e.virtualName.length() > best.virtualName.length())) {
                best = e;
            }
        }
        return best;
    }

    /**
     * 权限检查。need: read / write / delete。返回 null 表示允许，否则返回错误信息。
     */
    private static String checkPermission(Path real, String need) {
        RootEntry e = findRoot(real);
        if (e == null) {
            return "权限不足：该路径不在任何已授权区域内（可访问: " + getReadableRootsDesc() + "）";
        }
        if ("write".equals(need) && !e.canWrite) {
            return "权限不足：区域 /" + e.virtualName + " 为只读（可写区域: " + getWritableRootsDesc() + "）";
        }
        if ("delete".equals(need) && !e.canDelete) {
            return "权限不足：区域 /" + e.virtualName + " 无删除权限";
        }
        return null;
    }

    // ==================== 路径解析 ====================

    /**
     * 将用户输入的路径规范化成虚拟路径（如 "提示词/建筑"），空串表示总根 /
     * 不以 / 开头时拼接当前工作目录
     */
    public static String normalizeVirtual(String userPath) {
        String combined;
        if (userPath == null || userPath.isEmpty()) {
            combined = currentDbPath;
        } else if (userPath.startsWith("/")) {
            combined = userPath.substring(1);
        } else {
            combined = currentDbPath.isEmpty() ? userPath : currentDbPath + "/" + userPath;
        }
        while (combined.endsWith("/")) {
            combined = combined.substring(0, combined.length() - 1);
        }
        return combined;
    }

    /**
     * 核心路径解析：虚拟路径（/区域/子路径）→ 真实路径。
     * 按最长区域前缀匹配，normalize 后必须仍在区域内，否则抛 SecurityException。
     */
    public static Path resolveWithinRoots(String userPath) {
        String combined = normalizeVirtual(userPath);
        if (combined.isEmpty()) {
            throw new IllegalArgumentException("当前在总根 /，请指定具体目录（如 /提示词）");
        }
        // 最长前缀匹配区域（/提示词xx 不会误匹配 /提示词）
        RootEntry best = null;
        for (RootEntry e : roots) {
            if (combined.equals(e.virtualName) || combined.startsWith(e.virtualName + "/")) {
                if (best == null || e.virtualName.length() > best.virtualName.length()) {
                    best = e;
                }
            }
        }
        if (best == null) {
            String first = combined.contains("/") ? combined.substring(0, combined.indexOf('/')) : combined;
            throw new SecurityException("未知区域: /" + first + "（可访问: " + getReadableRootsDesc() + "）");
        }
        String rest = combined.equals(best.virtualName) ? "" : combined.substring(best.virtualName.length() + 1);
        Path resolved = (rest.isEmpty() ? best.realPath : best.realPath.resolve(rest)).normalize();
        if (!resolved.startsWith(best.realPath)) {
            throw new SecurityException("路径越狱: /" + combined);
        }
        return resolved;
    }

    /**
     * 将用户路径解析为安全的真实路径（可指定 mustExist）
     */
    public static Path resolveDbPath(String userPath, boolean mustExist) {
        Path resolved = resolveWithinRoots(userPath);
        if (mustExist && !Files.exists(resolved)) {
            throw new IllegalArgumentException("路径不存在: /" + normalizeVirtual(userPath));
        }
        return resolved;
    }

    /**
     * 将用户路径解析为安全的真实路径（不要求存在）
     */
    public static Path resolveDbPath(String userPath) {
        return resolveWithinRoots(userPath);
    }

    /**
     * 真实路径 → 虚拟路径显示（如 /提示词/记忆.md）
     */
    public static String virtualPathOf(Path real) {
        RootEntry e = findRoot(real);
        if (e != null) {
            String rel = e.realPath.relativize(real).toString().replace('\\', '/');
            return "/" + e.virtualName + (rel.isEmpty() ? "" : "/" + rel);
        }
        return real.toString();
    }

    // ==================== 工作目录 ====================

    public static void resetCurrentDbPath() { currentDbPath = ""; }
    public static String getCurrentDbPath() { return currentDbPath; }

    // ==================== 文件工具 ====================

    /**
     * cd(path) — 切换工作目录（支持所有已配置区域，/ 为总根）
     */
    public static String executeCd(Map<String, Object> args) {
        try {
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            if (path.isEmpty()) return "cd: 路径不能为空";

            if ("/".equals(path)) {
                currentDbPath = "";
                return "当前目录: /";
            }

            // 规范化成虚拟路径并验证目录存在
            String virtual = normalizeVirtual(path);
            if (virtual.isEmpty()) {
                currentDbPath = "";
                return "当前目录: /";
            }
            Path resolved = resolveWithinRoots(path);
            if (!Files.isDirectory(resolved)) {
                return "cd: /" + virtual + " 不是目录";
            }
            currentDbPath = virtual;
            return "当前目录: /" + virtual;
        } catch (SecurityException e) {
            return "cd: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "cd: " + e.getMessage();
        } catch (Exception e) {
            return "cd: 错误 - " + e.getMessage();
        }
    }

    /**
     * ls(path?) — 列出目录内容（总根 / 下列出所有已配置区域）
     */
    public static String executeLs(Map<String, Object> args) {
        try {
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            String virtual = normalizeVirtual(path);

            // 总根：列出所有已配置区域
            if (virtual.isEmpty()) {
                StringBuilder rootSb = new StringBuilder("[总根] ");
                for (RootEntry e : roots) {
                    rootSb.append("[D]").append(e.virtualName).append("/");
                    if (!Files.exists(e.realPath)) rootSb.append("(未创建)");
                    rootSb.append(" ");
                }
                return rootSb.toString().trim();
            }

            Path target = resolveWithinRoots(path);
            if (!Files.exists(target)) {
                return "ls: /" + virtual + " 不存在";
            }
            if (!Files.isDirectory(target)) {
                return "ls: 不是目录，是文件: " + target.getFileName();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[/").append(virtual).append("] ");

            try (var stream = Files.list(target)) {
                List<Path> entries = stream.sorted((a, b) -> {
                    boolean aDir = Files.isDirectory(a);
                    boolean bDir = Files.isDirectory(b);
                    if (aDir != bDir) return aDir ? -1 : 1; // 目录优先
                    return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                }).collect(Collectors.toList());

                if (entries.isEmpty()) {
                    sb.append("(空)");
                } else {
                    for (Path entry : entries) {
                        if (Files.isDirectory(entry)) {
                            sb.append("[D]").append(entry.getFileName()).append("/ ");
                        } else {
                            long chars = Files.size(entry);
                            int lines = countLines(entry);
                            sb.append("[").append(entry.getFileName()).append("|").append(chars).append("c>").append(lines).append("L] ");
                        }
                    }
                }
            }

            return sb.toString().trim();
        } catch (SecurityException e) {
            return "ls: " + e.getMessage();
        } catch (Exception e) {
            return "ls: 错误 - " + e.getMessage();
        }
    }

    /**
     * 统计文件行数
     */
    public static int countLines(Path file) {
        try {
            return (int) Files.lines(file).count();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * read(path, line?, count?, confirm?) — 按行号读取文件
     */
    public static String executeRead(Map<String, Object> args) {
        try {
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            if (path.isEmpty()) return "read: 路径不能为空";

            int line = args.containsKey("line") ? ((Number) args.get("line")).intValue() : 1;
            int count = args.containsKey("count") ? ((Number) args.get("count")).intValue() : 50;
            boolean confirm = args.containsKey("confirm") && args.get("confirm") instanceof Boolean && (Boolean) args.get("confirm");
            if (line < 1) line = 1;
            if (count < 1) count = 50;
            if (count > 200) count = 200;

            Path target = resolveDbPath(path, true);
            if (Files.isDirectory(target)) {
                return "read: " + path + " 是目录，请用 ls 查看内容";
            }

            List<String> lines = Files.readAllLines(target);
            int totalLines = lines.size();

            // 大文件二次确认（>1000行且未确认）
            if (totalLines > 1000 && !confirm) {
                return "【警告】文件 " + target.getFileName() + " 共 " + totalLines + " 行，超过1000行。\n" +
                       "确认读取请再次调用 read，带上 confirm=true。建议先用 search_file 定位目标行号再精准读取。";
            }

            if (line > totalLines) {
                return "read: 行号 " + line + " 超出文件总行数 " + totalLines;
            }

            // 按行拼接读取内容，最多 20000 字符
            int endLine = Math.min(line - 1 + count, totalLines);
            StringBuilder chunk = new StringBuilder();
            for (int i = line - 1; i < endLine; i++) {
                String l = lines.get(i);
                if (chunk.length() + l.length() + 1 > 20000) break;
                chunk.append(l).append("\n");
            }
            String content = chunk.toString();
            if (!content.isEmpty()) content = content.substring(0, content.length() - 1);

            StringBuilder sb = new StringBuilder();
            sb.append("文件: ").append(virtualPathOf(target)).append(" (共 ").append(totalLines).append(" 行)\n");
            sb.append("读取行: ").append(line).append("-").append(endLine);
            if (endLine < totalLines) {
                sb.append(" (剩余 ").append(totalLines - endLine).append(" 行)");
            }
            sb.append("\n---\n");
            sb.append(content);
            if (endLine < totalLines) {
                sb.append("\n---\n继续: read(path, line=").append(endLine + 1).append(")");
            }
            return sb.toString();
        } catch (SecurityException e) {
            return "read: " + e.getMessage();
        } catch (Exception e) {
            return "read: 错误 - " + e.getMessage();
        }
    }

    /**
     * write(path, content) — 新建或覆盖写入文件（仅可写区域）
     * 自动创建父目录。
     */
    public static String executeWrite(Map<String, Object> args) {
        try {
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            String content = args.containsKey("content") ? args.get("content").toString() : "";
            if (path.isEmpty()) return "write: 路径不能为空";
            if (content.length() > 50000) return "write: 内容过长（超过 50000 字符）";

            Path target = resolveDbPath(path);
            String denied = checkPermission(target, "write");
            if (denied != null) return "write: " + denied;
            if (Files.isDirectory(target)) {
                return "write: " + path + " 是目录";
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return "已写入 " + virtualPathOf(target) + " (" + content.length() + " 字符)";
        } catch (SecurityException e) {
            return "write: " + e.getMessage();
        } catch (Exception e) {
            return "write: 错误 - " + e.getMessage();
        }
    }

    /**
     * edit(path, old_text, new_text) — 精确修改文件内容（仅可写区域）
     * old_text 必须在文件中唯一匹配，不唯一则拒绝修改，防止误改。
     */
    public static String executeEdit(Map<String, Object> args) {
        try {
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            String oldText = args.containsKey("old_text") ? args.get("old_text").toString() : "";
            String newText = args.containsKey("new_text") ? args.get("new_text").toString() : "";
            if (path.isEmpty()) return "edit: 路径不能为空";
            if (oldText.isEmpty()) return "edit: old_text 不能为空";

            Path target = resolveDbPath(path, true);
            String denied = checkPermission(target, "write");
            if (denied != null) return "edit: " + denied;
            if (Files.isDirectory(target)) {
                return "edit: " + path + " 是目录";
            }

            String content = Files.readString(target);
            int idx = content.indexOf(oldText);
            if (idx == -1) {
                return "edit: 未找到匹配文本（old_text 必须与文件现有内容完全一致）";
            }
            int idx2 = content.indexOf(oldText, idx + oldText.length());
            if (idx2 != -1) {
                return "edit: 匹配文本不唯一（文件中出现多次），请提供更长、更独特的片段";
            }

            String newContent = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
            Files.writeString(target, newContent);
            return "已修改 " + virtualPathOf(target) + ": 替换 1 处";
        } catch (SecurityException e) {
            return "edit: " + e.getMessage();
        } catch (Exception e) {
            return "edit: 错误 - " + e.getMessage();
        }
    }

    /**
     * delete_file(path, confirm?) — 删除文件（仅授权删除的区域）
     * 第一次调用返回预览，再次调用并带 confirm=true 才真正删除。
     */
    public static String executeDeleteFile(Map<String, Object> args) {
        try {
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            boolean confirm = args.containsKey("confirm") && args.get("confirm") instanceof Boolean && (Boolean) args.get("confirm");
            if (path.isEmpty()) return "delete_file: 路径不能为空";

            Path target = resolveDbPath(path, true);
            String denied = checkPermission(target, "delete");
            if (denied != null) return "delete_file: " + denied;
            if (Files.isDirectory(target)) {
                return "delete_file: " + path + " 是目录（不支持删除目录）";
            }

            String vp = virtualPathOf(target);
            if (!confirm) {
                pendingFileDeletes.put(vp, target);
                return "【确认】将删除文件 " + vp + "（" + Files.size(target) + " 字节）。\n" +
                       "确认请再次调用 delete_file，带上 confirm=true。";
            }

            // 确认：只允许删除之前预览过的文件
            Path recorded = pendingFileDeletes.get(vp);
            if (recorded == null || !recorded.equals(target)) {
                return "delete_file: 请先不带 confirm 调用一次预览，再带 confirm=true 确认删除";
            }
            Files.delete(target);
            pendingFileDeletes.remove(vp);
            return "已删除 " + vp;
        } catch (SecurityException e) {
            return "delete_file: " + e.getMessage();
        } catch (Exception e) {
            return "delete_file: 错误 - " + e.getMessage();
        }
    }

    /**
     * mkdir(path) — 创建文件夹（仅可写区域）
     */
    public static String executeMkdir(Map<String, Object> args) {
        try {
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            if (path.isEmpty()) return "mkdir: 路径不能为空";

            Path target = resolveDbPath(path);
            String denied = checkPermission(target, "write");
            if (denied != null) return "mkdir: " + denied;
            if (Files.exists(target)) {
                return "mkdir: " + path + " 已存在";
            }

            Files.createDirectories(target);
            return "已创建文件夹 " + virtualPathOf(target);
        } catch (SecurityException e) {
            return "mkdir: " + e.getMessage();
        } catch (Exception e) {
            return "mkdir: 错误 - " + e.getMessage();
        }
    }

    /**
     * move(source, target) — 移动/重命名文件或文件夹（源和目标都必须在可写区域内）
     * 目标已存在或源不存在时拒绝；禁止移动到自身内部。
     */
    public static String executeMove(Map<String, Object> args) {
        try {
            String source = args.containsKey("source") ? args.get("source").toString().trim() : "";
            String target = args.containsKey("target") ? args.get("target").toString().trim() : "";
            if (source.isEmpty()) return "move: source 不能为空";
            if (target.isEmpty()) return "move: target 不能为空";

            Path src = resolveDbPath(source, true);
            String srcDenied = checkPermission(src, "write");
            if (srcDenied != null) return "move: 源" + srcDenied;
            Path dst = resolveDbPath(target);
            String dstDenied = checkPermission(dst, "write");
            if (dstDenied != null) return "move: 目标" + dstDenied;

            if (src.equals(dst)) return "move: 源和目标相同";
            if (Files.exists(dst)) return "move: 目标已存在: " + target;
            if (dst.startsWith(src)) return "move: 不能把文件夹移动到自身内部";

            Files.createDirectories(dst.getParent());
            Files.move(src, dst);
            return "已移动 " + virtualPathOf(src) + " → " + virtualPathOf(dst);
        } catch (SecurityException e) {
            return "move: " + e.getMessage();
        } catch (Exception e) {
            return "move: 错误 - " + e.getMessage();
        }
    }

    private static void LOG(String msg) { AI.LOG(msg); }
}
