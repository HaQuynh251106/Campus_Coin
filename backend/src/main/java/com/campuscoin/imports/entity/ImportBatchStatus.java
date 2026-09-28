package com.campuscoin.imports.entity;

public enum ImportBatchStatus {

    UPLOADED,

    PREVIEWED,

    COMMITTED,

    CANCELLED,

    FAILED;

    public boolean isOpen() {
        return this == UPLOADED || this == PREVIEWED;
    }
}
