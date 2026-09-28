package com.campuscoin.common.exception;

public class TipTemplateCodeImmutableException extends ApiException {

    public TipTemplateCodeImmutableException(String message) {
        super(ErrorCode.TIP_TEMPLATE_CODE_IMMUTABLE, message);
    }
}
