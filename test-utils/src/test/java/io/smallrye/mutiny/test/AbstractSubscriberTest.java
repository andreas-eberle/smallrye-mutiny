package io.smallrye.mutiny.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class AbstractSubscriberTest {

    @Test
    public void testOnNext() {
        List<String> items = new ArrayList<>();
        AbstractSubscriber<String> subscriber = new AbstractSubscriber<String>() {
            @Override
            public void onNext(String o) {
                items.add(o);
            }
        };

        subscriber.onNext("a");
        subscriber.onNext("b");
        subscriber.onComplete();
        assertThat(items).containsExactly("a", "b");
    }

    @Test
    public void testOnError() {
        AtomicBoolean called = new AtomicBoolean();
        AbstractSubscriber<String> subscriber = new AbstractSubscriber<String>() {
            @Override
            public void onError(Throwable t) {
                called.set(true);
            }
        };

        subscriber.onNext("a");
        subscriber.onNext("b");
        subscriber.onError(new Exception("boom"));
        assertThat(called).isTrue();
    }

    @Test
    public void testOnComplete() {
        AtomicBoolean called = new AtomicBoolean();
        AbstractSubscriber<String> subscriber = new AbstractSubscriber<String>() {
            @Override
            public void onComplete() {
                called.set(true);
            }
        };

        subscriber.onNext("a");
        subscriber.onNext("b");
        subscriber.onComplete();
        assertThat(called).isTrue();
    }

    @Test
    public void testSubscription() {
        Subscription subscription = mock(Subscription.class);
        AbstractSubscriber<String> subscriber = new AbstractSubscriber<>();

        subscriber.onSubscribe(subscription);
        subscriber.request(10);
        verify(subscription, times(1)).request(10);
    }

    @Test
    public void testSubscriptionUpstream() {
        Subscription subscription = mock(Subscription.class);
        AbstractSubscriber<String> subscriber = new AbstractSubscriber<>(2);

        subscriber.onSubscribe(subscription);
        verify(subscription, times(1)).request(2);
        subscriber.request(10);
        verify(subscription, times(1)).request(10);
        subscriber.cancel();
        verify(subscription, times(1)).cancel();
    }

    @Test
    public void testRequestWithoutSubscription() {
        AbstractSubscriber<String> subscriber = new AbstractSubscriber<>(2);
        assertThatThrownBy(() -> subscriber.request(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(subscriber::cancel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testOnSubscribedCalledTwice() {
        Subscription subscription = mock(Subscription.class);
        AbstractSubscriber<String> subscriber = new AbstractSubscriber<>(2);
        subscriber.onSubscribe(subscription);
        assertThatThrownBy(() -> subscriber.onSubscribe(subscription)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testIsCancelledWithUpfrontCancellation() {
        AssertSubscriber<Integer> subscriber = new AssertSubscriber<>(10, true);
        assertThat(subscriber.isCancelled()).isFalse();

        Subscription subscription = mock(Subscription.class);
        subscriber.onSubscribe(subscription);

        assertThat(subscriber.isCancelled()).isTrue();
        verify(subscription).cancel();
        verify(subscription, never()).request(anyLong());
    }

    @Test
    public void testIsCancelledWithCancellation() {
        AssertSubscriber<Integer> subscriber = new AssertSubscriber<>(10, false);
        assertThat(subscriber.isCancelled()).isFalse();

        subscriber.assertNotSubscribed()
                .assertNotTerminated();
        assertThat(subscriber.isCancelled()).isFalse();

        Subscription subscription = mock(Subscription.class);
        subscriber.onSubscribe(subscription);

        assertThat(subscriber.isCancelled()).isFalse();
        verify(subscription).request(10);

        subscriber.cancel();
        verify(subscription).cancel();
        assertThat(subscriber.isCancelled()).isTrue();
    }

    @Test
    public void testSpyWithItemAndCompletion() {
        Subscriber<Integer> spy = Mocks.subscriber(20);
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(spy);

        Subscription subscription = mock(Subscription.class);
        subscriber.onSubscribe(subscription);
        verify(spy).onSubscribe(subscription);
        verify(subscription).request(20);

        subscriber.onNext(1);
        subscriber.onNext(2);
        subscriber.onNext(3);
        subscriber.onComplete();

        verify(spy).onNext(1);
        verify(spy).onNext(2);
        verify(spy).onNext(3);
        verify(spy).onComplete();
        verify(spy, never()).onError(any(Throwable.class));

        assertThat(subscriber.failures()).isEmpty();
    }

    @Test
    public void testSpyWithItemAndFailure() {
        Subscriber<Integer> spy = Mocks.subscriber(20);
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(spy);

        Subscription subscription = mock(Subscription.class);
        subscriber.onSubscribe(subscription);
        verify(spy).onSubscribe(subscription);
        verify(subscription).request(20);

        subscriber.onNext(1);
        subscriber.onNext(2);
        subscriber.onNext(3);
        subscriber.onError(new IOException("boom"));

        verify(spy).onNext(1);
        verify(spy).onNext(2);
        verify(spy).onNext(3);
        verify(spy, never()).onComplete();
        verify(spy).onError(any(IOException.class));

        assertThat(subscriber.failures()).hasSize(1);
    }

    @Test
    public void testAwaitWithTimeout() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(10);

        assertThatThrownBy(() -> subscriber.await(Duration.ofMillis(1))).isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> await()
                .pollDelay(Duration.ofMillis(1))
                .atMost(Duration.ofMillis(2)).untilAsserted(subscriber::await)).isInstanceOf(ConditionTimeoutException.class);
    }

    @Test
    public void testAwaitWithInterruption() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(10);

        AtomicBoolean unblocked = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            subscriber.await(Duration.ofSeconds(100));
            unblocked.set(true);
        });
        thread.start();
        thread.interrupt();

        await().untilTrue(unblocked);

        unblocked.set(false);
        thread = new Thread(() -> {
            subscriber.await();
            unblocked.set(true);
        });
        thread.start();
        thread.interrupt();

        await().untilTrue(unblocked);
    }

    @Test(timeout = 10)
    public void testAwaitWhenAlreadyCompleted() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(10);
        subscriber.onComplete();

        subscriber.await(Duration.ofSeconds(100));
        subscriber.await();

        subscriber.assertCompletedSuccessfully();

    }

    @Test(timeout = 10)
    public void testAwaitWhenAlreadyFailed() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(10);
        subscriber.onError(new IOException("boom"));

        subscriber.await(Duration.ofSeconds(100));
        subscriber.await();

        subscriber.assertHasFailedWith(IOException.class, "boom");

    }

}
