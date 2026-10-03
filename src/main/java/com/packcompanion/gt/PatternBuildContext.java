package com.packcompanion.gt;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;

import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 「当前正在构建 pattern 的机器定义」上下文。
 *
 * <p>用途：{@code FactoryBlockPattern.where(...)} 是所有 pattern 定义语句的收口点，
 * 但该方法本身并不知道自己属于哪个机器——而「劳保线程仓只能装进温室」这个判断必须
 * 在注入候选方块时就知道机器身份。</p>
 *
 * <p>GTM 的 {@code MultiblockMachineBuilder.register()} 只做
 * {@code definition.setPatternFactory(GTMemoizer.memoize(() -> this.pattern.apply(definition)))}，
 * 也就是说 pattern 的构建是**延迟**发生在一个 Supplier 里的。本项目用一个 Mixin
 * （{@code MultiblockMachineDefinitionMixin}）把这个 Supplier 包一层，在真正执行
 * 构建时把 definition 放进本 ThreadLocal，于是构建期间的每个 {@code where(...)}
 * 都能读到它。</p>
 *
 * <p>用栈式保存/恢复（而不是简单 set/clear）是为了兼容「构建 A 的 pattern 时顺带
 * 构建了 B 的 pattern」这种嵌套情况。pattern 构建只在服务端主线程发生，ThreadLocal
 * 足够；即使出现异常也会在 finally 里恢复。</p>
 */
public final class PatternBuildContext {

    private static final ThreadLocal<MachineDefinition> CURRENT = new ThreadLocal<>();

    private PatternBuildContext() {}

    /** 在「当前机器 = definition」的上下文中执行 action */
    public static <T> T runWith(MachineDefinition definition, Supplier<T> action) {
        MachineDefinition previous = CURRENT.get();
        CURRENT.set(definition);
        try {
            return action.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    /** @return 正在构建 pattern 的机器定义；不在构建期间（或注入点未覆盖）时为 null */
    @Nullable
    public static MachineDefinition current() {
        return CURRENT.get();
    }
}
