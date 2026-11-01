package com.drawspace.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class WebSocketSessionContextRegistryTest {

    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PARTICIPANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    private final WebSocketSessionContextRegistry registry = new WebSocketSessionContextRegistry();

    private WebSocketSessionContext context() {
        return new WebSocketSessionContext(
                ROOM_ID, "ABC234", PARTICIPANT_ID, "Alice", "#EF4444");
    }

    @Test
    void registerFindAndRemoveRoundTrip() {
        WebSocketSessionContext context = context();

        registry.register("ws-1", context);

        assertThat(registry.find("ws-1")).isEqualTo(Optional.of(context));
        assertThat(registry.remove("ws-1")).isSameAs(context);
        assertThat(registry.find("ws-1")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void multipleSessionsForSameParticipantAreIndependent() {
        WebSocketSessionContext tab1 = context();
        WebSocketSessionContext tab2 = context();

        registry.register("tab-1", tab1);
        registry.register("tab-2", tab2);

        assertThat(registry.find("tab-1")).contains(tab1);
        assertThat(registry.find("tab-2")).contains(tab2);

        registry.remove("tab-1");

        assertThat(registry.find("tab-1")).isEmpty();
        assertThat(registry.find("tab-2")).contains(tab2);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> registry.register(null, context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("  ", context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("ws-1", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("")).isEmpty();
        assertThat(registry.remove(null)).isNull();
    }

    @Test
    void concurrentRegistrationAndRemovalAcrossManySessions() throws Exception {
        int threads = 16;
        int sessionsPerThread = 50;
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < sessionsPerThread; i++) {
                        String sessionId = "ws-" + threadIndex + "-" + i;
                        registry.register(sessionId, context());
                        if (registry.find(sessionId).isEmpty()) {
                            failures.add("missing after register: " + sessionId);
                        }
                        if (registry.remove(sessionId) == null) {
                            failures.add("missing on remove: " + sessionId);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(failures).isEmpty();
        assertThat(registry.size()).isZero();
    }
}
