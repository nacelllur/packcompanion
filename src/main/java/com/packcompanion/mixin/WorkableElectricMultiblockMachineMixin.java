package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.packcompanion.gt.MultiThreadedRecipeLogic;

import net.minecraft.network.chat.Component;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在电力多方块 GUI 文本末尾追加线程仓状态（检测到几个线程仓、启用多少线程、
 * 每个线程当前运行的配方与进度）。数据由 MultiThreadedRecipeLogic 的
 * DescSynced 字段从服务器同步，客户端只负责展示。
 */
@Mixin(WorkableElectricMultiblockMachine.class)
public abstract class WorkableElectricMultiblockMachineMixin {

    @Inject(method = "addDisplayText", at = @At("TAIL"), remap = false)
    private void packcompanion$appendThreadStatus(List<Component> textList, CallbackInfo ci) {
        RecipeLogic logic = ((IRecipeLogicMachine) (Object) this).getRecipeLogic();
        if (logic instanceof MultiThreadedRecipeLogic mtl) {
            mtl.appendThreadStatusDisplay(textList);
        }
    }
}
