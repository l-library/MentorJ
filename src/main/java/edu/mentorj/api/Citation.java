package edu.mentorj.api;

/**
 * 一条来源引用：回答引用的课程材料文档与页码 / 范围。
 */
public record Citation(String document, String pageOrScope) {
}
