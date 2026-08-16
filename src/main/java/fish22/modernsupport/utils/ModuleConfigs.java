package fish22.modernsupport.utils;

import fish22.modernsupport.ModernSupport;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块配置分块管理：meteor-client/config/ 目录下按配置名存放模块设置快照。
 * 配置全部保存在 config 文件夹里，读取/应用都从 config 文件夹进行
 * （启动时也从勾选的配置加载，不使用 modules.nbt 里的旧配置）。
 *
 * <p>勾选机制：配置列表单选，勾选即应用（先保存当前 → 加载目标）。
 * 启动时若一个都没勾选（未初始化），自动勾选第一个。
 * 手动放入 config 文件夹的 .nbt 文件也会出现在列表，可直接勾选。
 * 每个配置可配置「自动应用」的服务器列表：连接匹配的服务器时自动切换。
 */
public class ModuleConfigs {

    /** 配置存放目录（meteor 配置文件夹下的 config 子目录） */
    public static final File FOLDER = new File(MeteorClient.FOLDER, "config");

    /** 元数据文件：勾选的配置 + 各配置的自动应用/服务器列表 */
    private static final File META_FILE = new File(FOLDER, "meta.nbt");

    /** 默认配置名（首次使用时创建） */
    private static final String DEFAULT_NAME = "mainconfig";

    /** 元数据缓存 */
    private static CompoundTag meta;

    private ModuleConfigs() {
    }

    /** 初始化（ModernSupport.onInitialize 调用）：确保目录/默认配置，勾选并加载配置 */
    public static void init() {
        FOLDER.mkdirs();
        if (list().isEmpty() && !META_FILE.exists()) {
            // 首次使用：创建默认配置（复制当前模块设置）
            create(DEFAULT_NAME);
        }
        loadMeta();

        // 启动时一个都没勾选 → 未初始化，自动勾选第一个
        if (selected() == null) {
            List<String> names = list();
            if (!names.isEmpty()) {
                meta.putString("selected", names.get(0));
                saveMeta();
            }
        }

        // 启动加载：从 config 文件夹里勾选的配置读取，而不是 modules.nbt（旧配置）
        if (selected() != null) {
            applyFrom(selected());
        }
    }

    // ====== 配置列表/勾选 ======

    /** 所有配置名（扫描 config 文件夹 .nbt 文件，手动放入的也会出现） */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        File[] files = FOLDER.listFiles((dir, name) -> name.endsWith(".nbt") && !name.equals("meta.nbt"));
        if (files == null) return names;
        for (File file : files) {
            names.add(file.getName().substring(0, file.getName().length() - 4));
        }
        names.sort(String::compareTo);
        return names;
    }

    /** 当前勾选（应用）的配置名；未勾选返回 null */
    public static String selected() {
        if (meta == null) return null;
        String name = meta.getStringOr("selected", "");
        if (name.isEmpty()) {
            name = meta.getStringOr("current", ""); // 兼容旧版元数据
        }
        return !name.isEmpty() && exists(name) ? name : null;
    }

    /** 勾选配置（单选）：勾选即应用（保存当前 → 加载目标） */
    public static void select(String name) {
        if (!exists(name)) return;
        saveCurrent();
        applyFrom(name);
        meta.putString("selected", name);
        saveMeta();
    }

    /** 兼容旧调用：勾选即应用 */
    public static void apply(String name) {
        select(name);
    }

    /** 保存当前模块设置到当前勾选的配置 */
    public static void saveCurrent() {
        if (selected() != null) {
            writeTag(fileOf(selected()), Modules.get().toTag());
        }
    }

    /** 从配置文件加载应用到模块系统（只影响模块设置） */
    private static void applyFrom(String name) {
        CompoundTag tag = readTag(fileOf(name));
        if (tag == null) return;
        Modules.get().fromTag(tag);
    }

    // ====== 增删改 ======

    /** 新建配置（复制当前模块设置） */
    public static void create(String name) {
        if (!validName(name) || exists(name)) return;
        writeTag(fileOf(name), Modules.get().toTag());
        ensureMetaEntry(name);
        saveMeta();
    }

    /** 删除配置（至少保留一个）；删的是勾选配置时自动勾选剩余第一个 */
    public static void delete(String name) {
        if (list().size() <= 1) return;
        File file = fileOf(name);
        if (file.exists()) file.delete();
        if (name.equals(meta.getStringOr("selected", ""))) {
            meta.putString("selected", "");
        }
        removeMetaEntry(name);
        saveMeta();
        // 勾选的被删了 → 自动勾选剩余第一个（保持始终有一个勾选）
        if (selected() == null) {
            List<String> remaining = list();
            if (!remaining.isEmpty()) {
                meta.putString("selected", remaining.get(0));
                saveMeta();
            }
        }
    }

    /** 重命名配置 */
    public static void rename(String oldName, String newName) {
        if (!exists(oldName) || !validName(newName) || exists(newName)) return;
        if (fileOf(oldName).renameTo(fileOf(newName))) {
            CompoundTag entry = getMetaEntry(oldName);
            if (entry != null) entry.putString("name", newName);
            if (oldName.equals(meta.getStringOr("selected", ""))) {
                meta.putString("selected", newName);
            }
            saveMeta();
        }
    }

    /** 配置名是否合法（非空、无路径字符） */
    private static boolean validName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        return !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    private static boolean exists(String name) {
        return fileOf(name).exists();
    }

    private static File fileOf(String name) {
        return new File(FOLDER, name + ".nbt");
    }

    // ====== 元数据（自动应用 + 服务器列表） ======

    /** 配置是否开启自动应用 */
    public static boolean autoApplyEnabled(String name) {
        CompoundTag entry = getMetaEntry(name);
        return entry != null && entry.getBooleanOr("autoApply", false);
    }

    /** 设置自动应用开关 */
    public static void setAutoApply(String name, boolean enabled) {
        ensureMetaEntry(name).putBoolean("autoApply", enabled);
        saveMeta();
    }

    /** 配置的服务器列表 */
    public static List<String> servers(String name) {
        List<String> result = new ArrayList<>();
        CompoundTag entry = getMetaEntry(name);
        if (entry == null) return result;
        for (Tag tag : entry.getListOrEmpty("servers")) {
            result.add(((CompoundTag) tag).getStringOr("url", ""));
        }
        return result;
    }

    /** 设置服务器列表（整表替换） */
    public static void setServers(String name, List<String> servers) {
        CompoundTag entry = ensureMetaEntry(name);
        ListTag list = new ListTag();
        for (String url : servers) {
            CompoundTag item = new CompoundTag();
            item.putString("url", url);
            list.add(item);
        }
        entry.put("servers", list);
        saveMeta();
    }

    // ====== 服务器自动应用 ======

    /** 进入世界/服务器后：匹配各配置的服务器列表，命中则自动切换（服务器地址包含配置的 url 即匹配） */
    public static void onGameJoin() {
        if (MeteorClient.mc.getCurrentServer() == null) return;
        String host = MeteorClient.mc.getCurrentServer().ip;
        for (String name : list()) {
            if (!autoApplyEnabled(name)) continue;
            for (String url : servers(name)) {
                if (url.isEmpty()) continue;
                if (host.contains(url)) {
                    if (!name.equals(selected())) {
                        select(name);
                    }
                    return;
                }
            }
        }
    }

    // ====== 内部：meta 读写 ======

    private static void loadMeta() {
        meta = readTag(META_FILE);
        if (meta == null) {
            meta = new CompoundTag();
            meta.putString("selected", "");
            ensureMetaEntry(DEFAULT_NAME);
        }
    }

    private static void saveMeta() {
        if (meta != null) writeTag(META_FILE, meta);
    }

    private static CompoundTag ensureMetaEntry(String name) {
        if (meta == null) loadMeta();
        CompoundTag entry = getMetaEntry(name);
        if (entry == null) {
            entry = new CompoundTag();
            entry.putString("name", name);
            entry.putBoolean("autoApply", false);
            entry.put("servers", new ListTag());
            ListTag configs = meta.getListOrEmpty("configs");
            configs.add(entry);
            meta.put("configs", configs);
        }
        return entry;
    }

    private static CompoundTag getMetaEntry(String name) {
        if (meta == null) loadMeta();
        for (Tag tag : meta.getListOrEmpty("configs")) {
            CompoundTag entry = (CompoundTag) tag;
            if (entry.getStringOr("name", "").equals(name)) return entry;
        }
        return null;
    }

    private static void removeMetaEntry(String name) {
        if (meta == null) return;
        ListTag configs = meta.getListOrEmpty("configs");
        for (int i = 0; i < configs.size(); i++) {
            CompoundTag entry = configs.getCompound(i).orElse(null);
            if (entry != null && entry.getStringOr("name", "").equals(name)) {
                configs.remove(i);
                break;
            }
        }
        meta.put("configs", configs);
    }

    // ====== NBT 文件读写 ======

    private static CompoundTag readTag(File file) {
        if (!file.exists()) return null;
        try {
            return NbtIo.read(file.toPath());
        } catch (IOException e) {
            ModernSupport.LOG.error("[配置分块] 读取失败 {}", file, e);
            return null;
        }
    }

    private static void writeTag(File file, CompoundTag tag) {
        try {
            NbtIo.write(tag, file.toPath());
        } catch (IOException e) {
            ModernSupport.LOG.error("[配置分块] 写入失败 {}", file, e);
        }
    }
}
