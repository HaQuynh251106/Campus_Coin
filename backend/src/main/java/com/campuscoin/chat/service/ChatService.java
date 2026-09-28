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

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final int MAX_TOOL_ROUNDS = 5;

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

            log.info("Chat unavailable for userId={}", principal.userId());

            throw new ApiException(ErrorCode.AI_UNAVAILABLE, ex.getMessage());
        }

        log.info("Chat answered userId={} tools={}", principal.userId(), completion.toolsUsed());

        return new ChatResponse(completion.text(), completion.model(), completion.toolsUsed());
    }

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

        return new ChatAvailabilityResponse(true, chatPort.modelName(), null);
    }

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

        if (turns.size() > MAX_HISTORY_TURNS) {
            turns = new ArrayList<>(turns.subList(turns.size() - MAX_HISTORY_TURNS, turns.size()));
        }

        turns.add(new ChatTurn(ChatTurn.ChatRole.USER, request.message().strip()));
        return turns;
    }

    private Map<String, Object> invokeTool(String functionName, Map<String, Object> arguments,
                                           AuthenticatedUser principal) {
        ChatTool tool = ChatTool.byFunctionName(functionName);
        if (tool == null) {
            log.info("Chat asked for an undeclared tool userId={}", principal.userId());
            return Map.of("error", "That capability is not available.");
        }
        return toolExecutor.execute(tool, arguments, principal);
    }

    private static List<FunctionDeclaration> declarations() {
        List<FunctionDeclaration> declarations = new ArrayList<>();
        for (ChatTool tool : ChatTool.values()) {
            declarations.add(tool.declaration());
        }
        return declarations;
    }
}
