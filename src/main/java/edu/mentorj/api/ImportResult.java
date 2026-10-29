package edu.mentorj.api;

import java.util.List;

/**
 * 一次导入的结果。
 *
 * @param documents 已索引的文档名
 * @param chunks    已索引的知识块总数
 * @param failures  失败原因，每条形如 {@code 文件名: 原因}；成功时为空表
 */
public record ImportResult(List<String> documents, int chunks, List<String> failures) {
}
