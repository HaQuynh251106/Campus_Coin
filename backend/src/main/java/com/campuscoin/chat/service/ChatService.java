package com.campuscoin.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.chat.dto.ChatAvailabilityResponse;
import com.campuscoin.chat.dto.ChatRequest;
import com.campuscoin.chat.dto.ChatResponse;
import com.campuscoin.chat.dto.ChatTurnRequest;
import com.campuscoin.chat.tool.ChatTool;
import com.campuscoin.chat.tool.ChatToolExecutor;
import com.campuscoin.common.ai.AiProviderUnavailableException;
import com.campuscoin.common.ai.ChatCompletionPort;
import com.campuscoin.common.ai.ChatCompletionPort.ChatCompletion;
import com.campuscoin.common.ai.ChatCompletionPort.ChatCompletionRequest;
import com.campuscoin.common.ai.ChatCompletionPort.ChatTurn;
import com.campuscoin.common.exception.ApiException;
import com.campuscoin.common.exception.ErrorCode;
import com.campuscoin.common.setting.SettingReader;
import com.google.genai.types.FunctionDeclaration;

/**
 * The assistant's turn-taking: who is asking, what it may read, and what to do when the provider fails.
 *
 * <p><b>This class is where ownership is enforced, and it is enforced by a lambda capture.</b> The
 * {@link AuthenticatedUser} it is given comes from the verified bearer token - see
 * {@code ChatController} - and it is bound into the tool callback passed to the provider. The provider
 * receives a function it can call with a tool name and some arguments, and no way to reach anything else:
 * no identity travels through the callback's parameters, so there is no argument the model could supply
 * that would name a different student. Section 9's requirement is met by there being nothing to override,
 * which is why this is a closure over the principal rather than a parameter that is passed alongside it.
 *
 * <p><b>No conversation is stored.</b> The history arrives on the request and leaves with the answer.
 * There is no table, no cache and no session keyed to this service, so one student's history cannot be
 * read under another's account for the same reason a value that was never written cannot be
 * mis-attributed. It also means the feature adds no rows to clean up and no state that could go stale.
 *
 * <p><b>The read-only rule is enforced by which tools exist.</b> Section 7 makes the assistant read-only,
 * and the enforcement is that {@link ChatTool} has no member that writes: the anomaly scan, tip
 * generation, insight generation and marking a notification read are operations of the modules behind the
 * tools and are simply not reachable from here. This class cannot call them because it has no reference
 * to any of them - only to {@link ChatToolExecutor}, which exposes ten reads.
 *
 * <p><b>A provider failure becomes an error the student can see, never an answer.</b> This is the
 * deliberate reversal of the suggestion modules' policy and the reasoning is in
 * {@link ChatCompletionPort}. Here it has one concrete consequence: this class has no branch that
 * composes a reply. If the provider cannot answer, there is no answer - text written here would be shown
 * in the assistant's voice and would be a lie about its own authorship.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * How many times the model may ask for figures before it must answer.
     *
     * <p>Five, because a genuinely multi-step question needs a few - a trend question might read the
     * current month, the previous month and the budget status before it can compare anything - and
     * because a bound is what stops a model that has misunderstood the task from asking forever. Each
     * round is a provider call against a metered quota, so the bound is also what makes the worst case for
     * one question cost a known number of requests rather than an unknown one.
     */
    static final int MAX_TOOL_ROUNDS = 5;

    /**
     * The most turns of history one request may carry into the provider call.
     *
     * <p>The request is already bounded to thirty turns; this is the second bound, applied after the
     * blank turns have been dropped. It exists because the oldest turns of a long conversation are the
     * least likely to be what "that" refers to, and paying to send them on every question is the cost the
     * bound avoids.
     */
    static final int MAX_HISTORY_TURNS = 30;

    private final ChatCompletionPort chatPort;
    private final ChatToolExecutor toolExecutor;
    private final SettingReader settingReader;

    public ChatService(ChatCompletionPort chatPort,
                       ChatToolExecutor toolExecutor,
                       SettingReader settingReader) {
        this.chatPort = chatPort;
        this.toolExecutor = toolExecutor;
        this.settingReader = settingReader;
    }

    /**
     * Answers one question about the caller's own records.
     *
     * <p><b>No {@code @Transactional} here, deliberately.</b> The tools open the transactions their own
     * reads need, and wrapping the whole turn in one would break the refusal path: a tool that reports
     * "that month is not a month" is an ordinary result the model recovers from, but a service throwing
     * inside a shared transaction marks it rollback-only, and every later read in the same turn would then
     * fail. The turn is intentionally not atomic - each figure is read as its own consistent snapshot,
     * which is the same guarantee the student's own screens give them.
     *
     * @throws ApiException with {@link ErrorCode#AI_UNAVAILABLE} when the provider cannot answer. The
     *                      message it carries is written for the student and names no provider, status
     *                      code or model.
     */
    public ChatResponse ask(AuthenticatedUser principal, ChatRequest request) {
        List<ChatTurn> history = toTurns(request);

        ChatCompletionRequest completionRequest = new ChatCompletionRequest(
                history,
                (functionName, arguments) -> invokeTool(functionName, arguments, principal),
                declarations(),
                MAX_TOOL_ROUNDS);

        ChatCompletion completion;
        try {
            completion = chatPort.converse(completionRequest);
        } catch (AiProviderUnavailableException ex) {
            // Logged at info because an absent or overloaded provider is an operational condition rather
            // than a defect, and the student is told plainly. The exception's own cause is logged by the
            // adapter that threw it; nothing from it reaches the response.
            log.info("Chat unavailable for userId={}", principal.userId());

            // The message is the port's, not a generic one: only the adapter knows whether this
            // deployment has no credential at all or the provider is momentarily refusing, and it is the
            // adapter that wrote a sentence a student can act on.
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, ex.getMessage());
        }

        log.info("Chat answered userId={} tools={}", principal.userId(), completion.toolsUsed());

        return new ChatResponse(completion.text(), completion.model(), completion.toolsUsed());
    }

    /**
     * Whether the assistant can answer at all, without spending a provider call.
     *
     * <p><b>Why this is a GET and not a probe.</b> The provider's free tier allows a small number of
     * requests per model per day, so a "is it working?" endpoint that actually asked the provider would
     * spend the student's quota to answer a question about the quota. This reads the two configuration
     * facts instead - whether a credential is installed, and whether an administrator has switched the
     * feature on - and reports them. Both are deployment state, neither costs anything, and the pair is
     * what decides whether {@link #ask} will reach a provider at all.
     *
     * <p>It is reachable by the student rather than only by an operator because the UI needs it: a chat
     * panel that knows the assistant is off can say so before the student types a question, which is a
     * better answer than a failed send.
     */
    public ChatAvailabilityResponse availability() {
        boolean providerConfigured = chatPort.isExternalProvider();
        boolean switchedOn = settingReader.getString("ai.enabled", "true").equalsIgnoreCase("true");

        if (!providerConfigured) {
            return new ChatAvailabilityResponse(false, null,
                    "The assistant is not available on this installation.");
        }
        if (!switchedOn) {
            return new ChatAvailabilityResponse(false, null,
                    "The assistant is switched off at the moment.");
        }
        // The model is published, not the credential. Naming the model is the same disclosure
        // MonthlyInsightResponse makes beside the text it wrote; the key is never read here, never
        // returned, and never leaves the adapter that holds it.
        return new ChatAvailabilityResponse(true, chatPort.modelName(), null);
    }

    /**
     * Turns the request's history into the turns the provider will read.
     *
     * <p>Blank turns are dropped rather than sent. A client that echoes an empty assistant message - which
     * a failed request can leave behind - would otherwise put an empty part in the conversation, and some
     * providers reject a part with no content. Dropping it is also the honest reading: an empty turn is
     * not something anyone said.
     *
     * <p>The new message is appended last, so the conversation the provider sees always ends with the
     * student's current question. That ordering is what makes a pronoun in that question refer to the
     * turns before it.
     */
    private List<ChatTurn> toTurns(ChatRequest request) {
        List<ChatTurn> turns = new ArrayList<>();

        if (request.history() != null) {
            for (ChatTurnRequest turn : request.history()) {
                String text = turn.text() == null ? "" : turn.text().strip();
                if (text.isEmpty()) {
                    continue;
                }
                turns.add(new ChatTurn(
                        turn.role() == ChatRole.USER
                                ? ChatTurn.ChatRole.USER
                                : ChatTurn.ChatRole.ASSISTANT,
                        text));
            }
        }

        // Keep the most recent turns when a long conversation is posted. Trimming from the front leaves
        // the turns nearest the current question, which are the ones a reference resolves against.
        if (turns.size() > MAX_HISTORY_TURNS) {
            turns = new ArrayList<>(turns.subList(turns.size() - MAX_HISTORY_TURNS, turns.size()));
        }

        turns.add(new ChatTurn(ChatTurn.ChatRole.USER, request.message().strip()));
        return turns;
    }

    /**
     * Runs one tool the model asked for, for this caller only.
     *
     * <p><b>The name is resolved against the declared tools before anything else happens.</b> A model
     * that names something that was not offered - because it was talked into it, or because it
     * hallucinated - gets an object saying the capability is unknown, and the turn continues. That is
     * strictly better than an exception, which would fail the whole question over a recoverable
     * misunderstanding, and it is the reason the model can never reach a capability the application did
     * not publish.
     *
     * <p>The arguments are passed through as they arrived. They are untrusted text - they were shaped by
     * whatever the student typed - so {@link ChatToolExecutor} validates them and answers a malformed one
     * with a refusal rather than an exception.
     */
    private Map<String, Object> invokeTool(String functionName, Map<String, Object> arguments,
                                           AuthenticatedUser principal) {
        ChatTool tool = ChatTool.byFunctionName(functionName);
        if (tool == null) {
            log.info("Chat asked for an undeclared tool userId={}", principal.userId());
            return Map.of("error", "That capability is not available.");
        }
        return toolExecutor.execute(tool, arguments, principal);
    }

    /**
     * Every tool's declaration, which is what the model is shown.
     *
     * <p>Built from {@link ChatTool}'s own members rather than listed here, so a capability cannot be
     * declared to the provider without also being resolvable by {@link #invokeTool} - the two are the same
     * enum, and adding a read means editing one file.
     */
    private static List<FunctionDeclaration> declarations() {
        List<FunctionDeclaration> declarations = new ArrayList<>();
        for (ChatTool tool : ChatTool.values()) {
            declarations.add(tool.declaration());
        }
        return declarations;
    }
}
