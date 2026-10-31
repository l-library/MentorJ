package edu.mentorj.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.mentorj.api.Citation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort transcript persistence under the project's ignored {@code .idea} directory.
 *
 * <p>Only questions and answers are kept; notices and errors are session-local. A corrupt file or a read-only
 * project must never stop the chat, so both directions fail quietly.
 */
final class ChatHistoryStore {

    private static final int MAX_MESSAGES = 50;
    private static final int MAX_CHARS = 100_000;

    private final Path file;

    ChatHistoryStore(@Nullable Path file) {
        this.file = file;
    }

    List<ChatMessage> load() {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray array = root.getAsJsonArray("messages");
            if (array == null) {
                return List.of();
            }
            List<ChatMessage> messages = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                ChatMessage.Role role = role(string(item, "role"));
                String text = string(item, "text");
                if (role == null || text == null || text.isBlank()) {
                    continue;
                }
                long time = item.has("time") ? item.get("time").getAsLong() : System.currentTimeMillis();
                ChatMessage message = new ChatMessage(role, text, Instant.ofEpochMilli(time));
                if (role == ChatMessage.Role.ASSISTANT) {
                    boolean generalOnly = item.has("generalKnowledgeOnly")
                            && item.get("generalKnowledgeOnly").getAsBoolean();
                    message.done(text, citations(item), generalOnly);
                }
                messages.add(message);
            }
            return trimmed(messages);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    void save(List<ChatMessage> messages) {
        if (file == null) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            JsonArray array = new JsonArray();
            for (ChatMessage message : trimmed(messages)) {
                if (message.text().isBlank()) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("role", message.role().name());
                item.addProperty("text", message.text());
                item.addProperty("time", message.time().toEpochMilli());
                if (message.role() == ChatMessage.Role.ASSISTANT) {
                    item.addProperty("generalKnowledgeOnly", message.generalKnowledgeOnly());
                    JsonArray citations = new JsonArray();
                    for (Citation citation : message.citations()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("document", citation.document());
                        entry.addProperty("pageOrScope", citation.pageOrScope());
                        citations.add(entry);
                    }
                    item.add("citations", citations);
                }
                array.add(item);
            }
            root.add("messages", array);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // Chat history is a convenience; the in-memory transcript stays available.
        }
    }

    private static ChatMessage.Role role(String name) {
        if (name == null) {
            return null;
        }
        try {
            ChatMessage.Role role = ChatMessage.Role.valueOf(name.toUpperCase(Locale.ROOT));
            return role == ChatMessage.Role.USER || role == ChatMessage.Role.ASSISTANT ? role : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Citation> citations(JsonObject item) {
        JsonArray array = item.getAsJsonArray("citations");
        if (array == null) {
            return List.of();
        }
        List<Citation> citations = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String document = string(entry, "document");
            String page = string(entry, "pageOrScope");
            if (document != null && !document.isBlank() && page != null && !page.isBlank()) {
                citations.add(new Citation(document, page));
            }
        }
        return citations;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** Keeps the newest messages within both caps; one oversized message is still kept. */
    private static List<ChatMessage> trimmed(List<ChatMessage> messages) {
        List<ChatMessage> kept = new ArrayList<>();
        int chars = 0;
        for (int i = messages.size() - 1; i >= 0 && kept.size() < MAX_MESSAGES; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || (message.role() != ChatMessage.Role.USER
                    && message.role() != ChatMessage.Role.ASSISTANT)) {
                continue;
            }
            int length = message.text().length();
            if (!kept.isEmpty() && chars + length > MAX_CHARS) {
                break;
            }
            chars += length;
            kept.add(message);
        }
        Collections.reverse(kept);
        return kept;
    }
}
