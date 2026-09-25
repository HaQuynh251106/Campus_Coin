package com.campuscoin.common.exception;

/**
 * UC-02 A2 / BR-03: the account is DISABLED, so sign-in is refused.
 *
 * <p>This message is intentionally distinct from {@link InvalidCredentialsException}.
 * UC-02 A2 and B3 require the user to be told the account is disabled and to contact an
 * administrator; only the *email does not exist* versus *wrong password* distinction has to
 * stay hidden. The password is still verified first, so this is not reachable by someone who
 * does not already know the credentials.
 */
public class AccountDisabledException extends ApiException {

    public AccountDisabledException() {
        super(ErrorCode.ACCOUNT_DISABLED,
                "This account is currently disabled. Please contact the administrator.");
    }
}
