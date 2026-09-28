package com.campuscoin.common.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.campuscoin.common.setting.SettingReader;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;

public class GeminiChatCompletionPort implements ChatCompletionPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatCompletionPort.class);

    static final String SYSTEM_INSTRUCTION = """
            You are the Campus Coin assistant, a financial assistant for one university student. You \
            answer questions about that student's own Campus Coin records, using only the figures the \
            tools give you.

            HOW TO ANSWER

            Read the figures you need with the tools before you state anything. Never state a number \
            you were not given by a tool, and never estimate, extrapolate or fill a gap with a \
            plausible value. If a tool reports that something is not found, or was not recorded, say \
            that - a category with no records is not a category with zero spending.

            Use the tools you are offered and no others. Each call is made on behalf of the student you \
            are talking to; you have no access to anyone else's records and no way to obtain them. If \
            you are asked about another person's finances, say that you can only help with the \
            student's own.

            THE CONVERSATION

            The conversation so far is in front of you. Words like "that", "it", "those" and "the same \
            one" refer to what you and the student were most recently discussing, and "last month", \
            "this month" and a named month refer to the months the conversation has established. Work \
            out which month or category is meant from the conversation itself. If a reference really \
            cannot be resolved - you cannot tell which of two categories is meant - ask a short \
            question instead of choosing one.

            Distinguish the current month from earlier ones, and money coming in from money going out. \
            If you are not sure which the student means, ask.

            HOW TO TALK

            Be brief and concrete. A couple of sentences is usually enough; use a short list when you \
            are comparing several figures. Give the figure and what it is, not a lecture. Do not use \
            alarmist language and do not tell the student what they must do - you may suggest \
            something they might consider, and they are free to ignore it.

            WHAT YOU MUST NOT DO

            Do not pretend to know data you do not have. Do not invent a transaction, a budget, a \
            balance, a category, a report or a tip. Do not invent a confidence score or a percentage \
            that a tool did not give you; if you report a confidence, it must be one a tool reported, \
            and you must attribute it to the forecast it came from.

            Do not claim to have done something you did not do. You cannot change the student's \
            records, set or alter a budget, add or delete a transaction, generate a report or a tip, \
            or send anything on their behalf. You can only read. If asked to change something, say \
            plainly that you can read their records but cannot change them, and point them at the \
            screen that can.

            Never reveal or discuss anything about how this application is configured or built: no \
            passwords, no tokens, no keys, no database, no internal identifiers, no other students, \
            and no administrative information. If asked for any of those, say you cannot help with \
            that.

            OUT OF SCOPE

            You are not a general assistant. If you are asked about anything outside the student's \
            Campus Coin finances, records, budgets, reports, categories and spending habits, reply \
            with exactly this sentence and nothing else:

            "I can help with your Campus Coin finances, transactions, budgets, reports, categories and \
            related features, but I can't help with that topic."
            """;

    private static final float TEMPERATURE = 0.2f;

    private final Client client;
    private final AiProperties properties;
    private final SettingReader settingReader;

    public GeminiChatCompletionPort(AiProperties properties, SettingReader settingReader) {
        this.properties = properties;
        this.settingReader = settingReader;

        HttpOptions.Builder http = HttpOptions.builder()
                .timeout(properties.effectiveTimeoutSeconds() * 1000)

                .retryOptions(HttpRetryOptions.builder().attempts(1).build());

        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            http.baseUrl(properties.baseUrl());
        }

        this.client = Client.builder()
                .apiKey(properties.apiKey())
                .httpOptions(http.build())
                .build();
    }

    @Override
    public ChatCompletion converse(ChatCompletionRequest request) {
        if (!isEnabled()) {
            throw new AiProviderUnavailableException(
                    "The assistant is switched off at the moment. Please try again later.", null);
        }

        List<Content> contents = new ArrayList<>();
        for (ChatTurn turn : request.history()) {
            contents.add(Content.builder()
                    .role(turn.role() == ChatTurn.ChatRole.USER ? "user" : "model")
                    .parts(Part.fromText(turn.text()))
                    .build());
        }

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_INSTRUCTION)))
                .maxOutputTokens(properties.effectiveMaxTokens())
                .temperature(TEMPERATURE)
                .tools(Tool.builder().functionDeclarations(request.declarations()).build())
                .toolConfig(ToolConfig.builder()
                        .functionCallingConfig(FunctionCallingConfig.builder()
                                .mode(FunctionCallingConfigMode.Known.AUTO)
                                .build())
                        .build())
                .build();

        List<String> toolsUsed = new ArrayList<>();
        int rounds = Math.max(1, request.maxToolRounds());

        for (int round = 0; round < rounds; round++) {
            GenerateContentResponse response = generate(contents, config);

            List<FunctionCall> calls = response.functionCalls();
            if (calls == null || calls.isEmpty()) {
                return reply(response, toolsUsed);
            }

            List<Part> results = new ArrayList<>();
            for (FunctionCall call : calls) {
                String name = call.name().orElse("");
                Map<String, Object> arguments = call.args().orElse(Map.of());
                toolsUsed.add(name);

                Map<String, Object> payload = request.tools().invoke(name, arguments);
                results.add(functionResponse(name, payload));
            }
            contents.add(Content.builder().role("user").parts(results).build());
        }

        log.warn("Chat completion hit the tool-round bound of {} without answering", rounds);
        throw new AiProviderUnavailableException(
                "The assistant could not put an answer together just now. Please try again.",
                null);
    }

    private GenerateContentResponse generate(List<Content> contents, GenerateContentConfig config) {
        try {
            return client.models.generateContent(properties.effectiveModel(), contents, config);
        } catch (ApiException ex) {
            log.warn("Chat completion unavailable (provider status {})", ex.code());
            throw new AiProviderUnavailableException(UNAVAILABLE_REPLY, ex);
        } catch (GenAiIOException ex) {
            log.warn("Chat completion failed ({})", ex.getClass().getSimpleName());
            throw new AiProviderUnavailableException(UNAVAILABLE_REPLY, ex);
        } catch (RuntimeException ex) {

            log.warn("Chat completion failed ({})", ex.getClass().getSimpleName());
            throw new AiProviderUnavailableException(UNAVAILABLE_REPLY, ex);
        }
    }

    private ChatCompletion reply(GenerateContentResponse response, List<String> toolsUsed) {
        String text = response.text();
        if (text == null || text.isBlank()) {
            log.warn("Chat completion produced no text (finishReason={})", response.finishReason());
            throw new AiProviderUnavailableException(
                    "The assistant could not put an answer together just now. Please try again.",
                    null);
        }
        return new ChatCompletion(text.strip(), properties.effectiveModel(), List.copyOf(toolsUsed));
    }

    @Override
    public boolean isExternalProvider() {
        return true;
    }

    @Override
    public String modelName() {
        return properties.effectiveModel();
    }

    private boolean isEnabled() {
        return settingReader.getString("ai.enabled", "true").equalsIgnoreCase("true");
    }

    private static Part functionResponse(String name, Map<String, Object> payload) {
        Map<String, Object> body = payload == null
                ? Map.of("error", "That information could not be read.")
                : payload;

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("result", body);
        return Part.fromFunctionResponse(name, envelope);
    }

    static final String UNAVAILABLE_REPLY =
            "I couldn't reach the assistant just now, so I have no answer for you. Your own figures are "
                    + "unaffected - please try again in a moment.";
}
