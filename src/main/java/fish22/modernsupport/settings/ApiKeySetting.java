/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.settings;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * APIKEY 设置：值只保存在 openai/APIKEY.txt，绝不写进 Meteor 配置。
 *
 * <p>配置文件被别人拿走、或者分享给朋友时，密钥不会跟着配置一起泄露。
 * 界面上照常是一个输入框（用 {@code ApiKeyRenderer} 只显示一排点），
 * 保存时写到文件，下次开启模块再从文件读回来。
 */
public class ApiKeySetting extends Setting<String> {
    private final Supplier<Path> fileSupplier;
    private boolean loaded = false;

    public ApiKeySetting(String name, String description, Supplier<Path> fileSupplier, IVisible visible) {
        super(name, description, "", null, null, visible);
        this.fileSupplier = fileSupplier;
    }

    /** 输入框为空时显示的提示文字 */
    public String placeholder() {
        return "在此填写 APIKEY，保存到 openai/APIKEY.txt";
    }

    /** 第一次取用时从文件读一次；模块还没开启、没有目录时保持空 */
    @Override
    public String get() {
        if (!loaded) {
            Path file = fileSupplier.get();
            if (file != null) {
                loaded = true;
                value = read(file);
            }
        }
        return value;
    }

    /** 保存到文件（不写配置） */
    @Override
    public boolean set(String newValue) {
        value = newValue == null ? "" : newValue.trim();
        loaded = true;

        Path file = fileSupplier.get();
        if (file != null) {
            try {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, value);
            } catch (IOException ignored) {
            }
        }

        onChanged();
        return true;
    }

    /** 模块开启时手动从文件重新读一次，换机子/手动改文件后能生效 */
    public void reload() {
        Path file = fileSupplier.get();
        if (file == null) return;
        value = read(file);
        loaded = true;
    }

    private String read(Path file) {
        try {
            if (Files.exists(file)) return Files.readString(file).trim();
        } catch (IOException ignored) {
        }
        return value == null ? "" : value;
    }

    @Override
    protected String parseImpl(String str) {
        return str;
    }

    @Override
    protected boolean isValueValid(String value) {
        return true;
    }

    /** 空实现：配置里只留一个名字，不带密钥值 */
    @Override
    protected CompoundTag save(CompoundTag tag) {
        return tag;
    }

    /** 配置里没有密钥，加载时保持当前内存值（来自文件） */
    @Override
    protected String load(CompoundTag tag) {
        return value;
    }
}
