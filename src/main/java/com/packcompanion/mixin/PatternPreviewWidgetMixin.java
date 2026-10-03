package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.gui.widget.PatternPreviewWidget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * 修 GTM 7.5.3 的「JEI 多方块预览点击崩溃」。
 *
 * <p>复现：JEI 里打开任意多方块的预览 → 在 3D 预览里点一下 → 客户端崩溃
 * {@code NullPointerException: ... "this.patterns[this.index].predicateMap" is null}。</p>
 *
 * <p>成因（GTM 自身的缺陷）：{@code PatternPreviewWidget.initializePattern} 会用 shapeInfo 在
 * 假世界里跑一次 {@code checkPatternAt}，好把 {@code matchContext} 里的
 * {@code "predicates"} 拿出来当 {@code predicateMap}。如果那套 shapeInfo 在假世界里
 * <b>成不了形</b>（最常见的原因：pattern 的外壳谓词 or 了 {@code autoAbilities(...)}，
 * 要求至少 1 个能量仓/维护仓，而预览里没摆），这个 key 就不会被写入，
 * {@code predicateMap} 直接是 {@code null} —— 而 {@code onPosSelected} 在点击时无条件
 * {@code predicateMap.get(pos)}，于是必崩。</p>
 *
 * <p>注意这不是「装了某个 addon 才有的」问题：GTM 自带的
 * {@code gtceu:assembly_line} / {@code electric_blast_furnace} / {@code *_large_turbine}
 * 等机器的预览同样成不了形，点它们一样崩。所以这里在客户端统一兜底。</p>
 *
 * <p>本 mixin 只把那一处 {@code Map.get} 换成空安全调用：map 为 null 时返回 null，
 * 于是 {@code onPosSelected} 里的 {@code predicate != null} 判定为假、直接跳过
 * （点击不显示候选方块，但不再崩游戏）。同时**本 mod 自己注册的机器**应当从源头解决：
 * 用 {@code tools/litematic2gtm.py --preview-part} 往 shapeInfo 里注入能量/维护仓，
 * 让预览真的能成形（见 {@code tools/verify_preview_parts.py} 的验收）。</p>
 */
@Mixin(PatternPreviewWidget.class)
public abstract class PatternPreviewWidgetMixin {

    @Redirect(method = "onPosSelected(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)V",
              at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
              remap = false)
    private Object packcompanion$nullSafePredicateMapGet(Map<?, ?> predicateMap, Object pos) {
        return predicateMap == null ? null : predicateMap.get(pos);
    }
}
