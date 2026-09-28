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

final class StubGeminiProvider {

    static final String MODEL = "gemini-3.5-flash";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;

    private final Deque<Reply> script = new ConcurrentLinkedDeque<>();

    private volatile Reply fallback = Reply.text("stub reply");

    private final List<ObjectNode> requests = new CopyOnWriteArrayList<>();

    private StubGeminiProvider(HttpServer server) {
        this.server = server;
    }

    static StubGeminiProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubGeminiProvider stub = new StubGeminiProvider(server);

            server.createContext("/", exchange -> {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                try {
                    stub.requests.add((ObjectNode) JSON.readTree(raw));
                } catch (IOException ex) {

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

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    void willReply(String text) {
        script.clear();
        script.add(Reply.text(text));
    }

    void willCallTool(String functionName, Map<String, Object> arguments) {
        script.clear();
        script.add(Reply.toolCall(functionName, arguments));
    }

    void willCallTools(List<String> functionNames) {
        script.clear();
        script.add(Reply.toolCalls(functionNames));
    }

    void willReplyBlank() {
        script.clear();
        script.add(Reply.blank());
    }

    void willFail(int status, String providerBody) {
        script.clear();
        script.add(Reply.failure(status, providerBody));
    }

    void willCallToolThenReply(String functionName, Map<String, Object> arguments, String reply) {
        script.clear();
        script.add(Reply.toolCall(functionName, arguments));
        script.add(Reply.text(reply));
    }

    void willCallToolsThenReply(List<String> functionNames, String reply) {
        script.clear();
        script.add(Reply.toolCalls(functionNames));
        script.add(Reply.text(reply));
    }

    void willAlwaysFail(int status, String providerBody) {
        script.clear();
        fallback = Reply.failure(status, providerBody);
    }

    private Reply nextReply() {
        Reply next = script.poll();
        if (next != null) {
            fallback = next;
        }
        return fallback;
    }

    int requestCount() {
        return requests.size();
    }

    List<ObjectNode> requests() {
        return List.copyOf(requests);
    }

    ObjectNode lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    ObjectNode request(int index) {
        return requests.get(index);
    }

    void reset() {
        requests.clear();
        script.clear();
        fallback = Reply.text("stub reply");
    }

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

    boolean allTurnsHaveRole(int index, String role) {
        ArrayNode contents = (ArrayNode) request(index).path("contents");
        for (int i = 0; i < contents.size(); i++) {
            if (!role.equals(contents.get(i).path("role").asText())) {
                return false;
            }
        }
        return true;
    }

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

        private static Reply ok(JsonNode part) {
            return okParts(JSON.createArrayNode().add(part));
        }

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
