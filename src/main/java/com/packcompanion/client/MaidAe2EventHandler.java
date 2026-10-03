package com.packcompanion.client;

import com.packcompanion.net.PackCompanionChannel;

import com.github.tartaricacid.touhoulittlemaid.api.event.client.MaidContainerGuiEvent;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 女仆 GUI「打开 AE2 无线终端」按钮（TLM 官方扩展点实现）。
 *
 * <p>订阅 {@code MaidContainerGuiEvent.Init}——TLM 主容器 GUI
 * （AbstractMaidContainerGui.m_7856_）初始化尾部 post 的官方事件，
 * 事件提供 {@code addButton(String, AbstractWidget)}，加入的按钮由
 * TLM 在渲染循环（m_88315_）自动渲染并处理点击（javap 反编确认）。</p>
 *
 * <p><b>本类 import 了 TLM 类型</b>——只能由 {@link MaidAe2Compat}
 * 在确认 TLM/AE2 在位后反射加载注册，桌面实例（无 TLM/AE2）永不加载。</p>
 *
 * <p>按钮行为：onPress → 发 C2S 包，服务端扫玩家背包找 AE2 无线终端
 * （含 ae2wtlib 全系）并 openFromInventory 打开——菜单替换女仆容器
 * （切换式）。绑定方式 = AE2 官方坐标绑定（终端 shift+右键 ME 网络方块）。</p>
 *
 * <p>按钮位置：主界面 256×256，左面板（约 128 宽）与右侧格子区之间的
 * 中缝顶部 (leftPos+131, topPos+6)；如与控件重叠可调。</p>
 */
public final class MaidAe2EventHandler {

    /** 事件按钮注册 key（removeButton 时用）。 */
    public static final String BUTTON_KEY = "packcompanion_ae2_terminal";

    private MaidAe2EventHandler() {
    }

    @SubscribeEvent
    public static void onMaidGuiInit(MaidContainerGuiEvent.Init event) {
        try {
            Button button = Button.builder(
                            Component.literal("AE"),
                            btn -> PackCompanionChannel.sendOpenTerminal())
                    .bounds(event.getLeftPos() + 131, event.getTopPos() + 6, 20, 20)
                    .tooltip(Tooltip.create(Component.literal("打开 AE2 无线终端")))
                    .build();
            event.addButton(BUTTON_KEY, button);
        } catch (Throwable ignored) {
            // 极端情况放弃加按钮，绝不崩 GUI
        }
    }
}