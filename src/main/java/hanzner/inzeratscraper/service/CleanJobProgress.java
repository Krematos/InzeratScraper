package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.entity.AdProcessingStatus;

/**
 * Rozhraní pro sledování průběhu čištění inzerátů.
 */
public interface CleanJobProgress {

    /**
     * Zpětné volání po zjištění celkového počtu kandidátů k čištění.
     */
    void onTotalKnown(int total);

    /**
     * Zpětné volání po dokončení čištění jednoho inzerátu.
     */
    void onItemCompleted(AdProcessingStatus status);
}
