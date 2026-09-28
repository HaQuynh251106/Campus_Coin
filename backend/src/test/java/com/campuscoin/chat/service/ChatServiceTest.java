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

class ChatServiceTest {

    private static final Long STUDENT_ID = 42L;

    private final RecordingPort port = new RecordingPort();
    private final ChatService chatService = new ChatService(
            port,

            null,
            settingReader());

    private static final AuthenticatedUser STUDENT = new AuthenticatedUser(STUDENT_ID,
            "student@campuscoin.edu", "Test Student", UserRole.STUDENT, "session-hash");

    private static SettingReader settingReader() {
        SystemSettingRepository empty = (SystemSettingRepository) java.lang.reflect.Proxy
                .newProxyInstance(ChatServiceTest.class.getClassLoader(),
                        new Class<?>[] {SystemSettingRepository.class},
                        (proxy, method, args) -> java.util.Optional.empty());
        return new SettingReader(empty);
    }

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

    @Test
    @DisplayName("The declared tools are exactly the nine reads the boundary permits")
    void theDeclaredToolsAreExactlyTheNineReads() {

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

        Schema parameters = ChatTool.TRANSACTIONS.declaration().parameters().orElseThrow();

        assertThat(parameters.required().orElseThrow()).containsExactlyInAnyOrder("from", "to");
        assertThat(parameters.properties().orElseThrow()).containsKeys("from", "to", "category", "kind");
    }

    @Test
    @DisplayName("A declaration carries no figure, no name and no identifier")
    void declarationsDiscloseNoData() {

        for (ChatTool tool : ChatTool.values()) {
            String serialised = tool.declaration().toJson();
            assertThat(serialised)
                    .as("%s carries no email, no user id and no amount", tool.functionName())
                    .doesNotContain("@", "userId", "user_id", "studentId");
        }
    }

    @Test
    @DisplayName("The tool callback takes a name and arguments, and nothing that could carry an identity")
    void theProviderIsGivenNoIdentity() {

        Method invoke = ChatCompletionPort.ToolInvoker.class.getDeclaredMethods()[0];

        assertThat(ChatCompletionPort.ToolInvoker.class.getDeclaredMethods())
                .as("the invoker has exactly one method, so this is the one the provider calls")
                .hasSize(1);
        assertThat(invoke.getParameterTypes()).containsExactly(String.class, Map.class);
        assertThat(invoke.getReturnType()).isEqualTo(Map.class);

        ChatCompletionRequest sent = sentRequest(request("What is my balance?"));
        assertThat(sent.history()).isNotEmpty();
        assertThat(sent.declarations()).isNotEmpty();
        assertThat(sent.tools()).isNotNull();
    }

    @Test
    @DisplayName("The provider is sent the conversation oldest first, ending with the new question")
    void theConversationIsOrderedAndEndsWithTheQuestion() {

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

        assertThat(sent.history().get(0).text()).isEqualTo("What is my balance?");
    }

    @Test
    @DisplayName("Blank turns in the posted history are dropped rather than sent")
    void blankTurnsAreDropped() {

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

        List<ChatTurnRequest> history = new ArrayList<>();
        for (int i = 0; i < ChatService.MAX_HISTORY_TURNS + 5; i++) {
            history.add(new ChatTurnRequest(ChatRole.USER, "turn " + i));
        }

        ChatCompletionRequest sent = sentRequest(new ChatRequest("the last question", history));

        assertThat(sent.history()).hasSize(ChatService.MAX_HISTORY_TURNS + 1);
        assertThat(sent.history().get(sent.history().size() - 1).text()).isEqualTo("the last question");

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

        assertThat(ChatRole.values()).containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
    }

    @Test
    @DisplayName("A tool the application never declared is refused as a result, not an exception")
    void anUndeclaredToolIsRefusedWithoutFailingTheTurn() {

        ChatCompletionRequest sent = sentRequest(request("Tell me about my account"));

        Map<String, Object> result = sent.tools().invoke("getStudentBalanceById", Map.of("userId", 99));

        assertThat(result).containsEntry("error", "That capability is not available.");

        assertThat(result.values().toString()).doesNotContain("99");
    }

    @Test
    @DisplayName("An undeclared tool is refused for the student whose turn it is, whatever arguments it carries")
    void anUndeclaredToolCannotBeUsedToReachAnotherStudent() {

        ChatCompletionRequest sent = sentRequest(request("What did student 99 spend?"));

        Map<String, Object> result = sent.tools().invoke("getBalance", Map.of("userId", 99L));

        assertThat(result).containsEntry("error", "That capability is not available.");
    }

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

        port.answers("I can help with your Campus Coin finances, transactions, budgets, reports, "
                + "categories and related features, but I can't help with that topic.", List.of());

        var response = chatService.ask(STUDENT, request("Who won the 2018 World Cup?"));

        assertThat(response.toolsUsed()).isEmpty();
        assertThat(response.reply()).contains("I can't help with that topic.");
    }

    @Test
    @DisplayName("A provider failure is reported as AI_UNAVAILABLE and never becomes a reply")
    void aProviderFailureIsReportedNotDisguised() {

        port.fails(new AiProviderUnavailableException(
                "I couldn't reach the assistant just now.", null));

        assertThat(catchThrowableOfType(() -> chatService.ask(STUDENT, request("What is my balance?")),
                ApiException.class))
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE);

                    assertThat(ex.getMessage()).isEqualTo("I couldn't reach the assistant just now.");
                });
    }

    @Test
    @DisplayName("The error a failed turn produces names no provider, status, model or credential")
    void theFailureMessageDisclosesNothingAboutTheDeployment() {

        port.fails(new AiProviderUnavailableException(
                "I couldn't reach the assistant just now.", null));

        ApiException thrown = catchThrowableOfType(
                () -> chatService.ask(STUDENT, request("What is my balance?")), ApiException.class);

        assertThat(thrown.getMessage())
                .doesNotContainIgnoringCase("gemini", "google", "api key", "apikey", "quota",
                        "429", "503", "http", "token", "model");
    }

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

        assertThat(availability.model()).isNull();

        assertThat(availability.reason()).isNotBlank();
        assertThat(availability.reason())
                .doesNotContainIgnoringCase("gemini", "google", "api key", "credential", "gemini_api_key");
    }

    @Test
    @DisplayName("Availability asks the provider nothing")
    void availabilitySpendsNoProviderCall() {

        chatService.availability();

        assertThat(port.requests).isEmpty();
    }

    private ChatCompletionRequest sentRequest(ChatRequest request) {
        chatService.ask(STUDENT, request);
        return port.lastRequest();
    }

    private static <T extends Throwable> T catchThrowableOfType(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, Class<T> type) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(callable, type);
    }
}
