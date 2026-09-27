package com.campuscoin.chat.service;

/**
 * Who spoke a turn of the conversation.
 *
 * <p><b>Two members, and the absence of a third is the security decision.</b> A conversational provider
 * normally takes three roles - a system instruction, the user, and the assistant - and the system
 * instruction is the part that fixes the assistant's rules. If this enum had a {@code SYSTEM} member, a
 * client could send one, and a student could rewrite the instruction that tells the assistant not to
 * discuss other accounts, not to invent figures and not to leave its scope. The instruction is therefore
 * the server's own constant on every call, and the only roles a request may carry are the two that
 * cannot change it.
 *
 * <p>The names match the values the client uses in its own transcript, so a history can be posted back
 * without a translation step on either side.
 */
public enum ChatRole {

    /** The student's own words. */
    USER,

    /** A previous reply of the assistant's. */
    ASSISTANT
}
