package edu.mentorj.api;

import java.util.List;

/**
 * 回答增量回调。纯 Java 类型，避免 {@code api} 包依赖平台 API。
 *
 * <p>回调在调用方的后台线程上执行，需要更新界面时由界面组自行切回 EDT。
 * 增量按顺序拼接等于最终 {@code AgentResponse.text()}；实现也可以一次性回调全文。
 */
public interface StreamListener {

    /**
     * 回答正文增量。
     *
     * @param delta 新增的正文片段，非空
     */
    void onText(String delta);

    /**
     * 本轮以工具调用结束：此前收到的正文增量不是最终回答，界面应丢弃并显示工具状态。
     *
     * @param toolNames 本轮要执行的工具名，按调用顺序
     */
    default void onToolCalls(List<String> toolNames) {
    }

    /** 取消轮询；返回 {@code true} 时请求应尽快收尾。 */
    default boolean isCancelled() {
        return false;
    }
}
