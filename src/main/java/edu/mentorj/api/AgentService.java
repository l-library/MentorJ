package edu.mentorj.api;

/** agent 的唯一入口，agent 核心组实现，界面组调用。 */
public interface AgentService {

    /**
     * 处理一次请求并返回回答。同步阻塞，调用方必须放在后台线程，禁止在 EDT 调用。
     */
    AgentResponse respond(AgentRequest request);
}
