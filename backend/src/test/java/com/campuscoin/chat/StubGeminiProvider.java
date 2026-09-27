package com.campuscoin.chat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

/**
 * A stub of the provider's wire protocol, standing in for Google's endpoint.
 *
 * <p><b>What this is, and what it deliberately is not.</b> It is an HTTP server that speaks the
 * provider's request and response shape - the shape the real SDK produces, confirmed against a live
 * capture of {@code google-genai-1.73.0} rather than assumed. It is <em>not</em> a mock of this
 * application's code: nothing in {@code com.campuscoin.chat} or {@code com.campuscoin.common.ai} is
 * replaced, subclassed or spied. The application builds its real {@code GeminiChatCompletionPort},
 * constructs the real SDK {@code Client}, and issues a real HTTP request that this server answers.
 *
 * <p>That distinction is the whole reason the integration suite is worth having. A stubbed
 * {@code ChatCompletionPort} would prove the service's turn-taking and nothing else; this proves that
 * the request actually leaves the process, that the tool result really is assembled onto the wire in
 * the shape the provider expects, and that a provider fault really does become the 503 the student
 * sees. It is also what keeps the suite runnable: the provider's free tier allows a small number of
 * requests per model per day, and a test suite that spent them would be a suite nobody could run twice.
 *
 * <p><b>Why a real HTTP server and not an SDK-level test double.</b> {@code AiProperties.baseUrl}
 * exists for exactly this - its javadoc says "overridable so a test can point at a stub server" - and
 * the suggestion adapter has honoured it since module 12. Using it means the code under test is the
 * production path from the controller down, with only the third party swapped for a scripted stand-in.
 *
 * <p>Requests are recorded, so a test can assert on what the application actually sent. That is how the
 * chain in the brief's section 13 is proved step by step rather than end to end in one assertion: the
 * second request the stub receives must carry the figures the first response asked for, read from the
 * signed-in student's own rows.
 */
final class StubGeminiProvider {

    /** The model the stub reports having answered, matching {@code campuscoin.ai.model}. */
    static final String MODEL = "gemini-3.5-flash";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;

    /**
     * The answers still to be given, in order. A scenario that involves a tool call needs at least two
     * entries: the model asks for a figure, and then, on the next round with that figure in front of it,
     * answers. A script with a single entry is repeated, so a test that only cares about one answer - or
     * about a provider fault that should happen on every round - does not have to say so.
     */
    private final Deque<Reply> script = new ConcurrentLinkedDeque<>();

    /** The last answer given, reused once the script runs out. */
    private volatile Reply fallback = Reply.text("stub reply");

    /** Every request body the application sent, in order, parsed. */
    private final List<ObjectNode> requests = new CopyOnWriteArrayList<>();

    private StubGeminiProvider(HttpServer server) {
        this.server = server;
    }

    /** Starts a stub on an ephemeral port. The caller owns it and must {@link #stop()} it. */
    static StubGeminiProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubGeminiProvider stub = new StubGeminiProvider(server);

            server.createContext("/", exchange -> {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                try {
                    stub.requests.add((ObjectNode) JSON.readTree(raw));
                } catch (IOException ex) {
                    // A body that will not parse is recorded as an empty object rather than thrown:
                    // the test's assertion on the body is the thing that should fail, not the harness.
                    stub.requests.add(JSON.createObjectNode());
                }

                Reply reply = stub.nextReply();
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            return stub;
        } catch (IOException ex) {
            throw new IllegalStateException("The stub provider could not be started.", ex);
        }
    }

    /** The value {@code campuscoin.ai.base-url} must carry for the application to reach this stub. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    // ------------------------------------------------------------------
    //  Scripting the next answer
    // ------------------------------------------------------------------

    /** Answer with prose, as a real reply. */
    void willReply(String text) {
        script.clear();
        script.add(Reply.text(text));
    }

    /** Answer with a tool call, which is how the model asks the application for figures. */
    void willCallTool(String functionName, Map<String, Object> arguments) {
        script.clear();
        script.add(Reply.toolCall(functionName, arguments));
    }

    /** Answer with a tool call for each name given, in one turn - the model asking for several at once. */
    void willCallTools(List<String> functionNames) {
        script.clear();
        script.add(Reply.toolCalls(functionNames));
    }

    /** Answer with no text at all, which the adapter must treat as a fault rather than an empty reply. */
    void willReplyBlank() {
        script.clear();
        script.add(Reply.blank());
    }

    /** Refuse, as the provider does when the daily quota is spent ({@code 429}) or it is overloaded. */
    void willFail(int status, String providerBody) {
        script.clear();
        script.add(Reply.failure(status, providerBody));
    }

    /**
     * Ask for a figure, and then answer - the two-round shape a grounded reply has.
     *
     * <p>Explicit rather than implied, because a script of one tool call would be repeated: the model
     * would ask for the same figure on every round until the round bound was reached, and the turn would
     * fail with a 503 that looked like a defect in the application. A test that means "read this, then
     * answer" should have to say so.
     *
     * @param functionName the tool the model asks for in the first round
     * @param arguments    its arguments
     * @param reply        the prose it answers with once it has the figure
     */
    void willCallToolThenReply(String functionName, Map<String, Object> arguments, String reply) {
        script.clear();
        script.add(Reply.toolCall(functionName, arguments));
        script.add(Reply.text(reply));
    }

    /** Ask for several figures in one turn, then answer once they are in front of it. */
    void willCallToolsThenReply(List<String> functionNames, String reply) {
        script.clear();
        script.add(Reply.toolCalls(functionNames));
        script.add(Reply.text(reply));
    }

    /** Refuse on the first call and keep refusing, which is what a spent quota does. */
    void willAlwaysFail(int status, String providerBody) {
        script.clear();
        fallback = Reply.failure(status, providerBody);
    }

    /** The next answer: the head of the script, or the last one given once it is exhausted. */
    private Reply nextReply() {
        Reply next = script.poll();
        if (next != null) {
            fallback = next;
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    //  Reading what the application sent
    // ------------------------------------------------------------------

    /** How many requests reached the stub. */
    int requestCount() {
        return requests.size();
    }

    /** The request bodies the application sent, in order. */
    List<ObjectNode> requests() {
        return List.copyOf(requests);
    }

    /** The last request body, or {@code null} when nothing was sent. */
    ObjectNode lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    /** The request at {@code index}, which must exist. */
    ObjectNode request(int index) {
        return requests.get(index);
    }

    /** Forget everything sent so far, so one test's calls cannot be read by the next. */
    void reset() {
        requests.clear();
        script.clear();
        fallback = Reply.text("stub reply");
    }

    /**
     * The function responses the application put on the wire in {@code request(index)}.
     *
     * <p>This is the arrow from the tool layer to the provider - the point in the protocol where the
     * student's own figures leave the server. A test reads it to prove that what left was the data the
     * tool read, and not something the model supplied.
     */
    List<JsonNode> functionResponsesIn(int index) {
        ObjectNode request = request(index);
        ArrayNode contents = (ArrayNode) request.path("contents");
        List<JsonNode> found = new CopyOnWriteArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            ArrayNode parts = (ArrayNode) contents.get(i).path("parts");
            for (int p = 0; p < parts.size(); p++) {
                if (parts.get(p).has("functionResponse")) {
                    found.add(parts.get(p).path("functionResponse"));
                }
            }
        }
        return List.copyOf(found);
    }

    /** The plain text turns the application sent, so a test can check the system instruction and history. */
    List<String> textPartsIn(int index) {
        ObjectNode request = request(index);
        ArrayNode contents = (ArrayNode) request.path("contents");
        List<String> found = new CopyOnWriteArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            ArrayNode parts = (ArrayNode) contents.get(i).path("parts");
            for (int p = 0; p < parts.size(); p++) {
                if (parts.get(p).has("text")) {
                    found.add(parts.get(p).path("text").asText());
                }
            }
        }
        return List.copyOf(found);
    }

    /** Whether every text turn in {@code request(index)} is the given role, e.g. {@code "user"}. */
    boolean allTurnsHaveRole(int index, String role) {
        ArrayNode contents = (ArrayNode) request(index).path("contents");
        for (int i = 0; i < contents.size(); i++) {
            if (!role.equals(contents.get(i).path("role").asText())) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  The wire shapes
    // ------------------------------------------------------------------

    /** One scripted answer: an HTTP status and the body to send. */
    private record Reply(int status, String body) {

        static Reply text(String text) {
            ObjectNode part = JSON.createObjectNode().put("text", text);
            return ok(part);
        }

        static Reply toolCall(String functionName, Map<String, Object> arguments) {
            return toolCalls(List.of(functionName), Map.of(functionName, arguments));
        }

        static Reply toolCalls(List<String> functionNames) {
            return toolCalls(functionNames, Map.of());
        }

        private static Reply toolCalls(List<String> functionNames, Map<String, Map<String, Object>> arguments) {
            ArrayNode parts = JSON.createArrayNode();
            for (String name : functionNames) {
                ObjectNode call = JSON.createObjectNode().put("name", name);
                call.set("args", JSON.valueToTree(arguments.getOrDefault(name, Map.of())));
                parts.add(JSON.createObjectNode().set("functionCall", call));
            }
            return okParts(parts);
        }

        static Reply blank() {
            ObjectNode part = JSON.createObjectNode().put("text", "   ");
            return ok(part);
        }

        static Reply failure(int status, String providerBody) {
            return new Reply(status, providerBody);
        }

        /** One part, as the {@code parts} array. */
        private static Reply ok(JsonNode part) {
            return okParts(JSON.createArrayNode().add(part));
        }

        /**
         * Several parts at once.
         *
         * <p>Separate from {@link #ok} rather than one method taking a {@code JsonNode}, because
         * {@code ArrayNode.add(JsonNode)} on an array argument appends it <em>as an element</em> - which
         * writes {@code "parts":[[{...}]]}, a shape the SDK rejects with "Cannot deserialize value of type
         * Part$Builder from Array value". That is exactly the mistake this method exists to prevent:
         * a tool-call reply silently became a deserialisation failure, which the adapter correctly
         * reported as an unavailable provider, and every grounded-reply test failed as though the
         * application had not run its tool.
         */
        private static Reply okParts(ArrayNode parts) {
            ObjectNode content = JSON.createObjectNode().put("role", "model");
            content.set("parts", parts);
            ObjectNode candidate = JSON.createObjectNode().put("finishReason", "STOP");
            candidate.set("content", content);
            ObjectNode body = JSON.createObjectNode().put("modelVersion", MODEL);
            body.set("candidates", JSON.createArrayNode().add(candidate));
            return new Reply(200, body.toString());
        }
    }
}
