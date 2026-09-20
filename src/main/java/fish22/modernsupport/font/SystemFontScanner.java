package fish22.modernsupport.font;

import fish22.modernsupport.mixin.FontFamilyAccessor;
import fish22.modernsupport.mixin.SystemFontFaceAccessor;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.renderer.text.SystemFontFace;
import meteordevelopment.meteorclient.utils.files.ByteBufferUtils;
import meteordevelopment.meteorclient.utils.render.FontUtils;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 系统字体补充扫描
 *
 * <p>Meteor 自己只会加载 .ttf, 而且要求文件头是 TrueType; .otf (CFF) 和
 * .ttc (字体集合, 比如微软雅黑 msyh.ttc) 全都进不了字体列表。
 * 这里用 FreeType 把剩下的都读进来 (名字和粗细/斜体取自字体自身),
 * 顺便记下字体集合里的第几个字脸。
 */
public final class SystemFontScanner {
    /** 字体集合里每个字脸的位置: 身份比较, 键就是字体列表里那个 FontFace 对象 */
    private static final Map<FontFace, Integer> FACE_INDEX = new IdentityHashMap<>();

    private SystemFontScanner() {
    }

    /** 选中字体在字体文件里的字脸序号 (.ttc 才有意义, 其它都是 0) */
    public static int faceIndex(FontFace face) {
        Integer index = FACE_INDEX.get(face);
        return index == null ? 0 : index;
    }

    public static void scan() {
        Set<Path> loaded = loadedPaths();

        for (String dir : FontUtils.getSearchPaths()) {
            scanDir(new File(dir), loaded);
        }

        Fonts.FONT_FAMILIES.sort(Comparator.comparing(FontFamily::getName));
    }

    /** Meteor 已经加载过的字体文件, 不用再读一遍 */
    private static Set<Path> loadedPaths() {
        Set<Path> paths = new HashSet<>();

        for (FontFamily family : Fonts.FONT_FAMILIES) {
            for (FontFace face : ((FontFamilyAccessor) (Object) family).meteor$fonts()) {
                if (!(face instanceof SystemFontFace system)) continue;

                Path path = ((SystemFontFaceAccessor) (Object) system).meteor$path();
                if (path != null) paths.add(path.toAbsolutePath().normalize());
            }
        }

        return paths;
    }

    private static void scanDir(File dir, Set<Path> loaded) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDir(file, loaded);
                continue;
            }
            if (!file.isFile()) continue;

            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".ttf") && !name.endsWith(".otf") && !name.endsWith(".ttc")) continue;

            Path path = file.toPath().toAbsolutePath().normalize();
            if (loaded.contains(path)) continue;

            try {
                loadFaces(file);
            } catch (Throwable ignored) {
                // 单个字体读不了就算了 (损坏 / 没权限 / 不认识的数据), 不影响其它字体
            }
        }
    }

    private static void loadFaces(File file) throws Exception {
        ByteBuffer data = ByteBufferUtils.readFully(file.toPath(), BufferUtils::createByteBuffer);

        FT_Face first = newFace(data, 0);
        int faces = (int) Math.max(first.num_faces(), 1);

        for (int i = 0; i < faces; i++) {
            FT_Face face = i == 0 ? first : newFace(data, i);

            try {
                add(file, face, i);
            } finally {
                doneFace(face);
            }
        }
    }

    private static void add(File file, FT_Face face, int index) {
        String family = face.family_nameString();
        if (family == null || family.isBlank()) return;

        String style = face.style_nameString();
        FontInfo.Type type = FontInfo.Type.fromString(style == null ? "" : style.trim());

        FontFamily fontFamily = Fonts.getFamily(family);
        if (fontFamily == null) {
            fontFamily = new FontFamily(family);
            Fonts.FONT_FAMILIES.add(fontFamily);
        }

        if (fontFamily.hasType(type)) return;

        FontFace fontFace = new SystemFontFace(new FontInfo(family, type), file.toPath());
        if (fontFamily.addFont(fontFace)) FACE_INDEX.put(fontFace, index);
    }

    private static FT_Face newFace(ByteBuffer data, int index) {
        synchronized (FreeTypeUtil.LIBRARY_LOCK) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), data, index, pointer), "加载字体");

                FT_Face face = FT_Face.create(pointer.get(0));
                try {
                    FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE), "选择 Unicode 字符表");
                } catch (RuntimeException e) {
                    FreeType.FT_Done_Face(face);
                    throw e;
                }
                return face;
            }
        }
    }

    private static void doneFace(FT_Face face) {
        synchronized (FreeTypeUtil.LIBRARY_LOCK) {
            FreeType.FT_Done_Face(face);
        }
    }
}
