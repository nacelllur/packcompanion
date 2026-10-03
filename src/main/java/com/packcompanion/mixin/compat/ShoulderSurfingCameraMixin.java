package com.packcompanion.mixin.compat;

import com.github.exopandora.shouldersurfing.api.math.Vec2f;
import com.github.exopandora.shouldersurfing.client.ShoulderSurfingCamera;
import com.packcompanion.client.ZeroGShoulderCameraLock;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 失重（space_gravity 零重力）+ 越肩视角（ShoulderSurfing）时的镜头锁定。
 *
 * <p>机制与根因见 {@link com.packcompanion.client.ZeroGShoulderCameraLock} 的 javadoc。
 * 两个注入都在 {@code ShoulderSurfingCamera.setup}（SS 自己的方法，仅越肩视角会被调用）：</p>
 * <ol>
 *   <li><b>HEAD：旋转轴对齐</b>——把 SS 自己缓存的 {@code rotation}/{@code renderRotation}
 *       改成玩家当前插值视角（失重下 space_gravity 已把玩家朝向同步为失重朝向）。
 *       SS 的 {@code CameraMixin} 稍后会用 {@code renderRotation} 再 setRotation 一次，
 *       对齐后这一步变成 no-op，于是「偏移基准轴 == 最终渲染轴」，
 *       角色相对画面的位置不再随翻滚漂移。</li>
 *   <li><b>RETURN：偏移锁死</b>——把 {@code targetOffset}/{@code renderOffset} 替换为
 *       Config 指定的固定常量（默认让身体停在画面左下角），不再随 transition lerp、
 *       拖拽、动态偏移与碰撞收缩变化。</li>
 * </ol>
 *
 * <p>roll 的抑制由 {@code ZeroGShoulderCameraLock} 的 Forge 事件处理器完成（LOWEST，
 * 覆盖 space_gravity 设置的 roll）。三者合力 ⇒ 身体恒定停在画面左下角。</p>
 *
 * <p>本 mixin 位于独立的 {@code packcompanion.client.mixins.json}（required=false）：
 * ShoulderSurfing 缺失时整体跳过，不影响其它实例启动。</p>
 */
@Mixin(value = ShoulderSurfingCamera.class, remap = false)
public abstract class ShoulderSurfingCameraMixin {

    @Shadow private Vec3 renderOffset;
    @Shadow private Vec3 targetOffset;
    @Shadow private Vec2f rotation;
    @Shadow private Vec2f renderRotation;

    @Inject(method = "setup(Lnet/minecraft/client/Camera;Lnet/minecraft/world/level/BlockGetter;FLnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"), remap = false)
    private void packcompanion$alignRotation(Camera camera, BlockGetter level, float partialTick,
                                             Entity cameraEntity, CallbackInfo ci) {
        if (cameraEntity == null || !ZeroGShoulderCameraLock.shouldLock()) return;
        // 与 vanilla Camera.setup 的 setRotation(getViewYRot, getViewXRot) 完全同源，
        // 保证 CameraMixin 后续的 setRotation(renderRotation) 与偏移基准轴一致
        Vec2f aligned = new Vec2f(cameraEntity.getViewXRot(partialTick),
                                  cameraEntity.getViewYRot(partialTick));
        this.rotation = aligned;
        this.renderRotation = aligned;
    }

    @Inject(method = "setup(Lnet/minecraft/client/Camera;Lnet/minecraft/world/level/BlockGetter;FLnet/minecraft/world/entity/Entity;)V",
            at = @At("RETURN"), remap = false)
    private void packcompanion$lockOffset(Camera camera, BlockGetter level, float partialTick,
                                          Entity cameraEntity, CallbackInfo ci) {
        if (!ZeroGShoulderCameraLock.shouldLock()) return;
        Vec3 lock = ZeroGShoulderCameraLock.lockedOffset();
        this.targetOffset = lock;
        this.renderOffset = lock;
    }
}
