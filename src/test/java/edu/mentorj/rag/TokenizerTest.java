package edu.mentorj.rag;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TokenizerTest {

    @Test
    public void splitsCamelCaseIdentifiers() {
        assertEquals(List.of("get", "user", "id", "getuserid"), Tokenizer.tokenize("getUserId"));
        assertEquals(List.of("assert", "throws", "assertthrows"), Tokenizer.tokenize("assertThrows"));
    }

    @Test
    public void keepsAnIdentifierWhosePartsAreAllStopwords() {
        assertEquals(List.of("beforeeach"), Tokenizer.tokenize("@BeforeEach"));
    }

    @Test
    public void splitsAcronymBeforeAWord() {
        assertEquals(List.of("xml", "parser", "xmlparser"), Tokenizer.tokenize("XMLParser"));
    }

    @Test
    public void splitsSnakeCaseAndPunctuation() {
        assertEquals(List.of("user", "id", "counter", "reset"),
                Tokenizer.tokenize("user_id = Counter.reset()"));
    }

    @Test
    public void lowercasesAndDropsStopwords() {
        assertEquals(List.of("unit", "test", "checks", "behavior", "code"),
                Tokenizer.tokenize("The unit test checks a behavior of the code and it"));
    }

    @Test
    public void emitsUnigramsAndBigramsForChinese() {
        assertEquals(List.of("异", "异常", "常", "常处", "处", "处理", "理"), Tokenizer.tokenize("异常处理"));
    }

    @Test
    public void keepsCodeTermsThatLookLikeStopwordsInEnglish() {
        assertTrue(Tokenizer.tokenize("java.lang.Exception").contains("exception"));
        assertFalse(Tokenizer.tokenize("java.lang.Exception").contains("lang."));
    }

    @Test
    public void handlesEmptyInput() {
        assertEquals(List.of(), Tokenizer.tokenize(null));
        assertEquals(List.of(), Tokenizer.tokenize("   "));
    }
}
