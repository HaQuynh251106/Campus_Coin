package com.campuscoin.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.campuscoin.auth.entity.UserSession;

/**
 * MySQL access for {@code user_sessions}.
 *
 * <p>The token filter needs the session row for every authenticated request, so it looks the
 * session up by its unique token hash - the {@code uk_sessions_token} key - rather than by
 * primary key. Only the hash is ever queried; the raw JWT is never stored or looked up.
 */
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findBySessionTokenHash(String sessionTokenHash);
}
