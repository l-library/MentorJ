package edu.mentorj.rag;

/**
 * 一个文档无法转成知识块。消息直接展示给用户，因此写清是哪个文件、为什么失败。
 */
public class DocumentProcessingException extends Exception {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
