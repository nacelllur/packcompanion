package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.packcompanion.Config;

import net.minecraft.resources.ResourceLocation;

/**
 * 「这台机器是不是温室」的判定，供「劳保线程仓只能装进温室」使用。
 *
 * <p>两种命中方式（任一成立即算温室）：</p>
 * <ol>
 *   <li>机器 id 出现在配置 {@code gtThreadHatch.greenhouseMachines} 里
 *       （默认 {@code gtceu:greenhouse}、{@code gtca:green_house}）；</li>
 *   <li>机器的配方类型 id 路径里含 {@code greenhouse} 或 {@code green_house}
 *       —— 覆盖其它 mod 新增的温室，不必改配置。</li>
 * </ol>
 *
 * <p>判定发生在 pattern 构建期，理论上可能早于某些注册步骤，因此整体兜底捕获异常并按
 * 「非温室」处理：判定失败最坏结果是劳保线程仓在那台机器上放不进去（退回普通线程仓），
 * 不会影响其它机器的 pattern 构建。</p>
 */
public final class Greenhouses {

    private Greenhouses() {}

    public static boolean isGreenhouse(MachineDefinition definition) {
        if (definition == null) return false;
        try {
            ResourceLocation id = definition.getId();
            if (id != null && Config.isGreenhouseMachine(id.toString())) return true;

            GTRecipeType[] types = definition.getRecipeTypes();
            if (types != null) {
                for (GTRecipeType type : types) {
                    if (type == null || type.registryName == null) continue;
                    String path = type.registryName.getPath();
                    if (path.contains("greenhouse") || path.contains("green_house")) return true;
                }
            }
        } catch (Throwable ignored) {
            // 注册时序异常：按非温室处理
        }
        return false;
    }
}
