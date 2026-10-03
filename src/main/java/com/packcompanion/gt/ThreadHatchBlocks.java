package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;

import com.packcompanion.PackCompanion;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 本 mod 线程仓方块的解析入口（供 pattern 谓词注入使用）。
 *
 * <p>为什么不直接用 {@code PartAbility.PARALLEL_HATCH.getAllBlocks()}：
 * 那是 {@code GTMemoizer} 的缓存快照，只在第一次被读取时求值一次。GTM 的
 * {@code GTMachines.init()} 中，自身的多方块与 KubeJS 的机器定义都早于
 * {@code GTCEuAPI.RegisterEvent} 派发，因此某些 pattern 在构建时读到的候选表
 * 可能尚未包含 addon 后注册的方块。这里改为持有自己的方块引用，绕开快照问题。</p>
 *
 * <p>两组方块互相独立：<b>普通线程仓</b>（7 档，注入到任意仓室槽位）与
 * <b>劳保线程仓</b>（单个，256 线程，仅注入到温室）。劳保线程仓刻意不注册任何
 * {@code PartAbility}，所以任何 {@code Predicates.abilities(...)} 都认不到它，
 * 「只有温室能用」由此保证。</p>
 */
public final class ThreadHatchBlocks {

    /** 普通线程仓的机器注册名前缀：thread_hatch_uv / thread_hatch_max ... */
    private static final String PREFIX = "thread_hatch";
    /** 劳保线程仓的机器注册名（刻意不含上面的前缀，避免被普通扫描误收） */
    private static final String LABOR_NAME = "labor_thread_hatch";

    private static final Block[] EMPTY = new Block[0];

    private static volatile Block[] cache;
    private static volatile Block[] laborCache;

    private ThreadHatchBlocks() {}

    /**
     * @return 全部普通线程仓方块；若机器尚未注册完成则返回空数组（此时不写缓存，稍后可重试）
     */
    public static Block[] get() {
        Block[] cached = cache;
        if (cached != null) return cached;
        Block[] resolved = resolve(PCMachines.THREAD_HATCH, PREFIX, false);
        if (resolved.length > 0) cache = resolved;
        return resolved;
    }

    /**
     * @return 劳保线程仓方块（通常只有 1 个）；未注册完成时返回空数组（不写缓存）
     */
    public static Block[] labor() {
        Block[] cached = laborCache;
        if (cached != null) return cached;
        Block[] resolved = resolve(
                PCMachines.LABOR_THREAD_HATCH == null ? null : new MachineDefinition[]{PCMachines.LABOR_THREAD_HATCH},
                LABOR_NAME, true);
        if (resolved.length > 0) laborCache = resolved;
        return resolved;
    }

    /**
     * @param defs       优先来源（机器定义数组，可能为 null）
     * @param name       兜底扫描用的注册名（前缀或全名）
     * @param exactName  true = 注册名必须完全等于 name；false = 以 name 开头
     */
    private static Block[] resolve(MachineDefinition[] defs, String name, boolean exactName) {
        List<Block> found = new ArrayList<>();
        if (defs != null) {
            for (MachineDefinition def : defs) {
                if (def != null && def.getBlock() != null) found.add(def.getBlock());
            }
        }
        if (found.isEmpty()) {
            // 兜底：直接按注册名扫描方块注册表（正常时序下用不到）
            for (Block block : ForgeRegistries.BLOCKS) {
                ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
                if (key == null || !PackCompanion.MODID.equals(key.getNamespace())) continue;
                String path = key.getPath();
                boolean hit = exactName ? path.equals(name) : path.startsWith(name);
                // 普通扫描必须排除劳保线程仓（否则它会被注入到任意机器）
                if (hit && (!exactName ? !LABOR_NAME.equals(path) : true)) found.add(block);
            }
        }
        return found.isEmpty() ? EMPTY : found.toArray(EMPTY);
    }
}
