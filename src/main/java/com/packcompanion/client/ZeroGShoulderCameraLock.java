package com.packcompanion.client;

import com.packcompanion.Config;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 失重（space_gravity 零重力/自由视角）+ 越肩视角（ShoulderSurfing）同时开启时的相机锁定。
 *
 * <p><b>症状</b>：零重力下用越肩视角，玩家身体会在画面里乱飘、跑出屏幕外。</p>
 *
 * <p><b>根因</b>（反编译两边源码确认）：</p>
 * <ol>
 *   <li>space_gravity 在 {@code ClientZeroGravityController.onCameraAngles} 里把
 *       玩家的 yRot/xRot 强制设为「自由翻滚朝向」（三维自由姿态），</li>
 *   <li>ShoulderSurfing 的 {@code CameraMixin} 在 vanilla {@code Camera.setup} 内：
 *       ①按<b>当时</b>的相机轴 {@code move(-x, y, z)} 摆好越肩偏移，
 *       ②随后又 {@code setRotation(renderRotation)} 覆盖相机角度（它自己缓存的旋转，与①不同步），</li>
 *   <li>再叠加 space_gravity 通过本事件设置的 roll（画面滚转）——
 *       相机位置偏移的基准轴与最终渲染轴不一致，角色相对画面的位置随翻滚漂移直至出画。</li>
 * </ol>
 *
 * <p><b>修法</b>（两个互补动作，都只在「失重 + 越肩」时生效，其余视角/状态零影响）：</p>
 * <ol>
 *   <li>mixin {@code ShoulderSurfingCamera.setup}：
 *       把 SS 的旋转轴对齐到玩家当前视角（失重下 space_gravity 已把玩家朝向同步为失重朝向，
 *       于是「偏移基准轴 == 最终渲染轴」），并把偏移替换为<b>固定常量</b>
 *       （Config 的 zeroGCamera.offsetX/Y/Z，默认让身体停在画面左下角）；</li>
 *   <li>本类的 {@code ViewportEvent.ComputeCameraAngles} 处理器（LOWEST 优先级，
 *       在 space_gravity 的 NORMAL 之后执行）：把画面 roll 归零，
 *       消除滚转导致的「角色绕画面中心甩出」。</li>
 * </ol>
 *
 * <p>注意：锁定期间越肩偏移为常量，会绕过 SS 的碰撞收缩（maxZoom），狭窄处相机可能轻微穿墙——
 * 这是「镜头固定」的应有取舍；普通重力 / 第一人称 / 第三人称均不受影响。</p>
 */
@Mod.EventBusSubscriber(modid = com.packcompanion.PackCompanion.MODID, value = Dist.CLIENT)
public final class ZeroGShoulderCameraLock {

    private ZeroGShoulderCameraLock() {
    }

    /** 失重 + 越肩同时成立才锁定（配置可关）。public：mixin 在别的包里调用。 */
    public static boolean shouldLock() {
        if (!Config.isZeroGCameraLockEnabled()) return false;
        return SpaceCamCompat.isZeroGravity() && SpaceCamCompat.isShoulderSurfing();
    }

    /** 锁定用的固定偏移（SS 的 offset 语义：move(-x, y, z)，x=视线、y=上、z=左）。public：mixin 在别的包里调用。 */
    public static net.minecraft.world.phys.Vec3 lockedOffset() {
        return new net.minecraft.world.phys.Vec3(
                Config.getZeroGCameraOffsetX(),
                Config.getZeroGCameraOffsetY(),
                Config.getZeroGCameraOffsetZ());
    }

    /**
     * 把画面 roll 抑制为 0：roll 只旋转画面（绕视线轴），不改变相机位置，
     * 而位置偏移已在「无 roll」的轴上摆好 —— roll 一转，角色就会绕画面中心甩出去。
     * 在 space_gravity（NORMAL 优先级）之后执行（LOWEST），直接覆盖它的 roll。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!shouldLock()) return;
        event.setRoll(0.0F);
    }

    /** 诊断用：当前是否处于锁定状态（供日志/排查）。 */
    public static boolean isActive() {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.getCameraEntity();
        if (e == null) return false;
        Camera c = mc.gameRenderer.getMainCamera();
        return shouldLock() && c.getEntity() == e;
    }
}
