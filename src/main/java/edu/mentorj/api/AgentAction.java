package edu.mentorj.api;

/** The kind of intelligent action a request performs. */
public enum AgentAction {
    /** Course Q&A: a sidebar question or a question about selected code. */
    ASK,
    /** Generate a JUnit test for the selected class or method from a natural-language requirement. */
    GENERATE_UNIT_TEST,
    /** Analyze staged changes and generate a Git commit message. */
    GENERATE_COMMIT_MESSAGE,
    /** A team-chosen creative intelligent action; the design is still open. */
    CREATIVE
}
