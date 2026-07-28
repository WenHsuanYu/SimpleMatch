package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.risk.v1.CancelOrderRequest;
import com.simplematch.contracts.risk.v1.CancelOrderResponse;
import com.simplematch.contracts.risk.v1.RiskServiceGrpc;
import com.simplematch.contracts.risk.v1.SubmitOrderRequest;
import com.simplematch.contracts.risk.v1.SubmitOrderResponse;
import io.grpc.ManagedChannel;

import java.util.concurrent.TimeUnit;

public final class GrpcRiskSubmissionClient implements RiskSubmissionClient {
    private final RiskServiceGrpc.RiskServiceBlockingStub blockingStub;
    private final long deadlineMillis;

    public GrpcRiskSubmissionClient(ManagedChannel managedChannel, long deadlineMillis) {
        this.blockingStub = RiskServiceGrpc.newBlockingStub(managedChannel);
        this.deadlineMillis = deadlineMillis;
    }

    @Override
    public RiskSubmissionResult submitNewOrder(OrderCommand command) {
        final SubmitOrderResponse response = blockingStub
                .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                .submitOrder(SubmitOrderRequest.newBuilder().setCommand(command).build());
        return new RiskSubmissionResult(response.getOrderId(), response.getAccepted(), response.getReasonCode(), response.getReasonText());
    }

    @Override
    public RiskSubmissionResult submitCancel(OrderCommand command) {
        final CancelOrderResponse response = blockingStub
                .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                .cancelOrder(CancelOrderRequest.newBuilder().setCommand(command).build());
        return new RiskSubmissionResult(response.getOrderId(), response.getAccepted(), response.getReasonCode(), response.getReasonText());
    }
}