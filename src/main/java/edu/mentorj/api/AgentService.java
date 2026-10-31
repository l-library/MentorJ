package edu.mentorj.api;

/** agent 的唯一入口，agent 核心组实现，界面组调用。 */
public interface AgentService {

    /**
     * 处理一次请求并返回回答。同步阻塞，调用方必须放在后台线程，禁止在 EDT 调用。
     */
    AgentResponse respond(AgentRequest request);

    /**
     * 处理一次请求，并在生成过程中回调增量。
     *
     * <p>同步阻塞，调用方必须放在后台线程；{@code listener} 也在这条后台线程上被回调。
     * 默认实现退化为完成后回调一次正文，保证旧实现无需修改。
     */
    default AgentResponse respond(AgentRequest request, StreamListener listener) {
        AgentResponse response = respond(request);
        if (!listener.isCancelled() && response.text() != null) {
            listener.onText(response.text());
        }
        return response;
    }
}
