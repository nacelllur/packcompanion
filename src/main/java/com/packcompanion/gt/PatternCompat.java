package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.PredicateBlocks;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;

import com.mojang.logging.LogUtils;
import com.packcompanion.Config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 让线程仓可以被放进「本来能放仓室部件」的槽位。
 *
 * <p>背景：GTM 的机器/第三方 mod 的机器（含 GTCA 温室）绝大多数只在 pattern 里
 * {@code .or(Predicates.autoAbilities(recipeTypes))}，即只开放能量/物品/流体仓，
 * 并没有专门的并行槽位。这类结构在编译期就定死了候选方块，addon 无法追加。
 * 于是本类在 pattern 定稿时（{@code FactoryBlockPattern.where}）追加一个
 * 「线程仓候选」，使其与原有仓室共用同一批槽位。</p>
 *
 * <p>为什么放在 {@code where} 阶段：此时该符号的谓词已经完全构建完（所有
 * {@code .or()} / {@code setMinGlobalLimited} 都已经调用过），追加候选不会再被
 * 后续的限流调用波及；而且追加的候选自带 {@code maxCount}，不会影响原有候选的
 * 计数与结构校验。</p>
 */
public final class PatternCompat {

    /** 每个多方块最多允许的线程仓数量（与原版并行槽上限一致） */
    private static final int MAX_THREAD_HATCHES = 2;

    /** 「仓室槽位」对应的一组能力：只要某个符号原本接受其中任意一种，线程仓就也能放上去 */
    private static final PartAbility[] IO_SLOT_ABILITIES = {
            PartAbility.INPUT_ENERGY,
            PartAbility.OUTPUT_ENERGY,
            PartAbility.SUBSTATION_INPUT_ENERGY,
            PartAbility.SUBSTATION_OUTPUT_ENERGY,
            PartAbility.IMPORT_ITEMS,
            PartAbility.EXPORT_ITEMS,
            PartAbility.IMPORT_FLUIDS,
            PartAbility.EXPORT_FLUIDS,
            PartAbility.IMPORT_FLUIDS_1X,
            PartAbility.EXPORT_FLUIDS_1X,
            PartAbility.IMPORT_FLUIDS_4X,
            PartAbility.EXPORT_FLUIDS_4X,
            PartAbility.IMPORT_FLUIDS_9X,
            PartAbility.EXPORT_FLUIDS_9X,
            PartAbility.PARALLEL_HATCH,
            PartAbility.PASSTHROUGH_HATCH,
    };

    /** IO_SLOT_ABILITIES 里所有方块 id 的并集（懒求值后缓存，避免每次 O(n) 扫描） */
    private static Set<Block> ioSlotBlocks;

    private static final Logger LOGGER = LogUtils.getLogger();

    private PatternCompat() {}

    /**
     * 若该符号谓词原本接受仓室部件，则把线程仓追加为同一槽位的合法方块。
     * 由 {@code FactoryBlockPatternMixin} 在每个 {@code where(...)} 时调用。
     *
     * <p>温室额外获得「劳保线程仓」（256 线程，且只允许装在温室）：
     * 该方块不注册任何 PartAbility，只在这里被注入，因此离开温室就无法成形。</p>
     */
    public static void acceptThreadHatch(TraceabilityPredicate predicate) {
        if (predicate == null) return;
        if (predicate.isAny() || predicate.isAir()) return;
        // 排除名单：这些机器明确「不可使用线程仓」（如 gtoecore 的生物系主机），
        // 即使它们开放了物品/流体仓槽位也不注入线程仓。
        if (isThreadHatchExcluded()) return;
        if (!Config.isInstallInAnyIOPort()) return;
        if (!acceptsIOPart(predicate)) return;

        appendCandidates(predicate, ThreadHatchBlocks.get());

        if (!Config.isLaborHatchInGreenhouse()) return;

        // 温室限定：只有当前正在构建 pattern 的机器是温室时，才把劳保线程仓作为候选
        MachineDefinition definition = PatternBuildContext.current();
        if (definition == null) {
            // 说明该 pattern 不是经 MultiblockMachineBuilder.register() 构建的（未覆盖路径）：
            // 此时无法判断机器身份，保守地不注入劳保线程仓，并提示一次便于定位。
            if (NO_CONTEXT_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("[packcompanion] 有 pattern 在没有机器上下文的情况下构建 → 劳保线程仓不会注入该机器。"
                        + "（该机器可能不是通过 MultiblockMachineBuilder 注册的；普通线程仓不受影响）");
            }
            return;
        }
        if (!Greenhouses.isGreenhouse(definition)) return;

        appendCandidates(predicate, ThreadHatchBlocks.labor());
        ResourceLocation id = definition.getId();
        if (id != null && LOGGED_GREENHOUSES.add(id.toString())) {
            LOGGER.info("[packcompanion] 劳保线程仓已注入温室 pattern：{}", id);
        }
    }

    /** 已提示过「劳保线程仓注入成功」的温室 id，避免同一机器重复刷日志 */
    private static final Set<String> LOGGED_GREENHOUSES = ConcurrentHashMap.newKeySet();
    /** 「无机器上下文」只提示一次 */
    private static final AtomicBoolean NO_CONTEXT_LOGGED = new AtomicBoolean(false);

    /** 明确「不可使用线程仓」的机器 id（这些机器即使开放物品/流体仓也不注入线程仓） */
    private static final Set<String> THREAD_HATCH_EXCLUDED = Set.of(
            "gtoecore:clone_production_workshop",
            "gtoecore:clone_manufacturing_chamber",
            "gtoecore:clone_maintenance_room");

    /**
     * 当前正在构建 pattern 的机器是否「不可使用线程仓」。两条判据：
     *
     * <ol>
     *   <li>机器 id 在 {@link #THREAD_HATCH_EXCLUDED} 名单里（如 gtoecore 生物系主机）；</li>
     *   <li>机器使用<b>聚变配方类型</b>（{@link GTRecipeTypes#FUSION_RECIPES}）——
     *       聚变机器（GT 原版聚变反应堆 MK1~3、GTOE 聚变光源核心）持有共享的
     *       {@code heat}/{@code color} 状态，且配方靠 {@code eu_to_start} 预热记账，
     *       本质是「一条配方一台机」；多线程并行会破坏预热语义，故一律不开线程仓槽位。
     *       <b>用配方类型而非硬编码 id 判定</b>，可自动覆盖后续新增及第三方聚变机器。</li>
     * </ol>
     */
    private static boolean isThreadHatchExcluded() {
        MachineDefinition definition = PatternBuildContext.current();
        if (definition == null) return false;
        ResourceLocation id = definition.getId();
        if (id != null && THREAD_HATCH_EXCLUDED.contains(id.toString())) return true;
        return usesFusionRecipes(definition);
    }

    /** 该机器是否使用聚变配方类型（用于识别聚变机器，见 {@link #isThreadHatchExcluded()}） */
    private static boolean usesFusionRecipes(MachineDefinition definition) {
        GTRecipeType[] types = definition.getRecipeTypes();
        if (types == null) return false;
        for (GTRecipeType type : types) {
            if (type == GTRecipeTypes.FUSION_RECIPES) return true;
        }
        return false;
    }

    /** 把一批方块作为「同槽位的额外候选」追加进谓词（已存在则不重复追加） */
    private static void appendCandidates(TraceabilityPredicate predicate, Block[] blocks) {
        if (blocks == null || blocks.length == 0) return;
        if (containsAnyBlock(predicate.common, blocks) || containsAnyBlock(predicate.limited, blocks)) return;

        PredicateBlocks candidate = new PredicateBlocks(blocks);
        // minCount 保持 -1（不改结构要求），只限制上限并给 JEI 预览留 1 个位置
        candidate.maxCount = MAX_THREAD_HATCHES;
        candidate.previewCount = 1;
        predicate.limited.add(candidate);
    }

    /** 谓词候选里是否已经包含 blocks 中的任意一个方块 */
    private static boolean containsAnyBlock(java.util.List<SimplePredicate> list, Block[] blocks) {
        for (SimplePredicate simple : list) {
            if (!(simple instanceof PredicateBlocks pb) || pb.blocks == null) continue;
            for (Block have : pb.blocks) {
                if (have == null) continue;
                for (Block want : blocks) {
                    if (have == want) return true;
                }
            }
        }
        return false;
    }

    /** 该符号谓词的候选方块里，是否包含任意「仓室部件」 */
    private static boolean acceptsIOPart(TraceabilityPredicate predicate) {
        Set<Block> parts = ioSlotBlocks();
        if (parts.isEmpty()) return false;
        return containsAny(predicate.common, parts) || containsAny(predicate.limited, parts);
    }

    private static boolean containsAny(java.util.List<SimplePredicate> list, Set<Block> parts) {
        for (SimplePredicate simple : list) {
            if (!(simple instanceof PredicateBlocks blocks) || blocks.blocks == null) continue;
            for (Block block : blocks.blocks) {
                if (block != null && parts.contains(block)) return true;
            }
        }
        return false;
    }

    private static Set<Block> ioSlotBlocks() {
        Set<Block> cached = ioSlotBlocks;
        if (cached != null) return cached;

        Set<Block> union = new HashSet<>();
        for (PartAbility ability : IO_SLOT_ABILITIES) {
            union.addAll(ability.getAllBlocks());
        }
        // 空集合说明 GTM 部件尚未注册（极端时序）——不缓存，留给下次重试
        if (union.isEmpty()) return Collections.emptySet();

        ioSlotBlocks = union;
        return union;
    }
}
