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

package fish22.modernsupport.mixin;

import fish22.modernsupport.utils.ModulePages;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * 模块列表过滤注入（WCategoryController）：
 * 分类窗口里的模块按当前页面的勾选名单 + 该板块自己的隐藏名单展示，
 * 替代原 hiddenModules 过滤（Meteor 自带隐藏名单在 {@link ModulePages#shouldShow} 内叠加保留）。
 */
@Mixin(targets = "meteordevelopment.meteorclient.gui.screens.ModulesScreen$WCategoryController", remap = false)
public abstract class MixinModuleListFilter {
    /** 当前正在处理的分类（判断板块要不要保留要用） */
    @Unique private Category modernsupport$currentCategory;

    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Ljava/util/List;contains(Ljava/lang/Object;)Z"))
    private boolean redirectContains(List<?> list, Object module) {
        return !ModulePages.get().shouldShow((Module) module);
    }

    /** 记下当前分类 */
    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/systems/modules/Modules;getGroup(Lmeteordevelopment/meteorclient/systems/modules/Category;)Ljava/util/List;"))
    private List<Module> modernsupport$captureCategory(Modules modules, Category category) {
        modernsupport$currentCategory = category;
        return modules.getGroup(category);
    }

    /**
     * 原版是"模块全被过滤掉就不建这个板块"，
     * 改成"本页勾了这个分类就照建"，这样模块全隐藏后板块还在，能右键恢复设置。
     */
    @Redirect(method = "init", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private boolean modernsupport$keepPanel(List<?> moduleList) {
        if (!moduleList.isEmpty()) return false;

        Category category = modernsupport$currentCategory;
        return category == null || !ModulePages.get().isCategorySelected(category.name);
    }
}
