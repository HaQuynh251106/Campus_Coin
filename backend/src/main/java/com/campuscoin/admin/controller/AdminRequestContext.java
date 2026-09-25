package com.campuscoin.admin.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client address recorded on an administrative audit row.
 *
 * <p><b>This duplicates a decision that already exists as {@code auth.controller.ClientAddress}, and
 * that is a cost this module accepted deliberately.</b> The two are the same five lines, and the rule
 * they encode - never read {@code X-Forwarded-For}, because it is client-controlled unless a trusted
 * proxy is known to have set it - is now stated in two places. The alternative was to widen
 * {@code ClientAddress} from package-private to public, which would expose an authentication-internal
 * helper to every module for a reason unconnected with authentication. Restating five lines was judged
 * the smaller cost, and it is recorded here and in the module report rather than hidden: if the two
 * ever disagree, the one that should change is the one that stopped reading the proxy header.
 *
 * <p><b>Why not read {@code X-Forwarded-For}.</b> This deployment configures no trusted proxy, so any
 * value in that header is whatever the caller typed, and storing it would put a forged address in the
 * audit column that exists to say who did what. A deployment behind a reverse proxy should enable
 * Spring's {@code ForwardedHeaderFilter}, which applies the rule consistently across the application
 * instead of header-by-header.
 *
 * <p>Read by six controllers, which is why it is one type rather than a call inlined six times: the
 * three endpoints that write an audit row with an address must not disagree about how it is resolved.
 */
final class AdminRequestContext {

    private AdminRequestContext() {
    }

    /**
     * The address to record, from the connection the request arrived on.
     *
     * <p>{@code getRemoteAddr()} is the peer address the container observed, which no request header
     * can influence - the property the audit column needs. It may be empty on some containers for an
     * already-closed connection; that is passed through as-is rather than substituted, because the
     * column is nullable and an absent address should read as absent.
     */
    static String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
