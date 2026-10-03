package com.packcompanion.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 女仆 GUI × AE2 终端按钮的注册入口（条件加载守卫）。
 *
 * <p>直接注册 import 了 TLM 事件类型的处理器类，会在桌面实例
 * （无 TLM/AE2）触发 NoClassDefFoundError。因此本类不 import 任何
 * TLM/AE2 类型，只在确认两个 mod 都存在后，用 {@code Class.forName}
 * 反射加载真正的处理器 {@link MaidAe2EventHandler} 并注册到 Forge 总线
 * ——延迟加载保证类型解析发生在 mod 齐备之后。</p>
 *
 * <p>调用点：{@code PackCompanion} 构造期（仅客户端侧执行有效，
 * 服务端 GUI 事件不触发、注册无害但为省事仍判 Dist）。</p>
 */
public final class MaidAe2Compat {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean registered;

    private MaidAe2Compat() {
    }

    /** 条件注册：TLM + AE2 同时在位才挂事件处理器。 */
    public static void register() {
        if (registered) return;
        registered = true;
        try {
            net.minecraftforge.fml.ModList modList = net.minecraftforge.fml.ModList.get();
            if (!modList.isLoaded("touhou_little_maid") || !modList.isLoaded("ae2")) {
                LOGGER.info("[packcompanion] maid AE2 terminal button skipped (TLM/AE2 missing)");
                return;
            }
            Class<?> handler = Class.forName("com.packcompanion.client.MaidAe2EventHandler");
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(handler);
            LOGGER.info("[packcompanion] maid AE2 terminal button registered");
        } catch (Throwable t) {
            LOGGER.warn("[packcompanion] maid AE2 terminal button register failed", t);
        }
    }
}