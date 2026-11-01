package edu.mentorj.ui;

import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.Citation;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentRequestsTest {

    @Test
    public void treatsAnswersWithoutCitationsOrGeneralFlagAsErrors() {
        assertTrue(AgentRequests.isError(new AgentResponse("LLM 尚未配置", List.of(), false)));
        assertFalse(AgentRequests.isError(new AgentResponse("answer",
                List.of(new Citation("Lec-3.pdf", "P12")), false)));
        assertFalse(AgentRequests.isError(new AgentResponse("answer", List.of(), true)));
    }
}
