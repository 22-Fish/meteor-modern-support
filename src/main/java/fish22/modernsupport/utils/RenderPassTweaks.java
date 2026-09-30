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

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;

/**
 * 渲染通道优化。
 *
 * <p>meteor 的界面和一部分透视绘制用的管线是「深度测试永远通过 + 不写深度」，
 * 也就是说深度信息根本不会被用到。但通道是按管线要不要深度来开的，于是每开一个这样的通道，
 * 显卡都要白读一遍、白写一遍整屏深度。界面一帧开几十个通道，攒起来就是可观的显存带宽。
 *
 * <p>这里遇到这种管线就不挂深度附件：画面完全一样（深度既不参与判断也不被改写），
 * 少掉的只是整屏深度的读和写。
 */
public final class RenderPassTweaks {
    private RenderPassTweaks() {
    }

    /** 这条管线是否会碰到深度（不碰就没必要挂深度附件） */
    public static boolean unusedDepth(RenderPipeline pipeline) {
        if (pipeline == null || !RenderSettings.skipUnusedDepthEnabled()) return false;

        DepthStencilState state = pipeline.getDepthStencilState();
        if (state == null) return true;

        return state.depthTest() == CompareOp.ALWAYS_PASS && !state.writeDepth();
    }
}
