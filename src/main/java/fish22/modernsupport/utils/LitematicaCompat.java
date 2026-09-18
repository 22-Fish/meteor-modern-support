/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 22_Fish
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fish22.modernsupport.utils;

import fish22.modernsupport.ModernSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 投影模组（Litematica）软兼容层。
 *
 * <p>本 mod 不在 {@code fabric.mod.json} / 构建脚本里写死投影前置：没有安装投影时本 mod 仍然
 * 可以正常加载，只是「简单放置」的投影相关功能不可用。所有对投影的访问都通过反射完成，
 * 因此这里只依赖原版和 Meteor 的类。
 *
 * <p>需要投影侧提供的接口（不同投影版本的类名基本一致，方法名可能略有变化，全部按需探测）：
 * <ul>
 *   <li>{@code fi.dy.masa.litematica.world.SchematicWorldHandler#getSchematicWorld()}</li>
 *   <li>{@code fi.dy.masa.litematica.util.RayTraceUtils#getGenericTrace(Level, Entity, double)}</li>
 *   <li>{@code fi.dy.masa.litematica.util.EasyPlaceUtils#handleEasyPlaceWithMessage()}</li>
 *   <li>{@code fi.dy.masa.litematica.materials.MaterialCache#getRequiredBuildItemForState(BlockState)}
 *       （拿不到时退回 {@link Item} 的 {@code asItem()}）</li>
 *   <li>{@code fi.dy.masa.litematica.data.DataManager#getRenderLayerRange()}
 *       （判断某个位置投影到底渲染没渲染）</li>
 *   <li>{@code fi.dy.masa.litematica.config.Configs.Generic#EASY_PLACE_PROTOCOL}
 *       和 {@code EasyPlaceProtocol#fromStringStatic(String)}
 *       （把投影的精准放置协议临时切到模块选的那一档）</li>
 * </ul>
 */
public final class LitematicaCompat {
    private static final String SCHEMATIC_WORLD_HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler";
    private static final String RAY_TRACE_UTILS = "fi.dy.masa.litematica.util.RayTraceUtils";
    private static final String EASY_PLACE_UTILS = "fi.dy.masa.litematica.util.EasyPlaceUtils";
    private static final String MATERIAL_CACHE = "fi.dy.masa.litematica.materials.MaterialCache";
    private static final String DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager";
    private static final String CONFIGS_GENERIC = "fi.dy.masa.litematica.config.Configs$Generic";
    private static final String EASY_PLACE_PROTOCOL_ENUM = "fi.dy.masa.litematica.util.EasyPlaceProtocol";

    private static boolean resolved;
    private static boolean available;

    private static Method getSchematicWorld;
    private static Method genericTrace3;
    private static Method genericTrace6;
    private static Method easyPlaceWithMessage;
    private static Method materialCacheInstance;
    private static Method materialCacheRequiredItem;

    /** 投影「渲染层范围」——投影自己画方块、自己做射线追踪都是按这个范围过滤的 */
    private static Method getRenderLayerRange;
    private static boolean layerRangeResolved;
    private static Method layerRangeIsWithinPos;
    private static Method layerRangeIsWithinXYZ;

    /** 投影的「轻松放置 - 协议版本」配置项（malilib 的 ConfigOptionList） */
    private static Object easyPlaceProtocolConfig;
    private static Method optionListGetValue;
    private static Method optionListSetValue;
    private static Method protocolFromString;

    /** 已经确认投影支持的协议名 → 枚举项；{@link #UNSUPPORTED_PROTOCOLS} 里的是投影不认的 */
    private static final Map<String, Object> SUPPORTED_PROTOCOLS = new HashMap<>();
    private static final Set<String> UNSUPPORTED_PROTOCOLS = new HashSet<>();

    /** 已经打过日志的失败点，避免每 tick 刷屏 */
    private static final Set<String> LOGGED_FAILURES = new HashSet<>();

    private LitematicaCompat() {
    }

    /** 一次投影射线命中的结果 */
    public record TargetHit(HitResult hit, boolean schematic) {
    }

    public static boolean isAvailable() {
        resolve();
        return available;
    }

    /** 投影自带的「轻松放置」入口是否可用（类名/方法名对得上） */
    public static boolean isOriginalEasyPlaceAvailable() {
        return isAvailable() && easyPlaceWithMessage != null;
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;

        try {
            Class<?> handler = Class.forName(SCHEMATIC_WORLD_HANDLER);
            getSchematicWorld = handler.getMethod("getSchematicWorld");
            available = true;
        } catch (Throwable t) {
            available = false;
            ModernSupport.LOG.warn("[简单放置] 未找到可用的投影模组(Litematica)接口，投影相关模式不可用: {}", t.toString());
            return;
        }

        try {
            Class<?> rayTrace = Class.forName(RAY_TRACE_UTILS);
            try {
                genericTrace3 = rayTrace.getMethod("getGenericTrace", Level.class, Entity.class, double.class);
            } catch (NoSuchMethodException ignored) {
                // 老版本可能只有带更多开关的重载，下面继续找
            }
            try {
                genericTrace6 = rayTrace.getMethod("getGenericTrace",
                    Level.class, Entity.class, double.class,
                    boolean.class, boolean.class, boolean.class);
            } catch (NoSuchMethodException ignored) {
                // 找不到就靠 getSchematicWorld 自己做原版射线兜底
            }
        } catch (Throwable t) {
            ModernSupport.LOG.warn("[简单放置] 投影射线追踪接口不可用，将退回原版准星判定: {}", t.toString());
        }

        try {
            Class<?> easyPlace = Class.forName(EASY_PLACE_UTILS);
            try {
                easyPlaceWithMessage = easyPlace.getMethod("handleEasyPlaceWithMessage");
            } catch (NoSuchMethodException ignored) {
                // 没有这个接口时「投影原有模式」会拒绝工作，但其它两个模式不受影响
            }
        } catch (Throwable t) {
            ModernSupport.LOG.warn("[简单放置] 投影轻松放置接口不可用，「投影原有模式」将不可用: {}", t.toString());
        }

        try {
            Class<?> materialCache = Class.forName(MATERIAL_CACHE);
            materialCacheInstance = materialCache.getMethod("getInstance");
            materialCacheRequiredItem = materialCache.getMethod("getRequiredBuildItemForState", BlockState.class);
        } catch (Throwable ignored) {
            // MaterialCache 是可选优化，失败不影响其它功能
        }

        try {
            getRenderLayerRange = Class.forName(DATA_MANAGER).getMethod("getRenderLayerRange");
        } catch (Throwable ignored) {
            // 没有这个接口的版本就不做渲染层过滤（当作全部渲染）
        }

        try {
            Object config = Class.forName(CONFIGS_GENERIC).getField("EASY_PLACE_PROTOCOL").get(null);
            Method getter = config.getClass().getMethod("getOptionListValue");
            optionListGetValue = getter;
            optionListSetValue = config.getClass().getMethod("setOptionListValue", getter.getReturnType());
            protocolFromString = Class.forName(EASY_PLACE_PROTOCOL_ENUM).getMethod("fromStringStatic", String.class);
            easyPlaceProtocolConfig = config;
        } catch (Throwable ignored) {
            // 老版本没有这个配置项时，「投影原有模式」照样用投影自己的协议设置
        }
    }

    /** 当前加载的投影世界（没有投影或没加载投影时返回 null） */
    public static Level getSchematicWorld() {
        if (!isAvailable() || getSchematicWorld == null) return null;

        try {
            Object world = getSchematicWorld.invoke(null);
            return world instanceof Level level ? level : null;
        } catch (Throwable t) {
            logFailure("getSchematicWorld", t);
            return null;
        }
    }

    /** 投影世界在某个位置的方块状态（没有投影时为 null） */
    public static BlockState getSchematicState(BlockPos pos) {
        Level schematic = getSchematicWorld();
        if (schematic == null || pos == null) return null;
        return schematic.getBlockState(pos);
    }

    /**
     * 用投影自己的射线追踪找准星指向的方块。
     *
     * <p>投影侧的 {@code getGenericTrace} 会同时考虑投影方块和原版方块，并返回命中类型；
     * 拿到 {@code SCHEMATIC_BLOCK} 就说明准星点的是投影幽灵方块，否则是原版方块。
     * 找不到可用接口时返回 {@code null}，由调用方走原版 {@code mc.hitResult} 兜底。
     */
    public static TargetHit getTargetHit(double reach) {
        if (!isAvailable() || mc.level == null || mc.player == null) return null;

        if (genericTrace3 != null) {
            TargetHit hit = invokeGenericTrace(genericTrace3, reach);
            if (hit != null) return hit;
        }

        if (genericTrace6 != null) {
            TargetHit hit = invokeGenericTrace(genericTrace6, reach);
            if (hit != null) return hit;
        }

        // 最后兜底：直接对投影世界做原版射线（能命中投影方块，但拿不到投影侧的优先级处理）
        Level schematic = getSchematicWorld();
        if (schematic != null) {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 end = eye.add(mc.player.calculateViewVector(mc.player.getXRot(), mc.player.getYRot()).scale(reach));
            BlockHitResult hit = schematic.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player
            ));
            if (hit.getType() == HitResult.Type.BLOCK) return new TargetHit(hit, true);
        }

        return null;
    }

    private static TargetHit invokeGenericTrace(Method method, double reach) {
        try {
            Object wrapper;
            if (method.getParameterCount() == 3) {
                wrapper = method.invoke(null, mc.level, mc.player, reach);
            } else {
                wrapper = method.invoke(null, mc.level, mc.player, reach, true, false, false);
            }
            return toTargetHit(wrapper);
        } catch (Throwable t) {
            logFailure("getGenericTrace", t);
            return null;
        }
    }

    private static TargetHit toTargetHit(Object wrapper) throws ReflectiveOperationException {
        if (wrapper == null) return null;

        Object type = invokeNoArg(wrapper, "getHitType");
        if (type == null) return null;
        if (type.toString().contains("MISS") || type.toString().contains("ENTITY")) return null;

        Object hit = invokeNoArg(wrapper, "getBlockHitResult", "getRayTraceResult", "getHitResult");
        if (!(hit instanceof HitResult result)) return null;
        if (result.getType() != HitResult.Type.BLOCK) return null;

        return new TargetHit(result, type.toString().contains("SCHEMATIC"));
    }

    private static Object invokeNoArg(Object target, String... methodNames) throws ReflectiveOperationException {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // 继续试下一个名字
            }
        }
        return null;
    }

    /** 投影状态下这个位置应该用哪个物品来放（拿不到投影 MaterialCache 时退回方块物品） */
    public static ItemStack getRequiredItem(BlockState state) {
        if (state == null) return ItemStack.EMPTY;

        if (materialCacheInstance != null && materialCacheRequiredItem != null) {
            try {
                Object cache = materialCacheInstance.invoke(null);
                Object stack = materialCacheRequiredItem.invoke(cache, state);
                if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) return itemStack;
            } catch (Throwable t) {
                logFailure("getRequiredBuildItemForState", t);
            }
        }

        Item item = state.getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : item.getDefaultInstance();
    }

    /**
     * 投影当前有没有渲染这个位置。
     *
     * <p>投影的「渲染层范围」之外的方块不画出来、投影自己的射线也打不到，所以模块也不应该去放；
     * 这里用的就是投影那个范围，和投影的画面对得上。
     *
     * @return 没装投影、接口探测失败或拿不到范围时返回 {@code true}（不过滤，保持原有行为）
     */
    public static boolean isPositionRendered(BlockPos pos) {
        if (pos == null || !isAvailable() || getRenderLayerRange == null) return true;

        try {
            Object range = getRenderLayerRange.invoke(null);
            if (range == null) return true;

            Method check = resolveLayerRangeCheck(range.getClass());
            if (check == null) return true;

            Object result = check.getParameterCount() == 1
                ? check.invoke(range, pos)
                : check.invoke(range, pos.getX(), pos.getY(), pos.getZ());

            return !(result instanceof Boolean value) || value;
        } catch (Throwable t) {
            logFailure("getRenderLayerRange", t);
            return true;
        }
    }

    private static Method resolveLayerRangeCheck(Class<?> rangeClass) {
        if (!layerRangeResolved) {
            layerRangeResolved = true;

            try {
                layerRangeIsWithinPos = rangeClass.getMethod("isPositionWithinRange", BlockPos.class);
            } catch (NoSuchMethodException ignored) {
                // 继续找 (x, y, z) 重载
            }

            if (layerRangeIsWithinPos == null) {
                try {
                    layerRangeIsWithinXYZ = rangeClass.getMethod("isPositionWithinRange", int.class, int.class, int.class);
                } catch (NoSuchMethodException ignored) {
                    // 两个都没有就不按渲染层过滤
                }
            }
        }

        return layerRangeIsWithinPos != null ? layerRangeIsWithinPos : layerRangeIsWithinXYZ;
    }

    /** 投影认不认这几个候选协议名（按顺序取第一个认的） */
    public static boolean isProtocolAvailable(String... configValues) {
        return resolveProtocol(configValues) != null;
    }

    /**
     * 把协议名（投影配置里的写法，例如 {@code auto} / {@code v3} / {@code v2} /
     * {@code slabs_only} / {@code none}）翻成投影自己的枚举项。
     *
     * <p>投影的 {@code fromStringStatic} 认不出来的时候会回退到某个档位，所以这里必须把拿回来的
     * 名字再核对一遍，确认真的支持才用。
     */
    private static Object resolveProtocol(String... configValues) {
        if (easyPlaceProtocolConfig == null || protocolFromString == null) return null;

        for (String value : configValues) {
            Object cached = SUPPORTED_PROTOCOLS.get(value);
            if (cached != null) return cached;
            if (UNSUPPORTED_PROTOCOLS.contains(value)) continue;

            try {
                Object entry = protocolFromString.invoke(null, value);
                Object name = entry == null ? null : entry.getClass().getMethod("getStringValue").invoke(entry);

                if (name instanceof String text && text.equalsIgnoreCase(value)) {
                    SUPPORTED_PROTOCOLS.put(value, entry);
                    return entry;
                }
            } catch (Throwable t) {
                logFailure("EasyPlaceProtocol.fromStringStatic", t);
                return null;
            }

            UNSUPPORTED_PROTOCOLS.add(value);
        }

        return null;
    }

    /**
     * 调用投影自带的「轻松放置」逻辑（投影原有模式）。
     *
     * <p>这个调用会走投影自己的拾取方块、半砖处理、精准放置协议和放置限制检查；本 mod 不重复实现这些细节。
     *
     * <p>{@code preferredProtocols} 是模块选的那一档协议（按投影配置里的写法，例如 {@code v2}）。
     * 本次调用期间会把投影的协议临时切过去，调用结束后还原成投影原来的设置；投影不支持这个协议
     * （或者老版本没有这个配置项）就直接用投影自己的设置放。
     */
    public static boolean runOriginalEasyPlace(String... preferredProtocols) {
        if (!isAvailable() || easyPlaceWithMessage == null) return false;

        Object previous = null;
        boolean overridden = false;

        Object protocol = preferredProtocols.length == 0 ? null : resolveProtocol(preferredProtocols);
        if (protocol != null && optionListGetValue != null && optionListSetValue != null) {
            try {
                previous = optionListGetValue.invoke(easyPlaceProtocolConfig);
                optionListSetValue.invoke(easyPlaceProtocolConfig, protocol);
                overridden = true;
            } catch (Throwable t) {
                logFailure("easyPlaceProtocolVersion", t);
                overridden = false;
            }
        }

        try {
            Object result = easyPlaceWithMessage.invoke(null);
            return result instanceof Boolean value && value;
        } catch (Throwable t) {
            logFailure("handleEasyPlaceWithMessage", t);
            return false;
        } finally {
            if (overridden) {
                try {
                    optionListSetValue.invoke(easyPlaceProtocolConfig, previous);
                } catch (Throwable t) {
                    logFailure("easyPlaceProtocolVersion 还原", t);
                }
            }
        }
    }

    private static void logFailure(String key, Throwable t) {
        if (LOGGED_FAILURES.add(key)) {
            ModernSupport.LOG.warn("[简单放置] 调用投影接口 {} 失败，后续不再重复提示: {}", key, t.toString());
        }
    }
}
