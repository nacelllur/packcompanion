package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.PredicateBlocks;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import com.packcompanion.PackCompanion;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import net.minecraftforge.registries.ForgeRegistries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让"并行仓 + 线程仓"可以在同一个多方块里共存。
 *
 * GTM 的 Predicates.autoAbilities(maintenance, muffler, parallel=true) 会给并行能力
 * 槽位设置 setMaxGlobalLimited(1)——整个结构只允许 1 个并行类部件。线程仓的方块注册在
 * PARALLEL_HATCH 能力下（用于放进该槽位），因此与原版并行仓互相顶替，无法同时放置。
 *
 * 本 mixin 在谓词构建完成后，把"候选方块中包含本 mod 线程仓"且上限为 1 的并行谓词
 * 上限提升到 2：一个名额给原版并行仓、一个名额给线程仓。两者效果独立叠加：
 * 并行仓提供每条配方的倍率（hatchParallel），线程仓提供独立配方条数
 * （MultiThreadedRecipeLogic），总吞吐 = 并行数 × 线程数。
 *
 * 只放宽恰好为 1 的上限：若其它 addon 已自定义更高的上限则不做干预；
 * 维护/消音等其它 maxCount==1 的谓词因候选方块不含本 mod 方块而不受影响。
 */
@Mixin(Predicates.class)
public abstract class PredicatesMixin {

    @Inject(method = "autoAbilities(ZZZ)Lcom/gregtechceu/gtceu/api/pattern/TraceabilityPredicate;",
            at = @At("RETURN"), remap = false)
    private static void packcompanion$allowParallelAndThreadHatch(boolean checkMaintenance, boolean checkMuffler,
            boolean checkParallel, CallbackInfoReturnable<TraceabilityPredicate> cir) {
        if (!checkParallel) return;
        TraceabilityPredicate predicate = cir.getReturnValue();
        if (predicate == null || predicate.limited == null) return;
        for (SimplePredicate simple : predicate.limited) {
            if (simple == null || simple.maxCount != 1) continue;
            if (!packcompanion$acceptsThreadHatch(simple)) continue;
            simple.maxCount = 2;
        }
    }

    /** 该谓词的候选方块里是否包含本 mod 的方块（即线程仓） */
    private static boolean packcompanion$acceptsThreadHatch(SimplePredicate simple) {
        if (!(simple instanceof PredicateBlocks blocks) || blocks.blocks == null) return false;
        for (Block block : blocks.blocks) {
            if (block == null) continue;
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (key != null && PackCompanion.MODID.equals(key.getNamespace())) {
                return true;
            }
        }
        return false;
    }
}
