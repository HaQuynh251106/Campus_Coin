package com.campuscoin.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.campuscoin.auth.entity.User;

/**
 * MySQL access for {@code users}.
 *
 * <p>Sign-in looks an account up by email, and registration checks whether an email is already
 * taken. The case-insensitive comparison is not done here: the column's collation
 * ({@code utf8mb4_0900_ai_ci}) already makes {@code uk_users_email} case-insensitive, and the
 * service normalises to lower case before writing, so a plain equality query matches both.
 *
 * <p>No query in this module filters by role or status at the SQL level. Authorisation and the
 * disabled-account rule are decided in the service so that each has one implementation and one
 * error code - pushing them into SQL would scatter the same rule across two layers.
 *
 * <p><b>There is deliberately no {@code SELECT ... FOR UPDATE} here, and the concurrent-update
 * behaviour is instead proved by test.</b> A profile or preference edit sets only the fields the
 * request named, and {@code @DynamicUpdate} on {@link User} restricts the {@code UPDATE} to exactly
 * those columns - so two concurrent edits touching different columns both survive rather than one
 * overwriting the other, which is the lost update worth preventing. Two edits to the <em>same</em>
 * column are last-writer-wins, which is the correct meaning of two requests that each asked to set
 * it. A lock would therefore add contention on the {@code users} row - the same row every sign-in
 * writes {@code last_login_at} to - without changing any outcome a caller can observe. See
 * {@code ProfileApiIT#concurrentEditsToDifferentColumnsAreBothKept}.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
