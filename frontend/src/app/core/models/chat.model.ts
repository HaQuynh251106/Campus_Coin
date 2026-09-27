/**
 * The conversational assistant — `GET`/`POST /api/v1/chat`.
 *
 * These mirror `com.campuscoin.chat.dto` exactly. Two things about them are deliberate rather than
 * incidental:
 *
 * - **There is no `userId`.** Identity is the bearer token the interceptor attaches. There is no field
 *   here to put one in, so the client cannot accidentally ask about somebody else.
 *
 *   Note what happens if a caller adds one anyway, because the two are easy to conflate: the backend
 *   does **not** reject it. Jackson ignores an unknown property, so a body carrying `userId` is
 *   answered normally — and answered for the *token's* owner. Verified by sending one student's id in
 *   another student's request: the reply held the caller's own figures. The field is inert rather than
 *   forbidden, which is the stronger guarantee, but a client must not be built to expect a `400`.
 * - **There is no `SYSTEM` role.** The assistant's instructions are the server's constant; a client
 *   that could send a system turn could rewrite them. `ChatRole` therefore has two members.
 *
 * `reply` is written by a language model and is always prose. It is **not** derived from any local
 * calculation, and the client must never substitute one of its own when a request fails — see
 * `ChatbotService`'s note on the failure path.
 */

/** Who wrote a turn: the student, or the assistant's earlier reply. */
export type ChatRole = 'USER' | 'ASSISTANT';

/** One turn of the conversation, as sent to the backend. */
export interface ChatTurn {
  role: ChatRole;
  text: string;
}

/** The reply, the model that wrote it, and the figures it read to do so. */
export interface ChatResponse {
  /** The assistant's answer. Never blank — a blank provider response is a `503`, not an empty reply. */
  reply: string;
  /** The model that wrote it, e.g. `gemini-3.5-flash`. Published so the answer can be attributed. */
  model: string;
  /**
   * The reads that grounded the answer, in the order the model asked for them.
   *
   * Empty is a meaningful value, not a gap: it means the answer needed no data, which is the shape an
   * out-of-scope refusal takes. It is never the credential and never a table name.
   */
  toolsUsed: string[];
}

/**
 * Whether a question would reach a provider. `model` is absent when `available` is `false`, and
 * `reason` is absent when it is `true` — so a panel renders either a notice or an input, never both.
 */
export interface ChatAvailability {
  available: boolean;
  /** Absent when unavailable: there is no model to name. */
  model?: string;
  /** A sentence for the student. Names no provider, credential or setting key. */
  reason?: string;
}
