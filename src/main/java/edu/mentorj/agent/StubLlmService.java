package edu.mentorj.agent;

import edu.mentorj.api.LlmService;

import java.util.List;

/** LLM 占位实现，接入真实 LLM API 后由 agent 核心组删除。 */
public final class StubLlmService implements LlmService {

    @Override
    public Result complete(Request request) {
        return new Result("（占位）LLM 服务尚未接入真实 API。", List.of());
    }
}
