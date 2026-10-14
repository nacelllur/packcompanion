package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 多方块「一键拆除」执行器。两条路径：
 *
 * <ol>
 *   <li><b>已成形</b>（首选）：直接用 GTM 的
 *       {@link MultiblockState#getCache()} —— 结构成形时记录的真实匹配方块集合，
 *       100% 准确，与朝向/翻转/重复数无关。参考 GTCEu-Terminals 的 Dismantler。</li>
 *   <li><b>未成形</b>：cache 为空，改为<b>枚举 pattern 网格</b> —— 但不手写坐标旋转
 *       （极易错，见项目铁律），而是反射调用 GTM 自己的私有方法
 *       {@code BlockPattern.setActualRelativeOffset}，坐标变换与 GTM 判定完全同源。
 *       只收「实心槽位」（谓词既非 any 也非 air，即不是结构外部/内部空腔）上的方块。</li>
 * </ol>
 *
 * <p><b>执行是分帧的</b>：{@code execute} 只把坐标排进队列，真正逐格结算/清空由
 * {@link #tick(ServerLevel)}（由 {@code ServerTickEvent} 驱动）每 tick 处理
 * {@link #BATCH_PER_TICK} 格。原因见该常量的注释 —— 一个 tick 内清掉 35³ 规模的
 * 结构会把光照/邻居更新队列压爆。</p>
 */
public final class NexusDemolitionHelper {

    private NexusDemolitionHelper() {}

    /** 未成形枚举的安全上限（防止可重复结构 max 很大时枚举爆炸）。 */
    private static final int ENUM_BUDGET = 20000;

    /**
     * 拆掉整台多方块（已成形路径），方块（保留 NBT）收进玩家背包，满了掉地上。
     *
     * @return 实际拆掉的方块数；0 表示没拆
     */
    public static int dismantle(ServerPlayer player, IMultiController controller) {
        if (!(controller instanceof MultiblockControllerMachine mcm)) return 0;
        if (!mcm.isFormed()) return 0;
        if (!(player.level() instanceof ServerLevel level)) return 0;

        MultiblockState state = mcm.getMultiblockState();
        if (state == null) return 0;

        Set<BlockPos> all = new HashSet<>();
        Collection<BlockPos> cache = state.getCache();
        if (cache != null && !cache.isEmpty()) {
            all.addAll(cache);
        }
        if (all.isEmpty()) {
            // 兜底：cache 空时退化到 parts + 控制器
            mcm.getParts().forEach(part -> all.add(part.self().getPos()));
        }
        if (all.isEmpty()) return 0;
        return execute(player, level, all, mcm.getPos());
    }

    /**
     * 拆掉未成形/半成形结构：枚举 pattern 网格，回收「实心槽位」上已放置的方块。
     *
     * <p>语义：以控制器为基准，按 pattern 定义（含最小重复数）圈定结构区域，
     * 凡是「该是结构方块」的位置上已经放了东西 → 回收。结构外部（{@code #} any）
     * 与内部空腔（{@code A} air）不动，避免误拆周边建筑。</p>
     *
     * @return 实际回收的方块数；0 表示没拆
     */
    public static int dismantleUnformed(ServerPlayer player, IMultiController controller) {
        if (!(controller instanceof MultiblockControllerMachine mcm)) return 0;
        if (!(player.level() instanceof ServerLevel level)) return 0;

        BlockPattern pattern = mcm.getPattern();
        if (pattern == null) return 0;

        Set<BlockPos> all = collectPatternPositions(level, mcm, pattern);
        if (all.isEmpty()) return 0;
        return execute(player, level, all, mcm.getPos());
    }

    // ------------------------------------------------------------------
    // 未成形：pattern 网格枚举（反射复用 GTM 的坐标变换）
    // ------------------------------------------------------------------

    private static Set<BlockPos> collectPatternPositions(ServerLevel level,
                                                         MultiblockControllerMachine mcm,
                                                         BlockPattern pattern) {
        Set<BlockPos> out = new HashSet<>();

        TraceabilityPredicate[][][] blockMatches = readField(pattern, "blockMatches",
                TraceabilityPredicate[][][].class);
        int[] centerOffset = readField(pattern, "centerOffset", int[].class);
        if (blockMatches == null || centerOffset == null || centerOffset.length < 5) return out;
        if (blockMatches.length == 0) return out;

        Method relativeOffset = findRelativeOffsetMethod(pattern);
        if (relativeOffset == null) return out;

        Direction facing = mcm.getFrontFacing();
        Direction upwards = mcm.getUpwardsFacing();
        boolean flipped = mcm.isFlipped();
        BlockPos controllerPos = mcm.getPos();

        int fingerLength = blockMatches.length;
        int thumbLength = blockMatches[0].length;
        int palmLength = thumbLength > 0 ? blockMatches[0][0].length : 0;
        int[][] aisleRepetitions = pattern.aisleRepetitions;

        int budget = ENUM_BUDGET;
        // z 游标语义与 GTM BlockPattern.checkPatternAt 完全一致：
        //   起点 -centerOffset[4]；首个 aisle 覆盖到 -centerOffset[3]；之后每个重复层 +1
        int zCursor = -centerOffset[4];
        for (int c = 0; c < fingerLength; c++) {
            int layers;
            if (c == 0) {
                layers = centerOffset[4] - centerOffset[3] + 1;
            } else {
                layers = (aisleRepetitions != null && c < aisleRepetitions.length)
                        ? aisleRepetitions[c][1] : 1;
            }
            if (layers < 1) layers = 1;

            for (int r = 0; r < layers; r++) {
                for (int b = 0; b < thumbLength; b++) {
                    for (int a = 0; a < palmLength; a++) {
                        TraceabilityPredicate predicate = blockMatches[c][b][a];
                        if (predicate == null) continue;
                        // 外部任意位 / 内部空腔位：不动（避免误拆周边与装饰）
                        if (predicate.isAny() || predicate.isAir()) continue;
                        if (--budget < 0) return out;

                        int x = a - centerOffset[0];
                        int y = b - centerOffset[1];
                        BlockPos rel = invokeRelativeOffset(relativeOffset, pattern,
                                x, y, zCursor, facing, upwards, flipped);
                        if (rel == null) continue;
                        BlockPos pos = controllerPos.offset(rel.getX(), rel.getY(), rel.getZ());
                        if (pos.equals(controllerPos)) continue;
                        if (!level.getBlockState(pos).isAir()) {
                            out.add(pos);
                        }
                    }
                }
                zCursor++;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(BlockPattern pattern, String name, Class<T> type) {
        try {
            Field f = BlockPattern.class.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(pattern);
            return type.isInstance(v) ? (T) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findRelativeOffsetMethod(BlockPattern pattern) {
        try {
            Method m = BlockPattern.class.getDeclaredMethod("setActualRelativeOffset",
                    int.class, int.class, int.class,
                    Direction.class, Direction.class, boolean.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static BlockPos invokeRelativeOffset(Method m, BlockPattern pattern,
                                                 int x, int y, int z,
                                                 Direction facing, Direction upwards, boolean flipped) {
        try {
            Object v = m.invoke(pattern, x, y, z, facing, upwards, flipped);
            return v instanceof BlockPos pos ? pos : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 公共执行：分帧回收 → 清空 → 入包
    // ------------------------------------------------------------------

    /**
     * 每 tick 最多处理的格数。35³ 那种结构有 3000+ 格，一个 tick 内全清完会把
     * 光照 / 邻居 / 区块更新的队列压爆（同一个 tick 内铺完同样规模方块时实测
     * 服务器单 tick 落后 46.8 秒）。摊到多 tick 后，视觉上是「几秒钟内逐层消失」，
     * 玩家感受不到卡顿；小结构（&lt; 160 格）仍然当 tick 完成，行为与旧实现一致。
     */
    private static final int BATCH_PER_TICK = 160;

    /**
     * 未完成的拆除任务，按维度排队。
     * <b>只在服务端线程访问</b>：提交发生在 {@code useOn}（服务端线程），
     * 推进发生在 {@code ServerTickEvent}（服务端线程）⇒ 无需加锁。
     */
    private static final Map<ServerLevel, ArrayDeque<Job>> JOBS = new HashMap<>();

    private static final class Job {
        final ServerLevel level;
        final UUID playerId;
        /** 玩家提交拆除时所在位置 —— 背包满了就把物品掉在这儿（玩家离线也能用）。 */
        final double dropX, dropY, dropZ;
        final Set<BlockPos> skip;
        /** 待处理坐标；控制器排在最后（先清结构件、最后清控制器，避免失效判定干扰取块）。 */
        final ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        final List<ItemStack> refunds = new ArrayList<>();
        int total;

        Job(ServerLevel level, ServerPlayer player, Set<BlockPos> skip) {
            this.level = level;
            this.playerId = player.getUUID();
            this.dropX = player.getX();
            this.dropY = player.getY();
            this.dropZ = player.getZ();
            this.skip = skip;
        }
    }

    /** 推进某维度的拆除任务；由 {@code PackCompanion#onServerTick} 每 tick 调用一次。 */
    public static void tick(ServerLevel level) {
        ArrayDeque<Job> queue = JOBS.get(level);
        if (queue == null || queue.isEmpty()) {
            if (queue != null) JOBS.remove(level);
            return;
        }
        Job job = queue.peekFirst();
        int budget = BATCH_PER_TICK;
        while (budget-- > 0 && !job.pending.isEmpty()) {
            process(job, job.pending.pollFirst());
        }
        if (job.pending.isEmpty()) {
            deliver(job);
            queue.pollFirst();
            if (queue.isEmpty()) JOBS.remove(level);
        }
    }

    private static int execute(ServerPlayer player, ServerLevel level,
                               Set<BlockPos> all, BlockPos controllerPos) {
        if (all.isEmpty()) return 0;

        // 双高门：只拆下半，避免掉落重复
        Set<BlockPos> skip = new HashSet<>();
        for (BlockPos pos : all) {
            BlockState st = level.getBlockState(pos);
            if (st.getBlock() instanceof DoorBlock
                    && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                skip.add(pos);
            }
        }

        Job job = new Job(level, player, skip);
        job.total = all.size();
        for (BlockPos pos : all) {
            if (!pos.equals(controllerPos)) job.pending.add(pos);
        }
        if (!skip.contains(controllerPos)) job.pending.add(controllerPos);

        JOBS.computeIfAbsent(level, k -> new ArrayDeque<>()).add(job);
        tick(level);   // 预算内能做完就做完（小结构行为与旧实现一致）
        return job.total;
    }

    /** 单格：先结算掉落物再清空 —— 顺序不能反，清空后读不到 BE/NBT。 */
    private static void process(Job job, BlockPos pos) {
        if (job.skip.contains(pos)) return;
        ServerLevel level = job.level;
        BlockState bs = level.getBlockState(pos);
        if (isSkippable(bs)) return;
        ItemStack refund = createRefundStack(level, pos);
        if (!refund.isEmpty()) mergeInto(job.refunds, refund);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** 任务收尾：物品进背包，满了掉在玩家提交拆除时的位置。 */
    private static void deliver(Job job) {
        MinecraftServer server = job.level.getServer();
        ServerPlayer player = server == null ? null
                : server.getPlayerList().getPlayer(job.playerId);
        for (ItemStack stack : job.refunds) {
            if (player != null && player.getInventory().add(stack)) continue;
            job.level.addFreshEntity(new ItemEntity(job.level,
                    job.dropX, job.dropY, job.dropZ, stack.copy()));
        }
    }

    private static boolean isSkippable(BlockState state) {
        return state.isAir() || state.getBlock() == Blocks.AIR;
    }

    /** 生成方块回收物（含 BE 的 NBT，保留线圈等级/仓内容等）。 */
    private static ItemStack createRefundStack(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return ItemStack.EMPTY;
        if (state.getBlock().asItem() == Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(state.getBlock().asItem());
        if (stack.isEmpty()) return ItemStack.EMPTY;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            try {
                be.saveToItem(stack);
                cleanNbtForStacking(stack);
            } catch (Throwable ignored) {
            }
        }
        return stack;
    }

    /** 只保留可叠加/有意义的关键 NBT 字段（照抄 GTCEu-Terminals）。 */
    private static void cleanNbtForStacking(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        CompoundTag bet = tag.getCompound("BlockEntityTag");
        if (bet.isEmpty()) return;
        CompoundTag clean = new CompoundTag();
        for (String key : new String[]{"CoverContainer", "Covers", "Upgrades", "WorkingEnabled",
                "Tier", "Material", "CustomName"}) {
            if (bet.contains(key)) clean.put(key, bet.get(key).copy());
        }
        if (!clean.isEmpty()) tag.put("BlockEntityTag", clean);
        else tag.remove("BlockEntityTag");
        if (tag.isEmpty()) stack.setTag(null);
    }

    /** 合并同种（同 item + 同 NBT）堆叠，尊重 maxStackSize。 */
    private static void mergeInto(List<ItemStack> out, ItemStack in) {
        if (in == null || in.isEmpty()) return;
        for (ItemStack existing : out) {
            if (ItemStack.isSameItemSameTags(existing, in)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                if (room > 0) {
                    int move = Math.min(room, in.getCount());
                    existing.grow(move);
                    in.shrink(move);
                    if (in.isEmpty()) return;
                }
            }
        }
        while (!in.isEmpty()) {
            int take = Math.min(in.getCount(), in.getMaxStackSize());
            ItemStack copy = in.copyWithCount(take);
            out.add(copy);
            in.shrink(take);
        }
    }
}