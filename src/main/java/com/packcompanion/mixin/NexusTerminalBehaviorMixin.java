package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.packcompanion.gt.MultiblockFacingFixer;
import com.raishxn.gtna.common.item.terminal.NexusTerminalBehavior;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * gtna 的 Nexus Terminal 自动建造后，立即按 shapeInfo 还原装饰方块朝向。
 *
 * <p>gtna 终端（{@code NexusTerminalBehavior.useOn}）摆完方块后内部会调
 * {@code GTNAStructureRefresh.refresh(controller, true)}，此时结构已经走完
 * 成形判定。但其 autoBuild 同样用 {@code BlockItem.place}（玩家视线），
 * 楼梯/按钮/半砖的朝向依旧全是错的。</p>
 *
 * <p>本 mixin 在 {@code useOn} RETURN 时，若返回成功且处于服务端，直接调
 * {@link MultiblockFacingFixer#repair} 把朝向还原成投影。</p>
 */
@Mixin(NexusTerminalBehavior.class)
public class NexusTerminalBehaviorMixin {

    @Inject(method = "useOn", at = @At("RETURN"), remap = false)
    private void packcompanion$repairFacingAfterAutoBuild(UseOnContext context,
                                                          CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getLevel().isClientSide) return;
        // 服务端 useOn 摆完返回的是 sidedSuccess(false) = CONSUME（不是 SUCCESS！），
        // 所以这里不能判 ==SUCCESS，只需排除「没摆」的 PASS/FAIL。
        InteractionResult result = cir.getReturnValue();
        if (result == InteractionResult.PASS || result == InteractionResult.FAIL) return;

        BlockPos pos = context.getClickedPos();
        MetaMachine machine = MetaMachine.getMachine(context.getLevel(), pos);
        if (machine instanceof IMultiController controller) {
            MultiblockFacingFixer.repair(controller);
        }
    }
}
