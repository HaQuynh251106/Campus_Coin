package com.campuscoin.common.ai;

import java.util.List;
import java.util.Map;

import com.google.genai.types.FunctionDeclaration;

public interface ChatCompletionPort {

    ChatCompletion converse(ChatCompletionRequest request);

    boolean isExternalProvider();

    default String modelName() {
        return null;
    }

    @FunctionalInterface
    interface ToolInvoker {

        Map<String, Object> invoke(String functionName, Map<String, Object> arguments);
    }

    record ChatTurn(ChatRole role, String text) {

        public enum ChatRole {
            USER,
            ASSISTANT
        }
    }

    record ChatCompletionRequest(List<ChatTurn> history,
                                 ToolInvoker tools,
                                 List<FunctionDeclaration> declarations,
                                 int maxToolRounds) {
    }

    record ChatCompletion(String text, String model, List<String> toolsUsed) {
    }
}
