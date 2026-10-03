package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.packcompanion.PackCompanion;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.eventbus.api.IEventBus;

/** GTM addon 初始化入口 */
public class PackCompanionGT {

    public static final String MODID = PackCompanion.MODID;
    private static final ResourceKey<CreativeModeTab> GT_MACHINE_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, new ResourceLocation("gtceu", "machine"));

    public static void init(IEventBus modEventBus) {
        // 构造期只注册 registrate；机器注册由 GTM 的 initializeAddon 回调（PCGTAddon）执行
        PCMachines.register(modEventBus);

        // GTM 在冻结 gtceu:machine 注册表之前会向所有 mod bus 派发 RegisterEvent——
        // 这是 addon 注册机器的官方窗口（initializeAddon 时注册表已冻结，会抛 IllegalStateException）。
        // 注意：GTMachines.init() 派发的是基类 new RegisterEvent(...)，不是 RL 子类——
        // EventBus 按精确类型分派，监听 RL 子类将永远收不到事件（曾导致 THREAD_HATCH 为 null）
        modEventBus.addGenericListener(MachineDefinition.class,
                (GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition> e) -> PCMachines.initMachines());

        // 把线程仓塞进 GT 机器创造模式标签页（注册失败时跳过，避免 NPE 崩游戏）
        modEventBus.addListener((net.minecraftforge.event.BuildCreativeModeTabContentsEvent e) -> {
            if (!e.getTabKey().equals(GT_MACHINE_TAB)) return;
            if (PCMachines.THREAD_HATCH != null) {
                for (var def : PCMachines.THREAD_HATCH) {
                    e.accept(def.asStack());
                }
            }
            if (PCMachines.LABOR_THREAD_HATCH != null) {
                e.accept(PCMachines.LABOR_THREAD_HATCH.asStack());
            }
        });
    }
}
