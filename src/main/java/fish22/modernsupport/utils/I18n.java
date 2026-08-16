/*
 * 翻译文件基于 Meteor-I18n-Support-plugin (CC0, 无需署名, 作者 kono_yalu)
 * 原作品: https://github.com/dingzhen-vape/Meteor-I18n-Support-plugin
 * 本实现改进了原插件: 支持独立语言设置、运行时切换、游戏目录动态加载语言文件
 */

package fish22.modernsupport.utils;

import fish22.modernsupport.utils.ModuleAccessor;
import fish22.modernsupport.utils.SettingAccessor;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Meteor 语言支持核心。
 *
 * 语言文件加载来源(合并, 后者覆盖前者):
 *  1. mod 内置资源 assets/meteor-modern-support/lang/<语言代码>.json (初始化兜底)
 *  2. 游戏目录 meteor-lang/<语言代码>/ 下所有 .json 文件 (动态加载, 供用户和其他插件使用)
 *
 * 键名规则:
 *  Module.<插件名>.<模块内部名>                -> 模块显示名
 *  Module.<插件名>.<模块内部名>.Description    -> 模块描述
 *  Setting.<插件名>.<设置内部名>               -> 设置显示名
 *  Setting.<插件名>.<设置内部名>.Description   -> 设置描述
 *  其中 <插件名> 为 "Meteor" (本体) 或各 addon 的显示名
 */
public class I18n {
    /** 游戏目录下的语言文件夹名 */
    public static final String LANG_FOLDER = "meteor-lang";
    /** 内置翻译资源所在命名空间 */
    public static final String NAMESPACE = "meteor-modern-support";
    /** 语言代码 -> 翻译键值表 (已合并内置 + 外部) */
    private static final Map<String, Map<String, String>> LANGS = new HashMap<>();
    /** 内置语言代码 (用于模板同步) */
    private static final Set<String> BUILTIN_LANGS = new LinkedHashSet<>();
    /** 内置模板原始内容 (语言代码 -> 字节), 用于对比同步外部主翻译文件 */
    private static final Map<String, byte[]> BUILTIN_RAW = new HashMap<>();
    /** 语言代码 -> 翻译键值表 (仅外部目录, 用于展示可用语言) */
    private static final Set<String> EXTERNAL_LANGS = new LinkedHashSet<>();

    /** 当前选择的语言代码, 空字符串 = 跟随 Minecraft 语言设置 */
    private static String selected = "";
    private static boolean initialized = false;

    private I18n() {}

    /** 启动时初始化: 加载内置 + 扫描外部目录 + 首次自动选定语言 (可重复调用) */
    public static void init() {
        if (initialized) return;
        initialized = true;
        LANGS.clear();
        EXTERNAL_LANGS.clear();
        loadBuiltin();
        syncTemplates();
        loadExternal();
        // 只在首次启动时自动选定语言 (与 Minecraft 匹配, 无匹配用英语);
        // reload (Reload 按钮) 时保留用户已手动选择的语言, 不覆盖
        if (selected.isEmpty()) {
            selected = suggestDefaultLang();
        }
        MeteorClient.LOG.info("I18n initialized: {} language(s) loaded, selected: {}", LANGS.size(), selected);
    }

    /** 重新加载所有语言文件 (外部目录变化后调用) */
    public static void reload() {
        initialized = false;
        init();
    }

    /** 重新加载语言文件并立即重新翻译全部界面 (其他插件向语言目录注入文件后调用) */
    public static void reloadAndApply() {
        reload();
        applyAll();
    }

    /** 当前实际生效的语言 (显示名) */
    public static String currentLangCode() {
        return selected.isEmpty() ? langNameFromCode(currentMinecraftLang()) : selected;
    }

    /** 建议的默认语言 (显示名): Minecraft 语言在可用语言列表内则选它, 否则 English 兜底 */
    public static String suggestDefaultLang() {
        if (!initialized) init();
        String name = langNameFromCode(currentMinecraftLang());
        return LANGS.containsKey(name) ? name : "English";
    }

    /** 读取 Minecraft 当前语言代码, 失败时回退 en_us */
    private static String currentMinecraftLang() {
        try {
            return Minecraft.getInstance().getLanguageManager().getSelected().toLowerCase();
        } catch (Exception e) {
            return "en_us";
        }
    }

    /**
     * 语言代码 → 显示名。内置资源文件名为代码 (zh_cn.json), MC 语言代码也是代码,
     * 统一转成显示名作为语言标识 (简体中文 / English)
     */
    public static String langNameFromCode(String code) {
        if (code == null) return "";
        String c = code.trim().toLowerCase();
        if (c.equals("zh_cn")) return "简体中文";
        if (c.equals("en_us")) return "English";
        return code.trim(); // 外部语言: 文件夹名/显示名原样
    }

    /** 设置语言并立即应用到所有模块/设置。每次切换都重新加载外部语言文件,
     *  保证修改 meteor-lang 下的文件后切一下语言即可生效 */
    public static void setLang(String name) {
        String n = (name == null ? "" : name.trim());
        if (n.equals(selected)) return;
        loadExternal();
        selected = n;
        applyAll();
    }

    /** 可用语言显示名列表: 内置 (简体中文/English) + 实时扫描游戏目录语言文件夹 (去重) */
    public static List<String> availableLangs() {
        if (!initialized) init();
        List<String> list = new ArrayList<>(LANGS.keySet());

        // 实时扫描外部文件夹, 运行中新增的语言文件夹也会出现在列表 (文件夹名即语言名, 不映射)
        try {
            Path langDir = FabricLoader.getInstance().getGameDir().resolve(LANG_FOLDER);
            if (Files.isDirectory(langDir)) {
                try (DirectoryStream<Path> dirs = Files.newDirectoryStream(langDir)) {
                    for (Path dir : dirs) {
                        if (Files.isDirectory(dir)) {
                            String name = dir.getFileName().toString();
                            if (!list.contains(name)) list.add(name);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }

    /** 查询当前语言下的翻译, 查不到返回 fallback */
    public static String get(String key, String fallback) {
        Map<String, String> map = LANGS.get(currentLangCode());
        if (map != null) {
            String value = map.get(key);
            if (value != null) return value;
        }
        return fallback;
    }

    /** 按模块归属计算翻译前缀: meteor 本体 addon 名 = "Meteor Client", 其他 addon 用各自显示名 */
    public static String modulePrefix(Module module) {
        if (module.addon == null) return "Meteor";
        return module.addon.name;
    }

    /** 翻译单个模块的 title/description (含其所有设置) */
    public static void applyModule(Module module) {
        if (!initialized) init();
        String prefix = modulePrefix(module);
        String key = "Module." + prefix + "." + module.name;

        ModuleAccessor accessor = (ModuleAccessor) (Object) module;
        accessor.setTitle(get(key, accessor.getOriginalTitle()));
        accessor.setDescription(get(key + ".Description", accessor.getOriginalDescription()));

        // 设置项按模块归属翻译
        for (SettingGroup group : module.settings) {
            for (Setting<?> setting : group) {
                applySetting(module, setting);
            }
        }
    }

    /** 翻译单个设置项 (前缀取自所属模块; 非模块设置如 Config 属于 meteor 基础设置, 用 "Meteor") */
    public static void applySetting(Module owner, Setting<?> setting) {
        String prefix = owner == null ? "Meteor" : modulePrefix(owner);
        String key = "Setting." + prefix + "." + setting.name;

        SettingAccessor accessor = (SettingAccessor) (Object) setting;
        accessor.setTitle(get(key, accessor.getOriginalTitle()));
        accessor.setDescription(get(key + ".Description", accessor.getOriginalDescription()));
    }

    /** 全量重翻译: 遍历所有模块及设置 + Meteor 设置主界面 (Config) 的设置 (语言切换/配置加载时调用) */
    public static void applyAll() {
        if (!initialized) init();
        for (Module module : Modules.get().getAll()) {
            applyModule(module);
        }
        applyConfigSettings();
    }

    /** 翻译 Meteor 设置主界面 (Config) 的非模块设置, 前缀固定为 Meteor */
    public static void applyConfigSettings() {
        try {
            Config config = Config.get();
            if (config == null) return;
            for (SettingGroup group : config.settings) {
                for (Setting<?> setting : group) {
                    applySetting(null, setting);
                }
            }
        } catch (Exception e) {
            MeteorClient.LOG.error("Failed to apply translations to Config settings", e);
        }
    }

    /**
     * 同步外部主翻译模板: 确保游戏目录 meteor-lang/<代码>/meteor.json 与内置一致。
     * 每次启动/重载都对比, 不同才替换 (主文件基本没人动, 别人都是新建自己的 JSON,
     * 所以自动同步是对的; 用户自定义翻译请新建文件, 别改 meteor.json)
     */
    private static void syncTemplates() {
        try {
            Path langDir = FabricLoader.getInstance().getGameDir().resolve(LANG_FOLDER);
            Files.createDirectories(langDir);

            for (Map.Entry<String, byte[]> entry : BUILTIN_RAW.entrySet()) {
                Path sub = langDir.resolve(entry.getKey());
                Files.createDirectories(sub);
                Path target = sub.resolve("meteor.json");

                byte[] builtin = entry.getValue();
                if (Files.exists(target)) {
                    byte[] current = Files.readAllBytes(target);
                    if (Arrays.equals(current, builtin)) continue; // 内容一致, 不动
                }
                Files.write(target, builtin);
                MeteorClient.LOG.info("Synced lang template {}", target);
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to sync lang templates", e);
        }
    }

    /**
     * 加载 mod 内置的翻译资源 (初始化兜底)。
     * 用 Fabric 官方 ModContainer API 枚举 jar 内资源, 不依赖 getResource(目录)
     * (生产环境对 jar 目录请求返回 null, 会导致内置翻译加载失败)
     */
    private static void loadBuiltin() {
        try {
            java.util.Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(NAMESPACE);
            if (container.isEmpty()) return;

            for (Path root : container.get().getRootPaths()) {
                Path langDir = root.resolve("assets/" + NAMESPACE + "/lang");
                if (!Files.isDirectory(langDir)) continue;

                try (DirectoryStream<Path> files = Files.newDirectoryStream(langDir, "*.json")) {
                    for (Path file : files) {
                        String code = file.getFileName().toString().replace(".json", "");
                        String name = langNameFromCode(code); // 统一用显示名作为语言标识
                        BUILTIN_LANGS.add(name);
                        try {
                            byte[] raw = Files.readAllBytes(file);
                            BUILTIN_RAW.put(name, raw);
                            mergeInto(name, new java.io.ByteArrayInputStream(raw));
                        } catch (IOException e) {
                            MeteorClient.LOG.error("Failed to load builtin lang file {}", file, e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to load builtin lang files", e);
        }
    }

    /** 扫描游戏目录 meteor-lang/ 下所有语言文件夹, 文件夹名 = 语言显示名, 内部所有 .json 合并 */
    private static void loadExternal() {
        try {
            Path langDir = FabricLoader.getInstance().getGameDir().resolve(LANG_FOLDER);
            if (!Files.isDirectory(langDir)) return;

            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(langDir)) {
                for (Path dir : dirs) {
                    if (!Files.isDirectory(dir)) continue;
                    // 文件夹名即语言名 (不映射旧代码)
                    String name = dir.getFileName().toString();
                    EXTERNAL_LANGS.add(name);

                    // 文件夹内所有 .json 按文件名排序后依次合并 (后面覆盖前面)
                    List<Path> jsons = new ArrayList<>();
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                        for (Path file : files) jsons.add(file);
                    }
                    jsons.sort(Path::compareTo);

                    for (Path file : jsons) {
                        try (InputStream stream = Files.newInputStream(file)) {
                            mergeInto(name, stream);
                            MeteorClient.LOG.info("Loaded lang file {} ({})", file, name);
                        } catch (IOException e) {
                            MeteorClient.LOG.error("Failed to load lang file {}", file, e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to scan lang folder {}", LANG_FOLDER, e);
        }
    }

    /** 把 JSON 语言文件内容合并进对应语言的键值表 (外部覆盖内置) */
    private static void mergeInto(String code, InputStream stream) {
        Map<String, String> map = LANGS.computeIfAbsent(code, c -> new HashMap<>());
        // 用 MC 原生的解析器, 与游戏语言文件同格式, 自动处理占位符转换
        Language.loadFromJson(stream, map::put);
    }
}
