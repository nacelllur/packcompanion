package com.packcompanion.jade;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade (WAILA) 插件入口：
 * - 服务端：向客户端下发线程仓的同步数据（线程数/占用关系/进度串）；
 * - 客户端：在准心指向多方块控制器时的 HUD 里追加每线程运行状态。
 *
 * Jade 通过 @WailaPlugin 注解自动发现本类，无需在 mods.toml 声明依赖（Jade 缺失时不加载）。
 */
@WailaPlugin
public class PackCompanionJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        // 对所有方块实体注册（Jade 会按准心目标逐个回调，内部有轻量 instanceof 过滤）
        registration.registerBlockDataProvider(ThreadDataProvider.INSTANCE, BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ThreadHatchComponentProvider.INSTANCE, Block.class);
    }
}
