package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;

import com.packcompanion.gt.PatternCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让线程仓可以放进任何「能放仓室部件」的槽位。
 *
 * <p>{@code where(char, TraceabilityPredicate)} 是所有 {@code .where(...)}（含字符串
 * 重载，字符串版会委托到这里）的收口点，且此时该符号谓词已经构建完毕——
 * 之后不会再有 {@code setMinGlobalLimited}/{@code setExactLimit} 之类的调用，
 * 因此在这里追加候选既能生效，又不会污染其它候选的计数。</p>
 */
@Mixin(FactoryBlockPattern.class)
public abstract class FactoryBlockPatternMixin {

    @Inject(method = "where(CLcom/gregtechceu/gtceu/api/pattern/TraceabilityPredicate;)Lcom/gregtechceu/gtceu/api/pattern/FactoryBlockPattern;",
            at = @At("HEAD"), remap = false)
    private void packcompanion$acceptThreadHatch(char symbol, TraceabilityPredicate blockMatcher,
            CallbackInfoReturnable<FactoryBlockPattern> cir) {
        PatternCompat.acceptThreadHatch(blockMatcher);
    }
}
