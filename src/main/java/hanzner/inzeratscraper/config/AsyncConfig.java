package hanzner.inzeratscraper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Konfigurace asynchronního zpracování a exekutorů.
 * Poskytuje exekutor na virtuálních vláknech určený pro paralelní I/O operace (volání LLM API).
 */
@Configuration
public class AsyncConfig {

    /**
     * Exekutor na virtuálních vláknech pro paralelní zpracování inzerátů přes LLM.
     * Souběh neomezuje pool vláken, nýbrž semafor v LLM klientovi.
     * Při ukončení kontextu se zavolá close(), které zajistí korektní shutdown a awaitTermination.
     */
    @Bean(name = "llmExecutor", destroyMethod = "close")
    public ExecutorService llmExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
