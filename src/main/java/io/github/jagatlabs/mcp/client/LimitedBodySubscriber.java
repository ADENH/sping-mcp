package io.github.jagatlabs.mcp.client;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Enforces the limit while receiving, including chunked bodies without Content-Length. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private long received;
    private Flow.Subscription subscription;
    private boolean failed;

    LimitedBodySubscriber(int limit) { this.limit = limit; }
    @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    @Override public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }
    @Override public void onNext(List<ByteBuffer> buffers) {
        if (failed) return;
        received += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
        if (received > limit) {
            failed = true;
            subscription.cancel();
            delegate.onError(new JagatlabsException("INVALID_UPSTREAM_RESPONSE"));
        } else delegate.onNext(buffers);
    }
    @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
    @Override public void onComplete() { if (!failed) delegate.onComplete(); }
}
