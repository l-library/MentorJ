package edu.mentorj.rag;

/**
 * A document cannot be turned into chunks. The message is shown to the user directly, so it names the file
 * and says why it failed.
 */
public class DocumentProcessingException extends Exception {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
