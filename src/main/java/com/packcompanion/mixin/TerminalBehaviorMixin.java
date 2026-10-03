package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.common.item.TerminalBehavior;
import com.packcompanion.gt.MultiblockFacingFixer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GTM 终端（TerminalBehavior）自动建造后，立即按 shapeInfo 还原装饰方块朝向。
 *
 * <p>GTM 终端的 {@code useOn} 在「潜行 + 右键未成形的多方块控制器」时调用
 * {@code BlockPattern.autoBuild(...)} 摆方块，然后返回 {@code sidedSuccess}。
 * 摆出来的装饰方块朝向来自玩家视线（{@code BlockItem.place}），全是错的。</p>
 *
 * <p>本 mixin 在 {@code useOn} RETURN 时，若返回值表示成功（说明确实执行了
 * autoBuild），就在服务端调 {@link MultiblockFacingFixer#repair} 把朝向还原成投影。
 * 不依赖 {@code isFormed()}——autoBuild 摆进去的方块类型是确定的，形状检查的
 * 异步标定晚几个 tick 也没关系。</p>
 */
@Mixin(TerminalBehavior.class)
public class TerminalBehaviorMixin {

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
