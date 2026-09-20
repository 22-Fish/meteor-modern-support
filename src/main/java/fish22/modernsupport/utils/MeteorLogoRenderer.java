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

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * 把 {@link MeteorLogoPainter} 画的帧塞进动态贴图，再贴到加载画面上。
 *
 * <p>贴图按屏幕像素比渲染（画多清楚就贴多清楚），只有动画状态变了才重画一帧，
 * 淡入淡出交给 blit 的颜色参数，不用每帧重算。
 */
public final class MeteorLogoRenderer {
    private static final Identifier TEXTURE_ID =
        Identifier.fromNamespaceAndPath("meteor-modern-support", "meteor_loading_logo");

    /** 贴图最大宽度（够清楚又不至于每帧上传太多数据） */
    private static final int MAX_TEXTURE_WIDTH = 1024;

    private static MeteorLogoPainter painter;
    private static DynamicTexture texture;
    private static int texWidth;
    private static int texHeight;
    private static int[] rgbaScratch;
    private static double paintedDrawMs = Double.NaN;
    private static double paintedLightMs = Double.NaN;

    private MeteorLogoRenderer() {
    }

    public static boolean enabled() {
        return RenderSettings.loadingAnimationEnabled();
    }

    /**
     * 在原版 logo 的位置画一帧 meteor logo 动画。
     *
     * @param x         原版 logo 条带左边
     * @param y         原版 logo 条带上边
     * @param boxWidth  条带宽（原版是 4d）
     * @param boxHeight 条带高（原版是 d）
     * @param color     原版算好的淡入淡出颜色（含 alpha）
     */
    public static void draw(GuiGraphicsExtractor gfx, int x, int y, int boxWidth, int boxHeight, int color,
                            double drawMs, double lightMs) {
        if (boxWidth <= 0 || boxHeight <= 0) return;
        if ((color >>> 24) == 0) return;

        double aspect = (MeteorLogoPainter.VIEW_HEIGHT + MeteorLogoPainter.PAD * 2.0)
            / (MeteorLogoPainter.VIEW_WIDTH + MeteorLogoPainter.PAD * 2.0);
        int width = boxWidth;
        int height = Math.max(1, (int) Math.round(boxWidth * aspect));
        int top = y + (boxHeight - height) / 2;

        Minecraft mc = Minecraft.getInstance();
        double guiScale = mc.getWindow().getGuiScale();
        int newTexWidth = (int) Math.round(Math.max(64.0, Math.min(MAX_TEXTURE_WIDTH, width * guiScale)));
        int newTexHeight = Math.max(8, (int) Math.round(newTexWidth * aspect));
        if (!ensureTexture(mc, newTexWidth, newTexHeight)) return;

        if (drawMs != paintedDrawMs || lightMs != paintedLightMs) {
            painter.paint(texWidth, texHeight, drawMs, lightMs);
            upload();
            paintedDrawMs = drawMs;
            paintedLightMs = lightMs;
        }

        gfx.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, x, top, 0.0f, 0.0f, width, height,
            texWidth, texHeight, texWidth, texHeight, color);
    }

    private static boolean ensureTexture(Minecraft mc, int width, int height) {
        if (texture == null || width != texWidth || height != texHeight) {
            if (texture != null) mc.getTextureManager().release(TEXTURE_ID);

            texWidth = width;
            texHeight = height;
            painter = new MeteorLogoPainter();
            rgbaScratch = new int[width * height];
            paintedDrawMs = Double.NaN;
            paintedLightMs = Double.NaN;
            texture = new DynamicTexture("meteor-loading-logo", width, height, true);
        }

        // 资源包重载后动态贴图还在，这里顺手补注册，代价只有一次 map put
        mc.getTextureManager().register(TEXTURE_ID, texture);
        return true;
    }

    /** 画布（ARGB）→ NativeImage（内存里按 RGBA 排列）→ 上传 */
    private static void upload() {
        NativeImage pixels = texture.getPixels();
        if (pixels == null || pixels.isClosed()) return;

        int[] src = painter.pixels();
        for (int i = 0; i < src.length; i++) {
            int c = src[i];
            rgbaScratch[i] = (c & 0xFF000000) | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >>> 16) & 0xFF);
        }

        IntBuffer buffer = MemoryUtil.memIntBuffer(pixels.getPointer(), texWidth * texHeight);
        buffer.put(rgbaScratch);
        texture.upload();
    }
}
