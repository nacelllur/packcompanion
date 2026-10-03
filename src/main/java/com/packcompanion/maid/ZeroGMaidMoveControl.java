package com.packcompanion.maid;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * 女仆专用三维漂浮移动控制（方案 B 核心）。
 *
 * <p>继承 MoveControl 并整体替换女仆的 moveControl 字段 ——
 * MoveControl.tick() 是 vanilla 官方的"每 tick 设速点"（在 aiStep 内、
 * travel 之前执行），这里设的 deltaMovement 会被随后的 travel → move()
 * 消费执行，时序正确（蝙蝠/鹦鹉的飞行就是这套机制）。</p>
 *
 * <p>行为：</p>
 * <ul>
 *   <li>每 tick 强制 {@code setNoGravity(true)} —— 女仆永久失重，不下坠。</li>
 *   <li>{@code MOVE_TO}（有目标点）：朝目标做三维直线飞行，
 *       速度用 lerp 平滑（每 tick 逼近目标速度 15%，有加减速过渡），
 *       yRot/yBodyRot/yHeadRot 平滑转向移动方向。</li>
 *   <li>其他状态（无目标）：阻尼减速悬停，身体自由。</li>
 * </ul>
 *
 * <p>速度语义：{@code setWantedPosition(x,y,z,speed)} 的第四参数
 * 直接当作目标速度（格/tick）使用，不经过移动速度属性。</p>
 *
 * <p>本类只在 EntityMaidMixin（TLM 存在时才应用）里被实例化，
 * TLM 缺失时不会被加载，无 NoClassDefFoundError 风险。</p>
 */
public class ZeroGMaidMoveControl extends MoveControl {

    /** 速度平滑因子（每 tick 向目标速度逼近的比例）。 */
    private static final double LERP = 0.15;

    /** 视为"到位"的距离（格）——小于此值停住悬停。 */
    private static final double ARRIVE_DIST_SQ = 0.09;

    /** 转向速率（度/tick 上限）。 */
    private static final float TURN_SPEED = 8.0F;

    public ZeroGMaidMoveControl(Mob mob) {
        super(mob);
    }

    @Override
    public void tick() {
        // 永久失重（每 tick 兜底，防 vanilla/TLM 重置）
        this.mob.setNoGravity(true);

        if (this.operation != Operation.MOVE_TO) {
            // 无目标：阻尼悬停
            Vec3 v = this.mob.getDeltaMovement();
            if (v.lengthSqr() > 1.0E-6) {
                this.mob.setDeltaMovement(v.scale(0.8D));
            }
            this.mob.setZza(0.0F);
            return;
        }

        double dx = this.wantedX - this.mob.getX();
        double dy = this.wantedY - this.mob.getY();
        double dz = this.wantedZ - this.mob.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq < ARRIVE_DIST_SQ) {
            // 到位：停住悬停
            this.operation = Operation.WAIT;
            Vec3 v = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(v.scale(0.6D));
            this.mob.setZza(0.0F);
            return;
        }

        double dist = Math.sqrt(distSq);
        double speed = Math.max(this.speedModifier, 0.01D);

        // 目标速度向量（三维单位向量 × 目标速度）
        Vec3 desired = new Vec3(dx / dist, dy / dist, dz / dist).scale(speed);
        // 平滑逼近（加减速过渡，不是瞬间满速）
        Vec3 cur = this.mob.getDeltaMovement();
        Vec3 next = new Vec3(
                cur.x + (desired.x - cur.x) * LERP,
                cur.y + (desired.y - cur.y) * LERP,
                cur.z + (desired.z - cur.z) * LERP);
        // 限速（防 travel 叠加输入后超速）
        if (next.lengthSqr() > speed * speed) {
            next = next.normalize().scale(speed);
        }
        this.mob.setDeltaMovement(next);

        // 平滑转向移动方向
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float yaw = Mth.rotLerp(TURN_SPEED / 100.0F, this.mob.getYRot(), targetYaw);
        this.mob.setYRot(yaw);
        this.mob.yBodyRot = yaw;
        this.mob.yHeadRot = yaw;
        this.mob.setZza(0.0F);
    }
}
