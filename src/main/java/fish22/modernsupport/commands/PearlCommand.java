/*
 * This file is part of meteor-modern-support (meteor现代化支持).
 * Copyright (c) 2026 22_Fish
 * GPL-3.0-or-later — 见项目根目录 LICENSE。
 */

package fish22.modernsupport.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fish22.modernsupport.modules.PearlBot;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/**
 * {@code .pearl <玩家名>} — 让「珍珠点大管家」去拉指定玩家的珍珠点。
 *
 * <p>和私聊触发的效果完全一样，只是走命令入口，方便手动敲、也方便别的程序
 * （比如 openAI 那边）直接调用。
 */
public class PearlCommand extends Command {

    public PearlCommand() {
        super("pearl", "让珍珠点大管家去拉指定玩家的珍珠点", "pullpearl");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(argument("player", StringArgumentType.word())
            .executes(context -> {
                String player = StringArgumentType.getString(context, "player");

                PearlBot bot = Modules.get().get(PearlBot.class);
                if (bot == null) {
                    error("找不到「珍珠点大管家」模块。");
                    return SINGLE_SUCCESS;
                }

                String reason = bot.pullFor(player);
                if (reason == null) info("已出发去拉 " + player + " 的珍珠点。");
                else warning(reason);

                return SINGLE_SUCCESS;
            })
        );
    }
}
