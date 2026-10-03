package com.packcompanion.gt;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.gregtechceu.gtceu.common.data.models.GTMachineModels;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;

import static com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.PARALLEL_HATCH;

/**
 * GTM addon 注册入口：7 档线程仓（UV → MAX）。
 * 模型直接复用 GTM 并行仓 overlay 贴图（占位），由 GTM 运行时模型管线生成 blockstate/模型 JSON。
 */
public class PCMachines {

    public static final GTRegistrate REGISTRATE = GTRegistrate.create(PackCompanionGT.MODID);

    /** 线程数：UV=4, UHV=8, UEV=16, UIV=32, UXV=64, OpV=128, MAX=256 */
    private static final int[] TIERS = {
            GTValues.UV, GTValues.UHV, GTValues.UEV, GTValues.UIV, GTValues.UXV, GTValues.OpV, GTValues.MAX
    };

    /** 复用的 GTM 并行仓贴图档位（占位美术）：UV→mk1 ... MAX→mk4 */
    private static final int[] OVERLAY_MK = {1, 2, 2, 3, 3, 4, 4};

    /** 在 GTM initializeAddon 回调时赋值（构造期注册会导致 GTMachineModels 提前类初始化 → NPE） */
    public static MachineDefinition[] THREAD_HATCH;

    /** 劳保线程仓：固定 256 线程，且只允许装进温室（见 PatternCompat / Greenhouses） */
    public static MachineDefinition LABOR_THREAD_HATCH;

    /** 劳保线程仓的线程数（= MAX 档） */
    public static final int LABOR_THREADS = 256;

    /** 劳保线程仓自己的覆层贴图目录（由 GTM 并行仓 mk4 贴图改色而来，见资源文件） */
    private static final ResourceLocation LABOR_OVERLAY =
            new ResourceLocation(PackCompanionGT.MODID, "block/machines/labor_thread_hatch");

    private static MachineDefinition[] registerThreadHatches() {
        MachineDefinition[] defs = new MachineDefinition[TIERS.length];
        for (int i = 0; i < TIERS.length; i++) {
            int tier = TIERS[i];
            String vn = GTValues.VN[tier].toLowerCase();
            int threads = 4 << (tier - GTValues.UV);
            int mk = OVERLAY_MK[i];
            defs[i] = REGISTRATE
                    .machine("thread_hatch_" + vn, holder -> new ThreadHatchPartMachine(holder, tier))
                    .langValue(GTValues.VN[tier] + " Thread Hatch")
                    .rotationState(RotationState.ALL)
                    // 必须设置 tier：模型工厂 tieredHullModel() 用 builder.getOwner().getTier()
                    // 选择底壳模型，缺省 0 会取到不存在的 hull 模型 → 全脸缺失贴图（紫黑棋盘格）
                    .tier(tier)
                    .abilities(PARALLEL_HATCH)
                    .modelProperty(GTMachineModelProperties.IS_FORMED, false)
                    .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
                    // 延迟到模型烘焙期再引用 GTMachineModels：其类初始化依赖 GTMaterials，
                    // 而材质注册晚于 mod 构造期，提前引用会导致 ExceptionInInitializerError
                    .model((ctx, prov, builder) -> GTMachineModels.createWorkableTieredHullMachineModel(
                            GTCEu.id("block/machines/parallel_hatch_mk" + mk)).configureModel(ctx, prov, builder))
                    .tooltips(
                            Component.translatable("packcompanion.machine.thread_hatch.tooltip"),
                            Component.translatable("packcompanion.machine.thread_hatch.max_threads", threads))
                    .register();
        }
        return defs;
    }

    /**
     * 劳保线程仓：256 线程，</b>只能装进温室</b>。
     *
     * <p>关键点：这里刻意【不】调用 {@code .abilities(PARALLEL_HATCH)}——不注册任何
     * PartAbility，任何 {@code Predicates.abilities(...)} / {@code autoAbilities(...)}
     * 都认不到它，因此它在普通多方块里放上去不会成形。它的合法性只来自
     * {@link PatternCompat} 在「当前机器是温室」时注入的候选方块（判定见
     * {@link Greenhouses}）。</p>
     *
     * <p>线程数固定为 {@link #LABOR_THREADS}（256）：直接复用 MAX 档的 ThreadHatchPartMachine，
     * 其 maxThreads = 4 &lt;&lt; (MAX - UV) = 256，GUI 仍可在 1..256 之间调节。</p>
     */
    private static MachineDefinition registerLaborThreadHatch() {
        int tier = GTValues.MAX;
        return REGISTRATE
                .machine("labor_thread_hatch", holder -> new ThreadHatchPartMachine(holder, tier))
                .langValue("Labor Thread Hatch")
                .rotationState(RotationState.ALL)
                .tier(tier)
                .modelProperty(GTMachineModelProperties.IS_FORMED, false)
                .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
                .model((ctx, prov, builder) -> GTMachineModels.createWorkableTieredHullMachineModel(LABOR_OVERLAY)
                        .configureModel(ctx, prov, builder))
                .tooltips(
                        Component.translatable("packcompanion.machine.labor_thread_hatch.tooltip"),
                        Component.translatable("packcompanion.machine.thread_hatch.max_threads", LABOR_THREADS),
                        Component.translatable("packcompanion.machine.labor_thread_hatch.greenhouse_only"))
                .register();
    }

    /** 由 GTM 的 initializeAddon 回调调用：材质与机器注册完成后才安全 */
    public static void initMachines() {
        THREAD_HATCH = registerThreadHatches();
        LABOR_THREAD_HATCH = registerLaborThreadHatch();
    }

    /** 在 mod 构造期调用：注册 registrate 事件监听 */
    public static void register(IEventBus modEventBus) {
        REGISTRATE.registerRegistrate();
    }
}
