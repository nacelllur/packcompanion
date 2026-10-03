package com.packcompanion.mixin.compat;

import com.packcompanion.client.MaidPoseSync;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Mob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 女仆「自由视角姿态同步」渲染注入点（纯空壳——逻辑全在
 * {@link MaidPoseSync}，mixin 保留包内不得放辅助类/嵌套类，历史崩溃实录）。
 *
 * <p>注入 {@code EntityMaidRenderer.m_7392_(Mob,FF,PoseStack,MultiBufferSource,I)}——
 * <b>实际渲染女仆的是 EntityMaidRenderer</b>（extends MobRenderer，内部才持有
 * gecko 渲染器字段；注入 GeckoEntityMaidRenderer 不生效，2026-10-02 实录）。</p>
 *
 * <p><b>@At("RETURN")</b>（非 TAIL）：目标 render 有 early-return 分支，
 * TAIL 只覆盖最后一条 return、漏 pop 会崩 PoseStack not empty；
 * RETURN 覆盖所有出口，且 end 只按 PUSHED 集合配对——每条路径恰好 pop 一次。</p>
 *
 * <p>handler 参数类型必须与目标方法逐参精确一致（含 MultiBufferSource，
 * 写 Object 会让描述符校验失败、mixin 静默跳过——Invalid descriptor 实录）。</p>
 *
 * <p>client 侧 mixin，独立 packcompanion.tlm.mixins.json（required=false）。</p>
 */
@Mixin(value = EntityMaidRenderer.class, remap = false)
public abstract class EntityMaidRendererMixin {

    @Inject(
            method = "m_7392_(Lnet/minecraft/world/entity/Mob;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            remap = false)
    private void packcompanion$onMaidRenderHead(Mob mob, float entityYaw, float partialTick,
                                                PoseStack poseStack, MultiBufferSource bufferSource,
                                                int packedLight, CallbackInfo ci) {
        MaidPoseSync.begin(mob, poseStack);
    }

    @Inject(
            method = "m_7392_(Lnet/minecraft/world/entity/Mob;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"),
            remap = false)
    private void packcompanion$onMaidRenderTail(Mob mob, float entityYaw, float partialTick,
                                                PoseStack poseStack, MultiBufferSource bufferSource,
                                                int packedLight, CallbackInfo ci) {
        MaidPoseSync.end(mob, poseStack);
    }
}