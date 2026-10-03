package com.packcompanion.net;

import com.packcompanion.PackCompanion;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * packcompanion 的 Forge 网络通道（客户端 → 服务端 C2S）。
 *
 * <p>用途：女仆 GUI 的「打开 AE2 无线终端」按钮——客户端点击按钮
 * 发 {@link OpenAE2TerminalPacket}，服务端扫描玩家背包找到 AE2 无线终端
 * （含 ae2wtlib 的 WCT/WAT/WET/WUT，均继承 WirelessTerminalItem），
 * 调其 {@code openFromInventory} 打开菜单——AE2 的 MenuOpener 服务端
 * 打开菜单即天然替换当前容器菜单（"切换式"）。</p>
 *
 * <p><b>AE2 调用全走反射</b>（与 SpaceCamCompat 同套路）：桌面实例
 * （versions/GT-New）没有 AE2，若直接 import
 * {@code appeng.items.tools.powered.WirelessTerminalItem}，
 * 消息类加载时就会 NoClassDefFoundError。反射句柄首次成功解析后缓存，
 * AE2 缺失时静默提示。</p>
 */
public final class PackCompanionChannel {

    private static final String PROTOCOL_VERSION = "1";
    private static int nextId = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(PackCompanion.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private PackCompanionChannel() {
    }

    /** 在 {@code PackCompanion} 构造期调用，进程内只注册一次。 */
    public static void register() {
        CHANNEL.registerMessage(nextId++,
                OpenAE2TerminalPacket.class,
                OpenAE2TerminalPacket::encode,
                OpenAE2TerminalPacket::decode,
                OpenAE2TerminalPacket::handle);
    }

    /** 客户端：向服务端请求打开 AE2 无线终端。 */
    public static void sendOpenTerminal() {
        CHANNEL.sendToServer(new OpenAE2TerminalPacket());
    }

    /** 数据包本体（无载荷，纯触发）。 */
    @SuppressWarnings("unused")
    public static class OpenAE2TerminalPacket {

        /** 反射缓存：WirelessTerminalItem 类 + openFromInventory(Player,int) 方法。 */
        private static volatile boolean resolved;
        private static Class<?> wtiClass;
        private static java.lang.reflect.Method openFromInventory;

        public OpenAE2TerminalPacket() {
        }

        public void encode(FriendlyByteBuf buf) {
            // 无载荷
        }

        public static OpenAE2TerminalPacket decode(@SuppressWarnings("unused") FriendlyByteBuf buf) {
            return new OpenAE2TerminalPacket();
        }

        public static void handle(OpenAE2TerminalPacket msg,
                                  java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                net.minecraft.server.level.ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    ctx.get().setPacketHandled(true);
                    return;
                }
                resolve();
                if (wtiClass == null || openFromInventory == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "[AE] 未安装 AE2，无线终端不可用"));
                    ctx.get().setPacketHandled(true);
                    return;
                }
                // 扫描背包，找第一个继承 WirelessTerminalItem 的物品（含 ae2wtlib 终端）
                net.minecraft.world.entity.player.Inventory inv = player.getInventory();
                try {
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        net.minecraft.world.item.ItemStack stack = inv.getItem(i);
                        if (stack.isEmpty()) continue;
                        if (wtiClass.isInstance(stack.getItem())) {
                            boolean ok = (Boolean) openFromInventory.invoke(stack.getItem(), player, i);
                            if (!ok) {
                                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                        "[AE] 无线终端无法打开：未绑定 ME 网络或能量不足"));
                            }
                            ctx.get().setPacketHandled(true);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "[AE] 打开无线终端失败：" + t.getClass().getSimpleName()));
                }
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[AE] 背包里没有 AE2 无线终端（普通/合成/仓库终端均可）"));
            });
            ctx.get().setPacketHandled(true);
        }

        private static void resolve() {
            if (resolved) return;
            resolved = true;
            try {
                wtiClass = Class.forName("appeng.items.tools.powered.WirelessTerminalItem");
                openFromInventory = wtiClass.getMethod("openFromInventory",
                        net.minecraft.world.entity.player.Player.class, int.class);
            } catch (Throwable t) {
                wtiClass = null;
                openFromInventory = null;
            }
        }
    }
}