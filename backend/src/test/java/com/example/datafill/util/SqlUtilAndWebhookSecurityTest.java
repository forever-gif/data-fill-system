package com.example.datafill.util;

import com.example.datafill.config.FeishuProperties;
import com.example.datafill.controller.FeishuApprovalController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SqlUtilAndWebhookSecurityTest {

    @Test
    public void testSqlUtilAcceptsValidIdentifiersAndRejectsInjection() {
        assertEquals("\"public\".\"填报_表-1\"", SqlUtil.quoteTable("public.填报_表-1"));
        assertEquals("\"simple_table\"", SqlUtil.quoteTable("simple_table"));

        assertThrows(IllegalArgumentException.class, () -> SqlUtil.quoteTable("users\"; DROP TABLE users;--"));
        assertThrows(IllegalArgumentException.class, () -> SqlUtil.quoteTable("public.test;select 1"));
        assertThrows(IllegalArgumentException.class, () -> SqlUtil.quoteTable("public.test--comment"));
        assertThrows(IllegalArgumentException.class, () -> SqlUtil.quoteTable("   "));
    }

    @Test
    public void testWebhookVerificationTokenCheck() {
        FeishuProperties props = new FeishuProperties();
        props.getApproval().setVerificationToken("secret-token-123");

        FeishuApprovalController controller = new FeishuApprovalController(props, null, null);

        ResponseEntity<?> rejected = controller.handleWebhook(
                Map.of("challenge", "abc", "token", "wrong-token"), null);
        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode());

        ResponseEntity<?> acceptedV1 = controller.handleWebhook(
                Map.of("challenge", "abc-123", "token", "secret-token-123"), null);
        assertEquals(HttpStatus.OK, acceptedV1.getStatusCode());
        assertEquals(Map.of("challenge", "abc-123"), acceptedV1.getBody());

        ResponseEntity<?> acceptedV2 = controller.handleWebhook(
                Map.of("challenge", "xyz-456", "header", Map.of("token", "secret-token-123")), null);
        assertEquals(HttpStatus.OK, acceptedV2.getStatusCode());
        assertEquals(Map.of("challenge", "xyz-456"), acceptedV2.getBody());
    }
}
