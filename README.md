# packcompanion — GregTech: Orbital Era 整合包适配层

**GregTech: Orbital Era** 整合包的跨 mod 兼容 / mixin 修补 Mod。Forge 1.20.1。

- Mod ID：`packcompanion`
- Java 包：`com.packcompanion`
- 版本：`1.20.1-2.0.0`
- 构建产物：`packcompanion-1.20.1-2.0.0.jar`

## 职责

packcompanion 是整合包的「适配层」——所有跨 mod 兼容、mixin 修补、可选 mod 软桥接都放这里，与「内容注册」的 [gtoecore](https://github.com/nacelllur/gtoecore) 分工明确：

| 层 | 工程 | 内容 |
|---|---|---|
| 内容注册 | gtoecore | GT 机器 / 多方块 / 配方 / 深空枢纽 |
| 适配层 | packcompanion | mixin 修补 / 跨 mod 兼容 / 杂项 |

## 功能清单

### GTM 修补
- `TerminalBehaviorMixin` / `NexusTerminalBehaviorMixin` — 终端 autoBuild 摆出的装饰方块**朝向修复**（配合 `MultiblockFacingFixer`）
- `PatternPreviewWidgetMixin` — JEI 多方块预览空安全兜底（GTM 自身 bug）
- `MultiblockInfoCategoryMixin` — JEI 悬停越界修复
- `FactoryBlockPatternMixin` / `PredicatesMixin` / `MultiblockMachineBuilderMixin` / `Workable*MachineMixin` — 结构 / 预览相关修补

### 女仆（车万女仆 TLM）深度定制
- `EntityMaidMixin` — 双模式移动：空闲 / 跟随 = 失重漂浮（`ZeroGMaidMoveControl`），工作 / 宅家 = 原版落地
- `MaidPoseSync` + `EntityMaidRendererMixin` — 失重态身体翻滚姿态延迟同步（150ms 跟影）
- `TaskManagerMixin` — 工作选单只留「空闲 + 游戏」
- `MaidFollowOwnerTaskMixin` — 禁用 TLM 自带传送
- `MaidAe2EventHandler` — 女仆 GUI 的 AE2 无线终端按钮（切换式打开）

### 杂项
- 进服欢迎消息 / 新手指南书 / `/packguide` 指令（`config/packcompanion-common.toml` 可配置）
- Jade 线程仓信息插件
- 线程仓 / 劳保仓（历史遗留的 GT 机器部件，编译期依赖 gtceu）

## 构建

```bash
export JAVA_HOME=/path/to/jdk-17
gradle build
```

> ⚠️ `libs/` 编译依赖（GTCEu / LDLib / Registrate / TLM / AE2 / ae2wtlib / gtna / JEI / Jade）需从整合包实例复制，**不入库**；
> `repo/` 本地 Maven（mixin 0.8.5 注解处理器）同样不入库，首次构建会从 Sponge 仓库在线拉取。

产物 `build/libs/packcompanion-1.20.1-2.0.0.jar` 复制到整合包 `mods/` 即可（生产环境为 SRG 命名，`reobfJar` 自动处理）。

## 配置示例（`config/packcompanion-common.toml`）

```toml
[welcome]
    welcomeMessage = "§e欢迎来到本整合包！输入 §6/packguide §e可随时获取指南书。"
    welcomeEveryJoin = false

[starterBook]
    giveStarterBook = true
    bookTitle = "整合包指南"
    bookAuthor = "整合包作者"
    bookPages = ["第一页内容...", "第二页内容..."]
```

## 可选 mod 约定

本工程的 mixin 大量针对「整合包有、桌面开发实例没有」的可选 mod（TLM / AE2 / space_gravity / ShoulderSurfing 等），遵循：

1. 独立 mixin 配置（`required=false`），Manifest `MixinConfigs` 逗号拼接
2. 目标 jar 放 `libs/` 仅编译期引用
3. 常驻类引用可选 mod 一律**纯反射**（参考 `SpaceCamCompat`）
4. mixin 包（`com.packcompanion.mixin.*`）内严禁非 @Mixin 类——内部类会导致 `IllegalClassLoadError` 崩溃

## 相关仓库

- [gtoecore](https://github.com/nacelllur/gtoecore) — 内容注册层
- [GregTech-Orbital-Era](https://github.com/nacelllur/GregTech-Orbital-Era) — 整合包本体

## License

MIT