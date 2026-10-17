package com.nexus.action.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

/** Defaults, validation and startup rejection of the outbox configuration (contract R12, R22). */
class CommandOutboxSettingsTest {

    /** Builder over the documented defaults so each test changes exactly one property. */
    private record Config(boolean enabled, String window, int attempts, String attemptTimeout, String initialBackoff, double multiplier,
                          String maxBackoff, double jitter, String lease, int batch, String poll, String scan) {
        static Config defaults() {
            return new Config(false, "PT15S", 3, "PT3S", "PT0.5S", 2.0, "PT4S", 0.2, "PT6S", 10, "PT0.25S", "PT1S");
        }
        CommandOutboxSettings build() {
            return new CommandOutboxSettings(enabled, Duration.parse(window), attempts, Duration.parse(attemptTimeout), Duration.parse(initialBackoff),
                    multiplier, Duration.parse(maxBackoff), jitter, Duration.parse(lease), batch, Duration.parse(poll), Duration.parse(scan));
        }
    }

    private static void rejected(UnaryOperator<Config> change, String expectedFragment) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> change.apply(Config.defaults()).build());
        assertTrue(e.getMessage().contains(expectedFragment), "expected '" + expectedFragment + "' in: " + e.getMessage());
    }

    @Test
    void theDispatcherWorkerCountDefaultsToFourAndIsBoundedBetweenOneAndSixteen() {
        assertEquals(4, Config.defaults().build().workers());
        for (int ok : new int[]{1, 4, 16}) assertEquals(ok, build(ok).workers());
        for (int bad : new int[]{0, -1, 17}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> build(bad));
            assertTrue(e.getMessage().contains("workers must be between 1 and 16"), e.getMessage());
        }
    }

    private static CommandOutboxSettings build(int workers) {
        return new CommandOutboxSettings(false, Duration.ofSeconds(15), 3, Duration.ofSeconds(3), Duration.ofMillis(500), 2.0,
                Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1), workers);
    }

    @Test
    void defaultsAreValidAndTheFlagIsOff() {
        CommandOutboxSettings s = Config.defaults().build();
        assertFalse(s.enabled());
        assertEquals(Duration.ofSeconds(15), s.deliveryWindow());
        assertEquals(3, s.maxAttempts());
        assertEquals(Duration.ofSeconds(3), s.attemptTimeout());
        assertEquals(Duration.ofSeconds(6), s.leaseDuration());
        assertTrue(s.problems().isEmpty());
    }

    @Test
    void theDefaultWorstCaseScheduleFitsInsideTheWindow() {
        CommandOutboxSettings s = Config.defaults().build();
        // 3 attempts x 3 s + (0.5 s + 1.0 s) x 1.2 jitter = 10.8 s, inside 15 s.
        assertEquals(Duration.ofMillis(10_800), s.worstCaseSchedule());
        assertTrue(s.worstCaseSchedule().compareTo(s.deliveryWindow()) <= 0);
    }

    @Test
    void backoffGrowsByTheMultiplierAndIsCapped() {
        CommandOutboxSettings s = new Config(false, "PT20S", 5, "PT1S", "PT0.5S", 2.0, "PT2S", 0.0, "PT4S", 10, "PT0.25S", "PT1S").build();
        assertEquals(Duration.ZERO, s.baseBackoffBeforeAttempt(1));
        assertEquals(Duration.ofMillis(500), s.baseBackoffBeforeAttempt(2));
        assertEquals(Duration.ofMillis(1000), s.baseBackoffBeforeAttempt(3));
        assertEquals(Duration.ofMillis(2000), s.baseBackoffBeforeAttempt(4));
        assertEquals(Duration.ofMillis(2000), s.baseBackoffBeforeAttempt(5), "capped at max-backoff");
    }

    @Test
    void theWindowAboveTheFirmwareDerivedCeilingIsRejected() {
        rejected(c -> new Config(c.enabled(), "PT21S", c.attempts(), c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()),
                "delivery-window must not exceed PT20S");
        assertEquals(Duration.ofSeconds(20), new Config(false, "PT20S", 3, "PT3S", "PT0.5S", 2.0, "PT4S", 0.2, "PT6S", 10, "PT0.25S", "PT1S").build().deliveryWindow(),
                "exactly the ceiling is allowed");
    }

    @Test
    void aTooShortOrFractionalWindowIsRejected() {
        rejected(c -> new Config(false, "PT1S", 1, "PT1S", "PT0.5S", 2.0, "PT4S", 0.2, "PT3S", 10, "PT0.25S", "PT1S"), "delivery-window must be at least");
        rejected(c -> new Config(false, "PT15.5S", c.attempts(), c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()),
                "whole number of seconds");
    }

    @Test
    void attemptBoundsAreEnforced() {
        rejected(c -> new Config(false, c.window(), 0, c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()), "max-attempts must be between 1 and 5");
        rejected(c -> new Config(false, c.window(), 6, c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()), "max-attempts must be between 1 and 5");
        rejected(c -> new Config(false, c.window(), c.attempts(), "PT0S", c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()), "attempt-timeout must be positive");
        rejected(c -> new Config(false, c.window(), c.attempts(), "PT16S", c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), "PT30S", c.batch(), c.poll(), c.scan()), "attempt-timeout must not exceed delivery-window");
    }

    @Test
    void theRetryScheduleMustFitTheWindow() {
        // 5 attempts x 3 s = 15 s alone already fills a 15 s window before any backoff.
        rejected(c -> new Config(false, "PT15S", 5, "PT3S", "PT0.5S", 2.0, "PT4S", 0.2, "PT6S", 10, "PT0.25S", "PT1S"), "does not fit delivery-window");
    }

    @Test
    void backoffAndJitterParametersAreBounded() {
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), "PT0S", c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()), "initial-backoff must be positive");
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), "PT2S", c.multiplier(), "PT1S", c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()), "max-backoff must be at least initial-backoff");
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), c.initialBackoff(), 0.5, c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()), "backoff-multiplier");
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), c.initialBackoff(), 5.0, c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), c.scan()), "backoff-multiplier");
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), 0.9, c.lease(), c.batch(), c.poll(), c.scan()), "jitter-fraction");
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), -0.1, c.lease(), c.batch(), c.poll(), c.scan()), "jitter-fraction");
    }

    @Test
    void theLeaseMustOutlastOneAttemptByTheMargin() {
        rejected(c -> new Config(false, c.window(), c.attempts(), "PT3S", c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), "PT4S", c.batch(), c.poll(), c.scan()), "lease-duration must be at least attempt-timeout plus PT2S");
        assertEquals(Duration.ofSeconds(5), new Config(false, "PT15S", 3, "PT3S", "PT0.5S", 2.0, "PT4S", 0.2, "PT5S", 10, "PT0.25S", "PT1S").build().leaseDuration(),
                "exactly attempt-timeout plus the margin is allowed");
    }

    @Test
    void workerLoopBoundsAreEnforced() {
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), 0, c.poll(), c.scan()), "claim-batch-size");
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), 101, c.poll(), c.scan()), "claim-batch-size");
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), c.batch(), "PT0.01S", c.scan()), "poll-interval");
        rejected(c -> new Config(false, c.window(), c.attempts(), c.attemptTimeout(), c.initialBackoff(), c.multiplier(), c.maxBackoff(), c.jitter(), c.lease(), c.batch(), c.poll(), "PT60S"), "expiry-scan-interval");
    }

    @Test
    void severalProblemsAreAllReported() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new Config(false, "PT30S", 9, "PT3S", "PT0.5S", 2.0, "PT4S", 0.2, "PT6S", 10, "PT0.25S", "PT1S").build());
        assertTrue(e.getMessage().contains("delivery-window") && e.getMessage().contains("max-attempts"), e.getMessage());
    }

    // ------------------------------------------------------------------------- Spring wiring and startup rejection

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                // the running application has Spring Boot's conversion service (Duration from ISO-8601); a bare test context does not
                .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(CommandOutboxSettings.class);
    }

    @Test
    void theShippedApplicationYmlLeavesTheOutboxDisabledWithTheDocumentedDefaults() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            CommandOutboxSettings s = context.getBean(CommandOutboxSettings.class);
            assertFalse(s.enabled(), "decision D7: the outbox feature flag defaults to OFF");
            assertEquals(Duration.ofSeconds(15), s.deliveryWindow());
            assertEquals(3, s.maxAttempts());
            assertEquals(Duration.ofSeconds(3), s.attemptTimeout());
            assertEquals(Duration.ofMillis(500), s.initialBackoff());
            assertEquals(2.0, s.backoffMultiplier());
            assertEquals(Duration.ofSeconds(4), s.maxBackoff());
            assertEquals(0.2, s.jitterFraction());
            assertEquals(Duration.ofSeconds(6), s.leaseDuration());
            assertEquals(10, s.claimBatchSize());
        });
    }

    @Test
    void theFlagCanBeEnabledOnlyExplicitly() {
        runner().withPropertyValues("nexus.command.outbox.enabled=true")
                .run(context -> assertTrue(context.getBean(CommandOutboxSettings.class).enabled()));
    }

    @Test
    void anInvalidConfigurationStopsApplicationStartup() {
        runner().withPropertyValues("nexus.command.outbox.delivery-window=PT25S").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(context.getStartupFailure().getMessage() + rootMessage(context.getStartupFailure()))
                    .contains("delivery-window must not exceed PT20S");
        });
        runner().withPropertyValues("nexus.command.outbox.max-attempts=7").run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("nexus.command.outbox.lease-duration=PT3S").run(context -> assertThat(context).hasFailed());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }

    @Test
    void onlyTheExactWordsTrueAndFalseAreAcceptedForTheFlag() {
        for (String on : new String[]{"true", "TRUE", "True", " true "})
            runner().withPropertyValues("nexus.command.outbox.enabled=" + on)
                    .run(context -> assertTrue(context.getBean(CommandOutboxSettings.class).enabled(), "[" + on + "]"));
        for (String off : new String[]{"false", "FALSE", " false "})
            runner().withPropertyValues("nexus.command.outbox.enabled=" + off)
                    .run(context -> assertFalse(context.getBean(CommandOutboxSettings.class).enabled(), "[" + off + "]"));
    }

    @Test
    void everyOtherSpellingStopsStartupInsteadOfBeingGuessed() {
        // Spring's own boolean conversion would silently accept 1, on and yes; the outbox flag must not be interpreted two ways.
        for (String bad : new String[]{"1", "0", "on", "off", "yes", "no", "enabled", "t", "y", "2", "tru", "null"}) {
            runner().withPropertyValues("nexus.command.outbox.enabled=" + bad).run(context -> {
                assertThat(context).as("[" + bad + "]").hasFailed();
                assertThat(context.getStartupFailure().getMessage() + rootMessage(context.getStartupFailure()))
                        .contains("must be exactly true or false");
            });
        }
    }

    @Test
    void parseEnabledIsTheSingleParser() {
        assertTrue(CommandOutboxSettings.parseEnabled("true"));
        assertFalse(CommandOutboxSettings.parseEnabled("FALSE"));
        for (String bad : new String[]{null, "", " ", "1", "on", "yes"})
            assertThrows(IllegalStateException.class, () -> CommandOutboxSettings.parseEnabled(bad), String.valueOf(bad));
    }

    @Test
    void theDispatcherCannotBeSwitchedOffWhileTheOutboxIsEnabledButMayBeWhileItIsNot() {
        var providers = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        CommandOutboxSettings enabled = new CommandOutboxSettings(true, Duration.ofSeconds(15), 3, Duration.ofSeconds(3), Duration.ofMillis(500), 2.0,
                Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1));
        CommandOutboxSettings disabled = new CommandOutboxSettings(false, Duration.ofSeconds(15), 3, Duration.ofSeconds(3), Duration.ofMillis(500), 2.0,
                Duration.ofSeconds(4), 0.2, Duration.ofSeconds(6), 10, Duration.ofMillis(250), Duration.ofSeconds(1));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new CommandOutboxDispatcher(null, null, enabled,
                providers.getBeanProvider(OutboxOutcomeListener.class), providers.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), false));
        assertTrue(e.getMessage().contains("not allowed while nexus.command.outbox.enabled=true"), e.getMessage());
        CommandOutboxDispatcher idle = new CommandOutboxDispatcher(null, null, disabled,
                providers.getBeanProvider(OutboxOutcomeListener.class), providers.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), false);
        idle.shutdown();
    }
}
