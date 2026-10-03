package com.packcompanion.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * space_gravity × ShoulderSurfing 软桥接（纯反射，两个 mod 任一缺失时静默降级）。
 *
 * <p>本类<b>禁止 import</b> 这两个 mod 的任何类——它会被零重力相机锁定的
 * Forge 事件处理器（客户端常驻）加载，若硬引用会在 mod 缺失时直接
 * {@code NoClassDefFoundError}。反射句柄首次成功解析后缓存。</p>
 *
 * <p>检测逻辑：</p>
 * <ul>
 *   <li>失重：读 {@code ClientZeroGravityController.zeroGravityEnabled}（private static，
 *       由 space_gravity 的同步包在客户端设置——这是客户端唯一可靠的失重状态源，
 *       玩家 NBT 不会同步到客户端）。</li>
 *   <li>越肩：调 {@code IShoulderSurfing.getInstance().isShoulderSurfing()}。</li>
 * </ul>
 */
public final class SpaceCamCompat {

    private static volatile boolean sgResolved;
    private static Field sgZeroGravityField;

    private static volatile boolean ssResolved;
    private static Object ssInstance;
    private static Method ssIsShoulderSurfing;

    private SpaceCamCompat() {
    }

    /** @return space_gravity 的客户端零重力开关是否已开启（mod 缺失 / 反射失败时返回 false） */
    public static boolean isZeroGravity() {
        if (!sgResolved) resolveSpaceGravity();
        if (sgZeroGravityField == null) return false;
        try {
            return sgZeroGravityField.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return ShoulderSurfing 当前是否处于越肩视角（mod 缺失 / 反射失败时返回 false） */
    public static boolean isShoulderSurfing() {
        if (!ssResolved) resolveShoulderSurfing();
        if (ssIsShoulderSurfing == null) return false;
        try {
            return (Boolean) ssIsShoulderSurfing.invoke(ssInstance);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void resolveSpaceGravity() {
        sgResolved = true;
        try {
            Class<?> cls = Class.forName("com.spacegravity.spacegravity.ClientZeroGravityController");
            Field f = cls.getDeclaredField("zeroGravityEnabled");
            f.setAccessible(true);
            sgZeroGravityField = f;
        } catch (Throwable ignored) {
            sgZeroGravityField = null;
        }
    }

    private static void resolveShoulderSurfing() {
        ssResolved = true;
        try {
            Class<?> iface = Class.forName(
                    "com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing");
            Object instance = iface.getField("INSTANCE").get(null);
            ssInstance = instance;
            ssIsShoulderSurfing = iface.getMethod("isShoulderSurfing");
        } catch (Throwable ignored) {
            ssInstance = null;
            ssIsShoulderSurfing = null;
        }
    }

    // ==================== 客户端：本玩家的失重翻滚姿态 ====================

    private static volatile boolean sgOrientResolved;
    private static Field sgLocalOrientationField;
    private static Method sgToCameraAngles;
    private static Method caYaw;
    private static Method caPitch;
    private static Method caRoll;

    /**
     * 本玩家的失重翻滚姿态（读 space_gravity 客户端 {@code ClientZeroGravityController.localOrientation}
     * 并经 {@code ZeroGravityOrientation.toCameraAngles} 转成 yaw/pitch/roll 度数）。
     *
     * @return float[3] = {yaw, pitch, roll}；非失重 / mod 缺失 / 反射失败时返回 null。
     */
    public static float[] getLocalCameraAngles() {
        if (!sgOrientResolved) resolveSgOrientation();
        if (sgLocalOrientationField == null || sgToCameraAngles == null) return null;
        try {
            Object orientation = sgLocalOrientationField.get(null);
            if (orientation == null) return null;
            Object angles = sgToCameraAngles.invoke(null, orientation);
            if (angles == null) return null;
            return new float[]{
                    (Float) caYaw.invoke(angles),
                    (Float) caPitch.invoke(angles),
                    (Float) caRoll.invoke(angles)};
        } catch (Throwable t) {
            return null;
        }
    }

    private static void resolveSgOrientation() {
        sgOrientResolved = true;
        try {
            Class<?> controller = Class.forName(
                    "com.spacegravity.spacegravity.ClientZeroGravityController");
            Field f = controller.getDeclaredField("localOrientation");
            f.setAccessible(true);
            sgLocalOrientationField = f;

            Class<?> orient = Class.forName(
                    "com.spacegravity.spacegravity.ZeroGravityOrientation");
            Class<?> dataCls = Class.forName(
                    "com.spacegravity.spacegravity.ZeroGravityOrientation$OrientationData");
            Class<?> anglesCls = Class.forName(
                    "com.spacegravity.spacegravity.ZeroGravityOrientation$CameraAngles");
            sgToCameraAngles = orient.getMethod("toCameraAngles", dataCls);
            caYaw = anglesCls.getMethod("yaw");
            caPitch = anglesCls.getMethod("pitch");
            caRoll = anglesCls.getMethod("roll");
        } catch (Throwable ignored) {
            sgLocalOrientationField = null;
            sgToCameraAngles = null;
        }
    }
}
