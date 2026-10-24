package hanzner.inzeratscraper;

import hanzner.inzeratscraper.config.ConcurrencyConfigValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyConfigValidatorTest {

    @Test
    @DisplayName("Platná výchozí konfigurace úspěšně projde validací")
    void testValidDefaultConfigurationPasses() {
        ConcurrencyConfigValidator validator = new ConcurrencyConfigValidator(3, 10, 5, 15);
        assertDoesNotThrow(validator::validate);
    }

    @Test
    @DisplayName("Neplatná hodnota llm.max-concurrency (0 nebo záporná nebo příliš vysoká) vyhodí IllegalStateException")
    void testInvalidMaxConcurrencyThrows() {
        ConcurrencyConfigValidator zeroValidator = new ConcurrencyConfigValidator(0, 10, 5, 15);
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, zeroValidator::validate);
        assertTrue(ex1.getMessage().contains("llm.max-concurrency"));

        ConcurrencyConfigValidator tooHighValidator = new ConcurrencyConfigValidator(51, 10, 5, 15);
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, tooHighValidator::validate);
        assertTrue(ex2.getMessage().contains("llm.max-concurrency"));
    }

    @Test
    @DisplayName("Neplatná hodnota llm.requests-per-minute vyhodí IllegalStateException")
    void testInvalidRequestsPerMinuteThrows() {
        ConcurrencyConfigValidator zeroValidator = new ConcurrencyConfigValidator(3, 0, 5, 15);
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, zeroValidator::validate);
        assertTrue(ex1.getMessage().contains("llm.requests-per-minute"));

        ConcurrencyConfigValidator tooHighValidator = new ConcurrencyConfigValidator(3, 20_000, 5, 15);
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, tooHighValidator::validate);
        assertTrue(ex2.getMessage().contains("llm.requests-per-minute"));
    }

    @Test
    @DisplayName("Neplatná hodnota llm.max-attempts vyhodí IllegalStateException")
    void testInvalidMaxAttemptsThrows() {
        ConcurrencyConfigValidator zeroValidator = new ConcurrencyConfigValidator(3, 10, 0, 15);
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, zeroValidator::validate);
        assertTrue(ex1.getMessage().contains("llm.max-attempts"));

        ConcurrencyConfigValidator tooHighValidator = new ConcurrencyConfigValidator(3, 10, 25, 15);
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, tooHighValidator::validate);
        assertTrue(ex2.getMessage().contains("llm.max-attempts"));
    }

    @Test
    @DisplayName("Neplatná hodnota scraper.processing-timeout-minutes vyhodí IllegalStateException")
    void testInvalidProcessingTimeoutThrows() {
        ConcurrencyConfigValidator zeroValidator = new ConcurrencyConfigValidator(3, 10, 5, 0);
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, zeroValidator::validate);
        assertTrue(ex1.getMessage().contains("scraper.processing-timeout-minutes"));

        ConcurrencyConfigValidator tooHighValidator = new ConcurrencyConfigValidator(3, 10, 5, 2000);
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, tooHighValidator::validate);
        assertTrue(ex2.getMessage().contains("scraper.processing-timeout-minutes"));
    }
}
