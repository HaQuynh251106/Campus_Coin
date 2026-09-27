package com.campuscoin.common.ai;

import java.util.List;
import java.util.Map;

import com.google.genai.types.FunctionDeclaration;

/**
 * The conversational provider port: one question, one answer, with the model allowed to ask the
 * application for the figures it needs (section 2 of the brief).
 *
 * <p><b>The provider still never sees the database, and this interface is why.</b> It has no
 * repository, no {@code EntityManager} and no user id - the same discipline {@link AiSuggestionPort}
 * states. What it has is {@code tools}, a callback the caller supplies. When the model wants a figure it
 * emits a call by name; this port hands that name and its arguments to the callback and gives back
 * whatever the callback returns. The callback is owned by the calling service, which holds the
 * authenticated principal, so the only data the provider can ever receive is what that service chose to
 * return. Model-driven tool calls therefore do not loosen the boundary - they make the boundary the
 * only way for the model to learn anything:
 *
 * <pre>
 *   Angular -&gt; Spring Boot -&gt; the provider asks for a figure by name
 *                            -&gt; the service reads the student's own rows for that name
 *           &lt;- the provider writes a reply from those figures alone &lt;-
 * </pre>
 *
 * <p><b>{@link #converse} throws where {@link AiSuggestionPort} returns empty, and the difference is
 * deliberate.</b> UC-08 and UC-17 both have a deterministic answer the application can give without any
 * provider - a keyword match and a rule-based summary - so an outage there is invisible and harmless.
 * A conversation has no such answer: the whole feature <em>is</em> the provider, and the only honest
 * thing a rule-based stand-in could do is produce text no model wrote and let the student read it as if
 * one had. Section 15 of the brief forbids exactly that. So an unavailable provider is an error the
 * caller must surface, not an empty result it can paper over.
 *
 * <p><b>Nothing here stores a conversation.</b> History arrives with each call and leaves with the
 * answer; there is no session, no cache and no per-user state in this interface or its implementations.
 * That is what makes one student's history structurally unable to reach another's.
 */
public interface ChatCompletionPort {

    /**
     * Answers one turn, calling {@code request.tools()} as often as the model asks and no more than
     * {@code request.maxToolRounds()} times.
     *
     * @return the reply, with the model that wrote it and the tools it read
     * @throws AiProviderUnavailableException if no provider is configured, or the provider refused,
     *                                        timed out, or answered with something unusable. The caller
     *                                        reports this to the student as an error; it must not
     *                                        substitute an answer of its own.
     */
    ChatCompletion converse(ChatCompletionRequest request);

    /**
     * Whether an external provider is behind this instance.
     *
     * <p>Reported so the caller can refuse before it calls, rather than catching an exception it
     * expected. It does not change any behaviour.
     */
    boolean isExternalProvider();

    /**
     * The model this instance would call, or {@code null} when there is no provider behind it.
     *
     * <p>Published so the availability endpoint can tell a student which model answered - the same
     * disclosure {@code MonthlyInsightResponse.model} makes beside the text a model wrote. It is not the
     * credential: no implementation of this method has access to the key it was constructed with, and the
     * key is never read outside the adapter's own constructor.
     *
     * <p>Defaulted to {@code null} rather than declared abstract because "there is no model" is the
     * honest answer for the no-op, and forcing every future implementation to restate it would invite one
     * to invent a value.
     */
    default String modelName() {
        return null;
    }

    /**
     * Reads one named figure for the caller.
     *
     * <p>Implemented by the chat service, which is the only party that knows who is asking. The
     * implementation is handed a name the <em>model</em> chose and arguments the model supplied, both of
     * which are untrusted: it must resolve the name against the tools it declares, take identity from its
     * own authenticated context rather than from the arguments, and return a small object rather than
     * throwing.
     */
    @FunctionalInterface
    interface ToolInvoker {

        /**
         * @param functionName the tool the model named. An unrecognised name is not an error - the
         *                     implementation returns an object saying it is unknown, so the model can
         *                     correct itself instead of the turn failing.
         * @param arguments    the arguments the model supplied, which may be empty, partial, or of the
         *                     wrong type. The implementation is responsible for validating them.
         * @return a JSON-serialisable object for the model to read. Never {@code null}.
         */
        Map<String, Object> invoke(String functionName, Map<String, Object> arguments);
    }

    /**
     * One turn of the conversation.
     *
     * @param role {@link ChatRole#USER} for the student and {@link ChatRole#ASSISTANT} for a previous
     *             reply. There is no system role: the instruction is supplied separately so no client can
     *             write itself one.
     * @param text the turn's text, already trimmed and bounded by the caller
     */
    record ChatTurn(ChatRole role, String text) {

        /** The student's own words, or a previous reply of the assistant's. */
        public enum ChatRole {
            USER,
            ASSISTANT
        }
    }

    /**
     * Everything one call needs.
     *
     * @param history      the conversation so far, oldest first, ending with the student's new question.
     *                     Supplied by the client on each request and never stored, so resolving "that" or
     *                     "last month" is the provider reading its own conversation rather than the
     *                     application matching a phrase.
     * @param tools        the callback that reads a figure for the authenticated caller
     * @param declarations what the model is allowed to ask for, so it can only call a capability the
     *                     application published. These are the SDK's own type rather than a type of this
     *                     interface's own invention: the declarations <em>are</em> the provider's wire
     *                     format, only one provider is configured by this build, and a parallel
     *                     representation would be a second place for a schema to be wrong. The coupling is
     *                     to {@code ChatTool}, which builds them, and it is deliberate rather than
     *                     accidental - if a second provider were added, this is the parameter that would
     *                     grow a neutral form.
     * @param maxToolRounds how many times the model may ask for figures before it must answer. A bound
     *                     rather than a target: a question needing one read uses one round, and a model
     *                     that keeps asking stops here instead of looping.
     */
    record ChatCompletionRequest(List<ChatTurn> history,
                                 ToolInvoker tools,
                                 List<FunctionDeclaration> declarations,
                                 int maxToolRounds) {
    }

    /**
     * What the provider produced.
     *
     * @param text      the reply, which is never blank - a blank one is treated as a provider fault by
     *                  the implementation rather than returned
     * @param model     the model that wrote it, for the provenance the brief asks to record
     * @param toolsUsed the tools the model actually read, in the order it asked for them. Empty for an
     *                  answer given without reading anything, which is the shape an out-of-scope refusal
     *                  takes.
     */
    record ChatCompletion(String text, String model, List<String> toolsUsed) {
    }
}
