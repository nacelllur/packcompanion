package com.packcompanion.jade;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.packcompanion.gt.MultiThreadedRecipeLogic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * 服务端数据提供器：准心指向多方块控制器时，把 MultiThreadedRecipeLogic
 * 的同步值（线程仓数 / 线程数 / 每线程状态串）写进 Jade 的下行数据包。
 * 结构中没有线程仓时不写任何数据，HUD 保持原样。
 */
public class ThreadDataProvider implements IServerDataProvider<BlockAccessor> {

    public static final ThreadDataProvider INSTANCE = new ThreadDataProvider();

    private static final ResourceLocation UID = new ResourceLocation("packcompanion", "thread_server_data");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    public static final String TAG_HATCHES = "pcHatches";
    public static final String TAG_THREADS = "pcThreads";
    public static final String TAG_STATUS = "pcStatus";

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        BlockEntity be = accessor.getBlockEntity();
        if (!(be instanceof IMachineBlockEntity machineBe)) return;
        MetaMachine machine = machineBe.getMetaMachine();
        if (!(machine instanceof IRecipeLogicMachine logicMachine)) return;
        RecipeLogic logic = logicMachine.getRecipeLogic();
        if (!(logic instanceof MultiThreadedRecipeLogic multiThreaded)) return;
        int hatches = multiThreaded.getSyncedHatches();
        if (hatches <= 0) return; // 无线程仓：不向 HUD 注入任何内容
        data.putInt(TAG_HATCHES, hatches);
        data.putInt(TAG_THREADS, multiThreaded.getSyncedThreads());
        data.putString(TAG_STATUS, multiThreaded.getSyncedThreadStatus());
    }
}
