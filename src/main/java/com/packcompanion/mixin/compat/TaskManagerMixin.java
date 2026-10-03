package com.packcompanion.mixin.compat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskBoardGames;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 女仆工作模式选单过滤（用户 2026-10-02 语义：女仆不做任何工作，
 * 选单只保留「空闲」和「游戏」两项，其余任务全部隐藏）。
 *
 * <p>注入 {@code TaskManager.getNotHiddenTaskList(EntityMaid)} 的 RETURN——
 * 这是女仆工作模式 GUI 读取可选任务列表的统一入口（javap 反编确认）。
 * 过滤后仅保留：</p>
 * <ul>
 *   <li>{@code TaskManager.getIdleTask()}（空闲）</li>
 *   <li>{@code TaskBoardGames} 实例（游戏，棋类等休闲任务，无寻路移动需求）</li>
 * </ul>
 * <p>其余全部剔除（farm/attack/shears/milk/torch/honey 等都不会再出现在选单，
 * 女仆也就不会被指派任何工作）。</p>
 *
 * <p>注意：TaskManager 的此方法是 static，mixin handler 也必须 static；
 * mixin 目标类 = 方法声明类 TaskManager（不是子类）。</p>
 *
 * <p>独立 packcompanion.tlm.mixins.json（required=false，仅 TLM 存在时应用）。</p>
 */
@Mixin(value = TaskManager.class, remap = false)
public abstract class TaskManagerMixin {

    @Inject(
            method = "getNotHiddenTaskList(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)"
                    + "Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false)
    private static void packcompanion$keepOnlyIdleAndBoardGames(EntityMaid maid,
                                                                CallbackInfoReturnable<List<IMaidTask>> cir) {
        List<IMaidTask> src = cir.getReturnValue();
        if (src == null) return;
        IMaidTask idle;
        try {
            idle = TaskManager.getIdleTask();
        } catch (Throwable t) {
            return; // 拿不到 idle 就不动列表，避免崩 GUI
        }
        List<IMaidTask> keep = new ArrayList<>();
        for (IMaidTask task : src) {
            if (task == idle || task instanceof TaskBoardGames) {
                keep.add(task);
            }
        }
        cir.setReturnValue(keep);
    }
}