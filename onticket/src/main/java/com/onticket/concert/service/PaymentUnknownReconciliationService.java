package com.onticket.concert.service;

import com.onticket.concert.dto.VerifiedReservRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

@RequiredArgsConstructor
@Slf4j
@Service
public class PaymentUnknownReconciliationService {

    private final PaymentReconciliationPort reconciliationPort;
    private final CheckoutPaymentVerificationTransactionService verificationTransactionService;
    private final VerifiedReservationTransactionService reservationTransactionService;

    public PaymentReconciliationResult reconcile(String merchantUid) {
        PaymentUnknownReconciliationPreparation preparation = verificationTransactionService
                .claimUnknownForReconciliation(merchantUid);
        if (preparation.isCompleted()) {
            return preparation.existingResult();
        }

        PaymentUnknownReconciliationClaim claim = preparation.claim();
        PaymentReconciliationSnapshot snapshot;
        try {
            snapshot = reconciliationPort.lookup(claim.paymentId());
        } catch (RuntimeException exception) {
            markUnknown(claim);
            log.warn(
                    "Payment reconciliation lookup failed. merchantUid={}, exception={}",
                    claim.merchantUid(),
                    exception.getClass().getSimpleName()
            );
            throw exception;
        }

        if (snapshot == null) {
            markUnknown(claim);
            log.warn("Payment reconciliation returned null. merchantUid={}", claim.merchantUid());
            return PaymentReconciliationResult.manualReviewRequired();
        }
        if (snapshot.status() == PaymentReconciliationStatus.UNRESOLVED) {
            markUnknown(claim);
            return PaymentReconciliationResult.stillUnresolved();
        }
        if (snapshot.status() == PaymentReconciliationStatus.REJECTED) {
            verificationTransactionService.releaseKnownFailure(
                    claim.merchantUid(),
                    claim.username(),
                    claim.concertTimeId(),
                    claim.seatNumbers(),
                    claim.paymentId(),
                    claim.reservationIdempotencyKey(),
                    claim.bookingFingerprint()
            );
            return PaymentReconciliationResult.paymentRejected();
        }

        PaymentApproval approval = snapshot.approval();
        if (!matchesClaim(claim, approval)) {
            markUnknown(claim);
            log.warn("Payment reconciliation approval mismatch. merchantUid={}", claim.merchantUid());
            return PaymentReconciliationResult.manualReviewRequired();
        }

        VerifiedReservRequest request = requestFor(claim);
        try {
            return PaymentReconciliationResult.reservationConfirmed(
                    reservationTransactionService.reserveWithCheckout(
                            claim.username(),
                            claim.concertId(),
                            request,
                            claim.reservationIdempotencyKey(),
                            claim.checkoutFingerprint(),
                            claim.bookingFingerprint(),
                            approval
                    )
            );
        } catch (Exception exception) {
            markUnknown(claim);
            log.warn(
                    "Payment reconciliation could not confirm reservation. merchantUid={}, exception={}",
                    claim.merchantUid(),
                    exception.getClass().getSimpleName()
            );
            return PaymentReconciliationResult.compensationRequired();
        }
    }

    private boolean matchesClaim(
            PaymentUnknownReconciliationClaim claim,
            PaymentApproval approval
    ) {
        return approval != null
                && approval.approved()
                && Objects.equals(approval.paymentId(), claim.paymentId())
                && Objects.equals(approval.merchantUid(), claim.merchantUid())
                && Objects.equals(approval.username(), claim.username())
                && approval.approvedAmount() == claim.expectedAmount()
                && approval.approvedAt() != null;
    }

    private VerifiedReservRequest requestFor(PaymentUnknownReconciliationClaim claim) {
        VerifiedReservRequest request = new VerifiedReservRequest();
        request.setMerchantUid(claim.merchantUid());
        request.setPaymentId(claim.paymentId());
        request.setConcertTimeId(claim.concertTimeId());
        request.setSeatNumberList(claim.seatNumbers());
        return request;
    }

    private void markUnknown(PaymentUnknownReconciliationClaim claim) {
        verificationTransactionService.markUnknown(
                claim.merchantUid(),
                claim.username(),
                claim.paymentId(),
                claim.reservationIdempotencyKey(),
                claim.bookingFingerprint()
        );
    }
}
