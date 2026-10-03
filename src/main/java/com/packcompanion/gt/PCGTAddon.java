package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.addon.GTAddon;
import com.gregtechceu.gtceu.api.addon.IGTAddon;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;

/**
 * GTM 官方 addon 入口：由 GTM 在自身内容（材质/机器/模型）注册完成后回调 initializeAddon，
 * 此时再注册我们的机器才是安全时机（mod 构造期注册会触发 GTMachineModels 提前类初始化 → NPE）。
 */
@GTAddon
public class PCGTAddon implements IGTAddon {

    @Override
    public GTRegistrate getRegistrate() {
        return PCMachines.REGISTRATE;
    }

    @Override
    public void initializeAddon() {
        // 机器注册不在这里做：调用此回调时 gtceu:machine 注册表已冻结。
        // 机器注册在 GTCEuAPI.RegisterEvent（freeze 前）执行，见 PackCompanionGT。
    }

    @Override
    public String addonModId() {
        return PackCompanionGT.MODID;
    }
}
