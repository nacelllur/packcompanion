package com.packcompanion;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(PackCompanion.MODID)
public class PackCompanion {
    public static final String MODID = "packcompanion";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String FLAG_WELCOMED = MODID + ":welcomed";
    private static final String FLAG_GOT_BOOK = MODID + ":got_book";

    public PackCompanion() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(this);

        // GTM addon：注册线程仓（需在 mod 构造期、注册事件前完成）
        com.packcompanion.gt.PackCompanionGT.init(FMLJavaModLoadingContext.get().getModEventBus());

        // 网络通道（女仆 GUI 打开 AE2 无线终端）
        com.packcompanion.net.PackCompanionChannel.register();
        // 客户端：女仆 GUI 的 AE2 终端按钮（TLM/AE2 在位才注册）
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            com.packcompanion.client.MaidAe2Compat.register();
        }

        LOGGER.info("Pack Companion loaded");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        PackGuideCommand.register(event.getDispatcher());
    }

    /**
     * 每 tick 推进一次「一键拆除」任务。
     *
     * <p>{@link com.packcompanion.gt.NexusDemolitionHelper} 把整台多方块的拆除摊到多 tick
     * （每 tick 160 格）—— 一个 tick 内清掉 35³ 规模的结构会把光照/邻居更新队列压爆，
     * 表现为服务器直接卡住几十秒。见该类的 {@code BATCH_PER_TICK}。</p>
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            com.packcompanion.gt.NexusDemolitionHelper.tick(level);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        var data = player.getPersistentData();
        boolean firstJoin = !data.getBoolean(FLAG_WELCOMED);
        data.putBoolean(FLAG_WELCOMED, true);

        if (Config.welcomeEveryJoin.get() || firstJoin) {
            player.sendSystemMessage(Component.literal(Config.welcomeMessage.get()));
        }

        if (Config.giveStarterBook.get() && firstJoin && !data.getBoolean(FLAG_GOT_BOOK)) {
            data.putBoolean(FLAG_GOT_BOOK, true);
            ItemStack book = makeGuideBook();
            if (!player.getInventory().add(book)) {
                player.drop(book, false);
            }
        }
    }

    public static ItemStack makeGuideBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", Config.bookTitle.get());
        tag.putString("author", Config.bookAuthor.get());
        tag.putBoolean("resolved", true);

        ListTag pages = new ListTag();
        for (String page : Config.bookPages.get()) {
            pages.add(StringTag.valueOf(
                    Component.Serializer.toJson(Component.literal(page))));
        }
        tag.put("pages", pages);
        return book;
    }
}
