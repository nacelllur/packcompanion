package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.jetbrains.annotations.NotNull;

/**
 * 线程仓 —— 放入多方块后，多方块配方逻辑可同时运行 N 条独立配方（真·多线程）。
 * 电压等级决定最大线程数：UV=4, UHV=8, UEV=16, UIV=32, UXV=64, OpV=128, MAX=256。
 * GUI 可调当前线程数，默认最大。
 *
 * 注意：刻意【不】实现 IParallelHatch——线程效果完全由 MultiThreadedRecipeLogic
 * 通过扫描部件列表生效，这样线程仓可与原版并行仓共存（并行仓走 hatchParallel
 * 提供每条配方的倍率，线程仓提供独立配方条数，总吞吐 = 并行 × 线程）。
 * 若实现该接口，会被控制器当作唯一的 parallelHatch 缓存，与并行仓互相顶替。
 */
public class ThreadHatchPartMachine extends TieredPartMachine implements IFancyUIMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ThreadHatchPartMachine.class, com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine.MANAGED_FIELD_HOLDER);

    private static final int MIN_THREADS = 1;

    private final int maxThreads;

    public int getMaxThreads() {
        return maxThreads;
    }

    @Persisted
    private int currentThreads;

    public int getCurrentThreads() {
        return currentThreads;
    }

    public ThreadHatchPartMachine(IMachineBlockEntity holder, int tier) {
        super(holder, tier);
        this.maxThreads = 4 << (tier - GTValues.UV);
        this.currentThreads = maxThreads;
    }

    public void setCurrentThreads(int threads) {
        int clamped = Mth.clamp(threads, MIN_THREADS, this.maxThreads);
        if (clamped == this.currentThreads) return;
        this.currentThreads = clamped;
        // 让所有连接的多方块重新匹配配方
        for (IMultiController controller : this.getControllers()) {
            if (controller instanceof IRecipeLogicMachine rlm) {
                RecipeLogic logic = rlm.getRecipeLogic();
                logic.markLastRecipeDirty();
                // 关键：GTM 的 RecipeLogic 在「空闲且没有任何匹配配方」时会【自行退订】tick
                // （RecipeLogic.serverTick 末尾的 unsubscribe，因为 WorkableMultiblockMachine
                // .keepSubscribing() 返回 false）。只置 recipeDirty 唤不醒它 —— 必须显式恢复订阅，
                // 否则调整线程数后机器要等到下一次物品变动才有反应（表现为「调线程数半天没反应」）。
                logic.updateTickSubscription();
            }
        }
    }

    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(0, 0, 180, 40);
        group.addWidget(new LabelWidget(5, 2, () -> Component.translatable(
                "packcompanion.machine.thread_hatch.threads",
                this.currentThreads, this.maxThreads).getString()));
        group.addWidget(new IntInputWidget(5, 16, 170, 16, this::getCurrentThreads, this::setCurrentThreads)
                .setMin(MIN_THREADS)
                .setMax(maxThreads));
        return group;
    }

    @Override
    @NotNull
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public boolean canShared() {
        return false;
    }
}
