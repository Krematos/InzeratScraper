package hanzner.inzeratscraper.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Výjimka vyhozená při pokusu o spuštění asynchronního clean jobu,
 * pokud již jeden job běží.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class JobConflictException extends RuntimeException {

    public JobConflictException(String message) {
        super(message);
    }
}
