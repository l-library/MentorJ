package edu.mentorj.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索用的分词：拉丁词拆驼峰与下划线，CJK 用单字加二元组。
 *
 * <p>为什么不按语言做假设：导入的材料可能是任何语言的，写死“中文查询翻成英文”等于给插件绑一个
 * 错误前提。CJK 走单字 + 二元组，中文查询至少能部分命中，不会整句零命中。
 */
final class Tokenizer {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "about", "after", "also", "an", "and", "any", "are", "as", "at",
            "be", "because", "been", "before", "being", "between", "but", "by",
            "can", "do", "does", "each", "for", "from",
            "had", "has", "have", "he", "her", "here", "him", "his", "how",
            "if", "in", "into", "is", "it", "its",
            "may", "me", "more", "most", "must", "my",
            "new", "no", "not", "now",
            "of", "old", "on", "one", "only", "or", "our", "out",
            "see", "she", "should", "so", "some", "such",
            "than", "that", "the", "their", "them", "then", "there", "these", "they", "this", "those",
            "to", "two", "up", "us", "use", "used", "using",
            "was", "we", "were", "what", "when", "where", "which", "while", "who", "why", "will", "with",
            "would", "you", "your");

    private Tokenizer() {
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                run.append(c);
            } else {
                flush(run, tokens);
            }
        }
        flush(run, tokens);
        return tokens;
    }

    /** 一个连续字母数字串可能混着 CJK 与拉丁，先按类别拆开再分别处理。 */
    private static void flush(StringBuilder run, List<String> tokens) {
        if (run.length() == 0) {
            return;
        }
        String value = run.toString();
        run.setLength(0);
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isCjk(c)) {
                emitLatin(latin, tokens);
                cjk.append(c);
            } else {
                emitCjk(cjk, tokens);
                latin.append(c);
            }
        }
        emitLatin(latin, tokens);
        emitCjk(cjk, tokens);
    }

    private static void emitLatin(CharSequence run, List<String> tokens) {
        StringBuilder word = new StringBuilder();
        int segments = 0;
        for (int i = 0; i < run.length(); i++) {
            char c = run.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && isWordStart(run, i)) {
                addWord(word, tokens);
                segments++;
            }
            word.append(c);
        }
        addWord(word, tokens);
        if (segments > 0) {
            // 驼峰拆成多段时额外保留整词，否则 @BeforeEach 会被拆成 before / each 两个停用词而整词丢失。
            addWord(new StringBuilder(run), tokens);
        }
    }

    /** {@code getUserId} 在 {@code U} 处断开，{@code XMLParser} 在 {@code P} 处断开。 */
    private static boolean isWordStart(CharSequence run, int index) {
        char previous = run.charAt(index - 1);
        if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
            return true;
        }
        return Character.isUpperCase(previous)
                && index + 1 < run.length()
                && Character.isLowerCase(run.charAt(index + 1));
    }

    private static void addWord(StringBuilder word, List<String> tokens) {
        if (word.length() == 0) {
            return;
        }
        String value = word.toString().toLowerCase(Locale.ROOT);
        word.setLength(0);
        if (value.length() > 1 && !STOPWORDS.contains(value)) {
            tokens.add(value);
        }
    }

    private static void emitCjk(CharSequence run, List<String> tokens) {
        for (int i = 0; i < run.length(); i++) {
            tokens.add(String.valueOf(run.charAt(i)));
            if (i + 1 < run.length()) {
                tokens.add(run.subSequence(i, i + 2).toString());
            }
        }
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
