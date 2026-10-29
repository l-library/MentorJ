package edu.mentorj.api;

import java.time.Instant;
import java.util.List;

/**
 * 索引现状。
 *
 * @param documents 已索引的文档名，按名称排序
 * @param chunks    知识块总数
 * @param indexedAt 最近一次导入的时间；尚未导入时为 {@code null}
 */
public record Statistics(List<String> documents, int chunks, Instant indexedAt) {
}
