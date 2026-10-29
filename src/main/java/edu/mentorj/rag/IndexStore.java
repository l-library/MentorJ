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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 索引文件的读写。
 *
 * <p>只存知识块，不存倒排表：全课程材料也只有两百多个块，启动时重建倒排是毫秒级，省掉一份会和
 * 知识块不一致的冗余结构。{@code version} 不匹配时丢弃旧索引，不做迁移。
 */
final class IndexStore {

    /** 2 起带向量；读取仍接受 1（只有 BM25 的旧索引），写入一律写当前版本。 */
    static final int VERSION = 2;
    private static final int OLDEST_SUPPORTED_VERSION = 1;
    /** 向量保留 4 位小数：再精细对余弦排序没有影响，文件却能小一截。 */
    private static final float VECTOR_PRECISION = 10_000f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private IndexStore() {
    }

    /**
     * @param vectors 与 {@code chunks} 等长的向量；空表表示这次导入没有可用的向量
     */
    static void write(Path file, List<KnowledgeChunk> chunks, List<float[]> vectors, Instant generatedAt)
            throws IOException {
        IndexFile index = new IndexFile();
        index.version = VERSION;
        index.generatedAt = generatedAt.toString();
        index.chunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            ChunkEntry entry = new ChunkEntry();
            entry.document = chunk.document();
            entry.pageOrScope = chunk.pageOrScope();
            entry.text = chunk.text();
            entry.vector = i < vectors.size() ? rounded(vectors.get(i)) : null;
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
     * @return 索引里的知识块（{@code score} 为 0，检索时再算）与生成时间；文件不存在或版本不匹配时
     *         返回空结果
     */
    static Loaded read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Loaded.EMPTY;
        }
        IndexFile index;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            index = GSON.fromJson(reader, IndexFile.class);
        } catch (JsonSyntaxException e) {
            throw new IOException(file.getFileName() + " 不是合法的索引文件", e);
        }
        if (index == null || index.version < OLDEST_SUPPORTED_VERSION || index.version > VERSION
                || index.chunks == null) {
            return Loaded.EMPTY;
        }
        List<KnowledgeChunk> chunks = new ArrayList<>(index.chunks.size());
        List<float[]> vectors = new ArrayList<>(index.chunks.size());
        boolean complete = true;
        for (ChunkEntry entry : index.chunks) {
            chunks.add(new KnowledgeChunk(entry.text, entry.document, entry.pageOrScope, 0));
            if (entry.vector == null) {
                complete = false;
            } else {
                vectors.add(entry.vector);
            }
        }
        // 向量必须是“全有”才能建语义排名：少一条就没法按块号对齐，宁可整体当作没有向量。
        return new Loaded(chunks, complete ? vectors : List.of(), parseInstant(index.generatedAt));
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static float[] rounded(float[] vector) {
        float[] copy = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            copy[i] = Math.round(vector[i] * VECTOR_PRECISION) / VECTOR_PRECISION;
        }
        return copy;
    }

    /** 一次读取的结果；{@code vectors} 为空表示索引里没有向量（只读索引或旧索引）。 */
    record Loaded(List<KnowledgeChunk> chunks, List<float[]> vectors, Instant generatedAt) {

        static final Loaded EMPTY = new Loaded(List.of(), List.of(), null);
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
        private float[] vector;
    }
}
