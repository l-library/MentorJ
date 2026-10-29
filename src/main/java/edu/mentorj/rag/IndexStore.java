package edu.mentorj.rag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import edu.mentorj.api.KnowledgeChunk;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 索引文件的读写。
 *
 * <p>只存知识块，不存倒排表：全课程材料也只有两百多个块，启动时重建倒排是毫秒级，省掉一份会和
 * 知识块不一致的冗余结构。{@code version} 不匹配时丢弃旧索引，不做迁移。
 */
final class IndexStore {

    static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private IndexStore() {
    }

    static void write(Path file, List<KnowledgeChunk> chunks, Instant generatedAt) throws IOException {
        IndexFile index = new IndexFile();
        index.version = VERSION;
        index.generatedAt = generatedAt.toString();
        index.chunks = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            ChunkEntry entry = new ChunkEntry();
            entry.document = chunk.document();
            entry.pageOrScope = chunk.pageOrScope();
            entry.text = chunk.text();
            index.chunks.add(entry);
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(index, writer);
        }
    }

    /**
     * @return 索引里的知识块（{@code score} 为 0，检索时再算）；文件不存在或版本不匹配时返回空表
     */
    static List<KnowledgeChunk> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        IndexFile index;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            index = GSON.fromJson(reader, IndexFile.class);
        } catch (JsonSyntaxException e) {
            throw new IOException(file.getFileName() + " 不是合法的索引文件", e);
        }
        if (index == null || index.version != VERSION || index.chunks == null) {
            return List.of();
        }
        List<KnowledgeChunk> chunks = new ArrayList<>(index.chunks.size());
        for (ChunkEntry entry : index.chunks) {
            chunks.add(new KnowledgeChunk(entry.text, entry.document, entry.pageOrScope, 0));
        }
        return chunks;
    }

    private static final class IndexFile {
        private int version;
        private String generatedAt;
        private List<ChunkEntry> chunks;
    }

    private static final class ChunkEntry {
        private String document;
        private String pageOrScope;
        private String text;
    }
}
