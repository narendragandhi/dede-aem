package com.dede.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    void redactsCredentialsWhereverTheyAppear() {
        assertThat(SecretRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhIn0.c2ln failed"))
            .isEqualTo("Authorization: Bearer [REDACTED] failed");
        assertThat(SecretRedactor.redact("got eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhIn0.c2lnbmF0dXJl back")).isEqualTo("got [REDACTED] back");
        assertThat(SecretRedactor.redact("login password=hunter2 for bob")).isEqualTo("login password=[REDACTED] for bob");
        assertThat(SecretRedactor.redact("clientSecret=abc123, retry")).isEqualTo("clientSecret=[REDACTED], retry");
        assertThat(SecretRedactor.redact("{\"api.password\": \"p w\", \"x\": 1}")).isEqualTo("{\"api.password\": [REDACTED], \"x\": 1}");
        assertThat(SecretRedactor.redact("api_key: 12345")).isEqualTo("api_key: [REDACTED]");
        assertThat(SecretRedactor.redact("Password: letmein")).isEqualTo("Password: [REDACTED]");
        assertThat(SecretRedactor.redact("key AKIAIOSFODNN7EXAMPLE used")).isEqualTo("key [REDACTED] used");
        assertThat(SecretRedactor.redact("stripe sk_live_51HxYzABCDEFGH ok")).isEqualTo("stripe [REDACTED] ok");
        assertThat(SecretRedactor.redact("jdbc https://admin:s3cr3t@db.example/x")).isEqualTo("jdbc https://admin:[REDACTED]@db.example/x");
        assertThat(SecretRedactor.redact("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY----- done")).isEqualTo("[REDACTED] done");
        assertThat(SecretRedactor.redact("mail from alice.smith@example.co.uk bounced")).isEqualTo("mail from [EMAIL] bounced");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "class:com.acme.TokenService",
        "method:com.acme.auth.TokenService.getToken()",
        "com.acme.InvalidTokenException: session expired",
        "org.acme.CredentialStore: cache refreshed in 12 ms",
        "res:acme/components/password-reset",
        "/content/acme/en/forgot-password.html",
        "vuln:forbidden-api:core_src_Main.java:12:loginAdministrative",
        "Could not adapt to model com.acme.TeaserModel at /content/acme/en",
        "16.11.2026 10:00:00.000",
        "@Component(service = Servlet.class)"})
    void leavesNamesPathsAndOrdinaryMessagesAlone(String text) {
        assertThat(SecretRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void recognisesCredentialFieldNames() {
        assertThat(SecretRedactor.isSensitiveName("config.api.password")).isTrue();
        assertThat(SecretRedactor.isSensitiveName("clientSecret")).isTrue();
        assertThat(SecretRedactor.isSensitiveName("config.api.endpoint")).isFalse();
        assertThat(SecretRedactor.isSensitiveName(null)).isFalse();
        assertThat(SecretRedactor.redact(null)).isNull();
    }
}
