package com.packcompanion.jade;

import com.packcompanion.gt.MultiThreadedRecipeLogic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 客户端 HUD 渲染：读取服务端下发的线程状态串，复用
 * {@link MultiThreadedRecipeLogic#appendThreadLines} 解析成与机器 GUI 一致的文本
 * （物品名 + 进度百分比），逐行追加到 Jade tooltip 末尾。
 */
public class ThreadHatchComponentProvider implements IBlockComponentProvider {

    public static final ThreadHatchComponentProvider INSTANCE = new ThreadHatchComponentProvider();

    private static final ResourceLocation UID = new ResourceLocation("packcompanion", "thread_status");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (!data.contains(ThreadDataProvider.TAG_HATCHES)) return;
        int hatches = data.getInt(ThreadDataProvider.TAG_HATCHES);
        if (hatches <= 0) return;
        List<Component> lines = new ArrayList<>();
        MultiThreadedRecipeLogic.appendThreadLines(lines,
                hatches,
                data.getInt(ThreadDataProvider.TAG_THREADS),
                data.getString(ThreadDataProvider.TAG_STATUS),
                MultiThreadedRecipeLogic.getMaxJadeLines());
        for (Component line : lines) {
            tooltip.add(line);
        }
    }
}
