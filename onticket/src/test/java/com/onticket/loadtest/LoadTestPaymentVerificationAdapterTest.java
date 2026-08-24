package com.onticket.loadtest;

import com.onticket.concert.service.PaymentApproval;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class LoadTestPaymentVerificationAdapterTest {

    private final LoadTestPaymentVerificationAdapter adapter = new LoadTestPaymentVerificationAdapter();

    @Test
    void acceptsProfileScopedPaymentFixture() {
        PaymentApproval approval = adapter.verify("LT:load-user-001:30000:request-1");

        assertThat(approval.approved()).isTrue();
        assertThat(approval.username()).isEqualTo("load-user-001");
        assertThat(approval.approvedAmount()).isEqualTo(30_000);
        assertThat(approval.approvedAt()).isNotNull();
    }

    @Test
    void rejectsMalformedPaymentFixture() {
        assertThat(adapter.verify("real-payment-id").approved()).isFalse();
        assertThat(adapter.verify("LT:load-user-001:invalid:request-1").approved()).isFalse();
        assertThat(adapter.verify("LT:load-user-001:0:request-1").approved()).isFalse();
        assertThat(adapter.verify(null).approved()).isFalse();
    }

    @Test
    void registersOnlyWithLoadtestProfile() {
        try (AnnotationConfigApplicationContext defaultContext = new AnnotationConfigApplicationContext()) {
            defaultContext.register(LoadTestPaymentVerificationAdapter.class);
            defaultContext.refresh();

            assertThat(defaultContext.getBeansOfType(LoadTestPaymentVerificationAdapter.class)).isEmpty();
        }

        try (AnnotationConfigApplicationContext loadtestContext = new AnnotationConfigApplicationContext()) {
            loadtestContext.getEnvironment().setActiveProfiles("loadtest");
            loadtestContext.register(LoadTestPaymentVerificationAdapter.class);
            loadtestContext.refresh();

            assertThat(loadtestContext.getBeansOfType(LoadTestPaymentVerificationAdapter.class)).hasSize(1);
        }
    }
}
