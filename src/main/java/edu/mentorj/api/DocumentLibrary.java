package edu.mentorj.api;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档导入、索引清空与统计，RAG 组实现，界面组调用。
 *
 * <p>阻塞方法，界面组必须在后台线程调用；与 {@link KnowledgeBase#retrieve} 可并发。
 */
public interface DocumentLibrary {

    /**
     * 全量重建索引：清空现有索引 → 逐个解析传入的文档 → 切块 → 建索引 → 落盘。
     *
     * <p>不支持的格式与解析失败都记进 {@link ImportResult#failures()}，不抛异常，也不影响其余文档。
     *
     * @param documents 待索引的文档；传空表等于清空索引
     * @param progress  进度回调，可为 {@code null}
     * @return 本次导入的结果，不返回 {@code null}
     */
    ImportResult importDocuments(List<Path> documents, ProgressListener progress);

    /** 清空索引，下次检索返回空表。 */
    void clear();

    /** 索引现状；尚未导入时 {@link Statistics#indexedAt()} 为 {@code null}。 */
    Statistics statistics();
}
