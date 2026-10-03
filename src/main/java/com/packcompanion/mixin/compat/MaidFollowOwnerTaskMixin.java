package com.packcompanion.mixin.compat;

import com.packcompanion.Config;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁用 TLM 自带的「跟随主人」brain 任务（仅当 packcompanion 女仆跟随启用时）。
 *
 * <p>背景（2026-10-02 16:43 崩溃/抽风实录）：女仆被 packcompanion 接管为
 * 永久失重三维飞行移动后，TLM 的 {@code MaidFollowOwnerTask} 与我们每 tick 的
 * {@code moveTo} 互相打架——该任务的 {@code start()} 里自带
 * {@code teleportToOwner}（超远瞬移），反复 stop/start 导致女仆<b>反复传送</b>。</p>
 *
 * <p>女仆移动已全归 packcompanion 的自定义 MoveControl + 飞行寻路接管
 * （含"绕障碍寻路跟随主人"），TLM 的地面跟随任务在这个模式下纯属多余，
 * 直接掐断它的启动条件即可。宅家模式判定等开关仍由我们的 EntityMaidMixin 负责。</p>
 *
 * <p>独立 {@code packcompanion.tlm.mixins.json}（required=false）。</p>
 */
@Mixin(value = MaidFollowOwnerTask.class, remap = false)
public abstract class MaidFollowOwnerTaskMixin {

    @Inject(
            method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void packcompanion$disableVanillaFollow(CallbackInfoReturnable<Boolean> cir) {
        // packcompanion 跟随接管期间，TLM 跟随任务永不启动（含其内置的传送）
        if (Config.isMaidZeroGFollowEnabled()) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
