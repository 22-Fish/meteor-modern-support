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

package fish22.modernsupport.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体列表 — 在屏幕上列出周围的实体和掉落物
 *
 * <p>「显示实体」决定哪些实体进入列表，默认是全部实体类型，并且不分维度，
 * 所以服务器用自定义维度名也照样显示；
 * 「主世界 / 下界 / 末地 高亮实体」是各维度想重点关注的类型，
 * 高亮实体用「高亮实体颜色」显示，带最近距离，并排在列表最前面
 * （未识别的自定义维度按主世界处理；不高亮的实体只显示数量）。
 *
 * <p>掉落物按「物品1 / 物品2 / 其他」三档分类，汇总同类物品的总数量：
 * 「物品1」「物品2」显示数量和最近一件的距离，「其他物品」只显示数量和名字。
 * 统计每 tick 做一次，渲染只读取缓存，所以列表不会逐帧闪烁。
 *
 * <p>位置、行高、字体大小、左右对齐在「界面」分组里调整。
 * 两个「通知」开关只在本机聊天栏提示一次检测结果，不含坐标。
 */
public class EntityList extends Module {

    /** 列表贴在屏幕的哪一侧 */
    public enum DisplaySide {
        LEFT("左"),
        RIGHT("右");

        private final String displayName;

        DisplaySide(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgItems = settings.createGroup("物品");
    private final SettingGroup sgUi = settings.createGroup("界面");

    // ====== 实体 ======

    private final Setting<Set<EntityType<?>>> displayEntities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("显示实体")
        .description("进入列表的实体类型，所有维度通用。")
        .defaultValue(allEntityTypes().toArray(new EntityType<?>[0]))
        .build()
    );
    private final Setting<SettingColor> entityColor = sgGeneral.add(new ColorSetting.Builder()
        .name("实体颜色")
        .description("普通实体行的文字颜色。")
        .defaultValue(Color.YELLOW)
        .build()
    );
    private final Setting<Set<EntityType<?>>> overworldHighlight = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("主世界高亮实体")
        .description("主世界（以及没认出来的自定义维度）要重点显示的实体类型，高亮实体带最近距离。")
        .build()
    );
    private final Setting<Set<EntityType<?>>> netherHighlight = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("下界高亮实体")
        .description("下界要重点显示的实体类型，高亮实体带最近距离。")
        .defaultValue(
            EntityType.EXPERIENCE_ORB,
            EntityType.COW,
            EntityType.SHEEP,
            EntityType.PIG,
            EntityType.HORSE,
            EntityType.ZOMBIE,
            EntityType.CREEPER,
            EntityType.BOGGED,
            EntityType.HUSK,
            EntityType.SLIME,
            EntityType.VILLAGER,
            EntityType.SPIDER,
            EntityType.CAVE_SPIDER,
            EntityType.DROWNED,
            EntityType.ZOMBIE_VILLAGER
        )
        .build()
    );
    private final Setting<Set<EntityType<?>>> endHighlight = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("末地高亮实体")
        .description("末地要重点显示的实体类型，高亮实体带最近距离。")
        .build()
    );
    private final Setting<Boolean> highlightNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("高亮实体提示")
        .description("检测到高亮实体时，在本机聊天栏提示一次。")
        .defaultValue(false)
        .build()
    );
    private final Setting<SettingColor> highlightEntityColor = sgGeneral.add(new ColorSetting.Builder()
        .name("高亮实体颜色")
        .description("高亮实体行的文字颜色。")
        .defaultValue(Color.ORANGE)
        .build()
    );

    // ====== 物品 ======

    private final Setting<List<Item>> primaryItems = sgItems.add(new ItemListSetting.Builder()
        .name("物品1")
        .description("重点物品，掉落物单独用「物品1颜色」显示。")
        .defaultValue(
            Items.ELYTRA,
            Items.SHULKER_BOX,
            Items.WHITE_SHULKER_BOX,
            Items.ORANGE_SHULKER_BOX,
            Items.MAGENTA_SHULKER_BOX,
            Items.LIGHT_BLUE_SHULKER_BOX,
            Items.YELLOW_SHULKER_BOX,
            Items.LIME_SHULKER_BOX,
            Items.PINK_SHULKER_BOX,
            Items.GRAY_SHULKER_BOX,
            Items.LIGHT_GRAY_SHULKER_BOX,
            Items.CYAN_SHULKER_BOX,
            Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX,
            Items.BROWN_SHULKER_BOX,
            Items.GREEN_SHULKER_BOX,
            Items.RED_SHULKER_BOX,
            Items.BLACK_SHULKER_BOX,
            Items.BUNDLE,
            Items.WHITE_BUNDLE,
            Items.ORANGE_BUNDLE,
            Items.MAGENTA_BUNDLE,
            Items.LIGHT_BLUE_BUNDLE,
            Items.YELLOW_BUNDLE,
            Items.LIME_BUNDLE,
            Items.PINK_BUNDLE,
            Items.GRAY_BUNDLE,
            Items.LIGHT_GRAY_BUNDLE,
            Items.CYAN_BUNDLE,
            Items.PURPLE_BUNDLE,
            Items.BLUE_BUNDLE,
            Items.BROWN_BUNDLE,
            Items.GREEN_BUNDLE,
            Items.RED_BUNDLE,
            Items.BLACK_BUNDLE,
            Items.ANCIENT_DEBRIS,
            Items.NETHERITE_SCRAP,
            Items.NETHERITE_INGOT,
            Items.NETHERITE_BLOCK,
            Items.NETHERITE_SWORD,
            Items.NETHERITE_AXE,
            Items.NETHERITE_HOE,
            Items.NETHERITE_PICKAXE,
            Items.NETHERITE_SHOVEL,
            Items.NETHERITE_HELMET,
            Items.NETHERITE_CHESTPLATE,
            Items.NETHERITE_LEGGINGS,
            Items.NETHERITE_BOOTS
        )
        .build()
    );
    private final Setting<SettingColor> primaryItemsColor = sgItems.add(new ColorSetting.Builder()
        .name("物品1颜色")
        .description("「物品1」中物品的文字颜色。")
        .defaultValue(Color.RED)
        .build()
    );
    private final Setting<Boolean> primaryItemsNotify = sgItems.add(new BoolSetting.Builder()
        .name("物品1通知")
        .description("检测到「物品1」中的掉落物时，在本机聊天栏提示一次。")
        .defaultValue(false)
        .build()
    );
    private final Setting<List<Item>> secondaryItems = sgItems.add(new ItemListSetting.Builder()
        .name("物品2")
        .description("次要物品，掉落物单独用「物品2颜色」显示。")
        .build()
    );
    private final Setting<SettingColor> secondaryItemsColor = sgItems.add(new ColorSetting.Builder()
        .name("物品2颜色")
        .description("「物品2」中物品的文字颜色。")
        .defaultValue(Color.CYAN)
        .build()
    );
    private final Setting<SettingColor> otherItemsColor = sgItems.add(new ColorSetting.Builder()
        .name("其他物品颜色")
        .description("不在「物品1」「物品2」中的掉落物的文字颜色（这类只显示名字和数量，不带距离）。")
        .defaultValue(new SettingColor(170, 0, 255))
        .build()
    );
    private final Setting<List<Item>> itemBlacklist = sgItems.add(new ItemListSetting.Builder()
        .name("物品黑名单")
        .description("这里的物品不会被统计，也不会触发通知。")
        .build()
    );

    // ====== 界面 ======

    private final Setting<Integer> xOffset = sgUi.add(new IntSetting.Builder()
        .name("X偏移")
        .description("列表距离屏幕边缘的水平距离。")
        .defaultValue(20)
        .min(0)
        .sliderMax(2048)
        .build()
    );
    private final Setting<Integer> yOffset = sgUi.add(new IntSetting.Builder()
        .name("Y偏移")
        .description("列表第一行的垂直位置。")
        .defaultValue(500)
        .min(0)
        .sliderMax(2048)
        .build()
    );
    private final Setting<DisplaySide> displaySide = sgUi.add(new EnumSetting.Builder<DisplaySide>()
        .name("显示位置")
        .description("列表贴在屏幕左边还是右边。")
        .defaultValue(DisplaySide.LEFT)
        .build()
    );
    private final Setting<Integer> lineHeight = sgUi.add(new IntSetting.Builder()
        .name("行高")
        .description("两行文字之间的间距。")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .build()
    );
    private final Setting<Double> textScale = sgUi.add(new DoubleSetting.Builder()
        .name("字体大小")
        .description("列表文字的大小倍率。")
        .defaultValue(1.0)
        .min(0.1)
        .sliderMax(6.0)
        .build()
    );

    // ====== 缓存 (每 tick 统计一次, 渲染时只读) ======

    private final List<ItemStat> primaryCache = new ArrayList<>();
    private final List<ItemStat> secondaryCache = new ArrayList<>();
    private final List<ItemStat> otherCache = new ArrayList<>();
    private final Map<EntityType<?>, EntityStat> entityCache = new LinkedHashMap<>();

    /** 已经提示过的实体 id, 防止同一次出现反复提示 */
    private final Set<Integer> notifiedIds = new HashSet<>();
    /** 本 tick 见过的实体 id, 用于清理上面那张表 */
    private final Set<Integer> visitedIds = new HashSet<>();
    /** 上一次统计时所在的维度, 换维度后重新提示 */
    private ResourceKey<Level> lastDimension;

    public EntityList() {
        super(Categories.Render, "实体列表", "在屏幕上列出周围的实体和掉落物的数量与最近距离。");
    }

    @Override
    public void onActivate() {
        clear();
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            clear();
            return;
        }

        scan();
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (Utils.isLoading() || !isActive()) return;

        int y = yOffset.get();
        y = drawItems(primaryCache, y, primaryItemsColor.get(), true, event.screenWidth);
        y = drawItems(secondaryCache, y, secondaryItemsColor.get(), true, event.screenWidth);
        y = drawItems(otherCache, y, otherItemsColor.get(), false, event.screenWidth);
        drawEntities(y, event.screenWidth);
    }

    /** 统计周围实体与掉落物, 结果写入缓存 */
    private void scan() {
        ResourceKey<Level> dimension = mc.level.dimension();
        if (dimension != lastDimension) {
            lastDimension = dimension;
            notifiedIds.clear();
        }

        Set<Item> primary = new HashSet<>(primaryItems.get());
        Set<Item> secondary = new HashSet<>(secondaryItems.get());
        Set<Item> blacklist = new HashSet<>(itemBlacklist.get());

        Set<EntityType<?>> display = displayEntities.get();
        Set<EntityType<?>> highlight = dimension == Level.NETHER ? netherHighlight.get()
            : dimension == Level.END ? endHighlight.get()
            : overworldHighlight.get();

        Map<Item, ItemStat> primaryMap = new HashMap<>();
        Map<Item, ItemStat> secondaryMap = new HashMap<>();
        Map<Item, ItemStat> otherMap = new HashMap<>();
        Map<EntityType<?>, EntityStat> entityMap = new HashMap<>();

        boolean notifyHighlight = highlightNotify.get();
        boolean notifyPrimary = primaryItemsNotify.get();
        visitedIds.clear();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (notifyHighlight || notifyPrimary) visitedIds.add(entity.getId());

            // 掉落物: 按物品分类汇总数量和最近距离
            if (entity instanceof ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                Item item = stack.getItem();
                if (blacklist.contains(item)) continue;

                Map<Item, ItemStat> target;
                if (primary.contains(item)) {
                    target = primaryMap;
                    if (notifyPrimary && notifiedIds.add(itemEntity.getId())) {
                        info("检测到物品: %s", Names.get(item));
                    }
                } else {
                    target = secondary.contains(item) ? secondaryMap : otherMap;
                }

                ItemStat stat = target.computeIfAbsent(item, ItemStat::new);
                stat.count += stack.getCount();

                double distance = mc.player.distanceTo(itemEntity);
                if (distance < stat.minDistance) stat.minDistance = distance;
                continue;
            }

            // 其他实体: 只统计「显示实体」里的类型 (全选时也跳过玩家自己)
            if (entity == mc.player) continue;

            EntityType<?> type = entity.getType();
            if (!display.contains(type)) continue;

            boolean highlighted = highlight.contains(type);
            EntityStat stat = entityMap.computeIfAbsent(type, EntityStat::new);
            stat.count++;
            if (highlighted) stat.highlighted = true;

            double distance = mc.player.distanceTo(entity);
            if (distance < stat.minDistance) stat.minDistance = distance;

            if (highlighted && notifyHighlight && notifiedIds.add(entity.getId())) {
                info("检测到高亮实体: %s", Names.get(type));
            }
        }

        if (notifyHighlight || notifyPrimary) notifiedIds.retainAll(visitedIds);

        fillItems(primaryCache, primaryMap);
        fillItems(secondaryCache, secondaryMap);
        fillItems(otherCache, otherMap);

        entityCache.clear();
        for (EntityStat stat : sortEntities(entityMap)) {
            entityCache.put(stat.type, stat);
        }
    }

    /** 按最近距离从近到远整理物品行 */
    private void fillItems(List<ItemStat> out, Map<Item, ItemStat> source) {
        out.clear();
        out.addAll(source.values());
        out.sort(Comparator
            .comparingDouble((ItemStat stat) -> stat.minDistance)
            .thenComparing((ItemStat stat) -> Names.get(stat.item))
        );
    }

    /** 高亮实体排最前, 然后数量多的在前, 最后按名字排, 保证显示顺序稳定 */
    private static List<EntityStat> sortEntities(Map<EntityType<?>, EntityStat> source) {
        List<EntityStat> stats = new ArrayList<>(source.values());
        stats.sort(Comparator
            .comparing((EntityStat stat) -> stat.highlighted).reversed()
            .thenComparing(Comparator.comparingInt((EntityStat stat) -> stat.count).reversed())
            .thenComparing((EntityStat stat) -> Names.get(stat.type))
        );
        return stats;
    }

    private int drawItems(List<ItemStat> stats, int y, Color color, boolean showDistance, int screenWidth) {
        for (ItemStat stat : stats) {
            String name = Names.get(stat.item);
            String text = showDistance
                ? String.format("%s x%s  (%.1f m)", name, stat.count, stat.minDistance)
                : String.format("%s x%s", name, stat.count);
            y = drawLine(text, y, color, false, screenWidth);
        }
        return y;
    }

    private int drawEntities(int y, int screenWidth) {
        Color normal = entityColor.get();
        Color highlighted = highlightEntityColor.get();

        for (Map.Entry<EntityType<?>, EntityStat> entry : entityCache.entrySet()) {
            EntityStat stat = entry.getValue();
            String name = Names.get(entry.getKey());
            // 高亮实体带最近距离, 普通实体只显示数量
            String text = stat.highlighted
                ? String.format("%s x%s  (%.1f m)", name, stat.count, stat.minDistance)
                : String.format("%s x%s", name, stat.count);
            y = drawLine(text, y, stat.highlighted ? highlighted : normal, true, screenWidth);
        }
        return y;
    }

    /** 画一行文字, 返回下一行的 y (右边对齐时按文字实际宽度算起点) */
    private int drawLine(String text, int y, Color color, boolean shadow, int screenWidth) {
        double scale = textScale.get();
        TextRenderer renderer = TextRenderer.get();

        renderer.begin(scale);
        int x = displaySide.get() == DisplaySide.RIGHT
            ? (int) (screenWidth - renderer.getWidth(text) - xOffset.get())
            : xOffset.get();
        renderer.render(text, x, y, color, shadow);
        renderer.end();

        return y + (int) (lineHeight.get() * scale);
    }

    /** 注册表里的全部实体类型, 作为「显示实体」的默认值 */
    private static Set<EntityType<?>> allEntityTypes() {
        Set<EntityType<?>> types = new HashSet<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) types.add(type);
        return types;
    }

    private void clear() {
        primaryCache.clear();
        secondaryCache.clear();
        otherCache.clear();
        entityCache.clear();
        notifiedIds.clear();
        visitedIds.clear();
        lastDimension = null;
    }

    /** 一类物品的汇总: 总数量 + 最近一件的距离 */
    private static class ItemStat {
        private final Item item;
        private int count;
        private double minDistance = Double.MAX_VALUE;

        private ItemStat(Item item) {
            this.item = item;
        }
    }

    /** 一类实体的汇总: 总数量 + 最近距离 + 是否高亮 */
    private static class EntityStat {
        private final EntityType<?> type;
        private int count;
        private double minDistance = Double.MAX_VALUE;
        private boolean highlighted;

        private EntityStat(EntityType<?> type) {
            this.type = type;
        }
    }
}
