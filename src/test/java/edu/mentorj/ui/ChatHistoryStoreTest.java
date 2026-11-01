package edu.mentorj.ui;

import edu.mentorj.api.Citation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatHistoryStoreTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private Path historyFile() throws IOException {
        return temporary.newFolder(".idea").toPath().resolve("mentorj-chat.json");
    }

    @Test
    public void roundTripsQuestionsAnswersAndCitations() throws IOException {
        Path file = historyFile();
        ChatHistoryStore store = new ChatHistoryStore(file);
        ChatMessage question = new ChatMessage(ChatMessage.Role.USER, "What is encapsulation?",
                Instant.ofEpochMilli(1000));
        ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT,
                "Encapsulation is **hiding the implementation**.",
                Instant.ofEpochMilli(2000));
        answer.done(answer.text(), List.of(new Citation("Lec-3.pdf", "P12-P14")), false);
        store.save(List.of(question, answer));

        List<ChatMessage> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals(ChatMessage.Role.USER, loaded.get(0).role());
        assertEquals("What is encapsulation?", loaded.get(0).text());
        assertEquals(Instant.ofEpochMilli(1000), loaded.get(0).time());
        assertEquals(ChatMessage.State.DONE, loaded.get(1).state());
        assertEquals(List.of(new Citation("Lec-3.pdf", "P12-P14")), loaded.get(1).citations());
        assertFalse(loaded.get(1).generalKnowledgeOnly());
    }

    @Test
    public void keepsOnlyTheNewestFiftyMessages() throws IOException {
        ChatHistoryStore store = new ChatHistoryStore(historyFile());
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            messages.add(new ChatMessage(ChatMessage.Role.USER, "q" + i));
            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "a" + i);
            answer.done("a" + i, List.of(), false);
            messages.add(answer);
        }
        store.save(messages);

        List<ChatMessage> loaded = store.load();
        assertEquals(50, loaded.size());
        assertEquals("q35", loaded.get(0).text());
        assertEquals("a59", loaded.get(49).text());
    }

    @Test
    public void skipsNoticesAndCorruptFiles() throws IOException {
        Path file = historyFile();
        ChatHistoryStore store = new ChatHistoryStore(file);
        store.save(List.of(new ChatMessage(ChatMessage.Role.NOTICE, "system notice")));
        assertTrue(store.load().isEmpty());

        Files.writeString(file, "not json", StandardCharsets.UTF_8);
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void missingFileAndNullPathLoadAsEmpty() {
        assertTrue(new ChatHistoryStore(null).load().isEmpty());
        assertTrue(new ChatHistoryStore(Path.of("does", "not", "exist.json")).load().isEmpty());
    }
}
