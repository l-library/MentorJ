package edu.mentorj.api;

/**
 * 导入进度回调。纯 Java 类型，避免 {@code api} 包依赖平台 API。
 *
 * <p>在导入线程上被调用，需要更新界面时由界面组自行切回 EDT。
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * @param done    已处理的文档数
     * @param total   本次导入的文档总数
     * @param message 当前正在处理的文档名
     */
    void onProgress(int done, int total, String message);
}
