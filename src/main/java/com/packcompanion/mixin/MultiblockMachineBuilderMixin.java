package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.registry.registrate.MultiblockMachineBuilder;

import com.packcompanion.gt.PatternBuildContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * 给「机器定义 → pattern 构建」之间补一个上下文。
 *
 * <p>背景：{@code MultiblockMachineBuilder.register()} 里唯一相关的动作是
 * {@code definition.setPatternFactory(GTMemoizer.memoize(() -> this.pattern.apply(definition)))}。
 * pattern 是**延迟**构建的，构建期间的 {@code FactoryBlockPattern.where(...)} 拿不到
 * 机器身份；而「劳保线程仓只能装进温室」必须在注入候选方块时就知道机器身份。</p>
 *
 * <p>做法：在 {@code register()} 返回后（普通 {@code @Inject}，机制上最稳；
 * 不用 {@code @ModifyVariable} 改参数、也不依赖 MixinExtras），把已经存进去的
 * pattern 工厂取出、包一层，真正构建时把 definition 放进 {@link PatternBuildContext}。</p>
 *
 * <p>覆盖范围：GTM 自身多方块、KubeJS 的 {@code multiblock} 机器
 * （KJSWrappingMultiblockBuilder 也构造 MultiblockMachineBuilder）、以及第三方 addon
 * （GTCA 等，都是 {@code REGISTRATE.multiblock(...)}）——它们都经过本方法。</p>
 */
@Mixin(MultiblockMachineBuilder.class)
public abstract class MultiblockMachineBuilderMixin {

    @Inject(method = "register()Lcom/gregtechceu/gtceu/api/machine/MultiblockMachineDefinition;",
            at = @At("RETURN"), remap = false)
    private void packcompanion$trackPatternBuildContext(
            CallbackInfoReturnable<MultiblockMachineDefinition> cir) {
        MultiblockMachineDefinition definition = cir.getReturnValue();
        if (definition == null) return;

        Supplier<BlockPattern> original;
        try {
            original = definition.getPatternFactory();
        } catch (Throwable t) {
            // 极端时序（例如 pattern 缺失抛出）时不干预注册流程
            return;
        }
        if (original == null) return;

        // 每次 register() 都会写入一个新的 memoize 包装，因此这里再包一层即可，不会叠加
        definition.setPatternFactory(() -> PatternBuildContext.runWith(definition, original));
    }
}
