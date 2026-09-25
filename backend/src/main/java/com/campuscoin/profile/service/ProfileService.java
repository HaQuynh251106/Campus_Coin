package com.campuscoin.profile.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.entity.User;
import com.campuscoin.auth.repository.UserRepository;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.profile.dto.ProfileResponse;
import com.campuscoin.profile.dto.UpdatePreferencesRequest;
import com.campuscoin.profile.dto.UpdateProfileRequest;
import com.campuscoin.profile.mapper.ProfileMapper;

/**
 * Reads and updates the signed-in student's own profile and preferences (UC-04, UC-27).
 *
 * <p>What the database owns, and this class therefore does not repeat: {@code ck_users_money}
 * rejects a negative money value and the {@code users} ENUMs reject an unknown theme or font
 * scale. Those are enforced twice on purpose - the request DTO validates first so the caller
 * receives a per-field error, and the database stays the authority if a write ever bypassed the
 * DTO.
 *
 * <p>What is genuinely this class's: deciding which columns a profile request may touch, and
 * deciding <em>whose</em> row it touches.
 *
 * <p>No procedure or trigger on {@code users} writes a profile column - the only database objects
 * that read them are {@code v_dashboard_summary} and {@code sp_generate_tips} - so performing the
 * write is the application's job.
 *
 * <p><b>Concurrent writes are handled by the entity's {@code @DynamicUpdate} rather than by a
 * lock.</b> A request sets only the fields it named and the {@code UPDATE} therefore names only
 * those columns, so two concurrent edits to different fields both survive; two edits to the same
 * field are last-writer-wins, which is what "set this value" means when two callers ask for it at
 * once. Both behaviours are asserted in {@code ProfileApiIT}. A row lock was considered and
 * rejected: it would serialise profile edits against each other and against every sign-in (which
 * writes {@code last_login_at} on the same row) without changing any outcome a caller observes.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserRepository userRepository;
    private final ProfileMapper profileMapper;

    public ProfileService(UserRepository userRepository, ProfileMapper profileMapper) {
        this.userRepository = userRepository;
        this.profileMapper = profileMapper;
    }

    /**
     * UC-04, UC-27: the caller's own profile.
     *
     * <p>The account is read from the database rather than reconstructed from the token. The token
     * carries the name it was issued with, which is older than the row after a profile edit;
     * reading the row is what makes an update visible on the next request without re-signing in.
     *
     * <p>{@code readOnly = true} documents that nothing is written and lets the persistence
     * provider skip dirty-checking.
     *
     * @throws NotFoundException if the account named by the token no longer exists
     */
    @Transactional(readOnly = true)
    public ProfileResponse getProfile(AuthenticatedUser principal) {
        return profileMapper.toProfile(requireCaller(principal));
    }

    /**
     * UC-04: update the caller's profile fields.
     *
     * <p>Every field is optional and a field left out is not changed, so a client may send only
     * what it edited. A field sent as {@code null} means the same thing - not changed - with one
     * deliberate exception: {@code academicYear} is nullable in the schema, so sending it as an
     * empty string ({@code ""}) clears it. That gives the client a way to unset a value it once
     * set, without a second endpoint, and keeps "not mentioned" and "clear this" distinguishable.
     * The three other fields have no empty state - there is no meaningful blank name or
     * allowance - so an empty string there is rejected by the DTO.
     *
     * <p>Only the four UC-04 columns are touched. The entity exposes no setter for {@code email},
     * {@code password_hash}, {@code role}, {@code status} or {@code token_version}, so no profile
     * request can reach them.
     *
     * @param principal the caller, whose identity comes from the verified token, never the body
     * @param request   the fields to change
     * @throws NotFoundException if the account named by the token no longer exists
     */
    @Transactional
    public ProfileResponse updateProfile(AuthenticatedUser principal, UpdateProfileRequest request) {
        User user = requireCaller(principal);

        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
        }
        if (request.academicYear() != null) {
            // Trimmed before the emptiness test so "   " clears the field rather than storing
            // three spaces, which would look empty on screen and compare unequal to "".
            String academicYear = request.academicYear().trim();
            user.setAcademicYear(academicYear.isEmpty() ? null : academicYear);
        }
        if (request.monthlyAllowanceBaseline() != null) {
            user.setMonthlyAllowanceBaseline(request.monthlyAllowanceBaseline());
        }
        if (request.monthlySavingsGoal() != null) {
            user.setMonthlySavingsGoal(request.monthlySavingsGoal());
        }

        // The UPDATE is issued by the persistence provider when the transaction commits, and
        // @DynamicUpdate on the entity restricts it to the columns that actually changed. That is
        // what stops a profile edit from writing back a stale status or token_version and undoing
        // a concurrent revocation - see the note on the entity.
        log.info("Profile updated userId={}", user.getId());
        return profileMapper.toProfile(user);
    }

    /**
     * UC-27: update the caller's display preferences.
     *
     * <p>Both fields are optional for the same reason as above: changing the appearance should not
     * require resending the text size. Neither has an empty state - the schema stores them as
     * ENUMs with no empty member - so a blank value is rejected while the body is bound, and only
     * a recognised member reaches this method.
     *
     * @param principal the caller
     * @param request   the preferences to change
     * @throws NotFoundException if the account named by the token no longer exists
     */
    @Transactional
    public ProfileResponse updatePreferences(AuthenticatedUser principal,
                                             UpdatePreferencesRequest request) {
        User user = requireCaller(principal);

        if (request.themePreference() != null) {
            user.setThemePreference(request.themePreference());
        }
        if (request.fontScale() != null) {
            user.setFontScale(request.fontScale());
        }

        log.info("Preferences updated userId={}", user.getId());
        return profileMapper.toProfile(user);
    }

    /**
     * Loads the caller's own row.
     *
     * <p>UC-04 and UC-27 have no path parameter and no body field naming a user, so the only
     * identity this module can act on is the one in the verified token. That is what makes BR-02
     * structural here rather than a check someone could forget to write: there is no user id in
     * the request to tamper with. The lookup exists to confirm the account still exists - the
     * token filter has already rejected a disabled account or a revoked session.
     *
     * @throws NotFoundException if the row is gone
     */
    private User requireCaller(AuthenticatedUser principal) {
        return userRepository.findById(principal.userId())
                .orElseThrow(() -> new NotFoundException("Your account could not be found."));
    }
}
