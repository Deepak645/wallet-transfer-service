package com.paytm.wallet;

import com.paytm.wallet.controller.TestFundingController;
import com.paytm.wallet.service.TestFundingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the "disabled means the endpoint doesn't exist" gating directly,
 * without booting the full application (no Testcontainers/DB needed): when
 * test.funding.enabled isn't "true", TestFundingController must not be
 * registered as a bean at all, so Spring's dispatcher has no mapping for
 * /test/wallets/{id}/fund and any request to it 404s naturally - see
 * TestFundingController's @ConditionalOnProperty.
 */
class TestFundingConditionalRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(TestFundingService.class, () -> mock(TestFundingService.class))
            .withUserConfiguration(TestFundingController.class);

    @Test
    void controllerNotRegisteredWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues("test.funding.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TestFundingController.class));
    }

    @Test
    void controllerNotRegisteredWhenPropertyMissing() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(TestFundingController.class));
    }

    @Test
    void controllerRegisteredWhenEnabled() {
        contextRunner.withPropertyValues("test.funding.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TestFundingController.class));
    }
}
