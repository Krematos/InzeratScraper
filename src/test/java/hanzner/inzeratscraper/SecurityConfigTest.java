package hanzner.inzeratscraper;

import hanzner.inzeratscraper.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigTest {

    @Test
    void testLoopbackAllowedWhenNoApiKeyConfigured() {
        SecurityConfig config = new SecurityConfig("", false);

        MockHttpServletRequest localhostReq = new MockHttpServletRequest();
        localhostReq.setRemoteAddr("127.0.0.1");
        assertTrue(config.isAuthorizedMigrationRequest(localhostReq));

        MockHttpServletRequest ipv6LocalhostReq = new MockHttpServletRequest();
        ipv6LocalhostReq.setRemoteAddr("::1");
        assertTrue(config.isAuthorizedMigrationRequest(ipv6LocalhostReq));

        MockHttpServletRequest externalReq = new MockHttpServletRequest();
        externalReq.setRemoteAddr("192.168.1.50");
        assertFalse(config.isAuthorizedMigrationRequest(externalReq));
    }

    @Test
    void testApiKeyRequiredWhenConfigured() {
        SecurityConfig config = new SecurityConfig("secret-migration-key", false);

        MockHttpServletRequest missingHeaderReq = new MockHttpServletRequest();
        missingHeaderReq.setRemoteAddr("127.0.0.1");
        assertFalse(config.isAuthorizedMigrationRequest(missingHeaderReq));

        MockHttpServletRequest wrongKeyReq = new MockHttpServletRequest();
        wrongKeyReq.setRemoteAddr("127.0.0.1");
        wrongKeyReq.addHeader("X-API-Key", "wrong-key");
        assertFalse(config.isAuthorizedMigrationRequest(wrongKeyReq));

        MockHttpServletRequest validKeyReq = new MockHttpServletRequest();
        validKeyReq.setRemoteAddr("127.0.0.1");
        validKeyReq.addHeader("X-API-Key", "secret-migration-key");
        assertTrue(config.isAuthorizedMigrationRequest(validKeyReq));
    }
}
