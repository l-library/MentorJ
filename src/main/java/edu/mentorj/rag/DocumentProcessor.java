package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;

import java.nio.file.Path;
import java.util.List;

/** 课程材料文档处理：解析（如 PPT）并切块，保留来源文档与页码 / 范围。 */
public final class DocumentProcessor {

    public List<KnowledgeChunk> process(Path document) {
        // TODO(RAG 组): 解析 PPT/PDF，按页切块并记录页码/范围
        return List.of();
    }
}
