/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一段文字按像素宽度硬折行（按字符折，中文没有空格也能折）。
 * Meteor 自带的自动换行按空格断词，中文长句会顶出屏幕，这里补上。
 */
public final class WrappedText {
    private WrappedText() {}

    public static String wrap(GuiTheme theme, String text, double maxPx) {
        if (text == null || text.isEmpty()) return "";

        List<String> lines = new ArrayList<>();
        for (String logical : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            double width = 0;

            for (int i = 0; i < logical.length(); ) {
                int codePoint = logical.codePointAt(i);
                String piece = new String(Character.toChars(codePoint));
                double pieceWidth = theme.textWidth(piece, piece.length(), false);

                if (line.length() > 0 && width + pieceWidth > maxPx) {
                    lines.add(line.toString());
                    line.setLength(0);
                    width = 0;
                }

                line.append(piece);
                width += pieceWidth;
                i += Character.charCount(codePoint);
            }

            lines.add(line.toString());
        }

        return String.join("\n", lines);
    }
}
