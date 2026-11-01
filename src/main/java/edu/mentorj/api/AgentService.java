package edu.mentorj.api;

/** The single entry point to the agent: implemented by the agent core team, called by the UI team. */
public interface AgentService {

    /**
     * Handle one request and return the answer. Blocks synchronously, so the caller must run it on a
     * background thread and never on the EDT.
     */
    AgentResponse respond(AgentRequest request);

    /**
     * Handle one request and stream the increments while generating.
     *
     * <p>Blocks synchronously, so the caller must run it on a background thread; {@code listener} is also called
     * back on that background thread. The default implementation degrades to one callback with the whole body
     * once generation finishes, so existing implementations need no change.
     */
    default AgentResponse respond(AgentRequest request, StreamListener listener) {
        AgentResponse response = respond(request);
        if (!listener.isCancelled() && response.text() != null) {
            listener.onText(response.text());
        }
        return response;
    }
}
