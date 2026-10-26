package crashnode;

import com.nexus.action.outbox.BrokerCommandPublisher;
import com.nexus.action.outbox.CommandStateHistory;
import com.nexus.action.outbox.OutboxDeliveryOperations;
import com.nexus.action.outbox.OutboxState;
import com.nexus.action.outbox.OutboxTransitionRecord;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEST-ONLY kill and pause points for the child backend process of the crash-recovery campaign (ledger P4-059 to P4-067). Nothing here is
 * in the production source tree or on the production classpath; it lives in a package that the application's component scan never visits,
 * and only {@link CrashNode} imports it. The process is stopped with {@link Runtime#halt}: no shutdown hook, no finally block, no
 * transaction rollback by the application: the database only sees the connections vanish, which is what a SIGKILL or a power cut looks
 * like to PostgreSQL and the broker.
 *
 * <pre>
 * crash.kill        NONE | AFTER_ACCEPTANCE | AFTER_CLAIM | AFTER_PUBLISH | IN_OUTCOME_TX | PAUSE_AFTER_PUBLISH
 * crash.kill.after  the n-th occurrence of that point halts (default 1)
 * crash.gate.file   while this file does not exist the dispatcher claims nothing (a standby process: it is fully started, so "restart"
 *                   latency does not eat into the 20 second delivery window, but it does no delivery work until the test releases it)
 * crash.pause.dir   PAUSE_AFTER_PUBLISH writes <dir>/paused after a confirmed publish and waits for <dir>/resume (at most 60 s)
 * </pre>
 * <ul>
 * <li>AFTER_ACCEPTANCE: the acceptance transaction has committed; the dispatcher is about to claim (halt at the first claim call).</li>
 * <li>AFTER_CLAIM: the claim transaction committed (IN_FLIGHT, attempt counted); halt immediately BEFORE the broker is asked.</li>
 * <li>AFTER_PUBLISH: the broker confirmed the publish (QoS 1 acknowledgement received); halt BEFORE the outcome transaction starts.</li>
 * <li>IN_OUTCOME_TX: halt INSIDE the outcome transaction: the outbox row and the action history row have been updated but not committed.</li>
 * <li>PAUSE_AFTER_PUBLISH: like AFTER_PUBLISH but the process waits instead of dying, so the test can take the database away first.</li>
 * </ul>
 */
@Configuration
public class CrashHooks {
    static final String MODE = System.getProperty("crash.kill", "NONE");
    static final int AFTER = Integer.getInteger("crash.kill.after", 1);
    static final String GATE = System.getProperty("crash.gate.file");
    static final String PAUSE_DIR = System.getProperty("crash.pause.dir");
    private static final AtomicInteger OCCURRENCES = new AtomicInteger();

    private static void maybeHalt(String point) {
        if (!MODE.equals(point)) return;
        if (OCCURRENCES.incrementAndGet() < AFTER) return;
        System.out.println("CRASHNODE_HALT " + point);
        System.out.flush();
        Runtime.getRuntime().halt(137);
    }

    private static void awaitGate() throws InterruptedException {
        if (GATE == null) return;
        Path gate = Path.of(GATE);
        while (!Files.exists(gate)) Thread.sleep(50);
    }

    private static void pauseAfterPublish() {
        if (!MODE.equals("PAUSE_AFTER_PUBLISH") || PAUSE_DIR == null) return;
        try {
            Files.writeString(Path.of(PAUSE_DIR, "paused"), "x");
            Path resume = Path.of(PAUSE_DIR, "resume");
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!Files.exists(resume) && System.nanoTime() < end) Thread.sleep(50);
        } catch (IOException | InterruptedException ignored) {
            // the test decides what happens next
        }
    }

    @Bean
    static BeanPostProcessor crashKillHooks() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof BrokerCommandPublisher publisher
                        && (MODE.equals("AFTER_CLAIM") || MODE.equals("AFTER_PUBLISH") || MODE.equals("PAUSE_AFTER_PUBLISH"))) {
                    return new BrokerCommandPublisher() {
                        @Override
                        public Attempt publish(String topic, byte[] payload, Duration timeout) {
                            maybeHalt("AFTER_CLAIM");
                            Attempt result = publisher.publish(topic, payload, timeout);
                            if (result.result() == Result.CONFIRMED) {
                                maybeHalt("AFTER_PUBLISH");
                                pauseAfterPublish();
                            }
                            return result;
                        }
                    };
                }
                if (bean instanceof OutboxDeliveryOperations && (MODE.equals("AFTER_ACCEPTANCE") || GATE != null)) {
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        if (invocation.getMethod().getName().equals("tryClaim")) {
                            awaitGate();
                            maybeHalt("AFTER_ACCEPTANCE");
                        }
                        return invocation.proceed();
                    });
                    return factory.getProxy();
                }
                if (bean instanceof CommandStateHistory && MODE.equals("IN_OUTCOME_TX")) {
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        if (invocation.getMethod().getName().equals("record") && invocation.getArguments().length == 1
                                && invocation.getArguments()[0] instanceof OutboxTransitionRecord t && t.to() == OutboxState.PUBLISHED)
                            maybeHalt("IN_OUTCOME_TX");
                        return invocation.proceed();
                    });
                    return factory.getProxy();
                }
                return bean;
            }
        };
    }
}
