package fish22.modernsupport.utils;

import fish22.modernsupport.ModernSupport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 配置自动保存
 *
 * <p>Meteor 默认只在游戏正常退出（JVM 关闭钩子）时保存配置，
 * 强退/崩溃会丢失配置。本工具在设置修改、模块开关变化时
 * 异步防抖保存模块配置，不阻塞主线程。
 */
public class AutoSave {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Meteor-AutoSave");
        thread.setDaemon(true);
        return thread;
    });

    /** 是否处于配置加载中（加载期间禁止保存，避免覆盖磁盘配置） */
    private static volatile boolean loading = false;

    /** 配置加载开始时间戳（超时保护：Modules.load 抛异常时 RETURN 注入不执行，防止 loading 永久为 true） */
    private static volatile long loadingSince = 0;

    /** 配置加载超时时间（毫秒）：超过视为加载结束，恢复自动保存 */
    private static final long LOAD_TIMEOUT_MS = 5000;

    /** 是否有待保存的修改 */
    private static volatile boolean dirty = false;

    /** 是否已有保存任务在跑（用于合并高频修改） */
    private static volatile boolean scheduled = false;

    /** 保存时机（Config 页面设置，默认每次变化保存） */
    private static volatile ConfigSaveMode mode = ConfigSaveMode.OnChange;

    private AutoSave() {
    }

    /** 设置保存时机（Config 页面的「配置保存」设置变化时调用，加载配置时也会走这里） */
    public static void setMode(ConfigSaveMode value) {
        mode = value == null ? ConfigSaveMode.OnChange : value;
    }

    /** 设置配置加载状态（Meteor 加载模块配置期间禁止保存） */
    public static void setLoading(boolean value) {
        loading = value;
        loadingSince = value ? System.currentTimeMillis() : 0;
    }

    /** 是否处于配置加载中（含超时保护：加载标志异常残留时自动失效） */
    private static boolean isLoading() {
        if (!loading) return false;
        // 加载超过时限仍没结束 → 视为加载异常中断（如配置损坏抛异常），自动复位，恢复自动保存
        if (System.currentTimeMillis() - loadingSince > LOAD_TIMEOUT_MS) {
            loading = false;
            loadingSince = 0;
            return false;
        }
        return true;
    }

    /** 配置发生变化，安排异步保存（可被高频调用，自动合并） */
    public static void onChanged() {
        // 配置加载期间不保存：此时保存会把加载中的默认值覆盖到磁盘，导致配置丢失
        if (isLoading()) return;
        // 启动配置还没套用（config 文件夹的配置块没读完）之前禁止写盘，否则会把默认值盖到配置块上
        if (!ModuleConfigs.ready()) return;
        // 退出时保存：运行期间不写盘，交给 JVM 关闭钩子
        if (mode == ConfigSaveMode.OnExit) return;

        dirty = true;
        if (scheduled) return;
        scheduled = true;

        EXECUTOR.execute(() -> {
            try {
                // 防抖：等待一小段时间，合并短时间内的多次修改（如拖动滑块）
                Thread.sleep(300);

                // 循环保存直到没有新修改
                while (dirty) {
                    dirty = false;
                    saveAll();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                scheduled = false;
                // 保存期间又产生了新修改，补一次
                if (dirty) {
                    onChanged();
                }
            }
        });
    }

    /** 退出时保存：把当前勾选的配置块写回磁盘（JVM 关闭钩子调用，两种模式都会走） */
    public static void saveOnExit() {
        if (!ModuleConfigs.ready()) return;
        try {
            ModuleConfigs.saveCurrent();
        } catch (Exception e) {
            ModernSupport.LOG.error("退出时保存配置失败", e);
        }
    }

    private static void saveAll() {
        try {
            // 只写当前勾选的配置块（meteor-client/config/<配置名>.nbt）
            // modules.nbt 已经由 MixinSystem 拦掉，不再写
            ModuleConfigs.saveCurrent();
        } catch (Exception e) {
            ModernSupport.LOG.error("自动保存配置失败", e);
        }
    }
}
