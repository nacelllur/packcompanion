package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.packcompanion.gt.MultiThreadedRecipeLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把多方块的配方逻辑替换为多线程版本。
 * 无线程仓时 MultiThreadedRecipeLogic 内部直接走原版逻辑，行为零变化。
 */
@Mixin(WorkableMultiblockMachine.class)
public class WorkableMultiblockMachineMixin {

    @Inject(method = "createRecipeLogic", at = @At("HEAD"), cancellable = true, remap = false)
    private void packcompanion$createThreadedLogic(Object[] args, CallbackInfoReturnable<RecipeLogic> cir) {
        cir.setReturnValue(new MultiThreadedRecipeLogic((IRecipeLogicMachine) (Object) this));
    }
}
