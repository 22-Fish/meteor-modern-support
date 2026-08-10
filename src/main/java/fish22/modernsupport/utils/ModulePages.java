/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 *
 * Copyright (c) 2026 fish22
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

import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块分页系统。
 *
 * <p>把模块列表拆成多个"页面"：
 * <ul>
 *   <li>第 0 页固定为主界面（不可删除、始终排第一），开启菜单默认展示</li>
 *   <li>自定义页面最多 {@link #MAX_PAGES} 个，英文名，不能重名</li>
 *   <li>每个页面勾选的是<b>分类（分组）</b>，勾选的分类下所有模块在该页展示；
 *       一个分类可以同时勾选到多个页面，删除页面只影响该页自己的名单</li>
 *   <li>启动时检查：没被记录过的分类（新插件的分类）自动勾进主界面并登记；
 *       已登记过的分类即使没有任何页面展示也不再自动处理</li>
 * </ul>
 */
public class ModulePages extends System<ModulePages> {
    /** 自定义页面数量上限（顶部栏放不下） */
    public static final int MAX_PAGES = 5;
    /** 主界面默认名（保持原 Modules 标签名，不改名） */
    public static final String DEFAULT_MAIN_NAME = "Modules";

    private static ModulePages INSTANCE;

    public static ModulePages get() {
        return INSTANCE;
    }

    /** 页面：名字 + 勾选的分类名集合 */
    public static class Page {
        public String name;
        public final Set<String> categories = new HashSet<>();
    }

    private final List<Page> pages = new ArrayList<>();
    /** 已登记过的模块名（新插件登记后不再自动处理） */
    private final Set<String> registered = new HashSet<>();
    /** 当前选中的页面（0 = 主界面） */
    private int current = 0;
    /** 是否通过顶部页面按钮打开模块列表（决定打开时是否重置回主界面） */
    private boolean openingViaBar = false;

    public ModulePages() {
        super("modulepages");
        INSTANCE = this;
        pages.add(createPage(DEFAULT_MAIN_NAME));
    }

    // ====== 查询 ======

    public List<Page> getPages() {
        return pages;
    }

    public Page getPage(int idx) {
        return pages.get(idx);
    }

    public int getCurrent() {
        return current;
    }

    public void setCurrent(int current) {
        this.current = Math.max(0, Math.min(current, pages.size() - 1));
    }

    public boolean isOpeningViaBar() {
        return openingViaBar;
    }

    public void setOpeningViaBar(boolean openingViaBar) {
        this.openingViaBar = openingViaBar;
    }

    /** 自定义页面数量（不含主界面） */
    public int pageCount() {
        return pages.size() - 1;
    }

    /** 模块所属的所有页面名（跨页面搜索结果显示标签用） */
    public List<String> pagesOf(Module module) {
        List<String> result = new ArrayList<>();
        for (Page page : pages) {
            if (page.categories.contains(module.category.name)) result.add(page.name);
        }
        return result;
    }

    /** 当前页是否展示该模块（该模块的分类在当前页勾选，叠加原 hiddenModules 隐藏设置） */
    public boolean shouldShow(Module module) {
        if (Config.get().hiddenModules.get().contains(module)) return false;
        return pages.get(current).categories.contains(module.category.name);
    }

    // ====== 修改 ======

    /** 新增页面（默认英文名 Page N，空名单），超过上限返回 null */
    public Page addPage() {
        if (pageCount() >= MAX_PAGES) return null;
        Page page = createPage(nextPageName());
        pages.add(page);
        save();
        return page;
    }

    /** 重命名页面：非空且与其他页面不重名 */
    public boolean renamePage(int idx, String name) {
        if (name == null || name.isBlank()) return false;
        for (int i = 0; i < pages.size(); i++) {
            if (i != idx && pages.get(i).name.equals(name)) return false;
        }
        pages.get(idx).name = name;
        save();
        return true;
    }

    /** 删除页面（主界面不可删），删除后当前页自动回退到主界面 */
    public void deletePage(int idx) {
        if (idx <= 0 || idx >= pages.size()) return;
        pages.remove(idx);
        if (current >= pages.size()) current = pages.size() - 1;
        save();
    }

    /** 勾选/取消勾选某页的分类 */
    public void toggleCategory(int pageIdx, String categoryName, boolean checked) {
        Page page = pages.get(pageIdx);
        if (checked) page.categories.add(categoryName);
        else page.categories.remove(categoryName);
        save();
    }

    /** 启动登记：没被记录过的分类（新插件的分类）自动勾进主界面并登记 */
    public void checkNewCategories() {
        boolean changed = false;
        for (Category category : Modules.loopCategories()) {
            if (registered.add(category.name)) {
                pages.get(0).categories.add(category.name);
                changed = true;
            }
        }
        if (changed) save();
    }

    // ====== 内部 ======

    private static Page createPage(String name) {
        Page page = new Page();
        page.name = name;
        return page;
    }

    private String nextPageName() {
        int n = 1;
        while (true) {
            String name = "Page " + n;
            boolean used = false;
            for (Page page : pages) {
                if (page.name.equals(name)) {
                    used = true;
                    break;
                }
            }
            if (!used) return name;
            n++;
        }
    }

    // ====== NBT 持久化 ======

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();

        ListTag pagesTag = new ListTag();
        for (Page page : pages) {
            CompoundTag pageTag = new CompoundTag();
            pageTag.putString("name", page.name);
            ListTag categoriesTag = new ListTag();
            for (String name : page.categories) categoriesTag.add(StringTag.valueOf(name));
            pageTag.put("categories", categoriesTag);
            pagesTag.add(pageTag);
        }
        tag.put("pages", pagesTag);

        ListTag registeredTag = new ListTag();
        for (String name : registered) registeredTag.add(StringTag.valueOf(name));
        tag.put("registered", registeredTag);

        tag.putInt("current", current);
        return tag;
    }

    @Override
    public ModulePages fromTag(CompoundTag tag) {
        pages.clear();

        ListTag pagesTag = tag.getListOrEmpty("pages");
        for (Tag t : pagesTag) {
            CompoundTag pageTag = (CompoundTag) t;
            Page page = createPage(pageTag.getStringOr("name", ""));
            ListTag categoriesTag = pageTag.getListOrEmpty("categories");
            for (Tag ct : categoriesTag) page.categories.add(ct.asString().orElse(""));
            pages.add(page);
        }

        // 数据损坏/为空时兜底主界面
        if (pages.isEmpty()) pages.add(createPage(DEFAULT_MAIN_NAME));

        registered.clear();
        ListTag registeredTag = tag.getListOrEmpty("registered");
        for (Tag t : registeredTag) registered.add(t.asString().orElse(""));

        current = Math.max(0, Math.min(tag.getIntOr("current", 0), pages.size() - 1));
        return this;
    }
}
