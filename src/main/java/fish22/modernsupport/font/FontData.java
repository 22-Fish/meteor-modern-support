package fish22.modernsupport.font;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 字体数据修补: 拔掉字体自带的内嵌点阵字形
 *
 * <p>为什么必须拔: 原版 TTF 管线真正取字形时用的是 FT_Load_Glyph(FT_LOAD_RENDER),
 * 没禁止内嵌点阵。中日字体 (宋体 / 新宋体 / MS Gothic / Cambria / Calibri 等) 自带
 * 小尺寸点阵, 只要请求的像素尺寸正好对上点阵档位, FreeType 就吐 1 位黑白图,
 * 原版 NativeImage 立刻抛 "Rendered glyph was not 8-bit grayscale" 把游戏搞崩
 * (这也是「平时没事, 一调字号就崩」的原因: 点阵只在尺寸对上档位时才启用)
 *
 * <p>做法: 把字体目录里点阵相关表的 tag 改成无效值 (FreeType 按 tag 查表, 查不到就不启用),
 * 之后字形一律从轮廓描线渲染, 永远是 8 位灰度
 */
final class FontData {
    private static final String[] BITMAP_TABLES = {"EBDT", "EBLC", "EBSC", "CBDT", "CBLC", "sbix", "bdat", "bloc"};

    private FontData() {
    }

    /** 返回改掉的表数量, 0 表示这个字体本来就没点阵 */
    static int stripBitmapStrikes(ByteBuffer data, int faceIndex) {
        // 字体里的表目录是「大端」存的, 而缓冲区是本机字节序 (小端), 必须换成大端视图再读
        ByteBuffer view = data.duplicate().order(ByteOrder.BIG_ENDIAN);

        int directory = tableDirectory(view, faceIndex);
        if (directory < 0 || directory + 12 > view.limit()) return 0;

        int tables = view.getShort(directory + 4) & 0xFFFF;
        if (tables <= 0 || tables > 4096) return 0;

        int patched = 0;

        for (int i = 0; i < tables; i++) {
            int record = directory + 12 + i * 16;
            if (record + 4 > view.limit()) break;

            String tag = tag(view, record);

            for (String bitmapTable : BITMAP_TABLES) {
                if (tag.equals(bitmapTable)) {
                    view.put(record, (byte) 0);
                    patched++;
                }
            }
        }

        return patched;
    }

    /** 表目录偏移: 普通字体在开头, 字体集合 (.ttc) 在头部偏移表里按字脸序号取 */
    private static int tableDirectory(ByteBuffer data, int faceIndex) {
        if (data.limit() < 12) return -1;

        if (tag(data, 0).equals("ttcf")) {
            int count = data.getInt(8);
            if (faceIndex < 0 || faceIndex >= count) return -1;

            int offsetPosition = 12 + faceIndex * 4;
            if (offsetPosition + 4 > data.limit()) return -1;

            return data.getInt(offsetPosition);
        }

        return faceIndex == 0 ? 0 : -1;
    }

    private static String tag(ByteBuffer data, int offset) {
        StringBuilder builder = new StringBuilder(4);
        for (int i = 0; i < 4; i++) builder.append((char) (data.get(offset + i) & 0xFF));
        return builder.toString();
    }
}
