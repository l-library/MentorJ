package edu.mentorj.api;

/**
 * 课程材料知识块及其来源。
 *
 * @param text        知识块内容
 * @param document    来源文档
 * @param pageOrScope 页码或范围
 * @param score       相关性分数，越大越相关
 */
public record KnowledgeChunk(String text, String document, String pageOrScope, double score) {
}
