package com.packcompanion.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.world.entity.Mob;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 女仆「自由视角姿态同步」核心逻辑（纯客户端，普通类——<b>严禁放 mixin 包</b>，
 * mixin 保留包内只允许 @Mixin 类，历史崩溃（IllegalClassLoadError）实录）。
 *
 * <p>行为（2026-10-02 用户语义终版）：</p>
 * <ul>
 *   <li><b>仅空闲/跟随（FLY）状态生效</b>：女仆处于失重漂浮（noGravity=true）
 *       且玩家开启自由视角时才应用姿态变换；工作/宅家（原版移动）时完全不碰
 *       渲染栈。</li>
 *   <li><b>只同步翻滚（pitch/roll）</b>：绕女仆模型中心 (0, MODEL_CENTER_Y, 0) 做
 *       俯仰 + 滚转，<b>不旋转朝向轴（yaw）</b>——女仆朝向前进方向/玩家的逻辑
 *       不变。</li>
 *   <li><b>延迟滞后</b>：取 poseDelayMs（默认 150ms）前的玩家姿态样本，
 *       再加指数平滑——女仆慢一拍软跟翻。</li>
 *   <li><b>栈配对严格保证</b>：begin 只有真正 push 时才登记 PUSHED 集合，
 *       end 只根据 PUSHED 集合 pop；每帧开始清空 PUSHED（清除上帧渲染异常
 *       造成的残留，防 PoseStack 下溢）。</li>
 * </ul>
 *
 * <p><b>性能设计（2026-10-02 审查后重构）</b>：所有女仆同步的是<b>同一个玩家</b>
 * 的姿态——历史队列<b>全局一份</b>（不再按女仆复制）；延迟样本取队头
 * （时间升序队列 + 过期淘汰，O(1) 摊销，替换原先每帧每女仆 O(600) 线性扫描）；
 * 反射读取玩家姿态<b>按毫秒缓存</b>（每帧最多一次，不再每女仆各算一次）。</p>
 */
public final class MaidPoseSync {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean loggedActive;

    /** 模型旋转中心（女仆腰部高度）。 */
    private static final double MODEL_CENTER_Y = 0.7D;

    /** 指数平滑系数（每帧向目标靠拢 25%）。 */
    private static final float SMOOTH = 0.25F;

    /** 历史绝对上限（防异常配置撑爆队列）。 */
    private static final int HISTORY_MAX = 400;

    /** 样本过期上限（超出 delayMs 这么多的样本直接丢弃，防失重暂停后跳变）。 */
    private static final long STALE_EXTRA_MS = 500L;

    /** 姿态历史样本：时间戳(ms) + 玩家姿态角(度)。 */
    private static final class Sample {
        final long timeMs;
        final float pitch;
        final float roll;

        Sample(long timeMs, float pitch, float roll) {
            this.timeMs = timeMs;
            this.pitch = pitch;
            this.roll = roll;
        }
    }

    /**
     * 全局玩家姿态历史（时间升序）。所有女仆共享同一份——它们同步的都是
     * 同一个玩家的姿态，按女仆复制历史是纯冗余。
     */
    private static final ArrayDeque<Sample> HISTORY = new ArrayDeque<>();

    /** 每只女仆当前已应用的平滑姿态（指数平滑状态，[0]=pitch, [1]=roll，度）。 */
    private static final Map<UUID, float[]> APPLIED_POSE = new HashMap<>();

    /** 本帧已 push 的实体集合（begin/end 严格配对的唯一依据）。 */
    private static final Set<UUID> PUSHED = new HashSet<>();

    /** 帧缓存：玩家姿态反射 + 延迟配置，同一毫秒内只计算一次。 */
    private static long cachedMs = -1L;
    private static boolean cachedZeroG;
    private static float[] cachedAngles;
    private static int cachedDelayMs = 150;

    private MaidPoseSync() {
    }

    /** 清除历史与状态（关失重/换维度等场景由调用方触发）。 */
    public static void resetAll() {
        HISTORY.clear();
        APPLIED_POSE.clear();
        PUSHED.clear();
        cachedMs = -1L;
        cachedAngles = null;
    }

    /**
     * 渲染前调用（mixin render HEAD，对应 {@code EntityMaidRenderer.m_7392_}）。
     *
     * @return 是否真正 push 了（end 只对 true 的实体 pop）。
     */
    public static boolean begin(Mob mob, PoseStack poseStack) {
        if (!(mob instanceof EntityMaid maid)) return false;

        long now = System.currentTimeMillis();
        if (now != cachedMs) {
            // 帧边界：清上帧残留（渲染异常时 @At("RETURN") 不触发，PUSHED 会残留）
            PUSHED.clear();
        }

        // 帧缓存反射：每帧最多一次（原先每女仆各调 4 次 invoke + new float[]）
        if (cachedMs != now) {
            cachedZeroG = SpaceCamCompat.isZeroGravity();
            cachedAngles = cachedZeroG ? SpaceCamCompat.getLocalCameraAngles() : null;
            cachedDelayMs = com.packcompanion.Config.getMaidZeroGPoseDelayMs();
            cachedMs = now;
        }
        if (!cachedZeroG || cachedAngles == null) return false;
        if (!maid.isNoGravity()) {
            // 工作/宅家（GROUND 态）：清平滑状态，避免恢复 FLY 时跳变
            APPLIED_POSE.remove(maid.getUUID());
            return false;
        }

        // 记录本帧玩家姿态进全局历史
        UUID id = maid.getUUID();
        HISTORY.addLast(new Sample(now, cachedAngles[1], cachedAngles[2]));

        // 淘汰过期样本（age > delay + STALE_EXTRA 的直接丢弃，防恢复时姿态跳变）
        long staleBefore = now - cachedDelayMs - STALE_EXTRA_MS;
        while (!HISTORY.isEmpty() && HISTORY.peekFirst().timeMs < staleBefore) {
            HISTORY.pollFirst();
        }
        // 淘汰已满足延迟且被更新样本覆盖的队头（目标之后仍留一个满足延迟的样本）
        while (HISTORY.size() > 1) {
            Iterator<Sample> it = HISTORY.iterator();
            Sample head = it.next();
            Sample second = it.next();
            if (now - second.timeMs >= cachedDelayMs) {
                HISTORY.pollFirst();
            } else {
                break;
            }
        }
        while (HISTORY.size() > HISTORY_MAX) {
            HISTORY.pollFirst();
        }

        // 目标 = 队头（最老的满足延迟的样本），O(1)
        Sample head = HISTORY.peekFirst();
        if (head == null || now - head.timeMs < cachedDelayMs) return false;

        // 指数平滑
        float[] applied = APPLIED_POSE.computeIfAbsent(id, k -> new float[]{0F, 0F});
        applied[0] += (head.pitch - applied[0]) * SMOOTH;
        applied[1] += (head.roll - applied[1]) * SMOOTH;

        // 绕模型中心翻滚：z 轴滚转（roll）+ x 轴俯仰（pitch）
        poseStack.pushPose();
        poseStack.translate(0.0D, MODEL_CENTER_Y, 0.0D);
        poseStack.mulPose(Axis.ZP.rotationDegrees(applied[1]));
        poseStack.mulPose(Axis.XP.rotationDegrees(applied[0]));
        poseStack.translate(0.0D, -MODEL_CENTER_Y, 0.0D);

        PUSHED.add(id);

        if (!loggedActive) {
            loggedActive = true;
            LOGGER.info("[packcompanion] maid pose sync ACTIVE (delayMs={}, pitch={}, roll={})",
                    cachedDelayMs, applied[0], applied[1]);
        }
        return true;
    }

    /**
     * 渲染后调用（mixin render RETURN，与 {@link #begin} 严格配对 pop）。
     * 只按 PUSHED 集合判断，不重判实时状态。
     */
    public static void end(Mob mob, PoseStack poseStack) {
        UUID id = mob.getUUID();
        if (!PUSHED.remove(id)) return;
        poseStack.popPose();
    }
}