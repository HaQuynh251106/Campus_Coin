package com.campuscoin.common.ai;

/**
 * The conversational provider could not answer.
 *
 * <p><b>This is the one AI path in the application that fails loudly, and the exception exists to make
 * that possible.</b> UC-08 and UC-17 answer {@link java.util.Optional#empty()} when the provider is
 * unavailable, because both have a deterministic answer the application already computes - a keyword
 * match and a rule-based summary - so an outage there costs the student nothing they can notice. A
 * conversation has no deterministic answer: the reply is the feature. Returning something the
 * application composed instead would put text in front of the student, in the assistant's voice, that
 * no model wrote, which is the outcome section 15 of the brief names and forbids.
 *
 * <p>So this is thrown rather than swallowed, and the chat service turns it into a visible error the
 * student can retry. It carries no cause's message into the response: a provider's error text can quote
 * the request, and {@code GlobalExceptionHandler} already refuses to publish internals. The detail is
 * logged by the implementation that threw it.
 *
 * <p>The message is written to be read by a student, not an operator, because the chat service puts it
 * in the reply the UI shows - so it says what happened and that trying again is reasonable, and it
 * carries no status code, no model name and no credential.
 */
public class AiProviderUnavailableException extends RuntimeException {

    /**
     * @param message a sentence a student can read. It must not name the provider's own status code,
     *                its endpoint, the model or anything else about the deployment.
     * @param cause   the provider's own failure, kept for the log and never returned to a client
     */
    public AiProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
