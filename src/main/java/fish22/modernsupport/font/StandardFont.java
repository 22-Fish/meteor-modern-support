package fish22.modernsupport.font;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import fish22.modernsupport.ModernSupport;
import fish22.modernsupport.mixin.FontManagerAccessor;
import fish22.modernsupport.mixin.MinecraftAccessor;
import fish22.modernsupport.utils.I18n;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 标准字体: 把「Meteor 里选中的字体」交给原版 FreeType 字体管线
 *
 * <p>原版这套管线就是桌面软件那套: size = em 像素 (每个字体同一字号视觉大小一致),
 * 字形按需生成, 图集自动扩容, 缺字走原版字体兜底 (中文等)。
 *
 * <p>字形按「实际显示倍数」光栅化: Meteor 正文是 begin(1)、标题是 begin(1.25),
 * 只有按同一个倍数生成再原样贴到屏幕上笔画才不虚
 * (拿 18px 的图硬拉到 22.5px 显示, 抗锯齿的灰边会碎成一片杂色)。
 */
public final class StandardFont {
    /** Config 设置, 由 MixinConfig 创建 */
    public static Setting<Boolean> enabled;
    public static Setting<Double> size;
    public static Setting<FontSharpness> sharpness;

    /** 自己的字形图集 (原版会给它自动开 256x256 的贴图) */
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("meteor-modern-support", "font/standard");
    /** 缺字兜底用原版默认字体集 (位图拉丁 + unifont) */
    private static final Identifier VANILLA_DEFAULT = Identifier.withDefaultNamespace("default");

    /** 文字按 begin(scale) 的 scale 倍显示, 再乘 2 才是屏幕像素, 所以 1 倍文字的超采样就是 2 */
    private static final double BASE_OVERSAMPLE = 2;
    /** 最多同时留几份不同清晰度的同款字体 (正文一份 + 标题一份) */
    private static final int MAX_FONTS = 2;
    /** 清晰度按这个步长量化, 差一点点就不重建 */
    private static final double STEP = 0.25;
    /** 设置停多久没变才真的重建 (字号滑条一拖会连着改很多次) */
    private static final long SETTLE_MS = 300;
    /** 抖动保护: BURST_WINDOW_MS 内重建超过 BURST 次, 就钉住当前字体 PIN_MS 不再换 */
    private static final int BURST = 3;
    private static final long BURST_WINDOW_MS = 1000;
    private static final long PIN_MS = 2000;
    /** 建字体时试渲染这几个字, 用来筛掉原版管线会崩的字体 (ASCII / 中日韩各一个) */
    private static final int[] VALIDATION_CODEPOINTS = {0x41, 0x67, 0x4E2D, 0x3042, 0xD55C};

    private static boolean dirty = true;
    private static long dirtyAt;
    /** 本模组建的字脸 (字形上传时要认出「这是我们的字形」) */
    private static final Set<FT_Face> FACES = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 每代字体用不同的贴图 id, 新旧图集不会互相顶掉 */
    private static int generation;
    private static String builtKey;
    private static Built current;
    /** 按量化后的超采样存, 访问顺序即 LRU 顺序 */
    private static final LinkedHashMap<Double, Built> fonts = new LinkedHashMap<>(4, 0.75f, true);
    /** 换掉的旧字体晚一步再释放 (顶点批里可能还引着它的图集) */
    private static final Deque<Built> graveyard = new ArrayDeque<>();
    private static final Deque<Long> rebuildTimes = new ArrayDeque<>();
    private static long pinnedUntil;

    private StandardFont() {
    }

    public static boolean isEnabled() {
        return enabled != null && enabled.get();
    }

    /** 设置变了 */
    public static void markDirty() {
        dirty = true;
        dirtyAt = System.currentTimeMillis();
    }

    /**
     * 取当前该用的字体
     *
     * @param textScale 调用方 begin(scale) 传进来的倍数 (正文一般 1, 标题 1.25)
     * @return 没开 / 建不出来返回 null, 调用方回退原版渲染
     */
    public static Font current(double textScale) {
        if (!isEnabled()) return null;

        long now = System.currentTimeMillis();
        trimGraveyard();

        if (dirty) {
            // 设置还在变: 先用着旧的, 等停下来再重建
            if (current != null && now - dirtyAt < SETTLE_MS) return current.font;

            dirty = false;

            String key = settingsKey();
            if (!key.equals(builtKey)) {
                clear();
                builtKey = key;
            }
        }

        double oversample = oversample(textScale);

        Built built = fonts.get(oversample);
        if (built != null) {
            current = built;
            return built.font;
        }

        // 抖动保护: 一会儿换一个清晰度, 说明同时有多个缩放混着画, 钉住当前这份别再建了
        if (current != null && now < pinnedUntil) return current.font;

        built = build(oversample, now);
        return built != null ? built.font : (current == null ? null : current.font);
    }

    /** 把 begin(scale) 的倍数换算成光栅化超采样, 量化到 STEP 并夹在合理范围 */
    private static double oversample(double textScale) {
        double value = BASE_OVERSAMPLE * Math.max(0.5, Math.min(textScale, 3));
        return Math.max(0.5, Math.round(value / STEP) * STEP);
    }

    private static String settingsKey() {
        Config config = Config.get();
        FontFace face = config == null ? null : config.font.get();
        double px = size != null ? size.get() : 9;
        FontSharpness sharp = sharpness == null ? null : sharpness.get();
        return face == null ? "" : face.info.family() + "|" + face.info.type() + "|" + px + "|" + sharp;
    }

    private static Built build(double oversample, long now) {
        Config config = Config.get();
        FontFace face = config == null ? null : config.font.get();
        if (face == null) return null;

        double px = size != null ? size.get() : 9;

        // 缓冲区必须用 MemoryUtil.memAlloc: 原版 TrueTypeGlyphProvider 关闭时会 memFree 它
        // (自己拿 BufferUtils.createByteBuffer 分配再被它 free 会直接崩在 jemalloc 里)
        ByteBuffer data = null;
        FT_Face ftFace = null;
        GlyphProvider newProvider = null;
        FontSet newFontSet = null;

        try {
            data = readFontData(face);
            int faceIndex = SystemFontScanner.faceIndex(face);

            // 拔掉内嵌点阵: 不然 FreeType 会吐 1 位黑白图, 原版拿它当灰度图会抛异常崩游戏
            FontData.stripBitmapStrikes(data, faceIndex);

            ftFace = newFace(data, faceIndex);
            FACES.add(ftFace);
            validate(ftFace, (float) px, (float) oversample);

            FT_Face fontFace = ftFace;   // provider 接管它的生命周期
            newProvider = new FastGlyphProvider(new TrueTypeGlyphProvider(data, fontFace, (float) px, (float) oversample, 0, 0, ""));
            data = null;
            ftFace = null;

            newFontSet = new FontSet(new GlyphStitcher(mc.getTextureManager(), TEXTURE_ID.withSuffix("/" + generation++)));
            newFontSet.reload(List.of(new GlyphProvider.Conditional(newProvider, FontOption.Filter.ALWAYS_PASS)), Set.of());

            Built built = new Built(oversample, new Font(new StandardFontProvider(newFontSet)), newFontSet, newProvider, fontFace);

            if (fonts.size() >= MAX_FONTS) {
                Map.Entry<Double, Built> oldest = fonts.entrySet().iterator().next();
                fonts.remove(oldest.getKey());
                graveyard.add(oldest.getValue());
            }
            fonts.put(oversample, built);
            current = built;

            checkBurst(now);
            return built;
        } catch (Throwable t) {
            ModernSupport.LOG.warn("标准字体加载失败, 继续用上一个能用的字体: {} @{}", face, oversample, t);
            if (mc != null && mc.player != null) {
                ChatUtils.warning(I18n.t("Text.StandardFontFailed", "标准字体加载失败, 继续用上一个能用的字体 (详见日志)") + " " + face.info.family());
            }

            if (newFontSet != null) closeQuietly(newFontSet);
            if (newProvider != null) closeQuietly(newProvider);
            if (ftFace != null) {
                FACES.remove(ftFace);
                doneFace(ftFace);
            }
            if (data != null) MemoryUtil.memFree(data);
            return null;
        }
    }

    /** 短时间反复重建说明缩放种类太多, 钉住当前这份, 免得一帧建一次把帧率拖垮 */
    private static void checkBurst(long now) {
        while (!rebuildTimes.isEmpty() && now - rebuildTimes.peekFirst() > BURST_WINDOW_MS) rebuildTimes.pollFirst();

        rebuildTimes.addLast(now);
        if (rebuildTimes.size() >= BURST) {
            pinnedUntil = now + PIN_MS;
            rebuildTimes.clear();
        }
    }

    private static void clear() {
        fonts.values().forEach(graveyard::add);
        fonts.clear();
        current = null;
    }

    private static void trimGraveyard() {
        // 只留一份待释放的: 换字体时正好有顶点批引用旧图集也不会用坏, 又不会攒一堆字体数据占内存
        while (graveyard.size() > 1) close(graveyard.pollFirst());
    }

    /** 读字体文件到 MemoryUtil 分配的缓冲区 (原版 provider 关闭时会 memFree 它, 必须是同一套分配器) */
    private static ByteBuffer readFontData(FontFace face) throws IOException {
        try (ReadableByteChannel channel = face.byteChannelForRead()) {
            if (channel instanceof SeekableByteChannel seekable) {
                long fileSize = seekable.size();
                if (fileSize > Integer.MAX_VALUE) throw new IOException("字体文件太大: " + fileSize);

                ByteBuffer data = MemoryUtil.memAlloc((int) fileSize);
                try {
                    while (data.hasRemaining()) {
                        if (seekable.read(data) < 0) break;
                    }
                    data.flip();
                    return data;
                } catch (Throwable t) {
                    MemoryUtil.memFree(data);
                    throw t;
                }
            }

            // 内置字体不是随机访问通道: 先读进数组, 再按实际大小分配, 免得反复扩容漏内存
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] chunk = new byte[8192];
            for (int read; (read = channel.read(ByteBuffer.wrap(chunk))) > 0; ) {
                out.write(chunk, 0, read);
            }

            byte[] bytes = out.toByteArray();
            ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
            data.put(bytes).flip();
            return data;
        }
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

    /**
     * 试渲染几个字: 不合格的字体宁可不用, 也别等它进游戏里崩
     * (原版管线遇到非灰度字形是直接抛异常的, 拦不住)
     */
    private static void validate(FT_Face face, float px, float oversample) {
        int pixelsPerEm = Math.round(px * oversample);
        FreeType.FT_Set_Pixel_Sizes(face, pixelsPerEm, pixelsPerEm);

        for (int codepoint : VALIDATION_CODEPOINTS) {
            int index = FreeType.FT_Get_Char_Index(face, codepoint);
            if (index == 0) continue;

            if (FreeType.FT_Load_Glyph(face, index, FreeType.FT_LOAD_RENDER) != 0) {
                throw new IllegalStateException("字体渲染不出 U+" + Integer.toHexString(codepoint));
            }

            FT_Bitmap bitmap = face.glyph().bitmap();
            if (bitmap.width() <= 0 || bitmap.rows() <= 0) continue;

            if (bitmap.pixel_mode() != FreeType.FT_PIXEL_MODE_GRAY) {
                throw new IllegalStateException("字形不是 8 位灰度 (pixel_mode=" + bitmap.pixel_mode() + ")");
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    /** 收掉一份字体: 先放图集, 再放字体数据 (provider 关闭时会 memFree 那块缓冲区) */
    private static void close(Built built) {
        if (built == null) return;

        FACES.remove(built.face());
        closeQuietly(built.set());
        closeQuietly(built.provider());
    }

    /** 字形上传时的灰阶查表; 不是本模组的字脸 (或不需要调整) 返回 null */
    public static int[] sharpnessLut(FT_Face face) {
        if (face == null || !FACES.contains(face)) return null;
        return sharpness == null ? null : sharpness.get().lut();
    }

    /** 原版默认字体集的字形源 (每次现查: 资源重载会换成新的字体集) */
    private static GlyphSource vanillaSource() {
        if (mc == null) return null;

        FontManager manager = ((MinecraftAccessor) mc).meteor$fontManager();
        if (manager == null) return null;

        Map<Identifier, FontSet> sets = ((FontManagerAccessor) manager).meteor$fontSets();
        FontSet set = sets == null ? null : sets.get(VANILLA_DEFAULT);
        return set == null ? null : set.source(false);
    }

    /** 一份建好的字体 */
    private record Built(double oversample, Font font, FontSet set, GlyphProvider provider, FT_Face face) {
    }

    /**
     * 少报一点「我支持的字」:
     * 原版 FontSet 装载时会把 provider 上报的每个码点都试载一遍度量, 微软雅黑 3 万码点要 118ms (全在渲染线程)
     * 只报 ASCII 就够让它把这个 provider 收进可用列表, 其它码点在画的时候照样能取到
     */
    private static final class FastGlyphProvider implements GlyphProvider {
        private final GlyphProvider delegate;
        private final IntSet reported;

        private FastGlyphProvider(GlyphProvider delegate) {
            this.delegate = delegate;

            IntSet supported = delegate.getSupportedGlyphs();
            IntSet ascii = new IntOpenHashSet();
            for (int codepoint = 32; codepoint <= 126; codepoint++) {
                if (supported.contains(codepoint)) ascii.add(codepoint);
            }

            // 万一这字体连 ASCII 都没有, 就老老实实全报
            this.reported = ascii.isEmpty() ? supported : ascii;
        }

        @Override
        public UnbakedGlyph getGlyph(int codepoint) {
            return delegate.getGlyph(codepoint);
        }

        @Override
        public IntSet getSupportedGlyphs() {
            return reported;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * 字形来源: 先问选中的字体, 没有这个字再问原版默认字体
     * 这样中文之类的缺字不会变方块, 而且两边基线一样 (都是原版那套 7 像素基线)
     */
    private static final class StandardFontProvider implements Font.Provider {
        private final FontSet set;
        private final GlyphSource own;
        private final GlyphSource combined = new GlyphSource() {
            @Override
            public BakedGlyph getGlyph(int codepoint) {
                BakedGlyph glyph = own.getGlyph(codepoint);
                if (glyph != null && glyph.info() != SpecialGlyphs.MISSING) return glyph;

                GlyphSource fallback = vanillaSource();
                if (fallback != null) {
                    BakedGlyph vanilla = fallback.getGlyph(codepoint);
                    if (vanilla != null) return vanilla;
                }

                return glyph;
            }

            @Override
            public BakedGlyph getRandomGlyph(RandomSource random, int width) {
                return own.getRandomGlyph(random, width);
            }
        };

        private StandardFontProvider(FontSet set) {
            this.set = set;
            this.own = set.source(false);
        }

        @Override
        public GlyphSource glyphs(FontDescription description) {
            return combined;
        }

        @Override
        public EffectGlyph effect() {
            return set.whiteGlyph();
        }
    }
}
