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
 * {@link DocumentLibrary} implementation: parse -> chunk -> hand the chunks to the knowledge base to index.
 *
 * <p>Import is a **full rebuild**, never incremental: the course material is only a couple of hundred
 * pages, so rebuilding costs less than maintaining incremental state and never leaves the index holding
 * stale pages, a state that is painful to debug.
 *
 * <p>A single file's failure is only recorded in {@link ImportResult#failures()} and does not abort the
 * batch: one encrypted PDF among ten decks must not keep the other nine from importing.
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
