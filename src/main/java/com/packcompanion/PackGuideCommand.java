package com.packcompanion;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public class PackGuideCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("packguide")
                .requires(src -> src.hasPermission(0))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ItemStack book = PackCompanion.makeGuideBook();
                    if (!player.getInventory().add(book)) {
                        player.drop(book, false);
                    }
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§a已发放整合包指南书！"), false);
                    return 1;
                }));
    }
}
