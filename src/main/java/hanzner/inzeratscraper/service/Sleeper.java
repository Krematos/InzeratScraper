package hanzner.inzeratscraper.service;

/**
 * Rozhraní pro pozastavení vlákna (spánek).
 * Umožňuje odstínit reálný Thread.sleep v unit testech pro okamžité a deterministické ověření.
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * Uspí aktuální vlákno na zadaný počet milisekund.
     *
     * @param millis počet milisekund k čekání
     * @throws InterruptedException pokud bylo vlákno během spánku přerušeno
     */
    void sleep(long millis) throws InterruptedException;
}
