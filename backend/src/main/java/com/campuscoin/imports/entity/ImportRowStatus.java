package com.campuscoin.imports.entity;

public enum ImportRowStatus {

    VALID,

    ERROR,

    DUPLICATE,

    IMPORTED,

    SKIPPED;

    public boolean isImportable() {
        return this == VALID;
    }
}
