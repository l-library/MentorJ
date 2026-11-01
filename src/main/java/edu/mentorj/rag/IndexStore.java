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
 * Reading and writing the index file.
 *
 * <p>Only chunks are stored, not the inverted index: the whole course material is a bit over two hundred
 * chunks, so rebuilding the inverted index at startup takes milliseconds, which avoids a second structure
 * that could drift out of step with the chunks. A mismatched {@code version} discards the old index instead
 * of migrating it.
 */
final class IndexStore {

    /** Version 2 onward carries vectors; reading still accepts 1 (an old BM25-only index), and writes always use
     * the current version. */
    static final int VERSION = 2;
    private static final int OLDEST_SUPPORTED_VERSION = 1;
    /** Vectors keep 4 decimal places: more precision does not change cosine ordering, and the file gets much
     * smaller. */
    private static final float VECTOR_PRECISION = 10_000f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private IndexStore() {
    }

    /**
     * @param vectors vectors with the same length as {@code chunks}; an empty list means this import produced
     *                no usable vectors
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
     * @return the chunks in the index ({@code score} is 0 and computed during retrieval) plus the generation time;
     *         an empty result when the file is absent or the version does not match
     */
    static Loaded read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Loaded.EMPTY;
        }
        IndexFile index;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            index = GSON.fromJson(reader, IndexFile.class);
        } catch (JsonSyntaxException e) {
            throw new IOException(file.getFileName() + " is not a valid index file", e);
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
        // Vectors must be all-or-nothing to build a semantic ranking: one missing vector breaks alignment by chunk
        // index, so the whole index is better treated as having no vectors at all.
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

    /** Result of one read; an empty {@code vectors} list means the index has no vectors (read-only or old index). */
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
