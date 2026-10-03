package com.packcompanion.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * 修 GTM 7.5.3 的「JEI 多方块预览 → 鼠标悬停渲染崩溃」。
 *
 * <p>复现：JEI 里打开任一多方块的「结构信息」页，鼠标在预览上移动 → 客户端崩溃
 * {@code IndexOutOfBoundsException: Index N out of bounds for length M}。</p>
 *
 * <p>成因（GTM 自身的缺陷）：{@code MultiblockInfoCategory.ProxyRecipeWidget.getSlotUnderMouse}
 * 从 JEI slot 名（形如 {@code "slot_13"}）解析出下标，然后
 * {@code widgets.get(index)} 去取 modularUI 的扁平 widget 列表——
 * <b>完全不检查越界</b>。当一台机器的 JEI slot 数比 widget 列表长时
 * （GTNA 液化炉这类带多个 shapeInfo / 子结构的机器最容易触发），
 * 鼠标一悬停就崩。</p>
 *
 * <p>修法：把那一处 {@code List.get} 换成「下标夹取到合法区间」——
 * 越界时返回最后一个合法 widget（而不是抛异常或返回 null 引发 NPE），
 * 于是 {@code slot.setPosition(...)} 拿到的是真实 widget 的坐标，
 * {@code isMouseOver} 自然判定为不命中 → 不显示悬停、不崩溃。</p>
 */
@Mixin(targets = "com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoCategory$1ProxyRecipeWidget")
public abstract class MultiblockInfoCategoryMixin {

    @Redirect(method = "lambda$getSlotUnderMouse$0",
              at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;"),
              remap = false)
    private static Object packcompanion$clampWidgetListGet(List<?> widgets, int index) {
        if (index >= 0 && index < widgets.size()) {
            return widgets.get(index);
        }
        // 越界：夹到最后/第一个合法元素，避免抛 IndexOutOfBounds 或返回 null 导致 NPE
        return widgets.isEmpty() ? null : widgets.get(Math.max(0, Math.min(index, widgets.size() - 1)));
    }
}