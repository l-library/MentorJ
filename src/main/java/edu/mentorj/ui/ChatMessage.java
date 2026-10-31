package edu.mentorj.ui;

import edu.mentorj.api.Citation;

import java.time.Instant;
import java.util.List;

/**
 * One entry in the local transcript.
 *
 * <p>Streaming mutates {@link #text()} on the EDT only; {@link ChatTranscript} owns the cards that render it.
 */
final class ChatMessage {

    enum Role { USER, ASSISTANT, NOTICE, ERROR }

    enum State { STREAMING, DONE, CANCELLED, FAILED }

    private final Role role;
    private final Instant time;
    private String text;
    private List<Citation> citations = List.of();
    private boolean generalKnowledgeOnly;
    private State state;

    ChatMessage(Role role, String text) {
        this(role, text, Instant.now());
    }

    ChatMessage(Role role, String text, Instant time) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.time = time;
        this.state = role == Role.ASSISTANT ? State.STREAMING : State.DONE;
    }

    Role role() {
        return role;
    }

    Instant time() {
        return time;
    }

    String text() {
        return text;
    }

    List<Citation> citations() {
        return citations;
    }

    boolean generalKnowledgeOnly() {
        return generalKnowledgeOnly;
    }

    State state() {
        return state;
    }

    void text(String value) {
        this.text = value == null ? "" : value;
    }

    void streaming() {
        state = State.STREAMING;
    }

    void done(String value, List<Citation> sources, boolean generalOnly) {
        text(value);
        citations = List.copyOf(sources);
        generalKnowledgeOnly = generalOnly;
        state = State.DONE;
    }

    void cancelled() {
        state = State.CANCELLED;
    }

    void failed(String value) {
        text(value);
        state = State.FAILED;
    }
}
