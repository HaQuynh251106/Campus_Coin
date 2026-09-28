package com.campuscoin.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campuscoin.encryption")
public record EncryptionProperties(String key, Integer keyVersion) {
}
