package com.campuscoin.common.ai;

/**
 * The conversational port with no provider behind it, and the default when none is configured.
 *
 * <p><b>It refuses, and refusing is the honest answer.</b> This is the one place where the no-op cannot
 * be the quiet, useful thing {@link NoopAiSuggestionPort} is. UC-08 and UC-17 without a provider still
 * work, because the application already computes a category match and a rule-based summary and neither
 * claims to be a model's work. A conversation without a provider has nothing behind it at all: the reply
 * <em>is</em> the feature. Composing one here - from the same figures, in the same friendly voice - would
 * put text in front of the student that reads exactly like an answer and is not one, which is the
 * deception section 15 of the brief forbids.
 *
 * <p>So every call throws, the chat service reports it, and the student is told the assistant is not
 * configured rather than shown a sentence no assistant wrote. The message names no provider and no
 * setting: which credential is missing is the operator's business, not the student's, and
 * {@code GET /api/v1/chat} reports availability separately for anyone who needs to know.
 *
 * <p>Nothing is logged. A deployment with no credential would otherwise write a line per question saying
 * so, and the absence is a supported configuration rather than a fault.
 */
public class NoopChatCompletionPort implements ChatCompletionPort {

    /** What a student is told when this deployment has no conversational provider. */
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
