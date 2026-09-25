package com.campuscoin.auth.entity;

/** Mirrors the {@code users.status} ENUM. UC-02 A2 and BR-03 refuse a DISABLED sign-in. */
public enum AccountStatus {
    ACTIVE,
    DISABLED
}
