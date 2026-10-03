package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 「终端自动建造后，把装饰方块朝向还原成投影」的修复器（GTM + gtna 终端通用）。
 *
 * <h2>为什么需要它</h2>
 * <p>GTM 终端（{@code TerminalBehavior}）与 gtna 终端（{@code NexusTerminalBehavior}）
 * 自动建造多方块时，位置来自 pattern、朝向却来自 {@code BlockItem.place(...)} 的
 * 玩家视线 + 启发式 {@code resetFacing()}——<b>不复现投影里的楼梯/按钮/半砖朝向</b>。
 * 改 pattern 无解（pattern 只管方块类型，让机器转个方向也能成形）。</p>
 *
 * <h2>做法</h2>
 * <p>机器自己的 {@code .shapeInfo(...)} 里已写好「控制器朝北」时每一格的完整
 * {@code BlockState}。本类在终端摆完后把它当"施工图"：</p>
 * <pre>
 *   Δp    = 该格在 shapeInfo.getBlocks() 里的下标 − 控制器格下标
 *   Δw    = cwRotate(Δp, steps)        // steps = 顺时针 NORTH→控制器实际朝向
 *   目标格 = 控制器坐标 + Δw
 *   目标态 = rotateState(shapeInfo 状态, steps)
 * </pre>
 * <p>只在该格<b>已经是同一种方块</b>时才写回（类型不同→不动，机器方块→跳过），
 * 只改"属性"不改"方块类型" ⇒ pattern 仍然匹配，结构不会散。</p>
 *
 * <p>与 gtoecore 的 {@code GTNStructureFixup} 同算法；本类放在 packcompanion 是为了
 * 让两个终端的 mixin 能直接调用（packcompanion 不依赖 gtoecore）。</p>
 */
public final class MultiblockFacingFixer {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 六个面布尔属性名（墙 / 多面方块用） */
    private static final String[] FACE_NAMES = {"north", "east", "south", "west", "up", "down"};

    private MultiblockFacingFixer() {}

    /**
     * 立即按 shapeInfo 还原装饰方块朝向。服务端调用。
     *
     * <p><b>不要求 {@code controller.isFormed()}</b>：终端摆完那一刻结构可能还没被
     * 异步检查标成成形，但 facing / shapes 都可用，直接修即可（摆进去的方块类型
     * 是确定的）。</p>
     *
     * @return 实际改动的格子数；0 表示无需修复（或该机器没有 shapeInfo）
     */
    public static int repair(IMultiController controller) {
        MetaMachine self = controller.self();
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return 0;
        if (!(self.getDefinition() instanceof MultiblockMachineDefinition def)) return 0;

        Supplier<List<MultiblockShapeInfo>> shapesSupplier = def.getShapes();
        if (shapesSupplier == null) return 0;
        List<MultiblockShapeInfo> shapes;
        try {
            shapes = shapesSupplier.get();
        } catch (Exception e) {
            return 0;
        }
        if (shapes == null || shapes.isEmpty()) return 0;

        BlockInfo[][][] blocks = shapes.get(0).getBlocks();
        if (blocks == null || blocks.length == 0) return 0;

        // 控制器在 shapeInfo 里的下标：找那格"方块类型等于控制器自己"的
        Block ctrlBlock = self.getBlockState().getBlock();
        int cx = -1, cy = -1, cz = -1;
        outer:
        for (int x = 0; x < blocks.length; x++) {
            BlockInfo[][] aisle = blocks[x];
            if (aisle == null) continue;
            for (int y = 0; y < aisle.length; y++) {
                BlockInfo[] column = aisle[y];
                if (column == null) continue;
                for (int z = 0; z < column.length; z++) {
                    BlockInfo info = column[z];
                    if (info == null) continue;
                    if (info.getBlockState().getBlock() == ctrlBlock) {
                        cx = x; cy = y; cz = z;
                        break outer;
                    }
                }
            }
        }
        if (cx < 0) return 0;

        Direction facing = self.getFrontFacing();
        if (facing == null || facing.getAxis() == Direction.Axis.Y) return 0;
        int steps = clockwiseSteps(Direction.NORTH, facing);

        BlockPos center = self.getPos();
        int changed = 0;
        LOGGER.info("修复入口 {}: 朝向修复开始（facing={}, steps={}, shape=({}x{}x{})）",
                self.getDefinition().getId(), facing, steps,
                blocks.length, blocks[0].length, blocks[0][0].length);

        // 结构若被上下翻转（isFlipped），shapeInfo 的「北向帧」映射不再是纯水平旋转，本修复不适用。
        // 终端 autoBuild 摆出来的一定是未翻转的，所以正常路径不会命中这里。
        if (controller.self().isFlipped()) {
            LOGGER.debug("{} 结构处于翻转态，跳过装饰方块朝向修复", self.getDefinition().getId());
            return 0;
        }

        for (int x = 0; x < blocks.length; x++) {
            BlockInfo[][] aisle = blocks[x];
            if (aisle == null) continue;
            for (int y = 0; y < aisle.length; y++) {
                BlockInfo[] column = aisle[y];
                if (column == null) continue;
                for (int z = 0; z < column.length; z++) {
                    BlockInfo info = column[z];
                    if (info == null) continue;
                    BlockState want = info.getBlockState();
                    if (want == null || want.isAir()) continue;
                    Block wantBlock = want.getBlock();
                    if (wantBlock == ctrlBlock) continue;
                    // 机器方块（仓室/总线等）交给 GTM 自己处理，避免覆盖玩家放的不同等级部件
                    if (wantBlock instanceof IMachineBlock) continue;

                    int[] d = rotateOffset(x - cx, y - cy, z - cz, steps);
                    BlockPos pos = center.offset(d[0], d[1], d[2]);
                    if (!level.isLoaded(pos)) continue;

                    BlockState cur = level.getBlockState(pos);
                    if (cur.getBlock() != wantBlock) continue;   // 类型不同 → 不动

                    BlockState target = rotateState(want, steps);
                    if (target == cur) continue;

                    level.setBlock(pos, target, Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            LOGGER.info("已按投影还原 {} 的装饰方块朝向：{} 格（facing={}, steps={}）",
                    self.getDefinition().getId(), changed, facing, steps);
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 旋转
    // ------------------------------------------------------------------

    /** 顺时针 90° 的步数，使 {@code from} 转到 {@code to}（水平方向）。 */
    public static int clockwiseSteps(Direction from, Direction to) {
        Direction d = from;
        for (int i = 0; i < 4; i++) {
            if (d == to) return i;
            d = d.getClockWise();
        }
        return 0;
    }

    /**
     * 偏移量顺时针水平旋转。俯视图上 (x,z) → (-z, x)：
     * NORTH(0,-1) → EAST(1,0) ✓
     */
    private static int[] rotateOffset(int dx, int dy, int dz, int steps) {
        for (int i = 0; i < (steps & 3); i++) {
            int nx = -dz;
            int nz = dx;
            dx = nx;
            dz = nz;
        }
        return new int[]{dx, dy, dz};
    }

    /**
     * 把一个「控制器朝北」坐标系下的方块状态，顺时针旋转 {@code steps} 步。
     *
     * <p>只动"方位型"属性，相对朝向/旋转不变的属性原样保留：</p>
     * <ul>
     *   <li>所有 {@link DirectionProperty}（{@code facing} / {@code vertical_direction} …）：
     *       水平方向转圈，{@code UP}/{@code DOWN} 不变；</li>
     *   <li>{@code axis}：X ↔ Z（奇数次旋转）；</li>
     *   <li>{@code rotation}（0..15）：+4·steps；</li>
     *   <li>墙 / 多面方块的六个面布尔：值跟随方向搬家；</li>
     *   <li>{@code half} / {@code type}（半砖上下）/ {@code shape}（楼梯形状，相对 facing）/
     *       {@code face}（按钮贴面）/ {@code hinge} / {@code powered} / {@code waterlogged} 等不动。</li>
     * </ul>
     */
    public static BlockState rotateState(BlockState state, int steps) {
        int s = steps & 3;
        if (s == 0) return state;
        BlockState out = state;

        // 1) 方向型属性
        for (Property<?> p : state.getProperties()) {
            if (!(p instanceof DirectionProperty dp)) continue;
            Direction v = state.getValue(dp);
            Direction nv = rotateDirection(v, s);
            if (nv != v) out = set(out, dp, nv);
        }

        // 2) axis: X <-> Z
        if ((s & 1) == 1 && state.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis a = state.getValue(BlockStateProperties.AXIS);
            Direction.Axis na = a == Direction.Axis.X ? Direction.Axis.Z
                    : a == Direction.Axis.Z ? Direction.Axis.X : a;
            if (na != a) out = set(out, BlockStateProperties.AXIS, na);
        }

        // 3) rotation 0..15（告示牌/旗帜/南瓜灯一类）
        for (Property<?> p : state.getProperties()) {
            if (!"rotation".equals(p.getName()) || p.getValueClass() != Integer.class) continue;
            int v = (Integer) state.getValue(p);
            int nv = (v + 4 * s) & 15;
            if (nv != v && p.getPossibleValues().contains(nv)) out = set(out, p, nv);
        }

        // 4) 六面布尔（墙 / 多面方块）：值跟着方向搬家
        for (String name : FACE_NAMES) {
            Property<?> src = findProperty(state, name);
            if (src == null || src.getValueClass() != Boolean.class) continue;
            Boolean val = (Boolean) state.getValue(src);
            Direction to = rotateDirection(dirByName(name), s);
            Property<?> dst = findProperty(state, to.getName().toLowerCase(Locale.ROOT));
            if (dst != null && dst.getValueClass() == Boolean.class) {
                out = set(out, dst, val);
            }
        }
        return out;
    }

    /** 水平方向顺时针转 {@code steps} 步；竖直方向原样返回。 */
    public static Direction rotateDirection(Direction d, int steps) {
        if (d.getAxis() == Direction.Axis.Y) return d;
        Direction r = d;
        for (int i = 0; i < (steps & 3); i++) {
            r = r.getClockWise();
        }
        return r;
    }

    private static Direction dirByName(String name) {
        switch (name) {
            case "north": return Direction.NORTH;
            case "south": return Direction.SOUTH;
            case "west": return Direction.WEST;
            case "east": return Direction.EAST;
            case "up": return Direction.UP;
            default: return Direction.DOWN;
        }
    }

    private static Property<?> findProperty(BlockState state, String name) {
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(name)) return p;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState set(BlockState state, Property property, Comparable value) {
        return state.setValue(property, value);
    }
}
