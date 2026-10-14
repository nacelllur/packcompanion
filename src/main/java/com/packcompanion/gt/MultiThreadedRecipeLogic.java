package com.packcompanion.gt;

import com.packcompanion.Config;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import net.minecraftforge.fluids.FluidStack;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.ActionResult;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;

import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import com.mojang.serialization.DynamicOps;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 多线程配方逻辑：结构中每存在一个线程仓（取最大 currentThreads），可同时运行 N 条独立配方。
 *
 * 设计要点：
 * - 有效线程数 <= 1 时直接走原版 {@link RecipeLogic#serverTick()}，行为与原版完全一致（零回归风险）。
 * - <b>配方去重</b>：线程仓的语义是「同时处理不同配方」，每个线程优先分配一条
 *   <b>尚未被其他线程占用</b> 的配方；「同一配方重复执行」属于并行仓的职责。
 *   若所有可匹配配方都已被占用，该线程保持空闲（GUI/Jade 显示 # 说明原因），
 *   除非 config 打开 {@code gtThreadHatch.allowDuplicateRecipes}。
 * - 能耗按线程数成倍抽取（每条线程各自 handleTickRecipeIO）。
 * - 基类 status/progress/duration 作为聚合显示镜像，驱动多方块模型与 GUI 进度条。
 * - 同步字符串按 5 tick 降频刷新（结构变化时立即刷新）。
 */
public class MultiThreadedRecipeLogic extends RecipeLogic {

    // 子类 FieldHolder：让 LDLib 同步系统把我们新增的 @DescSynced 字段一起同步到客户端
    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            MultiThreadedRecipeLogic.class, RecipeLogic.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /** 单 tick 内允许发起「查找新配方」的空闲线程数上限（防止 256 线程全量扫描配方表卡服） */
    private static final int MAX_SEARCHES_PER_TICK = 8;
    /** 同步字符串刷新间隔（tick）：进度显示每 5 tick 刷新一次，结构变化时立即刷新 */
    private static final int SYNC_INTERVAL = 5;
    /** GUI 逐线程明细的最大线程数，超过则退化为汇总显示 */
    private static final int MAX_DETAILED_THREADS = 32;
    /** Jade HUD 逐线程明细的最大行数 */
    private static final int MAX_JADE_LINES = 8;
    /** 排查日志的输出间隔（tick），5 秒一次，避免刷屏 */
    private static final int LOG_INTERVAL = 100;

    /**
     * 空闲搜索退避台阶（tick）：连续若干轮「搜了但一个线程都没启动」后，把空闲线程的
     * 配方查找间隔沿此数列逐步拉长（上限再由 {@link Config#getIdleSearchBackoffMax()} 截断）。
     * 最后几档等价于「3 秒查一次」，只有整机彻底没料可跑时才会走到。
     */
    private static final int[] IDLE_BACKOFF = { 1, 1, 2, 4, 8, 16, 32, 60 };

    /** 紧凑串字段分隔符：条目间 ';'，字段间 '|'（资源路径不含这两个字符） */
    private static final char SEP_ENTRY = ';';
    private static final char SEP_FIELD = '|';
    /** 空闲标记：无可用配方 */
    private static final String MARK_IDLE = "-";
    /** 空闲标记：有可匹配配方，但已被其他线程占用（去重导致本线程空闲） */
    private static final String MARK_OCCUPIED_BY_OTHER = "#";
    /** 空闲标记：存在可匹配且未被占用的配方，但启动失败 —— 供电/输入/输出空间不足（整体条目为 "+"） */
    private static final String MARK_IO_BLOCKED = "+";
    /** 可选第 5 字段标记：该线程持有配方，但正处于退避等待（真缺电，会等待较久） */
    private static final String MARK_STALLED = "!";
    /** 可选第 5 字段标记：该线程持有配方，本轮排队等电（共享能源仓竞争，下一 tick 即重试） */
    private static final String MARK_QUEUED = "~";

    /** 检测到的线程仓数量（服务器扫描，同步给客户端显示） */
    @Persisted
    @DescSynced
    private int syncedHatches = 0;

    /** 当前生效的线程数（取所有线程仓的最大 currentThreads） */
    @Persisted
    @DescSynced
    private int syncedThreads = 1;

    /**
     * 每线程紧凑状态（每 5 tick 刷新，结构变化立即刷新）：
     * 条目格式 "itemId|count|progress|duration"（itemId 取不到时为配方 id），
     * 退避等待中额外追加第 5 字段 "!"；
     * 条目间 ';'；空闲条目为 "-"（无配方）、"#"（配方已被其他线程占用）或 "+"（供电/原料/输出不足）。
     */
    @DescSynced
    private String syncedThreadStatus = "";

    private static class ThreadState {
        @Nullable GTRecipe recipe;
        @Nullable GTRecipe originRecipe;
        int progress;
        int duration;
        int runAttempt;
        int runDelay;
        /**
         * 本 tick 是否因「共享能源仓被别的线程抽干」而没拿到电。
         *
         * <p>与 {@link #runAttempt} 的区别是关键：</p>
         * <ul>
         *   <li>{@code starvedBySiblings == true}：本机整体电源是够的，只是本 tick 轮转时
         *       排在别的线程后面、能源仓已被抽空。这属于正常的共享竞争，**不该惩罚** ——
         *       下一 tick 轮转起点会换人，本线程自然能充上电，进度只需原地等待。</li>
         *   <li>{@code starvedBySiblings == false} 且抽电失败：整机真的缺电（所有线程都没抽到），
         *       才走 {@code runAttempt} 指数退避。</li>
         * </ul>
         * <p>不加这个区分时，256 线程共用一个能源仓会让排在后面的线程每 tick 都「失败」，
         * runAttempt 被顶到 5 → runDelay 300 tick，同时 progress 被打回 1，
         * 表现为「进度回退 + 工作一次停很久」。</p>
         */
        boolean starvedBySiblings;
        /** 本线程正在处理的配方的「主产物」itemId（无产物输出时为 null） */
        @Nullable String displayKey;
        /** 主产物数量 */
        int outputCount;
        /** 本线程本 tick 想启动的配方已被其他线程占用（仅用于 GUI 提示） */
        boolean occupiedByOther;
        /** 本线程本 tick 找到了未被占用的可匹配配方，但启动 IO 失败（供电/原料/输出空间不足，仅用于 GUI 提示） */
        boolean blockedByIO;
        /** 排查用：最近一次查找中，配方被拒的阶段（modify-null / conditions / contents / io-in / io-out） */
        @Nullable String lastReject;
        /** 排查用：最近一次查找中，迭代器产出的候选配方 id（最多前若干个） */
        @Nullable String lastCandidates;
        Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches;
    }

    private final List<ThreadState> states = new ArrayList<>();
    private final Set<ResourceLocation> occupiedScratch = new ObjectOpenHashSet<>();
    private int lastThreadCount = -1;
    /** 空闲线程查找配方的轮转起点，保证大线程数下每个线程都有机会被调度到 */
    private int searchRotation = 0;
    /**
     * 工作线程的 tick 起始下标，每 tick 轮转。
     * 多条线程共享一个能源仓时，固定顺序会让「排后面的线程」永远抢不到电，一次次撞上
     * EU 退避（runDelay 60→300 tick）→ 表现为「加线程后半天没反应」。
     * 轮转后各线程公平分电，整体变成「所有线程都在缓慢推进」。
     */
    private int tickRotation = 0;
    private int syncTick = 0;
    /** 排查日志的计数器（每 LOG_INTERVAL tick 输出一次） */
    private int logTick = 0;
    /** 线程结构/配方占用关系发生变化：跳过降频，立即同步 */
    private boolean structuralDirty = false;
    /** 连续多少轮空闲搜索「一个线程都没启动」（空闲退避的计时依据） */
    private int idleRounds = 0;
    /** 距离下次空闲搜索还需等待的 tick 数（0 = 本 tick 搜索） */
    private int searchCooldown = 0;

    /**
     * 「当前正在推进的线程配方」作用域变量 —— 仅在 {@link #handleThreadWorking} 调用
     * {@code machine.onWorking()} 期间非空。
     *
     * <p><b>为什么必须有它：</b>多线程模式下原版 {@link RecipeLogic#serverTick()} 不运行，
     * 基类 {@code lastRecipe} 恒为 null。而部分机器会在 onWorking 里读
     * {@code getRecipeLogic().getLastRecipe()} —— 最典型是 {@code FusionReactorMachine}
     * （第 191 行 {@code recipe.data}，只有运行时失效的 assert 兜底），拿到 null 直接
     * NullPointerException 崩服。此处把「正在推进的那条线程配方」暴露给它：既修崩溃，
     * 又保证读到的是当前线程真正在跑的那条（而非随便一条）。</p>
     */
    @Nullable
    private GTRecipe onWorkingRecipe;

    /**
     * 对外暴露「正在推进的线程配方」：{@link #onWorkingRecipe} 非空时优先返回，
     * 其余时刻回落到基类 {@code lastRecipe}（多线程模式下为 null，与改动前一致）。
     * 见 {@link #onWorkingRecipe} 的说明。
     */
    @Override
    public GTRecipe getLastRecipe() {
        GTRecipe scoped = onWorkingRecipe;
        return scoped != null ? scoped : super.getLastRecipe();
    }

    public MultiThreadedRecipeLogic(IRecipeLogicMachine machine) {
        super(machine);
    }

    //////////////////////////////////////
    // ******** 线程数计算 ********//
    //////////////////////////////////////

    private int getAvailableThreads() {
        if (machine.self() instanceof IMultiController controller && controller.isFormed()) {
            int n = 1;
            int hatches = 0;
            for (IMultiPart part : controller.getParts()) {
                if (part.self() instanceof ThreadHatchPartMachine hatch) {
                    hatches++;
                    n = Math.max(n, hatch.getCurrentThreads());
                }
            }
            if (syncedHatches != hatches) {
                syncedHatches = hatches;
                markStructuralDirty();
            }
            if (syncedThreads != n) {
                syncedThreads = n;
                markStructuralDirty();
            }
            return n;
        }
        if (syncedHatches != 0) {
            syncedHatches = 0;
            markStructuralDirty();
        }
        if (syncedThreads != 1) {
            syncedThreads = 1;
            markStructuralDirty();
        }
        return 1;
    }

    /** 线程数变化时调整 ThreadState 列表，使其数量与线程数严格一致 */
    private void adjustStates(int threads) {
        while (states.size() < threads) {
            ThreadState st = new ThreadState();
            st.chanceCaches = makeChanceCaches();
            states.add(st);
        }
        while (states.size() > threads) {
            threadInterrupt(states.remove(states.size() - 1));
        }
        markStructuralDirty();
    }

    private void clearAllStates() {
        if (states.isEmpty()) return;
        for (ThreadState st : states) {
            threadInterrupt(st);
        }
        states.clear();
        searchRotation = 0;
        markStructuralDirty();
    }

    private void markStructuralDirty() {
        structuralDirty = true;
    }

    //////////////////////////////////////
    // ******* 空闲搜索退避 *******//
    //////////////////////////////////////

    /**
     * 外部状态发生任何变化（材料进总线、产物被抽走、供电变化、线程数改变、结构重建）→
     * 立即解除空闲退避、回到全速查找。
     *
     * <p>退避只用来省「整机没料可跑时的空转查找」这一种浪费，因此它必须能被廉价地打断，
     * 否则就会重现「加了材料半天没反应」的观感问题。
     */
    private void wakeFromIdle() {
        idleRounds = 0;
        searchCooldown = 0;
    }

    /**
     * <b>为什么 override 这个方法就是零成本的唤醒钩子：</b>
     * GTM 的 {@code WorkableMultiblockMachine} 在结构成形时，会对每个 part 的
     * {@code RecipeHandlerList} 执行
     * {@code handlerList.subscribe(this.recipeLogic::updateTickSubscription)}
     * （见该文件 140 / 152 行），而那个 {@code recipeLogic} 正是被 Mixin 替换成的本类实例。
     * 于是任何物品 / 流体 / 能量容器的内容变化都会回调到此处 ——
     * 既不需要我们自己订阅，也不用操心结构变化时的退订
     * （{@code onStructureInvalid} / {@code onPartUnload} 已统一处理）。
     */
    @Override
    public void updateTickSubscription() {
        wakeFromIdle();
        super.updateTickSubscription();
    }

    //////////////////////////////////////
    // ********* 主循环 **********//
    //////////////////////////////////////

    @Override
    public void serverTick() {
        int threads = getAvailableThreads();

        if (threads <= 1) {
            // 无线程仓（或线程数被调到 1）：释放线程占用并完全走原版逻辑，零回归
            clearAllStates();
            lastThreadCount = 1;
            if (!syncedThreadStatus.isEmpty()) syncedThreadStatus = "";
            super.serverTick();
            return;
        }

        if (isSuspend()) return; // 机器被暂停：线程全部挂起（保留订阅，恢复即续跑）

        if (lastThreadCount != threads) {
            adjustStates(threads);
            lastThreadCount = threads;
            markLastRecipeDirty();
        }

        final boolean suspendFlag = isSuspendAfterFinish();

        // 1) 已持有配方的线程：轮转起点推进（共享能源/输入按 round-robin 分配）
        tickWorkingThreads(suspendFlag);

        // 2) 空闲线程：轮转取号 + 限流查找新配方（优先找「别人没在跑」的配方）
        if (!suspendFlag) {
            searchIdleThreads();
        }

        mirrorAggregateStatus();
    }

    /**
     * 推进所有持有配方的线程。起始下标每 tick 轮转：多条线程共享一个（或几个）能源仓时，
     * 固定顺序会使排在前面的线程每 tick 都先拿到电、排在后面的线程反复失败并触发
     * EU 退避（runDelay 最高 300 tick = 15 秒），看起来就是「加了线程却半天没反应」。
     */
    private void tickWorkingThreads(boolean suspendFlag) {
        int n = states.size();
        if (n == 0) return;
        int start = Math.floorMod(tickRotation++, n);

        // 第一遍：真正推进配方。抽电失败的线程先只做标记，不立即施加退避惩罚 ——
        // 因为「先后顺序」决定了谁能抽到电，此刻还无法判断是整机缺电还是被兄弟线程抽干。
        passAdvanced = 0;
        passPowerFailed = 0;
        for (int k = 0; k < n; k++) {
            ThreadState st = states.get((start + k) % n);
            if (st.recipe != null) {
                tickState(st, suspendFlag);
            }
        }

        // 整机缺电 = 本轮没有任何线程推进成功，且确实有线程因 EU 失败。
        // 这种情况才是真缺电，值得退避；否则只是共享能源仓的正常竞争，下一 tick 轮转会轮到别人，
        // 本线程原地等待即可，进度与 runDelay 都不该被惩罚。
        globalPowerShortage = (passAdvanced == 0 && passPowerFailed > 0);
    }

    /** 本轮成功推进的线程数、因 EU 不足失败的线程数、以及整机是否真的缺电 */
    private int passAdvanced;
    private int passPowerFailed;
    private boolean globalPowerShortage;

    private void tickState(ThreadState st, boolean suspendFlag) {
        if (st.progress < st.duration) {
            if (st.runDelay > 0) {
                st.runDelay--;
            } else {
                handleThreadWorking(st);
            }
        }
        if (st.recipe != null && st.progress >= st.duration) {
            onThreadFinish(st, suspendFlag);
        }
    }

    /**
     * 空闲线程查找配方：轮转起点 + 单 tick 上限，避免线程数很大时每个 tick 全量扫描配方表。
     * 小线程数（<= MAX_SEARCHES_PER_TICK）时等价于「每 tick 都查」，不会有额外延迟。
     *
     * <p><b>空闲退避：</b>当整机「搜了但一个线程都没启动」时（无匹配配方 / 供电、原料、输出空间不足），
     * 说明机器处于「没料可跑」的稳态，而这件事不会自己改变，每 tick 全速重查纯属空转。
     * 此时按 {@link #IDLE_BACKOFF} 逐步拉长下一次查找的间隔；一旦有任何外部变化
     * （{@link #updateTickSubscription()} 被库存变化回调）或有线程空出来（{@link #wakeFromIdle()}），
     * 立即回到全速。
     *
     * <p><b>唯一的例外是不退避：</b>若存在「配方已被其他线程占用」的空闲线程，则保持全速 ——
     * 这类线程是在等别人跑完接手，降频会把接手延迟推到退避上限。
     */
    private void searchIdleThreads() {
        int n = states.size();
        if (n == 0) return;

        // 退避期：跳过本 tick 的查找。正在工作的线程不受影响，它们由 tickWorkingThreads 推进。
        if (searchCooldown > 0) {
            searchCooldown--;
            maybeLogSearch();
            return;
        }

        int budget = MAX_SEARCHES_PER_TICK;
        int start = Math.floorMod(searchRotation, n);
        int started = 0;
        boolean anyOccupied = false;
        for (int k = 0; k < n && budget > 0; k++) {
            ThreadState st = states.get((start + k) % n);
            if (st.recipe != null) continue;
            budget--;
            if (threadFindRecipe(st)) {
                started++;
            } else if (st.occupiedByOther) {
                anyOccupied = true;
            }
        }
        searchRotation = (start + MAX_SEARCHES_PER_TICK) % n;

        if (started > 0 || anyOccupied) {
            wakeFromIdle();
        } else if (Config.isIdleSearchBackoff()) {
            int step = Math.min(idleRounds, IDLE_BACKOFF.length - 1);
            int interval = Math.min(IDLE_BACKOFF[step], Math.max(1, Config.getIdleSearchBackoffMax()));
            searchCooldown = interval - 1;
            if (idleRounds < IDLE_BACKOFF.length - 1) {
                idleRounds++;
            }
        }
        maybeLogSearch();
    }

    /**
     * 排查用日志：把所有「空闲且没跑起来」的线程状态按 {@link #LOG_INTERVAL} tick 一次输出。
     * 一眼区分三种成因：
     * - {@code no-match}：迭代器没有产出任何候选 → 该机器配方类型里确实没有匹配的配方（放错物品/未定义配方）；
     * - {@code occupied}：候选都被其他线程占用 → 去重导致的正常空闲；
     * - {@code blocked:<阶段>}：找到配方但启动失败 → 修饰/输入/输出不足（阶段与放大的消耗都会打印）。
     */
    private void maybeLogSearch() {
        if (!Config.isLogThreadSearch()) return;
        if (++logTick % LOG_INTERVAL != 0) return;
        StringBuilder sb = null;
        for (int i = 0; i < states.size(); i++) {
            ThreadState st = states.get(i);
            if (st.recipe != null) continue;
            String stateText;
            if (st.occupiedByOther) {
                stateText = "occupied";
            } else if (st.blockedByIO) {
                stateText = "blocked:" + st.lastReject;
            } else if (st.lastCandidates != null && !"(none)".equals(st.lastCandidates)) {
                // 迭代器有候选，但没有任何一个被启动，也没被判为「被占用」→ 其余拦截阶段
                stateText = "blocked:" + (st.lastReject == null ? "unknown" : st.lastReject);
            } else {
                stateText = "no-match";
            }
            if (sb == null) sb = new StringBuilder();
            sb.append("#").append(i + 1).append('=').append(stateText);
            if (st.lastCandidates != null) sb.append(" candidates[").append(st.lastCandidates).append(']');
            sb.append(' ');
        }
        if (sb == null) return;
        GTCEu.LOGGER.info("[packcompanion/thread] {} @{} threads={} idle -> {}", machine.self().getClass().getSimpleName(),
                machine.self().getPos(), states.size(), sb);
    }

    //////////////////////////////////////
    // ******* 线程运行阶段 *******//
    //////////////////////////////////////

    private void handleThreadWorking(ThreadState st) {
        var conditionResult = RecipeHelper.checkConditions(st.recipe, this);
        if (conditionResult.isSuccess()) {
            var handleTick = handleThreadTickRecipe(st);
            if (handleTick.isSuccess()) {
                // 成功推进一个 tick 就清零退避：否则历史上偶发的缺电会把 runAttempt 顶到 5，
                // 让该线程长期背着 300 tick 的空等，表现为「响应极慢」。
                st.runAttempt = 0;
                st.runDelay = 0;
                st.starvedBySiblings = false;
                passAdvanced++;
                // 作用域内把「本线程正在推进的配方」暴露给 getLastRecipe()：
                // FusionReactorMachine.onWorking 等机器回调会读它，多线程模式下基类
                // lastRecipe 恒为 null，直接读会 NPE 崩服（见 onWorkingRecipe 字段说明）。
                onWorkingRecipe = st.recipe;
                boolean keepWorking = false;
                try {
                    keepWorking = machine.onWorking();
                } finally {
                    onWorkingRecipe = null;
                }
                if (!keepWorking) {
                    threadInterrupt(st);
                    return;
                }
                st.progress++;
            } else {
                boolean euShortage = handleTick.io() == IO.IN && handleTick.capability() == EURecipeCapability.CAP;
                if (euShortage) {
                    passPowerFailed++;
                    // 区分两种「抽不到电」：
                    //  - globalPowerShortage：整机所有活跃线程本轮都没推进 → 真的缺电，退避
                    //  - 否则：只是本 tick 轮转时排在别人后面、能源仓被抽空 → 正常竞争，
                    //    下一 tick 轮转起点会换人，原地等待即可，不惩罚、不打回进度。
                    st.starvedBySiblings = !globalPowerShortage;
                } else {
                    st.starvedBySiblings = false;
                }
                if (euShortage && globalPowerShortage) {
                    // 真·整机缺电：指数退避（对齐原版 runAttempt 逻辑）
                    st.runAttempt = Math.min(st.runAttempt + 1, 5);
                    st.runDelay = st.runAttempt * 60;
                }
                if (!st.starvedBySiblings) {
                    // 只有真缺电或非电力原因才回退进度：共享竞争导致的等待不该清零进度，
                    // 否则多条线程共用一个能源仓时进度会被反复打回起点，永远跑不完。
                    if (st.progress > 0 && machine.regressWhenWaiting()) {
                        st.progress = 1;
                    }
                }
            }
        } else {
            st.starvedBySiblings = false;
            if (st.progress > 0 && machine.regressWhenWaiting()) {
                st.progress = 1;
            }
        }
    }

    private ActionResult handleThreadTickRecipe(ThreadState st) {
        if (!st.recipe.hasTick()) return ActionResult.SUCCESS;
        var result = RecipeHelper.matchTickRecipe(machine, st.recipe);
        if (!result.isSuccess()) return result;
        result = RecipeHelper.handleTickRecipeIO(machine, st.recipe, IO.IN, st.chanceCaches);
        if (!result.isSuccess()) return result;
        return RecipeHelper.handleTickRecipeIO(machine, st.recipe, IO.OUT, st.chanceCaches);
    }

    //////////////////////////////////////
    // ******* 线程配方分配 *******//
    //////////////////////////////////////

    /** 收集「其他线程正在处理」的配方 id */
    private Set<ResourceLocation> collectOccupiedRecipeIds(ThreadState self) {
        occupiedScratch.clear();
        for (ThreadState other : states) {
            if (other == self) continue;
            if (other.recipe != null && other.recipe.id != null) {
                occupiedScratch.add(other.recipe.id);
            } else if (other.originRecipe != null && other.originRecipe.id != null) {
                occupiedScratch.add(other.originRecipe.id);
            }
        }
        return occupiedScratch;
    }

    /**
     * 为一条空闲线程分配配方。
     *
     * 关键规则：优先分配**其他线程没有在跑**的配方 —— 线程仓负责「同时处理不同配方」，
     * 同配方重复执行属于并行仓的职责，不应由线程仓重复占用。
     *
     * <b>为什么「排除被占用的配方」必须放进查找谓词，而不是取出来再过滤：</b>
     * GTM 的 `GTRecipeLookup` 成分树为每条配方只保存一条线性路径，`RecipeIterator` 每个成分组
     * 最多产出「第一条可达配方」。若先把候选取出来、再判断是否被别的线程占用，在
     * 「多种原料 → 多条配方」的结构里（例如温室：水 + 多种种子）迭代器可能只产出第一条可达配方，
     * 导致其余线程误判成「只有这一条配方、而且被占用了」而全部空闲。
     * 把排除条件放进谓词后，查找树在遍历途中就会跳过被占用的配方、继续找下一条不同的配方。
     */
    /**
     * 为一条空闲线程查找并启动配方。
     *
     * @return true 表示已成功为该线程启动一条配方（空闲退避据此判断「这一轮有没有收获」）
     */
    private boolean threadFindRecipe(ThreadState st) {
        st.occupiedByOther = false;
        st.blockedByIO = false;
        st.lastReject = null;
        st.lastCandidates = null;
        final boolean allowDuplicate = Config.isDuplicateRecipesAllowed();
        Set<ResourceLocation> occupied = collectOccupiedRecipeIds(st);

        if (tryStartRecipe(st, occupied, !allowDuplicate)) {
            return true;
        }

        if (!allowDuplicate) {
            // 没有启动成功。若确实存在「被其他线程占用」的匹配配方，则如实报「被占用」；
            // 但若同时存在「自由配方因 IO 失败」（blockedByIO），瓶颈是资源，优先报资源。
            GTRecipe occupiedCandidate = firstMatching(st, r -> r.id != null && occupied.contains(r.id)
                    && RecipeHelper.matchContents(machine, r).isSuccess());
            if (occupiedCandidate != null && !st.blockedByIO) {
                st.lastReject = "occupied";
                st.occupiedByOther = true;
                markStructuralDirty();
            }
        }
        return false;
    }

    /**
     * 查找并尝试启动一条配方。
     *
     * @param excludeOccupied true = 直接跳过已被其他线程占用的配方（默认语义：一个线程一条配方）
     * @return true 表示已为该线程成功启动配方
     */
    private boolean tryStartRecipe(ThreadState st, Set<ResourceLocation> occupied, boolean excludeOccupied) {
        var matches = machine.getRecipeType().searchRecipe(machine, r ->
                (!excludeOccupied || r.id == null || !occupied.contains(r.id))
                        && RecipeHelper.matchContents(machine, r).isSuccess());

        StringBuilder seen = new StringBuilder();
        while (matches.hasNext()) {
            GTRecipe match = matches.next();
            if (match == null) continue;
            if (seen.length() < 200) {
                if (seen.length() > 0) seen.append(',');
                seen.append(match.id != null ? match.id : "?");
            }

            GTRecipe modified = machine.fullModifyRecipe(match);
            if (modified == null) {
                // 修饰器拒绝（批量/并行在「可用输入或输出空间」不足时返回 ModifierFunction.NULL）
                st.blockedByIO = true;
                st.lastReject = "modify-null";
                markStructuralDirty();
                continue;
            }
            if (!RecipeHelper.checkConditions(modified, this).isSuccess()) {
                st.lastReject = "conditions";
                continue;
            }
            if (!RecipeHelper.matchContents(machine, modified).isSuccess()) {
                // 修饰器放大后的输入/输出装不下（超频后合并批量 ×N 的常见结果）
                st.blockedByIO = true;
                st.lastReject = "contents(" + describeModifiedCost(modified) + ")";
                markStructuralDirty();
                continue;
            }

            if (!machine.beforeWorking(modified)) return false; // 机器层面当前无法工作，与原版一致直接退出

            // 关键：先用「模拟」预演一次输入+输出，确认全部能通过再真正扣料。
            // RecipeRunner 的 IO.IN 是「先匹配后消耗」按能力依次执行，若后面的能力（例如 EU）
            // 失败，前面已经消耗掉的物品/流体会被吞掉且不退还 —— 表现为「放进去的材料凭空少了」。
            // N 条线程共享一个能源仓时这种情况会频繁发生，因此必须先模拟。
            boolean inputOk = RecipeHelper.handleRecipe(machine, modified, IO.IN, modified.inputs,
                    st.chanceCaches, false, true).isSuccess()
                    && RecipeHelper.handleRecipe(machine, modified, IO.OUT, modified.outputs,
                    st.chanceCaches, false, true).isSuccess();
            if (!inputOk) {
                st.blockedByIO = true;
                st.lastReject = "io-dry";
                markStructuralDirty();
                continue;
            }

            if (!RecipeHelper.handleRecipeIO(machine, modified, IO.IN, st.chanceCaches).isSuccess()) {
                // 输入不满足（批量合并后水量/原料不足等）：记录真实原因，换下一个候选配方
                st.blockedByIO = true;
                st.lastReject = "io-in";
                markStructuralDirty();
                continue;
            }
            if (!RecipeHelper.handleRecipeIO(machine, modified, IO.OUT, st.chanceCaches).isSuccess()) {
                st.blockedByIO = true;
                st.lastReject = "io-out";
                markStructuralDirty();
                continue;
            }
            startThread(st, match, modified);
            return true;
        }
        st.lastCandidates = seen.length() == 0 ? "(none)" : seen.toString();
        return false;
    }

    /** 只查一条满足条件的候选配方（用于「被占用」归因），不做任何启动动作 */
    @Nullable
    private GTRecipe firstMatching(ThreadState st, java.util.function.Predicate<GTRecipe> predicate) {
        var matches = machine.getRecipeType().searchRecipe(machine, predicate);
        for (int guard = 0; guard < 16 && matches.hasNext(); guard++) {
            GTRecipe r = matches.next();
            if (r != null) return r;
        }
        return null;
    }

    /** 排查用：把修饰后的配方启动成本（输入/输出概要）描述成短串，便于在日志里看出批量倍数 */
    private static String describeModifiedCost(GTRecipe recipe) {
        StringBuilder sb = new StringBuilder();
        List<Content> items = recipe.getInputContents(ItemRecipeCapability.CAP);
        for (int i = 0; i < items.size() && i < 3; i++) {
            Content c = items.get(i);
            if (c == null || c.content == null || c.isChanced()) continue;
            Object raw = c.content instanceof Ingredient ? c.content : ItemRecipeCapability.CAP.of(c.content);
            if (!(raw instanceof Ingredient ing)) continue;
            ItemStack[] stacks = ing.getItems();
            if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) continue;
            sb.append(ForgeRegistries.ITEMS.getKey(stacks[0].getItem())).append('x').append(stacks[0].getCount()).append(' ');
        }
        List<Content> fluids = recipe.getInputContents(com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability.CAP);
        for (int i = 0; i < fluids.size() && i < 2; i++) {
            Content c = fluids.get(i);
            if (c == null || c.content == null) continue;
            Object of = com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability.CAP.of(c.content);
            if (of instanceof com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient fi
                    && fi.getStacks().length > 0) {
                sb.append(fi.getStacks()[0].getFluid().builtInRegistryHolder().key().location())
                        .append('x').append(fi.getAmount()).append("mB").append(' ');
            }
        }
        return sb.length() == 0 ? "many" : sb.toString().trim();
    }

    /** 流体产物 displayKey 前缀（与物品 id 区分，describeOutput 据此走流体解析） */
    private static final String FLUID_KEY_PREFIX = "#fluid:";

    /** 从配方主产物解析显示用 key 与数量（物品优先，其次流体；都没有则回退显示配方 id） */
    private static void resolveOutputDisplay(GTRecipe recipe, ThreadState st) {
        st.displayKey = null;
        st.outputCount = 0;
        // 1) 物品产物
        if (resolveDisplayFromCapability(recipe, ItemRecipeCapability.CAP, st, null)) return;
        // 2) 流体产物（如合金冶炼炉的熔融金属）：key 带 #fluid: 前缀，数量为 mB
        resolveDisplayFromCapability(recipe, FluidRecipeCapability.CAP, st, FLUID_KEY_PREFIX);
    }

    /** 通用产物解析：取该能力下第一个 100% 产物（全是概率产物时取第一个兜底） */
    private static boolean resolveDisplayFromCapability(GTRecipe recipe, RecipeCapability<?> cap,
            ThreadState st, String keyPrefix) {
        List<Content> outputs = recipe.getOutputContents(cap);
        Content chosen = null;
        for (Content c : outputs) {
            if (c == null || c.content == null) continue;
            if (c.isChanced()) continue; // 跳过概率产物，取 100% 的主产物
            chosen = c;
            break;
        }
        if (chosen == null && !outputs.isEmpty()) {
            chosen = outputs.get(0); // 全是概率产物时取第一个兜底
        }
        if (chosen == null) return false;
        if (cap == ItemRecipeCapability.CAP) {
            Object raw = chosen.content instanceof Ingredient ? chosen.content : ItemRecipeCapability.CAP.of(chosen.content);
            if (!(raw instanceof Ingredient ingredient)) return false;
            ItemStack[] stacks = ingredient.getItems();
            if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) return false;
            st.displayKey = ForgeRegistries.ITEMS.getKey(stacks[0].getItem()).toString();
            st.outputCount = stacks[0].getCount();
            return true;
        }
        if (cap == FluidRecipeCapability.CAP) {
            Object raw = FluidRecipeCapability.CAP.of(chosen.content);
            if (!(raw instanceof FluidIngredient fi)) return false;
            FluidStack[] stacks = fi.getStacks();
            if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) return false;
            st.displayKey = keyPrefix + ForgeRegistries.FLUIDS.getKey(stacks[0].getFluid());
            st.outputCount = stacks[0].getAmount();
            return true;
        }
        return false;
    }

    private void startThread(ThreadState st, GTRecipe origin, GTRecipe modified) {
        st.recipe = modified;
        st.originRecipe = origin;
        st.progress = 0;
        st.duration = modified.duration;
        st.runAttempt = 0;
        st.runDelay = 0;
        st.starvedBySiblings = false;
        st.occupiedByOther = false;
        st.blockedByIO = false;
        resolveOutputDisplay(modified, st);
        markStructuralDirty();
    }

    //////////////////////////////////////
    // ******* 线程完成/中断 *******//
    //////////////////////////////////////

    private void onThreadFinish(ThreadState st, boolean suspendFlag) {
        // 一条线程跑完 = 它占用的配方被释放，其余空闲线程应立即重查接手（不能等退避计时走完）
        wakeFromIdle();
        machine.afterWorking();
        st.runAttempt = 0;
        st.runDelay = 0;
        GTRecipe finished = st.recipe;
        if (finished != null) {
            RecipeHelper.handleRecipeIO(machine, finished, IO.OUT, st.chanceCaches);
        }
        st.recipe = null;
        st.progress = 0;
        st.duration = 0;
        markStructuralDirty();
        if (suspendFlag || finished == null) {
            st.originRecipe = null;
            return;
        }
        // 尝试原地续跑同一条配方（仍是自己占用的配方，不与他人冲突）
        GTRecipe next = finished;
        if (machine.alwaysTryModifyRecipe() && st.originRecipe != null) {
            var modified = machine.fullModifyRecipe(st.originRecipe.copy());
            next = modified != null ? modified : null;
        }
        if (next != null
                && RecipeHelper.checkConditions(next, this).isSuccess()
                && RecipeHelper.matchContents(machine, next).isSuccess()
                && machine.beforeWorking(next)
                // 同样先模拟预演，避免 handleRecipeIO(IO.IN) 扣了料却因后续能力失败而吞掉材料
                && RecipeHelper.handleRecipe(machine, next, IO.IN, next.inputs,
                        st.chanceCaches, false, true).isSuccess()
                && RecipeHelper.handleRecipeIO(machine, next, IO.IN, st.chanceCaches).isSuccess()) {
            st.recipe = next;
            st.progress = 0;
            st.duration = next.duration;
            st.runAttempt = 0;
            st.runDelay = 0;
            st.starvedBySiblings = false;
            resolveOutputDisplay(next, st);
        } else {
            st.originRecipe = null;
            st.displayKey = null;
            st.outputCount = 0;
        }
    }

    private void threadInterrupt(ThreadState st) {
        // 线程被中断（结构重建 / 线程数下调 / 停机）= 占用关系变了，解除退避
        wakeFromIdle();
        if (st.recipe != null) {
            machine.afterWorking();
            markStructuralDirty();
        }
        st.recipe = null;
        st.originRecipe = null;
        st.progress = 0;
        st.duration = 0;
        st.occupiedByOther = false;
        st.blockedByIO = false;
        st.starvedBySiblings = false;
        st.runAttempt = 0;
        st.runDelay = 0;
    }

    //////////////////////////////////////
    // **** 聚合状态镜像（显示用）****//
    //////////////////////////////////////

    private void mirrorAggregateStatus() {
        boolean anyWorking = false;
        int mirrorProgress = 0, mirrorDuration = 0;
        for (ThreadState st : states) {
            if (st.recipe != null) {
                anyWorking = true;
                if (mirrorDuration == 0 && st.duration > 0) {
                    mirrorProgress = st.progress;
                    mirrorDuration = st.duration;
                }
            }
        }
        Status target = anyWorking ? Status.WORKING : Status.IDLE;
        if (getStatus() != target) {
            setStatus(target);
        }
        this.isActive = anyWorking;
        if (anyWorking) {
            this.progress = mirrorProgress;
            this.duration = mirrorDuration;
        }
        updateSyncedThreadStatus();
    }

    /**
     * 服务器端：把每线程状态压缩成一条同步字符串。
     * 进度变化按 {@link #SYNC_INTERVAL}(5 tick) 降频刷新；线程/配方占用关系变化立即刷新。
     */
    private void updateSyncedThreadStatus() {
        if (!structuralDirty && ++syncTick % SYNC_INTERVAL != 0) {
            return;
        }
        structuralDirty = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            ThreadState st = states.get(i);
            if (i > 0) sb.append(SEP_ENTRY);
            if (st.recipe != null) {
                String key = st.displayKey != null ? st.displayKey
                        : (st.recipe.id != null ? st.recipe.id.toString() : "?");
                sb.append(key).append(SEP_FIELD)
                        .append(st.outputCount).append(SEP_FIELD)
                        .append(st.progress).append(SEP_FIELD).append(st.duration);
                // 可选第 5 字段：该线程正在退避等待（真缺电）或本轮排队等电（共享竞争）
                if (st.runDelay > 0) sb.append(SEP_FIELD).append(MARK_STALLED);
                else if (st.starvedBySiblings) sb.append(SEP_FIELD).append(MARK_QUEUED);
            } else {
                sb.append(st.occupiedByOther ? MARK_OCCUPIED_BY_OTHER
                        : st.blockedByIO ? MARK_IO_BLOCKED : MARK_IDLE);
            }
        }
        String s = sb.toString();
        if (!syncedThreadStatus.equals(s)) syncedThreadStatus = s;
    }

    //////////////////////////////////////
    // *** 客户端显示（GUI / Jade 共用） ***//
    //////////////////////////////////////

    /** 供 Jade/外部读取的同步值 */
    public int getSyncedHatches() {
        return syncedHatches;
    }

    public int getSyncedThreads() {
        return syncedThreads;
    }

    public String getSyncedThreadStatus() {
        return syncedThreadStatus;
    }

    /** GUI 文本入口：逐线程明细最多 MAX_DETAILED_THREADS 行 */
    public void appendThreadStatusDisplay(List<Component> textList) {
        appendThreadLines(textList, syncedHatches, syncedThreads, syncedThreadStatus, MAX_DETAILED_THREADS);
    }

    /**
     * 把同步字符串解析成可读文本（GUI 与 Jade HUD 共用）。
     * 必须在客户端调用（需要本地化注册表解析物品名）。
     *
     * @param maxDetailedLines 逐线程明细的最大行数，超过则只显示汇总
     */
    public static void appendThreadLines(List<Component> textList, int hatches, int threads,
                                         String status, int maxDetailedLines) {
        if (hatches <= 0) {
            textList.add(Component.translatable("packcompanion.multiblock.thread_hatch.none")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        textList.add(Component.translatable("packcompanion.multiblock.thread_hatch.detected",
                hatches, threads).withStyle(ChatFormatting.AQUA));
        if (threads <= 1) return;

            String[] parts = status.split(";");
            if (parts.length > maxDetailedLines) {
                int running = 0, blocked = 0, idle = 0;
                for (String p : parts) {
                    if (MARK_OCCUPIED_BY_OTHER.equals(p)) blocked++;
                    else if (p.isEmpty() || MARK_IDLE.equals(p) || MARK_IO_BLOCKED.equals(p)) idle++;
                    else running++;
                }
            textList.add(Component.translatable("packcompanion.multiblock.thread.summary",
                    running, parts.length, idle).withStyle(ChatFormatting.YELLOW));
            if (blocked > 0) {
                textList.add(Component.translatable("packcompanion.multiblock.thread.occupied_summary", blocked)
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            return;
        }

        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (MARK_OCCUPIED_BY_OTHER.equals(p)) {
                textList.add(Component.translatable("packcompanion.multiblock.thread.occupied", i + 1)
                        .withStyle(ChatFormatting.DARK_GRAY));
                continue;
            }
            if (MARK_IO_BLOCKED.equals(p)) {
                // 有配方可跑但启动失败：供电/原料/输出空间不足。红色醒目，玩家能直接定位瓶颈。
                textList.add(Component.translatable("packcompanion.multiblock.thread.io_blocked", i + 1)
                        .withStyle(ChatFormatting.RED));
                continue;
            }
            if (p.isEmpty() || MARK_IDLE.equals(p)) {
                textList.add(Component.translatable("packcompanion.multiblock.thread.idle", i + 1)
                        .withStyle(ChatFormatting.DARK_GRAY));
                continue;
            }
            String[] f = p.split("\\|");
            Component item = describeOutput(f.length > 0 ? f[0] : "?", f.length > 1 ? parseIntSafe(f[1]) : 0);
            int prog = f.length > 2 ? parseIntSafe(f[2]) : 0;
            int dur = f.length > 3 ? parseIntSafe(f[3]) : 0;
            boolean stalled = f.length > 4 && MARK_STALLED.equals(f[4]);
            boolean queued = f.length > 4 && MARK_QUEUED.equals(f[4]);
            if (dur > 0) {
                MutableComponent line = Component.translatable("packcompanion.multiblock.thread.running",
                        i + 1, item,
                        String.format("%.1f", prog / 20.0), String.format("%.1f", dur / 20.0),
                        (int) (100.0 * prog / dur));
                if (stalled) {
                    // 退避等待中：整机确实缺电（本轮所有线程都没抽到电），会等待较久。
                    line.append(Component.translatable("packcompanion.multiblock.thread.stalled")
                            .withStyle(ChatFormatting.RED));
                } else if (queued) {
                    // 本轮排队等电：能源仓被先跑的线程抽空，下一 tick 轮转就轮到本线程，属正常现象。
                    line.append(Component.translatable("packcompanion.multiblock.thread.queued")
                            .withStyle(ChatFormatting.GOLD));
                }
                textList.add(line.withStyle(ChatFormatting.YELLOW));
            } else {
                textList.add(Component.translatable("packcompanion.multiblock.thread.preparing", i + 1, item)
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** displayKey -> 本地化名称（含数量）。物品显示 ×N；流体（#fluid: 前缀）显示 N mB；解析失败回退为原始字符串 */
    private static Component describeOutput(String key, int count) {
        Component name = null;
        boolean isFluid = key != null && key.startsWith(FLUID_KEY_PREFIX);
        if (isFluid) {
            String fluidId = key.substring(FLUID_KEY_PREFIX.length());
            ResourceLocation rl = ResourceLocation.tryParse(fluidId);
            if (rl != null) {
                var fluid = ForgeRegistries.FLUIDS.getValue(rl);
                if (fluid != null && fluid.defaultFluidState() != null && !fluid.defaultFluidState().isEmpty()) {
                    name = new FluidStack(fluid, 1000).getDisplayName();
                }
            }
            if (name == null) name = Component.literal(fluidId);
            if (count > 0) {
                // ≥10 桶显示为桶，否则显示 mB，避免一长串数字
                return count >= 10000 && count % 1000 == 0
                        ? name.copy().append(" ×" + (count / 1000) + " 桶")
                        : name.copy().append(" " + String.format("%,d", count) + " mB");
            }
            return name;
        }
        if (key != null) {
            ResourceLocation rl = ResourceLocation.tryParse(key);
            if (rl != null) {
                var item = ForgeRegistries.ITEMS.getValue(rl);
                if (item != null && item != net.minecraft.world.item.Items.AIR) {
                    name = new ItemStack(item).getHoverName();
                }
            }
        }
        if (name == null) {
            name = Component.literal(key == null ? "?" : key);
        }
        return count > 1 ? name.copy().append(" ×").append(String.valueOf(count)) : name;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    //////////////////////////////////////
    // ********* 生命周期 *********//
    //////////////////////////////////////

    @Override
    public void resetRecipeLogic() {
        for (ThreadState st : states) {
            threadInterrupt(st);
        }
        lastThreadCount = -1;
        searchRotation = 0;
        if (!syncedThreadStatus.isEmpty()) syncedThreadStatus = "";
        super.resetRecipeLogic();
    }

    @Override
    public void inValid() {
        for (ThreadState st : states) {
            st.recipe = null;
            st.originRecipe = null;
            st.occupiedByOther = false;
        }
        if (!syncedThreadStatus.isEmpty()) syncedThreadStatus = "";
        super.inValid();
    }

    /** Jade HUD 明细行数上限 */
    public static int getMaxJadeLines() {
        return MAX_JADE_LINES;
    }
}