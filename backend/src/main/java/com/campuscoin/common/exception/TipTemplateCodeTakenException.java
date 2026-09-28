package com.campuscoin.common.exception;

public class TipTemplateCodeTakenException extends ApiException {

    public TipTemplateCodeTakenException(String message) {
        super(ErrorCode.TIP_TEMPLATE_CODE_TAKEN, message);
    }
}
