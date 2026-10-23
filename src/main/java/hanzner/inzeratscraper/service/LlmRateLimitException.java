package hanzner.inzeratscraper.service;

import java.io.IOException;

/**
 * Typovaná výjimka vyhozená při vyčerpání pokusů o volání LLM API z důvodu překročení kvóty (HTTP 429).
 * Dědí z IOException pro zachování zpětné kompatibility signatur metod a správnou klasifikaci jako přechodná chyba.
 */
public class LlmRateLimitException extends IOException {

    public LlmRateLimitException(String message) {
        super(message);
    }

    public LlmRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
