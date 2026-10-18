package com.nexus.action.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level proofs that no command can reach the broker around the outbox (contract R31.1, R31.6, R31.7). These are
 * deliberately structural: they fail the build if a new class publishes to MQTT, if anything other than the dispatcher can
 * use the bounded publisher or the claim operations, if a requeue or resend operation appears, or if a delivery
 * guarantee is claimed without a negation. Behavioural proofs are in the integration tests.
 */
class OutboxArchitectureTest {

    private static final Path MAIN = locate("src/main/java");
    private static final Path FRONTEND = locate("../frontend/src");

    private static Path locate(String relative) {
        Path p = Path.of(relative);
        if (!Files.exists(p)) p = Path.of("backend").resolve(relative);
        return p.normalize();
    }

    private static List<Path> sources(Path root, String... suffixes) throws IOException {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> Stream.of(suffixes).anyMatch(x -> f.toString().endsWith(x)))
                    .filter(f -> !f.toString().contains(".test.") && !f.toString().contains("node_modules"))
                    .toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> namesOfFilesMatching(Pattern pattern) throws IOException {
        return sources(MAIN, ".java").stream().filter(f -> pattern.matcher(read(f)).find())
                .map(f -> f.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void onlyTheLegacyExecutorAndTheBoundedPublisherCanPutAMessageOnTheBroker() throws IOException {
        Set<String> publishers = sources(MAIN, ".java").stream()
                .filter(f -> {
                    String text = read(f);
                    boolean usesClient = text.contains("org.eclipse.paho.client.mqttv3.MqttClient")
                            || text.contains("org.eclipse.paho.client.mqttv3.IMqttClient")
                            || text.contains("org.eclipse.paho.client.mqttv3.MqttAsyncClient");
                    return usesClient && (text.contains(".publish(") || text.contains(".getTopic("));
                })
                .map(f -> f.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of("CommandExecutor.java", "PahoBrokerCommandPublisher.java"), publishers,
                "a new class publishes to MQTT; commands must go through the outbox dispatcher");
    }

    @Test
    void onlyTheDeliveryOperationsCanParkACommandInRetryWaitAndOnlyThroughTheRetryPolicy() throws IOException {
        // P4.3 / S1: no other class may write the RETRY_WAIT state, so a retry cannot be scheduled around the eligibility decision
        Set<String> writers = namesOfFilesMatching(Pattern.compile("state\\s*=\\s*'RETRY_WAIT'"));
        assertEquals(Set.of("OutboxDeliveryOperations.java"), writers, "RETRY_WAIT is written by exactly one class");
        String operations = read(MAIN.resolve("com/nexus/action/outbox/OutboxDeliveryOperations.java"));
        assertEquals(1, new Matcher(operations, "state = 'RETRY_WAIT'").count(), "and in exactly one statement");
        assertTrue(operations.contains("retryPolicy.decide(AttemptResult.NOT_SENT"),
                "that statement is reached only after the retry policy decided on a proven NOT_SENT outcome");
        // the repository and the entity expose no way to set the state or the schedule
        String entity = read(MAIN.resolve("com/nexus/action/outbox/CommandOutboxEntity.java"));
        assertTrue(!entity.contains("setState") && !entity.contains("setNextAttemptAt") && !entity.contains("setAttemptCount"),
                "the JPA entity has no mutator that could schedule a retry or change the attempt count");
    }

    @Test
    void retriesNeverDependOnRuleAuthorisationOrAcceptanceAndNeverCallThePublisherOutsideTheDispatcher() throws IOException {
        for (String name : List.of("OutboxDeliveryOperations.java", "OutboxRetryPolicy.java", "CommandOutboxDispatcher.java")) {
            String text = read(MAIN.resolve("com/nexus/action/outbox/" + name));
            assertTrue(!text.contains("RuleExecutionAuthorization") && !text.contains("CommandAcceptanceService") && !text.contains("com.nexus.rule."),
                    name + " must not reauthorise or reaccept a command (contract R08)");
        }
        assertEquals(Set.of("CommandOutboxDispatcher.java"), namesOfFilesMatching(Pattern.compile("publisher\\.publish\\(")),
                "only the dispatcher hands bytes to the bounded publisher");
    }

    @Test
    void theAckTimeoutWorkerTheReconcilerAndTheAckFactsCanNeverPublishRequeueOrTouchVerificationOrDesiredState() {
        // P4.4 / S2, S8: these classes only read and append history facts. They have no publisher, no MQTT client, no executor, no
        // dispatcher, no verification service, and their SQL never writes the outbox, the desired state or the verification.
        for (String name : List.of("CommandAckTimeoutScanner.java", "LegacyDeliveryReconciler.java", "CommandAckFacts.java")) {
            String text = read(MAIN.resolve("com/nexus/action/outbox/" + name));
            for (String forbidden : List.of("MqttClient", "BrokerCommandPublisher", "CommandExecutor", "CommandOutboxDispatcher", "VerificationService",
                    "CommandAcceptanceService", "OutboxDeliveryOperations", "current_desired_twin_state", "current_twin_verification_state",
                    "UPDATE command_outbox", "INSERT INTO command_outbox", "DELETE FROM command_outbox", "UPDATE action_history", "INSERT INTO action_history",
                    "FOR UPDATE OF o", "next_attempt_at"))
                assertTrue(!text.contains(forbidden), name + " must not reference " + forbidden);
        }
    }

    @Test
    void onlyTheOwningClassesAppendTheTimeoutAndTheDeliveryUnknownFacts() throws IOException {
        assertEquals(Set.of("CommandAckTimeoutScanner.java"), namesOfFilesMatching(Pattern.compile("Fact\\.ACK_TIMED_OUT")),
                "ACK_TIMED_OUT as a history fact has one owner (the rule scanner owns only the rule trigger fact of the same name)");
        assertEquals(Set.of("LegacyDeliveryReconciler.java"), namesOfFilesMatching(Pattern.compile("Fact\\.DELIVERY_UNKNOWN")),
                "DELIVERY_UNKNOWN is appended only by the reconciler");
    }

    @Test
    void theAckPathTakesNoLockOnTheOutboxRowSoItCannotCloseALockCycleWithTheDispatcher() {
        // R31.9 lock order: the ACK path locks the device row, then the action_history row; the dispatcher locks the outbox row, then
        // action_history. The ACK path must therefore only READ the outbox, never lock or write it.
        String handler = read(MAIN.resolve("com/nexus/device/command/DeviceCommandAckMessageHandler.java"));
        assertTrue(!handler.contains("command_outbox") && !handler.contains("OutboxDeliveryOperations") && !handler.contains("CommandOutboxRepository"),
                "the ACK handler never touches the outbox directly");
        String facts = read(MAIN.resolve("com/nexus/action/outbox/CommandAckFacts.java"));
        assertTrue(!facts.toUpperCase().contains("FOR UPDATE"), "the ACK fact reads take no lock at all");
        assertTrue(facts.contains("clock_timestamp()"), "the receipt time comes from the database clock");
    }

    @Test
    void theLegacyAckHandlerBehaviourIsPreservedNoValidationWasRemoved() {
        String handler = read(MAIN.resolve("com/nexus/device/command/DeviceCommandAckMessageHandler.java"));
        for (String guard : List.of("parseDeviceIdOrNull(topic)", "parseCommandIdOrNull(node)", "parseStatusOrNull(node)", "parseTimestampOrNull(node)",
                "devices.findByIdForUpdate(topicDeviceId)", "!topicDeviceId.equals(record.getDeviceId())", "updateAcknowledgement(commandId, topicDeviceId"))
            assertTrue(handler.contains(guard), "ACK validation step kept: " + guard);
    }

    @Test
    void theLegacyExecutorRefusesToPublishWhenTheOutboxIsEnabledOnBothEntryPoints() {
        String text = read(MAIN.resolve("com/nexus/action/application/executor/CommandExecutor.java"));
        Matcher guards = new Matcher(text, "if \\(directPublicationDisabled\\(\\)\\) return failure\\(request, DIRECT_PUBLICATION_DISABLED\\);");
        assertEquals(2, guards.count(), "execute and publishPrepared must both be guarded");
        int execute = text.indexOf("public ActionExecutionResult execute(");
        int prepared = text.indexOf("public ActionExecutionResult publishPrepared(");
        assertTrue(text.indexOf("directPublicationDisabled()", execute) < text.indexOf("prepare(request)", execute), "the guard comes first in execute");
        assertTrue(text.indexOf("directPublicationDisabled()", prepared) < text.indexOf("mqttClient.publish", prepared), "the guard comes before the publish");
    }

    @Test
    void onlyTheDispatcherUsesTheBoundedPublisher() throws IOException {
        Set<String> users = namesOfFilesMatching(Pattern.compile("\\bBrokerCommandPublisher\\b"));
        assertEquals(Set.of("BrokerCommandPublisher.java", "CommandOutboxDispatcher.java", "PahoBrokerCommandPublisher.java"), users);
    }

    @Test
    void onlyTheDispatcherAndItsReactionsTouchTheClaimAndOutcomeOperations() throws IOException {
        Set<String> users = namesOfFilesMatching(Pattern.compile("\\bOutboxDeliveryOperations\\b"));
        // OutboxRetryPolicy (P4.3) is the pure retry decision: it only reads the AttemptResult enum nested in the operations and never claims or completes anything
        assertEquals(Set.of("CommandOutboxDispatcher.java", "OutboxDeliveryOperations.java", "OutboxOutcomeListener.java", "OutboxOutcomeReactions.java",
                "OutboxRetryPolicy.java"), users);
        String policy = read(MAIN.resolve("com/nexus/action/outbox/OutboxRetryPolicy.java"));
        assertTrue(!policy.contains(".claim(") && !policy.contains(".complete(") && !policy.contains("JdbcTemplate") && !policy.contains("Clock"),
                "the retry policy is pure: no claim, no outcome, no SQL, no clock");
        // the reactions only receive committed resolutions; they never claim or complete anything
        String reactions = read(MAIN.resolve("com/nexus/action/application/OutboxOutcomeReactions.java"));
        assertTrue(!reactions.contains(".claim(") && !reactions.contains(".complete(") && !reactions.contains(".candidates("));
    }

    @Test
    void theDispatcherIsUnconditionalAndNeverReadsTheFlagSoItCanNeverDisagreeWithAcceptance() throws IOException {
        assertEquals(null, CommandOutboxDispatcher.class.getAnnotation(ConditionalOnProperty.class),
                "a conditional dispatcher could disagree with acceptance about whether the outbox is enabled");
        String dispatcher = read(MAIN.resolve("com/nexus/action/outbox/CommandOutboxDispatcher.java"));
        assertTrue(!dispatcher.contains("outbox.enabled}") && !dispatcher.contains("${nexus.command.outbox.enabled"), "the dispatcher does not read the property");
        // the only use of the setting is the startup validation in the constructor; no scheduled or claim path consults it
        assertEquals(1, new Matcher(dispatcher, "settings\\.enabled\\(\\)").count());
        assertTrue(dispatcher.indexOf("settings.enabled()") < dispatcher.indexOf("this.scheduledLoops = scheduledLoops"),
                "the dispatcher services existing obligations whatever the flag says (drain-only rollback, contract R22)");
    }

    @Test
    void theFlagIsReadInExactlyOnePlaceAndEveryConsumerAsksThatOneSetting() throws IOException {
        Set<String> readers = namesOfFilesMatching(Pattern.compile("\\$\\{nexus\\.command\\.outbox\\.enabled|NEXUS_COMMAND_OUTBOX_ENABLED"));
        assertEquals(Set.of("CommandOutboxSettings.java"), readers, "only CommandOutboxSettings may parse the flag");
        Set<String> consumers = sources(MAIN, ".java").stream()
                .filter(f -> { String t = read(f); return t.contains("CommandOutboxSettings") && t.contains(".enabled()"); })
                .map(f -> f.getFileName().toString()).filter(n -> !n.equals("CommandOutboxSettings.java")).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of("CommandAcceptanceService.java", "CommandDispatchCoordinator.java", "CommandExecutor.java", "CommandOutboxDispatcher.java"), consumers,
                "exactly the acceptance, coordinator, executor guard and the dispatcher start-up validation ask the one authoritative setting");
    }

    @Test
    void noRequeueResendOrRedriveOperationExistsAnywhereInTheBackend() throws IOException {
        Pattern declaration = Pattern.compile("(?i)(\\bclass|\\binterface|\\benum|\\brecord|\\bvoid|\\bpublic\\s+[\\w<>\\[\\],.? ]+)\\s+\\w*(?<![pP])(requeue|resend|redrive|retryDeadLetter|reviveDeadLetter)\\w*\\s*[({<]");
        // P4-FINAL (decision D4): a READ-ONLY listing of dead letters is approved (GET /api/v1/actions/dead-letters). What stays forbidden is
        // any requeue, resend or redrive endpoint under any method, and any endpoint that CHANGES something about a dead letter or the outbox.
        Pattern mapping = Pattern.compile("(?i)@(Get|Post|Put|Patch|Delete|Request)Mapping\\([^)]*(requeue|resend|redrive)");
        Pattern mutatingMapping = Pattern.compile("(?i)@(Post|Put|Patch|Delete|Request)Mapping\\([^)]*(dead-?letter|outbox)");
        assertEquals(Set.of(), namesOfFilesMatching(declaration), "a requeue or resend operation was added");
        assertEquals(Set.of(), namesOfFilesMatching(mapping), "an HTTP endpoint for requeue or resend was added");
        assertEquals(Set.of(), namesOfFilesMatching(mutatingMapping), "a non-GET endpoint on dead letters or the outbox was added");
    }

    @Test
    void theLifecycleControllerOffersReadOnlyEndpointsOnly() throws IOException {
        Path controller = sources(MAIN, ".java").stream().filter(f -> f.getFileName().toString().equals("CommandLifecycleController.java")).findFirst().orElseThrow();
        String text = read(controller);
        java.util.regex.Matcher mappings = Pattern.compile("@(\\w+)Mapping").matcher(text);
        int count = 0;
        while (mappings.find()) {
            String kind = mappings.group(1);
            if (kind.equals("Request")) continue;                   // the class-level @RequestMapping("/api/v1/actions") prefix
            assertEquals("Get", kind, "every endpoint of the lifecycle controller is a GET");
            count++;
        }
        assertEquals(2, count, "exactly the lifecycle and the dead-letter listing");
        assertTrue(!text.contains("@RequestBody") && !text.contains("JdbcTemplate") && !text.contains("Repository"), "no body, no direct data access in the controller");
    }

    @Test
    void theExistingRealtimeReplayIsAboutWebSocketMessagesAndNeverTouchesCommands() throws IOException {
        // RealtimeReplayController re-delivers already-broadcast UI events to a reconnecting browser. It is not a command resend.
        for (Path file : sources(MAIN.resolve("com/nexus/realtime"), ".java")) {
            String text = read(file);
            assertTrue(!text.contains("CommandExecutor") && !text.contains("CommandOutbox") && !text.contains("BrokerCommandPublisher")
                    && !text.contains("MqttClient") && !text.contains("ActionType.SEND_COMMAND"), file.getFileName() + " must not touch command delivery");
        }
    }

    @Test
    void noOutboxStateIsMutableThroughAnyWebLayerClass() throws IOException {
        Set<String> web = sources(MAIN, ".java").stream()
                .filter(f -> f.toString().replace('\\', '/').contains("/api/"))
                .filter(f -> {
                    String t = read(f);
                    return t.contains("OutboxDeliveryOperations") || t.contains("CommandOutboxDispatcher") || t.contains("CommandStateHistory");
                })
                .map(f -> f.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(), web, "no controller may reach the dispatcher, the delivery operations or the history writer");
    }

    @Test
    void noExactlyOnceDeliveryOrPublicationIsEverClaimedWithoutANegation() throws IOException {
        Pattern claim = Pattern.compile("(?i)exactly[- ]once\\s+(publish\\w*|deliver\\w*|send\\w*|command\\w*|mqtt|message\\w*|physical|effect\\w*)");
        Pattern negation = Pattern.compile("(?i)\\b(not|never|no|neither|without|cannot|isn't|does not)\\b");
        List<String> offences = new java.util.ArrayList<>();
        List<Path> all = new java.util.ArrayList<>(sources(MAIN, ".java", ".yml", ".sql"));
        all.addAll(sources(FRONTEND, ".ts", ".tsx"));
        for (Path file : all) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!claim.matcher(lines.get(i)).find()) continue;
                String context = lines.get(i) + " " + (i > 0 ? lines.get(i - 1) : "");
                if (!negation.matcher(context).find()) offences.add(file.getFileName() + ":" + (i + 1) + " " + lines.get(i).trim());
            }
        }
        assertTrue(offences.isEmpty(), "an unqualified exactly-once claim: " + offences);
    }

    @Test
    void deliveryNeverReauthorisesRevalidatesOrReevaluatesConnectivity() {
        for (String file : List.of("com/nexus/action/outbox/CommandOutboxDispatcher.java", "com/nexus/action/outbox/OutboxDeliveryOperations.java",
                "com/nexus/infrastructure/mqtt/PahoBrokerCommandPublisher.java", "com/nexus/action/application/OutboxOutcomeReactions.java")) {
            String text = read(MAIN.resolve(file));
            for (String forbidden : List.of("RuleExecutionAuthorization", "RuleCapabilityValidator", "DeviceConnectivity", "CommandAcceptanceService",
                    "CommandValueValidator", "RuleLifecycleService", "RuleRegistry"))
                assertTrue(!text.contains(forbidden), file + " must not use " + forbidden + ": delivery never reauthorises (contract R08.3)");
        }
        // the dispatcher and its operations never lock or read the device row (lock order, contract R31.9)
        String ops = read(MAIN.resolve("com/nexus/action/outbox/OutboxDeliveryOperations.java"));
        assertTrue(!ops.toLowerCase().contains("from devices") && !ops.toLowerCase().contains("join devices") && !ops.contains("DeviceRepository"));
    }

    @Test
    void theShippedConfigurationRunsTheDispatcherLoopsAndLeavesTheFlagOff() throws IOException {
        String yml = read(MAIN.getParent().resolve("resources/application.yml"));
        assertTrue(yml.contains("enabled: ${NEXUS_COMMAND_OUTBOX_ENABLED:false}"), "the flag defaults to OFF");
        assertTrue(yml.contains("dispatcher-enabled: ${NEXUS_COMMAND_OUTBOX_DISPATCHER_ENABLED:true}"), "the dispatcher loops default to ON in production");
    }

    /** Tiny regex counter kept local so the test reads top to bottom. */
    private record Matcher(String text, String regex) {
        int count() {
            java.util.regex.Matcher m = Pattern.compile(regex).matcher(text);
            int n = 0;
            while (m.find()) n++;
            return n;
        }
    }
}
