package fish22.modernsupport.utils;

import fish22.modernsupport.ModernSupport;
import meteordevelopment.meteorclient.systems.modules.Modules;

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

    /** 是否有待保存的修改 */
    private static volatile boolean dirty = false;

    /** 是否已有保存任务在跑（用于合并高频修改） */
    private static volatile boolean scheduled = false;

    private AutoSave() {
    }

    /** 设置配置加载状态（Meteor 加载模块配置期间禁止保存） */
    public static void setLoading(boolean value) {
        loading = value;
    }

    /** 配置发生变化，安排异步保存（可被高频调用，自动合并） */
    public static void onChanged() {
        // 配置加载期间不保存：此时保存会把加载中的默认值覆盖到磁盘，导致配置丢失
        if (loading) return;

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
                    saveModules();
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

    private static void saveModules() {
        try {
            Modules.get().save();
        } catch (Exception e) {
            ModernSupport.LOG.error("自动保存模块配置失败", e);
        }
    }
}
