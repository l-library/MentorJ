package edu.mentorj.rag;

import edu.mentorj.api.DocumentLibrary;
import edu.mentorj.api.ImportResult;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.ProgressListener;
import edu.mentorj.api.Statistics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link DocumentLibrary} 的实现：解析 → 切块 → 交给知识库建索引。
 *
 * <p>导入是**全量重建**，不做增量：课程材料只有两百多页，重建比维护增量状态便宜，也不会出现
 * “索引里有旧版本页面”这种查起来很痛苦的状态。
 *
 * <p>单个文件的失败只记进 {@link ImportResult#failures()}，不中断整批：十个课件里有一个加密的
 * PDF，不该让另外九个也导不进来。
 */
public final class RagDocumentLibrary implements DocumentLibrary {

    private final RagKnowledgeBase knowledgeBase;
    private final DocumentProcessor processor;

    public RagDocumentLibrary(RagKnowledgeBase knowledgeBase, DocumentProcessor processor) {
        this.knowledgeBase = knowledgeBase;
        this.processor = processor;
    }

    @Override
    public ImportResult importDocuments(List<Path> documents, ProgressListener progress) {
        List<Path> files = documents == null ? List.of() : List.copyOf(documents);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        List<String> indexed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int done = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (progress != null) {
                progress.onProgress(done, files.size(), name);
            }
            try {
                chunks.addAll(processor.process(file));
                indexed.add(name);
            } catch (DocumentProcessingException e) {
                failures.add(e.getMessage());
            }
            done++;
        }
        if (progress != null) {
            progress.onProgress(done, files.size(), "");
        }
        knowledgeBase.index(chunks);
        return new ImportResult(List.copyOf(indexed), chunks.size(), List.copyOf(failures));
    }

    @Override
    public void clear() {
        knowledgeBase.clear();
    }

    @Override
    public Statistics statistics() {
        return knowledgeBase.statistics();
    }
}
