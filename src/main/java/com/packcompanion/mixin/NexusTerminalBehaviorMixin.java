package com.packcompanion.mixin;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.packcompanion.gt.MultiblockFacingFixer;
import com.packcompanion.gt.NexusDemolitionHelper;
import com.raishxn.gtna.GTNACORE;
import com.raishxn.gtna.common.item.terminal.NexusTerminalBehavior;
import com.raishxn.gtna.common.item.terminal.ui.NexusTerminalUIFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * gtna 的 Nexus Terminal 两个修复：
 *
 * <ol>
 *   <li><b>拆除模式（一键拆除）</b>：gtna 的 {@code useOn} 触发条件
 *       （{@code !isFormed() || buildModule > 0 || replaceMode}）<b>不含 demolitionMode</b> ——
 *       导致开了拆除模式后，潜行右键要么被「建造」分支抢走（返回 CONSUME 而非 PASS），
 *       要么什么都不做。所以必须在 {@code HEAD} 抢先拦截，不能等 PASS 分支。
 *       <p><b>拆除必须由 UI 的「拆除模式」显式开启</b>：潜行右键在 gtna 里是建造手势，
 *       任何「自动推断出拆除意图」的兜底都会在玩家重复点击时清空整台机器。</p></li>
 *   <li><b>建造后修朝向</b>：autoBuild 用 {@code BlockItem.place}（玩家视线），楼梯/按钮/半砖
 *       朝向全错 → RETURN 时调 {@link MultiblockFacingFixer#repair}。</li>
 * </ol>
 *
 * <p><b>拆除实现</b>不重建 pattern 网格（坐标旋转极易错），改用 GTM 的
 * {@link com.gregtechceu.gtceu.api.pattern.MultiblockState#getCache()} ——
 * 结构成形时记录的真实匹配方块集合，逐块回收（保留 NBT）。参考 GTCEu-Terminals 的 Dismantler。</p>
 */
@Mixin(NexusTerminalBehavior.class)
public class NexusTerminalBehaviorMixin {

    /**
     * HEAD 拦截：拆除模式开启 + 潜行 + 对准多方块控制器 → 一键拆除，并取消 gtna 原逻辑。
     *
     * <p>必须在 HEAD 拦截的原因：gtna 在「未成形 / moduleBuild>0 / replaceMode」时会走建造分支，
     * 服务端返回 {@code CONSUME}（不是 PASS），挂在 RETURN 的兜底分支永远进不去。</p>
     */
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true, remap = false)
    private void packcompanion$demolitionHead(UseOnContext context,
                                              CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        if (level.isClientSide) return;
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return;

        BlockPos pos = context.getClickedPos();
        MetaMachine machine = MetaMachine.getMachine(level, pos);
        if (!(machine instanceof IMultiController controller)) return;
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        ItemStack terminalStack = player.getItemInHand(context.getHand());
        NexusTerminalUIFactory.AutoBuildSetting setting =
                NexusTerminalUIFactory.AutoBuildSetting.getSetting(terminalStack);
        if (setting == null) return;

        boolean formed = controller.isFormed();
        // 「建造意图」：勾了模块建造或替换模式 —— 这两种情况必须交回 gtna 去建造
        boolean wantsBuild = setting.getModuleBuild() > 0 || setting.isReplaceMode();

        // ⚠ 这里曾经有一条「兜底」判据（已成形 + 无建造意图 ⇒ 当作拆除）。
        // 它是错的，必须永不恢复：潜行右键在 gtna 里就是【建造】手势，
        // 于是「建造成功后再点一次（或一次点击因卡顿被重复派发）」会直接把整台机器
        // 清空并把材料返还给玩家 —— 2026-10-07 实测踩中：
        //   [packcompanion] nexus demolition: ... formed=true explicit=false fallback=true
        // 玩家只放了一个控制器，却得到「拆除完成 + 一堆物品」。
        // 拆除现在【只能】由终端 UI 的「拆除模式」显式开启；开关读取本身是可靠的
        // （上面那行日志里的 explicit=false 就如实反映了开关状态）。
        if (wantsBuild) {
            return;   // 明确要建造 → 完全交回 gtna
        }
        if (!setting.isDemolitionMode()) {
            return;   // 没开拆除模式 → 交回 gtna（建造 / 无操作，无任何副作用）
        }

        GTNACORE.LOGGER.info(
                "[packcompanion] nexus demolition: target={} formed={} moduleBuild={} replace={}",
                controller.self().getDefinition().getId(), formed,
                setting.getModuleBuild(), setting.isReplaceMode());

        // 成形走 cache（100% 准确）；未成形走 pattern 网格枚举（收「实心槽位」上的方块）
        int removed = formed
                ? NexusDemolitionHelper.dismantle(serverPlayer, controller)
                : NexusDemolitionHelper.dismantleUnformed(serverPlayer, controller);
        if (removed > 0) {
            level.playSound(null, pos, SoundEvents.ANVIL_BREAK, SoundSource.BLOCKS, 0.7f, 1.4f);
            player.displayClientMessage(
                    Component.translatable("packcompanion.demolition.done", removed), true);
        } else {
            player.displayClientMessage(
                    Component.translatable(formed
                            ? "packcompanion.demolition.failed"
                            : "packcompanion.demolition.nothing_unformed"), true);
        }
        cir.setReturnValue(InteractionResult.CONSUME);
    }

    /** RETURN：建造成功（非 PASS/FAIL）→ 按 shapeInfo 还原装饰方块朝向。 */
    @Inject(method = "useOn", at = @At("RETURN"), remap = false)
    private void packcompanion$repairFacing(UseOnContext context,
                                             CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getLevel().isClientSide) return;
        InteractionResult result = cir.getReturnValue();
        // 服务端 useOn 摆完返回的是 sidedSuccess(false) = CONSUME（不是 SUCCESS！），
        // 所以这里不能判 ==SUCCESS，只需排除「没摆」的 PASS/FAIL。
        if (result == InteractionResult.PASS || result == InteractionResult.FAIL) return;

        BlockPos pos = context.getClickedPos();
        MetaMachine machine = MetaMachine.getMachine(context.getLevel(), pos);
        if (machine instanceof IMultiController controller) {
            MultiblockFacingFixer.repair(controller);
        }
    }
}