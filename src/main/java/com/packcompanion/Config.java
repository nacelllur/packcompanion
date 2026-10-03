package com.packcompanion;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class Config {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> welcomeMessage;
    public static final ForgeConfigSpec.BooleanValue welcomeEveryJoin;
    public static final ForgeConfigSpec.BooleanValue giveStarterBook;
    public static final ForgeConfigSpec.ConfigValue<String> bookTitle;
    public static final ForgeConfigSpec.ConfigValue<String> bookAuthor;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> bookPages;
    public static final ForgeConfigSpec.BooleanValue allowDuplicateRecipes;
    public static final ForgeConfigSpec.BooleanValue installInAnyIOPort;
    public static final ForgeConfigSpec.BooleanValue logThreadSearch;
    public static final ForgeConfigSpec.BooleanValue idleSearchBackoff;
    public static final ForgeConfigSpec.IntValue idleSearchBackoffMax;
    public static final ForgeConfigSpec.BooleanValue laborHatchInGreenhouse;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> greenhouseMachines;
    public static final ForgeConfigSpec.BooleanValue zeroGCameraLockEnabled;
    public static final ForgeConfigSpec.DoubleValue zeroGCameraOffsetX;
    public static final ForgeConfigSpec.DoubleValue zeroGCameraOffsetY;
    public static final ForgeConfigSpec.DoubleValue zeroGCameraOffsetZ;
    public static final ForgeConfigSpec.BooleanValue maidZeroGFollowEnabled;
    public static final ForgeConfigSpec.DoubleValue maidZeroGSpeed;
    public static final ForgeConfigSpec.DoubleValue maidZeroGFollowDistance;
    public static final ForgeConfigSpec.IntValue maidZeroGPoseDelayMs;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("welcome");
        welcomeMessage = b.comment("玩家进服时显示的欢迎消息（支持 § 颜色代码）")
                .define("welcomeMessage", "§e欢迎来到本整合包！输入 §6/packguide §e可随时获取指南书。");
        welcomeEveryJoin = b.comment("true = 每次进服都显示；false = 仅首次进服显示")
                .define("welcomeEveryJoin", false);
        b.pop();

        b.push("starterBook");
        giveStarterBook = b.comment("首次进服时发放一本成书的指南书")
                .define("giveStarterBook", true);
        bookTitle = b.define("bookTitle", "整合包指南");
        bookAuthor = b.define("bookAuthor", "整合包作者");
        bookPages = b.comment("指南书内容，每一项为一页（支持 § 颜色代码与 \\n 换行）")
                .defineListAllowEmpty("bookPages", List.of(
                        "§6§l欢迎！§r\n\n这是一本整合包指南。\n\n你可以在 config/packcompanion-common.toml 中修改本书的全部内容。",
                        "§e§l新手必读§r\n\n1. 按照任务书逐步推进\n2. 注意整合包自带的平衡机制\n3. 遇到问题可查阅模组说明",
                        "§a祝你游玩愉快！"
                ), o -> o instanceof String);
        b.pop();

        b.push("gtThreadHatch");
        allowDuplicateRecipes = b.comment(
                        "线程仓是否允许同一条配方同时占用多个线程。\n",
                        "false（默认）= 线程仓专注「同时处理不同配方」：同一条配方只由一个线程处理，\n",
                        "  若所有可匹配配方都已被占用，多余线程保持空闲（GUI 会提示原因）；\n",
                        "  同一配方重复并行请使用「并行仓」。\n",
                        "true = 允许多个线程同时跑同一条配方（相当于额外并行，能耗也成倍增加）。")
                .define("allowDuplicateRecipes", false);
        installInAnyIOPort = b.comment(
                        "线程仓是否允许装入「任意多方块」的能量/物品/流体仓槽位（默认 true）。\n",
                        "开启后，即使目标多方块没有专用并行槽——例如第三方 mod 的机器（GTCA 的温室等）——\n",
                        "也能把线程仓放进去，只要该结构原本能放对应的仓室；JEI 结构预览里也会显示线程仓。\n",
                        "关闭后，线程仓只能放进带并行槽（PARALLEL_HATCH）的多方块。")
                .define("installInAnyIOPort", true);
        logThreadSearch = b.comment(
                        "排查用：把「线程找不到配方可跑」的原因按 5 秒一次输出到 logs/latest.log（前缀 [packcompanion/thread]）。\n",
                        "输出内容：每个空闲线程的状态（no-match = 无配方匹配 / occupied = 配方被其他线程占用 /\n",
                        "blocked:<阶段> = 找到了配方但启动失败）以及被拒绝的候选配方 id。\n",
                        "排查完成后可设为 false 关闭。")
                .define("logThreadSearch", true);
        idleSearchBackoff = b.comment(
                        "空闲时降低配方查找频率（默认 true）。\n",
                        "多方块里装了线程仓、但整机「没料可跑」时（没有匹配配方，或供电/原料/输出空间不足），\n",
                        "每 tick 反复查找配方纯属空转。开启后，连续几轮查找都没能让任何线程跑起来，\n",
                        "查找间隔会按 1→1→2→4→8→16→32→60 tick 逐步拉长（上限见 idleSearchBackoffMax）。\n",
                        "**不影响响应速度**：任何物品/流体/能量容器内容变化（放材料、抽走产物、来电）都会\n",
                        "立即解除退避并恢复全速；有线程正在「等别人腾出配方」时也不会退避。\n",
                        "关闭 = 恢复每 tick 全速查找（与旧版行为一致）。")
                .define("idleSearchBackoff", true);
        idleSearchBackoffMax = b.comment(
                        "空闲退避的最长查找间隔（tick，默认 60 = 3 秒，范围 1~1200）。\n",
                        "这是「空转开销 ↔ 发现新配方延迟」的取舍点；设为 1 相当于只降频不降广（接近旧版行为）。")
                .defineInRange("idleSearchBackoffMax", 60, 1, 1200);
        laborHatchInGreenhouse = b.comment(
                        "「劳保线程仓」是否允许装入温室（默认 true）。\n",
                        "劳保线程仓固定 256 线程，且**只**能装进温室——它的方块不会被任何通用\n",
                        "仓室槽位接受，只由 mod 在温室 pattern 构建时注入，因此放进非温室结构不会成形。\n",
                        "关闭后劳保线程仓变成完全无法安装（给不想要这台机器的整合包用）。")
                .define("laborHatchInGreenhouse", true);
        greenhouseMachines = b.comment(
                        "哪些机器算「温室」（用于劳保线程仓的安装许可）。\n",
                        "除此之外，配方类型 id 里含 greenhouse / green_house 的机器也会被自动认作温室，\n",
                        "所以通常不需要在这里手动登记。")
                .defineListAllowEmpty("greenhouseMachines",
                        List.of("gtceu:greenhouse", "gtca:green_house"),
                        o -> o instanceof String);
        b.pop();

        b.push("zeroGCamera");
        zeroGCameraLockEnabled = b.comment(
                        "失重（space_gravity 零重力/自由视角）+ 越肩视角（ShoulderSurfing）同时开启时，",
                        "把镜头锁定为固定偏移，让玩家身体恒定停在画面左下角。",
                        "需要 space_gravity 与 ShoulderSurfing 任一缺失则自动不生效。")
                .define("lockEnabled", true);
        zeroGCameraOffsetX = b.comment(
                        "锁定时相机偏移 X（沿视线方向的分量， ShoulderSurfing 的 move(-x,y,z) 语义）。",
                        "负值 = 相机后移；与 SS 的 offset_x 同义。")
                .defineInRange("offsetX", -0.75D, -10.0D, 10.0D);
        zeroGCameraOffsetY = b.comment(
                        "锁定时相机偏移 Y（正值 = 相机升高 → 玩家在画面更靠下）。")
                .defineInRange("offsetY", 1.0D, -5.0D, 5.0D);
        zeroGCameraOffsetZ = b.comment(
                        "锁定时相机偏移 Z（正值 = 相机在玩家左侧 → 玩家在画面右侧；",
                        "负值 = 相机在玩家右侧 → 玩家在画面左侧，即「左下角」）。")
                .defineInRange("offsetZ", -4.0D, -10.0D, 10.0D);
        b.pop();

        b.push("maidZeroG");
        maidZeroGFollowEnabled = b.comment(
                        "车万女仆永久失重漂浮跟随：女仆替换为三维飞行移动控制（无重力），",
                        "朝主人视线反方向 followDistance 格漂浮跟随；不做任何失重状态判定。",
                        "需要 touhoulittlemaid，缺失时自动不生效。")
                .define("followEnabled", true);
        maidZeroGSpeed = b.comment("女仆漂浮移动速度（格 / tick，自定义 MoveControl 的目标速度）。")
                .defineInRange("speed", 0.30D, 0.05D, 2.0D);
        maidZeroGFollowDistance = b.comment("女仆跟随点与主人的距离（格）：停在主人视线反方向这么远。")
                .defineInRange("followDistance", 2.5D, 0.5D, 10.0D);
        maidZeroGPoseDelayMs = b.comment(
                        "女仆自由视角姿态同步延迟：身体翻滚滞后玩家自由视角的毫秒数（慢一拍）。",
                        "0 = 硬同步；建议 100~300。仅空闲/跟随（失重漂浮）时生效。")
                .defineInRange("poseDelayMs", 150, 0, 2000);
        b.pop();

        SPEC = b.build();
    }

    /**
     * 安全读取「允许线程仓重复占用同一配方」开关。
     * 配置读取发生在运行时，但为避免配置未加载（极端时序）导致崩溃，这里做了兜底。
     */
    public static boolean isDuplicateRecipesAllowed() {
        try {
            return allowDuplicateRecipes.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * 安全读取「线程仓可装入任意多方块的仓室槽位」开关。
     * 谓词注入发生在结构构建期，仍可能早于配置就绪，这里做兜底（默认开启）。
     */
    public static boolean isInstallInAnyIOPort() {
        try {
            return installInAnyIOPort.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** 安全读取「输出线程排查日志」开关（配置未就绪时默认开启，便于首次排查）。 */
    public static boolean isLogThreadSearch() {
        try {
            return logThreadSearch.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** 安全读取「空闲时降低配方查找频率」开关（配置未就绪时默认开启）。 */
    public static boolean isIdleSearchBackoff() {
        try {
            return idleSearchBackoff.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** 安全读取「空闲退避的最长查找间隔（tick）」（配置未就绪时返回默认 60）。 */
    public static int getIdleSearchBackoffMax() {
        try {
            return idleSearchBackoffMax.get();
        } catch (IllegalStateException e) {
            return 60;
        }
    }

    /** 安全读取「劳保线程仓可装入温室」开关（配置未就绪时默认开启）。 */
    public static boolean isLaborHatchInGreenhouse() {
        try {
            return laborHatchInGreenhouse.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** @return 该机器 id 是否被配置显式登记为温室（配置未就绪时按内置默认值判断） */
    public static boolean isGreenhouseMachine(String machineId) {
        if (machineId == null) return false;
        List<? extends String> ids;
        try {
            ids = greenhouseMachines.get();
        } catch (IllegalStateException e) {
            ids = List.of("gtceu:greenhouse", "gtca:green_house");
        }
        return ids != null && ids.contains(machineId);
    }

    /** 安全读取「失重越肩镜头锁定」开关（配置未就绪时默认开启）。 */
    public static boolean isZeroGCameraLockEnabled() {
        try {
            return zeroGCameraLockEnabled.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** 安全读取「失重越肩锁定偏移 X」（配置未就绪时默认 -0.75）。 */
    public static double getZeroGCameraOffsetX() {
        try {
            return zeroGCameraOffsetX.get();
        } catch (IllegalStateException e) {
            return -0.75D;
        }
    }

    /** 安全读取「失重越肩锁定偏移 Y」（配置未就绪时默认 1.0）。 */
    public static double getZeroGCameraOffsetY() {
        try {
            return zeroGCameraOffsetY.get();
        } catch (IllegalStateException e) {
            return 1.0D;
        }
    }

    /** 安全读取「失重越肩锁定偏移 Z」（配置未就绪时默认 -4.0）。 */
    public static double getZeroGCameraOffsetZ() {
        try {
            return zeroGCameraOffsetZ.get();
        } catch (IllegalStateException e) {
            return -4.0D;
        }
    }

    /** 安全读取「女仆失重漂浮跟随」开关（配置未就绪时默认开启）。 */
    public static boolean isMaidZeroGFollowEnabled() {
        try {
            return maidZeroGFollowEnabled.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** 安全读取「女仆漂浮移动速度」（配置未就绪时默认 0.30）。 */
    public static double getMaidZeroGSpeed() {
        try {
            return maidZeroGSpeed.get();
        } catch (IllegalStateException e) {
            return 0.30D;
        }
    }

    /** 安全读取「女仆跟随距离」（配置未就绪时默认 2.5）。 */
    public static double getMaidZeroGFollowDistance() {
        try {
            return maidZeroGFollowDistance.get();
        } catch (IllegalStateException e) {
            return 2.5D;
        }
    }

    /** 安全读取「女仆自由视角姿态同步延迟拍长」（配置未就绪时默认 150ms）。 */
    public static int getMaidZeroGPoseDelayMs() {
        try {
            return maidZeroGPoseDelayMs.get();
        } catch (IllegalStateException e) {
            return 150;
        }
    }
}
