package com.packcompanion.mixin.compat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.packcompanion.Config;
import com.packcompanion.maid.ZeroGMaidMoveControl;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * 女仆双模式移动（方案 B 终版 + 姿态同步配套）：飞行跟随 + 原版工作。
 *
 * <p><b>为什么必须双模式（2026-10-02 血泪实录）</b>：
 * TLM 的 brain task（MaidMoveToBlockTask.getOrCreateArrivalMap）会<b>长期缓存</b>
 * {@code MaidPathFindingBFS}，其评估器在构造时从 navigation 拿取并焊死。
 * 换过 navigation 会污染缓存导致工作 AI 永不 moveTo → <b>navigation 永远不换</b>，
 * 只换 moveControl。工作/宅家时恢复原版（落地干活），空闲/跟随时漂浮。</p>
 *
 * <p><b>姿态同步联动</b>：客户端 {@code MaidPoseSync} 按 {@code noGravity}
 * 判定是否应用自由视角姿态——本 mixin 在 GROUND（工作/宅家）设
 * {@code noGravity=false}、FLY（空闲跟随）设 {@code noGravity=true}，
 * 因此姿态同步天然「仅空闲/跟随生效」，工作/宅家不被玩家翻滚姿态影响
 * （用户 2026-10-02 语义）。</p>
 *
 * <p>注入 {@code EntityMaid.aiStep()}（SRG {@code m_8107_}）HEAD，仅服务端。
 * 优先级：宅家 &gt; 工作 &gt; 跟随。</p>
 *
 * <p>无传送（teleportToOwner 与三维移动打架，用户要求禁用）。
 * 独立 {@code packcompanion.tlm.mixins.json}（required=false）。</p>
 */
@Mixin(value = EntityMaid.class, remap = false)
public abstract class EntityMaidMixin {

    /** 诊断日志限频：每 200 tick（10 秒）一条。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mob.moveControl 字段反射缓存（先 Mojang 名后 SRG 名，双环境兼容）。 */
    private static volatile boolean moveControlFieldResolved;
    private static Field moveControlField;

    /** TLM 原版 moveControl 存档（每只女仆各自的实例，首次替换前保存）。 */
    @Unique
    private MoveControl pc$originalMoveControl;
    @Unique
    private int pc$diagCounter;

    private static Field resolveMoveControlField() {
        if (moveControlFieldResolved) return moveControlField;
        moveControlFieldResolved = true;
        for (String name : new String[]{"moveControl", "f_21342_"}) {
            try {
                Field f = net.minecraft.world.entity.Mob.class.getDeclaredField(name);
                f.setAccessible(true);
                moveControlField = f;
                return f;
            } catch (Throwable ignored) {
                // 试下一个名字
            }
        }
        return null;
    }

    /** 女仆是否正处于工作任务中（任务非 idle）。 */
    private static boolean isWorkingTask(EntityMaid maid) {
        try {
            IMaidTask task = maid.getTask();
            return task != null && !"idle".equals(task.getUid().getPath());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 诊断用：安全取任务名（异常时返回 "?"）。 */
    private static String safeTaskName(EntityMaid maid) {
        try {
            IMaidTask task = maid.getTask();
            return task == null ? "null" : task.getUid().getPath();
        } catch (Throwable t) {
            return "?";
        }
    }

    @Inject(method = "m_8107_()V", at = @At("HEAD"), remap = false)
    private void packcompanion$zeroGFloatFollow(CallbackInfo ci) {
        if (!Config.isMaidZeroGFollowEnabled()) return;
        EntityMaid self = (EntityMaid) (Object) this;
        if (self.level().isClientSide) return;
        if (self.isPassenger() || self.isVehicle()) return;

        // 状态判定：工作 / 宅家 / 大脑不可移动 = 地面模式（原版移动系统），
        // 其余（idle 跟随）= 飞行模式（失重漂浮）
        boolean groundMode = self.isHomeModeEnable() || !self.canBrainMoving() || isWorkingTask(self);

        // 诊断日志（限频 10 秒一条）：模式/速度/任务/脑活动
        if (++pc$diagCounter % 200 == 1) {
            Vec3 vel = self.getDeltaMovement();
            String activity = "?";
            try {
                activity = String.valueOf(self.getBrain().getActiveActivities());
            } catch (Throwable ignored) {
            }
            LOGGER.info("[pc-maid] mode={} vel=({}) mc={} task={} actv={} noGrav={} home={} brain={} work={}",
                    groundMode ? "GROUND" : "FLY",
                    String.format("%.2f,%.2f,%.2f", vel.x, vel.y, vel.z),
                    self.getMoveControl().getClass().getSimpleName(),
                    safeTaskName(self), activity, self.isNoGravity(),
                    self.isHomeModeEnable(), self.canBrainMoving(), isWorkingTask(self));
        }

        // 首次保存 TLM 原版 moveControl（必须在任何替换之前）
        if (pc$originalMoveControl == null) pc$originalMoveControl = self.getMoveControl();

        Field mf = resolveMoveControlField();
        if (mf == null) return;

        if (groundMode) {
            // 恢复原版 moveControl：TLM 工作 AI 按地面寻路写死，必须落地才能干活；
            // 同时 noGravity=false → 姿态同步（按 noGravity 判定）不生效
            if (self.getMoveControl() instanceof ZeroGMaidMoveControl && pc$originalMoveControl != null) {
                try {
                    mf.set(self, pc$originalMoveControl);
                } catch (Throwable ignored) {
                }
            }
            self.setNoGravity(false);
            return;
        }

        // ===== 飞行模式（idle 跟随）=====
        // 只换 moveControl；navigation 永远保持 TLM 原版（保 BFS 缓存干净）
        if (!(self.getMoveControl() instanceof ZeroGMaidMoveControl)) {
            try {
                mf.set(self, new ZeroGMaidMoveControl(self));
            } catch (Throwable ignored) {
                return;
            }
        }

        // 失重（MoveControl.tick 里还有每 tick 兜底）→ 姿态同步按此生效
        self.setNoGravity(true);

        LivingEntity owner = self.getOwner();
        if (!(owner instanceof Player player)) return;

        double followDist = Config.getMaidZeroGFollowDistance();
        double speed = Config.getMaidZeroGSpeed();

        // 跟随点 = 主人视线反方向 followDist 格（略低 0.2 格，齐腰高度）
        Vec3 forward = player.getLookAngle();
        Vec3 target = player.position()
                .subtract(forward.scale(followDist))
                .add(0, -0.2, 0);

        double dist = self.position().distanceTo(target);

        if (dist > 0.6D) {
            // 直线飞行（不走 navigation.moveTo，绕开 TLM BFS 缓存脏状态）：
            // ZeroGMaidMoveControl 消费 wantedPos，自带碰撞检测 + 平滑加减速
            self.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
        }
        // 到位（dist <= 0.6）时不喂新目标：moveControl 自动阻尼悬停，身体自由
    }
}