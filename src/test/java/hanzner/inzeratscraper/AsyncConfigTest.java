package hanzner.inzeratscraper;

import hanzner.inzeratscraper.config.AsyncConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class);

    @Test
    @DisplayName("llmExecutor poskytuje virtuální vlákna pro zpracování úloh")
    void llmExecutor_ShouldExecuteOnVirtualThreads() throws ExecutionException, InterruptedException {
        AsyncConfig config = new AsyncConfig();
        try (ExecutorService executor = config.llmExecutor()) {
            assertNotNull(executor);
            Future<Boolean> future = executor.submit(() -> Thread.currentThread().isVirtual());
            assertTrue(future.get(), "Úloha by měla běžet na virtuálním vlákně");
        }
    }

    @Test
    @DisplayName("AsyncConfig registruje bean llmExecutor typu ExecutorService do kontextu")
    void contextRegistersLlmExecutorBean() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("llmExecutor");
            ExecutorService executor = context.getBean("llmExecutor", ExecutorService.class);
            assertThat(executor).isNotNull();
            Future<Boolean> future = executor.submit(() -> Thread.currentThread().isVirtual());
            assertThat(future.get()).isTrue();
        });
    }
}
