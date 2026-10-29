package edu.mentorj.rag;

import java.io.IOException;
import java.util.List;

/**
 * 文本向量化，RAG 内部接口。
 *
 * <p>不放进 {@code edu.mentorj.api}：它不是模块之间的契约，界面组与 agent 组都不需要知道检索用了
 * 哪种向量模型，也不知道有没有用。
 */
public interface EmbeddingProvider {

    /** 向量维度，必须与索引里存的向量一致。 */
    int dimensions();

    /**
     * 批量向量化，用于导入。
     *
     * @return 与 {@code texts} 等长、顺序一致的向量
     */
    List<float[]> embed(List<String> texts) throws IOException;

    /**
     * 单条向量化，用于提问。默认实现走批量接口，实现类可以给它更短的超时：
     * 提问路径上多等一秒都是用户能感觉到的。
     */
    default float[] embedQuery(String query) throws IOException {
        List<float[]> vectors = embed(List.of(query));
        if (vectors.size() != 1) {
            throw new IOException("期望 1 条向量，实际 " + vectors.size() + " 条");
        }
        return vectors.get(0);
    }
}
