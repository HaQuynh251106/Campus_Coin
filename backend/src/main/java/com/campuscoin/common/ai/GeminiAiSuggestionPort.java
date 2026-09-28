package com.campuscoin.common.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.campuscoin.common.setting.SettingReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;

public class GeminiAiSuggestionPort implements AiSuggestionPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiSuggestionPort.class);

    private static final String CATEGORISATION_SYSTEM_PROMPT = """
            You help a university student file one of their own expense or income records under a \
            category. You are given the student's own description of the record and the list of \
            categories they are allowed to use.

            Choose the single category from that list that fits the description best. The category \
            you name must be spelled exactly as it appears in the list - a name that is not in the \
            list is unusable. If the description is too vague to place, choose the most general \
            category available rather than inventing one.

            Set confidence to how certain you are, between 0 and 1. Set reason to a short phrase a \
            student would understand, describing why the category fits.

            This is a filing suggestion the student will review and may change. It is not advice \
            about their money, and you must not offer any.
            """;

    private static final String NARRATIVE_SYSTEM_PROMPT = """
            You describe one student's spending month back to them, using only the figures you are \
            given. You are given monthly totals and per-category expense totals and nothing else - \
            no individual purchases, and no information about who the student is.

            Write two short pieces of plain prose:

            summary: two or three sentences describing the month - what came in, what went out, the \
            net difference, and which category took the largest share. Use only the figures you \
            were given; never state a number you were not given.

            advice: one or two sentences suggesting something the student might consider next \
            month, based on what the figures show.

            Write about the amounts in the currency you are told. Do not offer financial advice, do \
            not use alarmist language, and do not tell the student what they must do: this is shown \
            to them as a suggestion they can ignore, and it is labelled as a suggestion rather than \
            as financial advice.
            """;

    private static final Schema SUGGESTION_SCHEMA = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.of(
                    "categoryName", Schema.builder()
                            .type(Type.Known.STRING)
                            .description("The chosen category, spelled exactly as offered")
                            .build(),
                    "type", Schema.builder()
                            .type(Type.Known.STRING)
                            .description("The category's own type: INCOME or EXPENSE")
                            .build(),
                    "confidence", Schema.builder()
                            .type(Type.Known.NUMBER)
                            .description("Certainty between 0 and 1")
                            .build(),
                    "reason", Schema.builder()
                            .type(Type.Known.STRING)
                            .description("A short phrase explaining the choice")
                            .build()))
            .required("categoryName", "type", "confidence", "reason")
            .build();

    private static final Schema NARRATIVE_SCHEMA = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.of(
                    "summary", Schema.builder()
                            .type(Type.Known.STRING)
                            .description("Two or three sentences describing the month")
                            .build(),
                    "advice", Schema.builder()
                            .type(Type.Known.STRING)
                            .description("One or two sentences to consider next month")
                            .build()))
            .required("summary", "advice")
            .build();

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Client client;
    private final AiProperties properties;
    private final SettingReader settingReader;

    public GeminiAiSuggestionPort(AiProperties properties, SettingReader settingReader) {
        this.properties = properties;
        this.settingReader = settingReader;

        HttpOptions.Builder http = HttpOptions.builder()
                .timeout(properties.effectiveTimeoutSeconds() * 1000);

        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            http.baseUrl(properties.baseUrl());
        }

        this.client = Client.builder()
                .apiKey(properties.apiKey())
                .httpOptions(http.build())
                .build();
    }

    @Override
    public Optional<CategorySuggestion> suggestCategory(CategorySuggestionRequest request) {
        if (!isEnabled()) {
            return Optional.empty();
        }

        String candidateList = request.categoryNames().stream()
                .map(candidate -> "- " + candidate.name() + " (" + candidate.type() + ")")
                .collect(Collectors.joining("\n"));

        String userMessage = "Record description: " + request.description()
                + "\n\nCategories this student may use:\n" + candidateList;

        try {
            GenerateContentResponse response = client.models.generateContent(
                    properties.effectiveModel(),
                    userMessage,
                    GenerateContentConfig.builder()
                            .systemInstruction(Content.fromParts(
                                    Part.fromText(CATEGORISATION_SYSTEM_PROMPT)))
                            .maxOutputTokens(properties.effectiveMaxTokens())
                            .responseMimeType("application/json")
                            .responseSchema(SUGGESTION_SCHEMA)
                            .build());

            JsonNode payload = parse(response.text());
            if (payload == null) {
                log.warn("Category suggestion unusable (malformed reply); continuing without one");
                return Optional.empty();
            }

            return Optional.of(new CategorySuggestion(
                    text(payload, "categoryName"),
                    text(payload, "type"),
                    clamp(payload.path("confidence").asDouble(0)),
                    text(payload, "reason")));
        } catch (ApiException ex) {

            log.warn("Category suggestion unavailable ({}); continuing without a suggestion",
                    ex.code());
            return Optional.empty();
        } catch (GenAiIOException ex) {

            log.warn("Category suggestion failed ({}); continuing without a suggestion",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        } catch (RuntimeException ex) {

            log.warn("Category suggestion failed ({}); continuing without a suggestion",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public Optional<MonthlyNarrative> narrateMonth(MonthlyNarrativeRequest request) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        if (!settingReader.getString("ai.send_aggregates_only", "true").equalsIgnoreCase("true")) {
            log.info("AI narrative skipped: ai.send_aggregates_only is off");
            return Optional.empty();
        }

        String categoryLines = request.topCategories().stream()
                .map(category -> "- " + category.name() + ": " + category.total().toPlainString())
                .collect(Collectors.joining("\n"));

        String userMessage = "Month: " + request.monthLabel()
                + "\nCurrency: " + request.currency()
                + "\nTotal income: " + amount(request.totalIncome())
                + "\nTotal expense: " + amount(request.totalExpense())
                + "\nNet difference: " + amount(request.netAmount())
                + "\n\nExpense by category, largest first:\n"
                + (categoryLines.isEmpty() ? "- (none recorded)" : categoryLines);

        try {
            GenerateContentResponse response = client.models.generateContent(
                    properties.effectiveModel(),
                    userMessage,
                    GenerateContentConfig.builder()
                            .systemInstruction(Content.fromParts(
                                    Part.fromText(NARRATIVE_SYSTEM_PROMPT)))
                            .maxOutputTokens(properties.effectiveMaxTokens())
                            .responseMimeType("application/json")
                            .responseSchema(NARRATIVE_SCHEMA)
                            .build());

            JsonNode payload = parse(response.text());
            if (payload == null) {
                log.warn("Monthly narrative unusable (malformed reply); keeping the rule-based "
                        + "summary");
                return Optional.empty();
            }

            return Optional.of(new MonthlyNarrative(
                    text(payload, "summary"),
                    text(payload, "advice"),
                    properties.effectiveModel()));
        } catch (ApiException ex) {
            log.warn("Monthly narrative unavailable ({}); keeping the rule-based summary",
                    ex.code());
            return Optional.empty();
        } catch (GenAiIOException ex) {
            log.warn("Monthly narrative failed ({}); keeping the rule-based summary",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Monthly narrative failed ({}); keeping the rule-based summary",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public boolean isExternalProvider() {
        return true;
    }

    private boolean isEnabled() {
        return settingReader.getString("ai.enabled", "true").equalsIgnoreCase("true");
    }

    private static JsonNode parse(String replyText) {
        if (replyText == null || replyText.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(replyText);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || !value.isValueNode() ? "" : value.asText("");
    }

    private static double clamp(double confidence) {
        if (Double.isNaN(confidence) || confidence < 0) {
            return 0;
        }
        return Math.min(confidence, 1);
    }

    private static String amount(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }
}
