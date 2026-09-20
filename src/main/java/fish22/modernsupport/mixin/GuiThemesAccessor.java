package fish22.modernsupport.mixin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** 读取全部 GUI 主题（翻译主题设置时需要遍历所有主题） */
@Mixin(value = GuiThemes.class, remap = false)
public interface GuiThemesAccessor {
    @Accessor("themes")
    static List<GuiTheme> modernsupport$getThemes() {
        throw new AssertionError();
    }
}
