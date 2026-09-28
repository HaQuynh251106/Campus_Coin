package com.campuscoin.common.ai;

public class NoopChatCompletionPort implements ChatCompletionPort {

    static final String NOT_CONFIGURED_REPLY =
            "The assistant isn't available on this installation right now, so I can't answer that. "
                    + "Everything else in Campus Coin works as usual - your dashboard, budgets and "
                    + "reports are all in the menu.";

    @Override
    public ChatCompletion converse(ChatCompletionRequest request) {
        throw new AiProviderUnavailableException(NOT_CONFIGURED_REPLY, null);
    }

    @Override
    public boolean isExternalProvider() {
        return false;
    }
}
