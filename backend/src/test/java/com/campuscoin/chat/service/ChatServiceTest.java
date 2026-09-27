package com.campuscoin.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.chat.tool.ChatTool;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.auth.entity.UserRole;
import com.campuscoin.chat.dto.ChatRequest;
import com.campuscoin.chat.dto.ChatTurnRequest;
import com.campuscoin.common.ai.AiProviderUnavailableException;
import com.campuscoin.common.ai.ChatCompletionPort;
import com.campuscoin.common.ai.ChatCompletionPort.ChatCompletion;
import com.campuscoin.common.ai.ChatCompletionPort.ChatCompletionRequest;
import com.campuscoin.common.ai.ChatCompletionPort.ChatTurn;
import com.campuscoin.common.exception.ApiException;
import com.campuscoin.common.exception.ErrorCode;
import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.common.setting.repository.SystemSettingRepository;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;

/**
 * The chat service's turn-taking: what it sends, whose data a tool may reach, and what it does when the
 * provider fails.
 *
 * <p><b>A plain unit test, deliberately.</b> {@link ChatService} takes a port, a tool executor and a
 * setting reader, so nothing about the decision it makes needs a database - and the decisions are what
 * matter here. What the tools then read from MySQL is proved separately by {@code ChatApiIT}, against
 * the real schema.
 *
 * <p><b>The port is replaced, and the replacement is the point.</b> {@link RecordingPort} answers
 * whatever the test tells it and records what it was asked. That makes three assertions possible which
 * no test of the HTTP surface could make: that the provider is handed the conversation the client sent
 * and nothing else, that the history is ordered so a reference can resolve, and that a provider failure
 * produces an error rather than a reply. The last is the brief's section 15 in executable form - a
 * conversation whose provider failed must not be answered by this application.
 *
 * <p><b>What is deliberately not asserted here.</b> That the model writes a good answer. That is the
 * provider's behaviour, not this code's, and a test that stubbed the model's prose and then asserted on
 * it would prove only that the stub was read back. What this class can hold is the contract the
 * application owes the provider and the student: the right turns, the right tools, and no invented
 * answer when there is none.
 */
class ChatServiceTest {

    private static final Long STUDENT_ID = 42L;

    private final RecordingPort port = new RecordingPort();
    private final ChatService chatService = new ChatService(
            port,
            // Never reached: every test below stops at the port, either because it throws or because the
            // port answers. A real executor would need nine services and a database, and none of the
            // decisions under test live in it.
            null,
            settingReader());

    private static final AuthenticatedUser STUDENT = new AuthenticatedUser(STUDENT_ID,
            "student@campuscoin.edu", "Test Student", UserRole.STUDENT, "session-hash");

    /** A setting reader whose table is always empty, so every key falls back to its documented default. */
    private static SettingReader settingReader() {
        SystemSettingRepository empty = (SystemSettingRepository) java.lang.reflect.Proxy
                .newProxyInstance(ChatServiceTest.class.getClassLoader(),
                        new Class<?>[] {SystemSettingRepository.class},
                        (proxy, method, args) -> java.util.Optional.empty());
        return new SettingReader(empty);
    }

    /** A port that answers what it is told, and remembers what it was asked. */
    private static final class RecordingPort implements ChatCompletionPort {

        private ChatCompletion answer = new ChatCompletion("A reply.", "gemini-3.5-flash", List.of());
        private RuntimeException failure;
        private boolean external = true;
        private final List<ChatCompletionRequest> requests = new ArrayList<>();

        private void answers(String text, List<String> toolsUsed) {
            this.answer = new ChatCompletion(text, "gemini-3.5-flash", toolsUsed);
        }

        private void fails(RuntimeException ex) {
            this.failure = ex;
        }

        private ChatCompletionRequest lastRequest() {
            assertThat(requests).as("the port was called").isNotEmpty();
            return requests.get(requests.size() - 1);
        }

        @Override
        public ChatCompletion converse(ChatCompletionRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        @Override
        public boolean isExternalProvider() {
            return external;
        }

        @Override
        public String modelName() {
            return external ? "gemini-3.5-flash" : null;
        }
    }

    private static ChatRequest request(String message, ChatTurnRequest... history) {
        return new ChatRequest(message, history.length == 0 ? null : List.of(history));
    }

    // ==================================================================
    //  The data boundary: what the model may ask for
    // ==================================================================

    @Test
    @DisplayName("The declared tools are exactly the nine reads the boundary permits")
    void theDeclaredToolsAreExactlyTheNineReads() {
        // A literal rather than a reflection over the enum, so adding a tool has to be a decision here
        // as well as an edit in ChatTool. The set is what section 3 permits and section 4 scopes: every
        // capability the brief lists that survives "one tool per existing service" is present, and the
        // writing operations of the modules behind them - the anomaly scan, tip generation, insight
        // generation, marking a notification read - are absent because section 7 makes the assistant
        // read-only.
        assertThat(java.util.Arrays.stream(ChatTool.values()).map(ChatTool::functionName))
                .containsExactlyInAnyOrder(
                        "getFinancialSummary",
                        "getMonthlySummary",
                        "getCategorySpending",
                        "getBudgetStatus",
                        "getTransactions",
                        "getSavingTips",
                        "getForecast",
                        "getAnomalies",
                        "getRecentActivity");
    }

    @Test
    @DisplayName("Every declaration the model is shown is one the service can resolve")
    void everyDeclarationIsResolvable() {
        // The two halves are the same enum, so this cannot drift - which is the reason ChatService
        // builds the declarations from ChatTool.values() rather than listing them. The assertion is what
        // would catch a future change that listed them separately and forgot one.
        ChatCompletionRequest sent = sentRequest(request("How am I doing?"));

        for (FunctionDeclaration declaration : sent.declarations()) {
            assertThat(ChatTool.byFunctionName(declaration.name().orElse(null)))
                    .as("declaration %s resolves back to a tool", declaration.name().orElse("(unnamed)"))
                    .isNotNull();
        }
        assertThat(sent.declarations()).hasSize(ChatTool.values().length);
    }

    @Test
    @DisplayName("A declaration carries a name, a description and a JSON-Schema object")
    void declarationsAreWellFormedForTheProvider() {
        // The declarations are built with the SDK's builders rather than parsed from JSON text, so a
        // mistake is a compile error. These assertions cover the part the compiler cannot: that the
        // schema a declaration carries is an object, which is what the provider requires of a
        // function's parameters.
        for (ChatTool tool : ChatTool.values()) {
            assertThat(tool.functionName()).isNotBlank();
            assertThat(tool.declaration().name()).contains(tool.functionName());
            assertThat(tool.declaration().description()).isPresent();
            assertThat(tool.declaration().description().orElseThrow()).isNotBlank();
            assertThat(tool.declaration().parameters()).isPresent();
            assertThat(tool.declaration().parameters().orElseThrow().type())
                    .contains(new Type(Type.Known.OBJECT));
        }
    }

    @Test
    @DisplayName("The tools that read a month declare the month as required")
    void monthToolsRequireTheirPeriod() {
        // A period the model forgot would otherwise reach the reports service as null and come back as a
        // refusal, costing a provider round trip to learn something the declaration should have said. The
        // declaration is the cheapest place to state it.
        for (ChatTool tool : List.of(ChatTool.MONTHLY_SUMMARY, ChatTool.CATEGORY_SPENDING,
                ChatTool.BUDGET_STATUS, ChatTool.SAVING_TIPS)) {
            Schema parameters = tool.declaration().parameters().orElseThrow();
            assertThat(parameters.required()).as("%s declares required arguments", tool.functionName())
                    .isPresent();
            assertThat(parameters.required().orElseThrow()).contains("period");
            assertThat(parameters.properties().orElseThrow()).containsKey("period");
        }
    }

    @Test
    @DisplayName("The one tool that reads individual records requires both ends of its range")
    void transactionsRequiresItsRange() {
        // TRANSACTIONS is the only tool that discloses single purchases, so it is the one whose scope has
        // to be mandatory rather than defaulted - an omitted range would mean "the whole history".
        Schema parameters = ChatTool.TRANSACTIONS.declaration().parameters().orElseThrow();

        assertThat(parameters.required().orElseThrow()).containsExactlyInAnyOrder("from", "to");
        assertThat(parameters.properties().orElseThrow()).containsKeys("from", "to", "category", "kind");
    }

    @Test
    @DisplayName("A declaration carries no figure, no name and no identifier")
    void declarationsDiscloseNoData() {
        // The declarations are sent to the provider on every call and are constants. If a value ever
        // appeared in one it would be a disclosure that no per-request check would notice, because it
        // would be identical for every student.
        for (ChatTool tool : ChatTool.values()) {
            String serialised = tool.declaration().toJson();
            assertThat(serialised)
                    .as("%s carries no email, no user id and no amount", tool.functionName())
                    .doesNotContain("@", "userId", "user_id", "studentId");
        }
    }

    // ==================================================================
    //  Ownership: what identity travels with a call
    // ==================================================================

    @Test
    @DisplayName("The tool callback takes a name and arguments, and nothing that could carry an identity")
    void theProviderIsGivenNoIdentity() {
        // The callback is a closure over the principal, so the strongest statement of that is about its
        // signature rather than about any particular value: `invoke(String, Map)` has no parameter of an
        // identity-shaped type, which means there is no argument a model could supply - however it
        // phrases the request, and whatever it puts in the arguments map - that names a student. A test
        // asserting a specific id were absent would pass for every other id.
        Method invoke = ChatCompletionPort.ToolInvoker.class.getDeclaredMethods()[0];

        assertThat(ChatCompletionPort.ToolInvoker.class.getDeclaredMethods())
                .as("the invoker has exactly one method, so this is the one the provider calls")
                .hasSize(1);
        assertThat(invoke.getParameterTypes()).containsExactly(String.class, Map.class);
        assertThat(invoke.getReturnType()).isEqualTo(Map.class);

        // And the request handed over carries the conversation and the declarations, nothing more.
        ChatCompletionRequest sent = sentRequest(request("What is my balance?"));
        assertThat(sent.history()).isNotEmpty();
        assertThat(sent.declarations()).isNotEmpty();
        assertThat(sent.tools()).isNotNull();
    }

    // ==================================================================
    //  The conversation: order and resolution
    // ==================================================================

    @Test
    @DisplayName("The provider is sent the conversation oldest first, ending with the new question")
    void theConversationIsOrderedAndEndsWithTheQuestion() {
        // This ordering is what makes "what about last month?" resolvable at all: the provider reads its
        // own conversation rather than the application matching a phrase, which is section 5's
        // requirement ("do not rely on regex matching to resolve these references").
        ChatCompletionRequest sent = sentRequest(request("What about last month?",
                new ChatTurnRequest(ChatRole.USER, "How much did I spend this month?"),
                new ChatTurnRequest(ChatRole.ASSISTANT, "You spent 271.50.")));

        assertThat(sent.history()).extracting(ChatTurn::role).containsExactly(
                ChatTurn.ChatRole.USER,
                ChatTurn.ChatRole.ASSISTANT,
                ChatTurn.ChatRole.USER);
        assertThat(sent.history()).extracting(ChatTurn::text).containsExactly(
                "How much did I spend this month?",
                "You spent 271.50.",
                "What about last month?");
    }

    @Test
    @DisplayName("A first question carries no history and is sent as a single turn")
    void aFirstQuestionIsASingleTurn() {
        ChatCompletionRequest sent = sentRequest(request("  What is my balance?  "));

        assertThat(sent.history()).hasSize(1);
        // Trimmed, so a question that arrived with padding is not sent with it and then quoted back
        // with it.
        assertThat(sent.history().get(0).text()).isEqualTo("What is my balance?");
    }

    @Test
    @DisplayName("Blank turns in the posted history are dropped rather than sent")
    void blankTurnsAreDropped() {
        // A failed request can leave an empty assistant bubble in the client's transcript. Some providers
        // reject a part with no content, and an empty turn is not something anyone said - so it is not
        // sent, and the question that follows it still is.
        ChatCompletionRequest sent = sentRequest(request("Why?",
                new ChatTurnRequest(ChatRole.USER, "What did I spend on Food?"),
                new ChatTurnRequest(ChatRole.ASSISTANT, "   "),
                new ChatTurnRequest(ChatRole.USER, "")));

        assertThat(sent.history()).extracting(ChatTurn::text)
                .containsExactly("What did I spend on Food?", "Why?");
    }

    @Test
    @DisplayName("A long conversation is trimmed from the front, keeping the turns nearest the question")
    void longHistoryIsTrimmedFromTheFront() {
        // The bound exists because the history is client-supplied and every turn is paid for on each
        // question. Trimming from the front rather than the back keeps what a pronoun most likely refers
        // to - the turns immediately before the question - and guarantees the question itself survives.
        List<ChatTurnRequest> history = new ArrayList<>();
        for (int i = 0; i < ChatService.MAX_HISTORY_TURNS + 5; i++) {
            history.add(new ChatTurnRequest(ChatRole.USER, "turn " + i));
        }

        ChatCompletionRequest sent = sentRequest(new ChatRequest("the last question", history));

        assertThat(sent.history()).hasSize(ChatService.MAX_HISTORY_TURNS + 1);
        assertThat(sent.history().get(sent.history().size() - 1).text()).isEqualTo("the last question");
        // The oldest turns are the ones dropped.
        assertThat(sent.history()).extracting(ChatTurn::text).doesNotContain("turn 0", "turn 4");
        assertThat(sent.history().get(0).text()).isEqualTo("turn 5");
    }

    @Test
    @DisplayName("The student's turns are USER and the assistant's are ASSISTANT, never a system turn")
    void rolesAreLimitedToTheTwoMembers() {
        ChatCompletionRequest sent = sentRequest(request("And now?",
                new ChatTurnRequest(ChatRole.USER, "one"),
                new ChatTurnRequest(ChatRole.ASSISTANT, "two")));

        assertThat(sent.history()).extracting(ChatTurn::role)
                .containsExactly(ChatTurn.ChatRole.USER, ChatTurn.ChatRole.ASSISTANT,
                        ChatTurn.ChatRole.USER);
        // The instruction is the adapter's own constant and is not in the history - a client cannot write
        // itself a system turn, which is why the enum has two members and the DTO type is not a string.
        assertThat(ChatRole.values()).containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
    }

    // ==================================================================
    //  A tool the model was not offered
    // ==================================================================

    @Test
    @DisplayName("A tool the application never declared is refused as a result, not an exception")
    void anUndeclaredToolIsRefusedWithoutFailingTheTurn() {
        // A model that names something that was not offered has either been talked into it or
        // hallucinated. Answering with an object saying the capability is unknown lets it correct itself
        // on the next round; throwing would fail the whole question over a recoverable misunderstanding -
        // and would also be the moment a capability the application did not publish became reachable.
        ChatCompletionRequest sent = sentRequest(request("Tell me about my account"));

        Map<String, Object> result = sent.tools().invoke("getStudentBalanceById", Map.of("userId", 99));

        assertThat(result).containsEntry("error", "That capability is not available.");
        // No figures, no ids, nothing about any student - the refusal carries only the refusal.
        assertThat(result.values().toString()).doesNotContain("99");
    }

    @Test
    @DisplayName("An undeclared tool is refused for the student whose turn it is, whatever arguments it carries")
    void anUndeclaredToolCannotBeUsedToReachAnotherStudent() {
        // The name is the only thing the model controls that could name a capability; the arguments are
        // the only thing it controls that could name a person. Both are refused: the name is resolved
        // against what was declared, and the principal is a closure over the caller, so a model asked to
        // fetch another student's balance has no route to it at all.
        ChatCompletionRequest sent = sentRequest(request("What did student 99 spend?"));

        Map<String, Object> result = sent.tools().invoke("getBalance", Map.of("userId", 99L));

        assertThat(result).containsEntry("error", "That capability is not available.");
    }

    // ==================================================================
    //  The reply, and the failure that must never become one
    // ==================================================================

    @Test
    @DisplayName("The reply, the model and the tools read come back from the port unchanged")
    void theReplyIsReturnedAsWritten() {
        port.answers("You spent 84.20 on Food in September.", List.of("getCategorySpending"));

        var response = chatService.ask(STUDENT,
                request("How much did I spend on Food last month?"));

        assertThat(response.reply()).isEqualTo("You spent 84.20 on Food in September.");
        assertThat(response.model()).isEqualTo("gemini-3.5-flash");
        assertThat(response.toolsUsed()).containsExactly("getCategorySpending");
    }

    @Test
    @DisplayName("An empty toolsUsed is a real answer, not a gap")
    void anAnswerThatReadNothingIsStillAnAnswer() {
        // The shape an out-of-scope refusal takes: the model answered from the conversation alone and
        // read no figures. Treating an empty list as "no answer" would turn a correct refusal into an
        // error.
        port.answers("I can help with your Campus Coin finances, transactions, budgets, reports, "
                + "categories and related features, but I can't help with that topic.", List.of());

        var response = chatService.ask(STUDENT, request("Who won the 2018 World Cup?"));

        assertThat(response.toolsUsed()).isEmpty();
        assertThat(response.reply()).contains("I can't help with that topic.");
    }

    @Test
    @DisplayName("A provider failure is reported as AI_UNAVAILABLE and never becomes a reply")
    void aProviderFailureIsReportedNotDisguised() {
        // Section 15's rule in executable form. The port throws rather than returning empty precisely so
        // this service cannot paper the failure over; the assertion that matters is that no ChatResponse
        // was produced at all, because a ChatResponse is what the student reads as the assistant's words.
        port.fails(new AiProviderUnavailableException(
                "I couldn't reach the assistant just now.", null));

        assertThat(catchThrowableOfType(() -> chatService.ask(STUDENT, request("What is my balance?")),
                ApiException.class))
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
                    // The port's own student-readable sentence is carried through: only the adapter knows
                    // whether this deployment has no credential or the provider is momentarily refusing.
                    assertThat(ex.getMessage()).isEqualTo("I couldn't reach the assistant just now.");
                });
    }

    @Test
    @DisplayName("The error a failed turn produces names no provider, status, model or credential")
    void theFailureMessageDisclosesNothingAboutTheDeployment() {
        // The message is shown in the chat panel, so it is student-facing copy. A rate limit, a bad key
        // and an overloaded service are the deployment's problem; a student can do nothing with any of
        // them, and naming the provider would say where their figures were sent.
        port.fails(new AiProviderUnavailableException(
                "I couldn't reach the assistant just now.", null));

        ApiException thrown = catchThrowableOfType(
                () -> chatService.ask(STUDENT, request("What is my balance?")), ApiException.class);

        assertThat(thrown.getMessage())
                .doesNotContainIgnoringCase("gemini", "google", "api key", "apikey", "quota",
                        "429", "503", "http", "token", "model");
    }

    // ==================================================================
    //  Availability: the status read, and what it may disclose
    // ==================================================================

    @Test
    @DisplayName("Availability reports the model when a provider is configured")
    void availabilityNamesTheModelWhenConfigured() {
        var availability = chatService.availability();

        assertThat(availability.available()).isTrue();
        assertThat(availability.model()).isEqualTo("gemini-3.5-flash");
        assertThat(availability.reason()).isNull();
    }

    @Test
    @DisplayName("No provider means unavailable, with a reason written for a student")
    void availabilityExplainsAnUnconfiguredDeployment() {
        port.external = false;

        var availability = chatService.availability();

        assertThat(availability.available()).isFalse();
        // No model to name, which is why the DTO omits the field rather than sending null.
        assertThat(availability.model()).isNull();
        // The reason says the assistant is unavailable; it does not name the credential that is missing,
        // which is the operator's business rather than the student's.
        assertThat(availability.reason()).isNotBlank();
        assertThat(availability.reason())
                .doesNotContainIgnoringCase("gemini", "google", "api key", "credential", "gemini_api_key");
    }

    @Test
    @DisplayName("Availability asks the provider nothing")
    void availabilitySpendsNoProviderCall() {
        // The free tier allows a small number of requests per model per day, so a status probe that
        // actually called the provider would spend the quota it was reporting on. Every value here comes
        // from configuration, which is why the call list is asserted empty.
        chatService.availability();

        assertThat(port.requests).isEmpty();
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    /** Asks one question and returns the request the port received. */
    private ChatCompletionRequest sentRequest(ChatRequest request) {
        chatService.ask(STUDENT, request);
        return port.lastRequest();
    }

    private static <T extends Throwable> T catchThrowableOfType(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, Class<T> type) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(callable, type);
    }
}
