package fish22.modernsupport.aibot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 搜索工具：search_name / search_content / search_file
 * 搜索范围 = 权限配置的所有区域（AiFiles），不存在的区域自动跳过。
 */
public class AiSearch {

    /**
     * search_name(keyword, path?) — 搜索文件名
     */
    public static String executeSearchName(Map<String, Object> args) {
        try {
            String keyword = args.containsKey("keyword") ? args.get("keyword").toString().trim() : "";
            if (keyword.isEmpty()) return "search_name: 关键词不能为空";
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";

            // 收集搜索范围：总根时搜全部区域，否则搜指定目录
            List<Path> searchRoots = resolveSearchRoots(path);

            String lowerKW = keyword.toLowerCase();
            List<String> results = new ArrayList<>();
            for (Path root : searchRoots) {
                Files.walk(root).filter(Files::isRegularFile).forEach(f -> {
                    if (f.getFileName().toString().toLowerCase().contains(lowerKW)) {
                        results.add(AiFiles.virtualPathOf(f));
                    }
                });
            }

            if (results.isEmpty()) {
                return "search_name: 未找到文件名含 \"" + keyword + "\" 的文件";
            }

            results.sort(String::compareToIgnoreCase);
            // 结果过多时截断，避免输出几百个文件名
            if (results.size() > 100) {
                return "匹配 " + results.size() + " 个文件，结果过多。前 100 个: " + String.join(" ", results.subList(0, 100)) +
                       " ...（建议加 path 限定目录或使用更精确的关键词）";
            }
            return results.size() + "个文件: " + String.join(" ", results);
        } catch (SecurityException e) {
            return "search_name: " + e.getMessage();
        } catch (Exception e) {
            return "search_name: 错误 - " + e.getMessage();
        }
    }

    /**
     * 解析搜索范围：path 为空或 "/" 时搜索全部已配置区域（不沿用 cd 的当前目录），否则搜索指定目录
     * path 按绝对虚拟路径解析（如 /提示词 或 提示词），与 cd 状态完全解耦；
     * 不存在的目录会被跳过（防止 Files.walk 抛异常）。
     */
    private static List<Path> resolveSearchRoots(String path) {
        List<Path> roots = new ArrayList<>();
        if (path == null || path.trim().isEmpty()) {
            // 默认全局搜索全部区域（通过 AiFiles 内部区域列表）
            roots.addAll(getAllRootPaths());
        } else {
            String virtual = path.trim();
            if (virtual.startsWith("/")) virtual = virtual.substring(1);
            while (virtual.endsWith("/")) virtual = virtual.substring(0, virtual.length() - 1);
            if (virtual.isEmpty()) {
                roots.addAll(getAllRootPaths());
            } else {
                // 绝对路径解析（加 / 前缀），不拼接 currentDbPath；目录或文件均可
                Path target = AiFiles.resolveWithinRoots("/" + virtual);
                if (!Files.exists(target)) {
                    throw new IllegalArgumentException("路径不存在: /" + virtual);
                }
                roots.add(target);
            }
        }
        // 防御：跳过不存在的目录（防止 Files.walk 抛异常）
        roots.removeIf(r -> !Files.exists(r));
        return roots;
    }

    /**
     * 获取所有已配置区域的真实路径（供全局搜索）
     */
    private static List<Path> getAllRootPaths() {
        List<Path> paths = new ArrayList<>();
        String[] names = AiFiles.getReadableRootsDesc().isEmpty() ? new String[0] : splitRoots(AiFiles.getReadableRootsDesc());
        for (String name : names) {
            // 通过虚拟路径解析回真实路径（保证与区域配置一致）
            try {
                paths.add(AiFiles.resolveWithinRoots(name));
            } catch (Exception ignored) {
                // 区域可能未创建，跳过
            }
        }
        return paths;
    }

    /**
     * 把 "/a /b/c" 拆成 ["/a", "/b/c"]
     */
    private static String[] splitRoots(String desc) {
        return desc.trim().split("\\s+");
    }

    /**
     * search_content(keyword, path?) — 分块检索工具
     * 稀疏匹配（BM25 简化打分：词频 × 稀有度加权）+ 严格匹配（strict=true 时全部关键词必须命中）
     * 按行切块（15 行一块、3 行重叠），返回命中块：得分 + 虚拟路径 + 行号范围 + 内容片段，按得分降序
     * path 支持目录或单个文件；不填则搜索全部区域；常见英文词（new/the 等）自动过滤防止噪音
     */
    public static String executeSearchContent(Map<String, Object> args) {
        try {
            String keyword = args.containsKey("keyword") ? args.get("keyword").toString().trim() : "";
            if (keyword.isEmpty()) return "search_content: 关键词不能为空";
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            boolean strict = args.containsKey("strict") && args.get("strict") instanceof Boolean && (Boolean) args.get("strict");
            int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 5;
            if (limit < 1) limit = 5;
            if (limit > 10) limit = 10;

            // 分词：按空白拆分，支持多关键词
            String[] terms = keyword.toLowerCase().split("\\s+");
            List<String> termList = new ArrayList<>();
            for (String t : terms) {
                if (!t.isEmpty()) termList.add(t);
            }
            if (termList.isEmpty()) return "search_content: 关键词不能为空";

            // 匹配门槛：默认所有词必须命中（AND 收紧，避免多词 OR 海量噪音）；
            // min_hits=n 可放宽为只需命中 n 个词；strict=true 等价全命中（与默认一致）
            int minHits = strict ? termList.size()
                    : (args.containsKey("min_hits") ? ((Number) args.get("min_hits")).intValue() : termList.size());
            if (minHits < 1) minHits = 1;
            if (minHits > termList.size()) minHits = termList.size();

            List<Path> searchRoots = resolveSearchRoots(path);

            // 第一遍：收集候选块并统计每个词的文档频率（包含该词的块数）
            List<SearchHit> allHits = new ArrayList<>();
            Map<String, Integer> termDocFreq = new HashMap<>();
            for (String t : termList) termDocFreq.put(t, 0);

            int fileCount = 0;
            for (Path root : searchRoots) {
                try (var stream = Files.walk(root)) {
                    java.util.Iterator<Path> it = stream.filter(Files::isRegularFile).iterator();
                    while (it.hasNext() && fileCount < 5000) {
                        Path f = it.next();
                        fileCount++;
                        try {
                            if (Files.size(f) > 2 * 1024 * 1024) continue; // 跳过超大文件
                            List<String> lines = Files.readAllLines(f);
                            String vp = AiFiles.virtualPathOf(f);
                            // 按行切块：15 行一块、3 行重叠
                            int chunkSize = 15, overlap = 3, step = chunkSize - overlap;
                            for (int start = 0; start < lines.size() && allHits.size() < 5000; start += step) {
                                int end = Math.min(start + chunkSize, lines.size());
                                StringBuilder block = new StringBuilder();
                                for (int i = start; i < end; i++) {
                                    block.append(lines.get(i)).append("\n");
                                }
                                String blockText = block.toString();
                                String lower = blockText.toLowerCase();
                                // 统计词频与命中词数；命中词数不足门槛的块跳过（默认全命中=AND）
                                int hitCount = 0;
                                int tfSum = 0;
                                for (String t : termList) {
                                    int tf = countOccurrences(lower, t);
                                    if (tf > 0) {
                                        tfSum += tf;
                                        hitCount++;
                                        termDocFreq.put(t, termDocFreq.get(t) + 1);
                                    }
                                }
                                if (hitCount < minHits) continue;
                                if (tfSum == 0) continue;
                                SearchHit hit = new SearchHit();
                                hit.virtualPath = vp;
                                hit.startLine = start + 1;
                                hit.endLine = end;
                                hit.content = blockText;
                                hit.rawScore = tfSum;
                                allHits.add(hit);
                            }
                        } catch (IOException e) { /* 跳过不可读文件 */ }
                    }
                }
            }

            if (allHits.isEmpty()) {
                return "search_content: 未找到包含 \"" + keyword + "\" 的内容";
            }

            // 第二遍：BM25 简化得分（词频 × IDF），稀有词权重更高
            int totalBlocks = allHits.size();
            for (SearchHit hit : allHits) {
                String lower = hit.content.toLowerCase();
                double score = 0;
                for (String t : termList) {
                    int tf = countOccurrences(lower, t);
                    if (tf > 0) {
                        int df = termDocFreq.get(t);
                        double idf = Math.log(1 + (double) totalBlocks / (1 + df));
                        score += tf * idf;
                    }
                }
                hit.score = score;
            }

            // 按得分降序，取前 limit 块
            allHits.sort((a, b) -> Double.compare(b.score, a.score));
            int show = Math.min(limit, allHits.size());

            // 按目录分组输出：同一目录下的命中合并显示，目录路径只出现一次
            LinkedHashMap<String, List<SearchHit>> byDir = new LinkedHashMap<>();
            for (int i = 0; i < show; i++) {
                SearchHit h = allHits.get(i);
                int slash = h.virtualPath.lastIndexOf('/');
                String dir = slash == -1 ? "/" : h.virtualPath.substring(0, slash);
                byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(h);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("检索 \"").append(keyword).append("\" 命中 ").append(allHits.size()).append(" 块（显示 ").append(show).append("）:");
            if (allHits.size() > 200) {
                sb.append("\n【注意】命中过多（").append(allHits.size()).append(" 块），建议加限定词（如 方法名+类名）或缩小 path 目录范围，减少噪音");
            }
            for (var dirEntry : byDir.entrySet()) {
                sb.append("\n").append(dirEntry.getKey()).append(":");
                for (SearchHit h : dirEntry.getValue()) {
                    String fileName = h.virtualPath.substring(h.virtualPath.lastIndexOf('/') + 1);
                    sb.append("\n  - ").append(fileName)
                      .append(" 行").append(h.startLine).append("-").append(h.endLine)
                      .append(" [得分 ").append(String.format("%.2f", h.score)).append("]\n");
                    String snippet = h.content.trim();
                    if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "...";
                    // 内容片段整体缩进，保持层级可读
                    sb.append("    ").append(snippet.replace("\n", "\n    "));
                }
            }
            return sb.toString();
        } catch (SecurityException e) {
            return "search_content: " + e.getMessage();
        } catch (Exception e) {
            return "search_content: 错误 - " + e.getMessage();
        }
    }

    /**
     * 统计子串在文本中的出现次数
     */
    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 检索命中的块
     */
    static class SearchHit {
        String virtualPath;
        int startLine;
        int endLine;
        String content;
        double score;
        int rawScore;
    }

    /**
     * search_file(keyword, path) — 搜索文件内匹配行
     */
    public static String executeSearchFile(Map<String, Object> args) {
        try {
            String keyword = args.containsKey("keyword") ? args.get("keyword").toString().trim() : "";
            String path = args.containsKey("path") ? args.get("path").toString().trim() : "";
            if (keyword.isEmpty()) return "search_file: 关键词不能为空";
            if (path.isEmpty()) return "search_file: 文件路径不能为空";
            boolean confirm = args.containsKey("confirm") && args.get("confirm") instanceof Boolean && (Boolean) args.get("confirm");

            Path target = AiFiles.resolveDbPath(path, true);
            if (Files.isDirectory(target)) {
                return "search_file: " + path + " 是目录，请用 search_content";
            }

            // 先统计所有匹配
            String lowerKW = keyword.toLowerCase();
            List<String> allMatches = new ArrayList<>();
            List<String> lines = Files.readAllLines(target);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase().contains(lowerKW)) {
                    String snippet = lines.get(i).trim();
                    if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                    allMatches.add("行" + (i + 1) + ": " + snippet);
                }
            }

            if (allMatches.isEmpty()) {
                return "文件搜索 \"" + keyword + "\" 在 " + target.getFileName() + " (共" + lines.size() + "行): 无匹配结果";
            }

            // 结果过多时二次确认
            if (!confirm && allMatches.size() > 200) {
                return "【警告】文件 " + target.getFileName() + " 中 \"" + keyword + "\" 匹配到 " + allMatches.size() + " 处（文件共" + lines.size() + "行），结果过多。\n" +
                       "确认继续请再次调用 search_file，带上 confirm=true。建议用更精确的关键词缩小范围。";
            }

            // 最多返回200处
            int maxShow = Math.min(200, allMatches.size());
            StringBuilder sb = new StringBuilder();
            sb.append(target.getFileName()).append("(").append(lines.size()).append("L) ").append(keyword).append("=").append(allMatches.size());
            if (allMatches.size() > maxShow) sb.append("(显").append(maxShow).append(")");
            sb.append(": ");
            for (int i = 0; i < maxShow; i++) {
                sb.append(allMatches.get(i)).append(" | ");
            }
            if (sb.length() > 2 && sb.substring(sb.length() - 2).equals("| ")) {
                sb.setLength(sb.length() - 2);
            }
            return sb.toString();
        } catch (SecurityException e) {
            return "search_file: " + e.getMessage();
        } catch (Exception e) {
            return "search_file: 错误 - " + e.getMessage();
        }
    }
}
