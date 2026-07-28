package com.simplematch.accountservice.grpc;

import io.grpc.stub.StreamObserver;

final class TestStreamObserver<T> implements StreamObserver<T> {
    private T value;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(T value) {
        this.value = value;
    }

    @Override
    public void onError(Throwable throwable) {
        this.error = throwable;
    }

    @Override
    public void onCompleted() {
        this.completed = true;
    }

    T value() {
        return value;
    }

    Throwable error() {
        return error;
    }

    boolean completed() {
        return completed;
    }
}