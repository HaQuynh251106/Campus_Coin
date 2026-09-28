package com.campuscoin.common.exception;

public class ThresholdNotAdjustableException extends ApiException {

    public ThresholdNotAdjustableException(String message) {
        super(ErrorCode.THRESHOLD_NOT_ADJUSTABLE, message);
    }
}
