package com.example.finalproject.global.sse.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseEmitterRepositoryTest {

    private static final Long USER_ID = 1L;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void remove_doesNotHideEmitterSavedWhileCleanupIsFinishing() throws Exception {
        SseEmitterRepository repository = new SseEmitterRepository();
        repository.save(USER_ID, "old", new SseEmitter());

        BlockingEmptySet emitterIds = replaceEmitterIdsWithBlockingSet(repository, "old");
        Future<?> removeFuture = executor.submit(() -> repository.remove(USER_ID, "old"));
        assertThat(emitterIds.awaitEmptyCheck()).isTrue();

        emitterIds.watchNewEmitterAdd();
        Future<?> saveFuture = executor.submit(() -> repository.save(USER_ID, "new", new SseEmitter()));
        boolean saveMutatedSetBeingRemoved = emitterIds.awaitNewEmitterAdd();

        emitterIds.releaseEmptyCheck();
        try {
            removeFuture.get(1, TimeUnit.SECONDS);
            saveFuture.get(1, TimeUnit.SECONDS);
        } finally {
            emitterIds.releaseEmptyCheck();
        }

        assertThat(saveMutatedSetBeingRemoved).isFalse();
        assertThat(repository.getEmitterIds(USER_ID)).containsExactly("new");
    }

    @SuppressWarnings("unchecked")
    private BlockingEmptySet replaceEmitterIdsWithBlockingSet(
            SseEmitterRepository repository,
            String existingEmitterId) throws Exception {

        Field userEmittersField = SseEmitterRepository.class.getDeclaredField("userEmitters");
        userEmittersField.setAccessible(true);
        Map<Long, Set<String>> userEmitters = (Map<Long, Set<String>>) userEmittersField.get(repository);

        BlockingEmptySet emitterIds = new BlockingEmptySet();
        emitterIds.add(existingEmitterId);
        userEmitters.put(USER_ID, emitterIds);
        return emitterIds;
    }

    private static class BlockingEmptySet extends AbstractSet<String> {

        private final Set<String> delegate = ConcurrentHashMap.newKeySet();
        private final CountDownLatch emptyCheckStarted = new CountDownLatch(1);
        private final CountDownLatch allowEmptyCheckToReturn = new CountDownLatch(1);
        private final CountDownLatch watchNewEmitterAdd = new CountDownLatch(1);
        private final CountDownLatch newEmitterAddStarted = new CountDownLatch(1);

        @Override
        public Iterator<String> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean add(String value) {
            if (watchNewEmitterAdd.getCount() == 0) {
                newEmitterAddStarted.countDown();
            }
            return delegate.add(value);
        }

        @Override
        public boolean remove(Object value) {
            return delegate.remove(value);
        }

        @Override
        public boolean isEmpty() {
            boolean wasEmpty = delegate.isEmpty();
            emptyCheckStarted.countDown();
            try {
                allowEmptyCheckToReturn.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return wasEmpty;
        }

        private boolean awaitEmptyCheck() throws InterruptedException {
            return emptyCheckStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseEmptyCheck() {
            allowEmptyCheckToReturn.countDown();
        }

        private void watchNewEmitterAdd() {
            watchNewEmitterAdd.countDown();
        }

        private boolean awaitNewEmitterAdd() throws InterruptedException {
            return newEmitterAddStarted.await(1, TimeUnit.SECONDS);
        }
    }
}
