package com.nexus.intelligence.assistant;

import com.nexus.intelligence.assistant.AssistantAnswer.Kind;
import com.nexus.intelligence.assistant.AssistantAnswer.Statement;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.intelligence.retrieval.EvidenceResult;
import com.nexus.intelligence.retrieval.EvidenceResult.Entity;
import com.nexus.intelligence.retrieval.EvidenceStatus;
import com.nexus.intelligence.retrieval.Freshness;
import com.nexus.intelligence.retrieval.LookupType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The sentences are written from evidence with fixed templates, each FACT cites references that resolve, and gaps are stated as gaps. Pure: no database, no model. */
class AnswerComposerTest {
    private static final Instant AT = Instant.parse("2026-10-20T10:00:00Z");
    private static final Entity DEVICE = new Entity("device", "d1", "Boiler 1");

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static EvidenceResult result(LookupType type, EvidenceStatus status, Entity entity, List<EvidenceItem> evidence, List<Map<String, Object>> rows, String reason, String... limits) {
        return new EvidenceResult(type, status, entity, AT, Freshness.FRESH, AT, evidence, rows, true, false, null, List.of(), List.of(limits), reason);
    }

    private static Set<String> refs(AnswerComposer.Composed c) {
        return c.sources().stream().map(EvidenceItem::ref).collect(Collectors.toSet());
    }

    @Test
    void p6_182_everyFactCitesReferencesThatResolveAndTheTextCarriesTheStoredValues() {
        EvidenceResult latest = result(LookupType.LATEST_TELEMETRY, EvidenceStatus.OBSERVED, DEVICE,
                List.of(EvidenceItem.present("telemetry:r1", "reading of temperature", 74.5)),
                List.of(row("readingId", "r1", "capabilityKey", "temperature", "value", 74.5, "unit", "CELSIUS", "deviceEventTime", "2026-10-20T09:59:00Z", "freshness", "FRESH")),
                null, "The time on each reading is the DEVICE event time.");
        EvidenceResult connectivity = result(LookupType.CONNECTIVITY, EvidenceStatus.OBSERVED, DEVICE, List.of(EvidenceItem.present("connectivity:d1/status", "connectivity status", "ONLINE")),
                List.of(row("status", "ONLINE", "lastChangedAt", "2026-10-20T08:00:00Z")), null, "This is the last observation the platform recorded, not a live check.");
        AnswerComposer.Composed c = AnswerComposer.compose(List.of(latest, connectivity), null, null);
        List<Statement> facts = c.statements().stream().filter(s -> s.kind() == Kind.FACT).toList();
        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).text()).contains("temperature").contains("74.5 CELSIUS").contains("\"Boiler 1\"").contains("2026-10-20T09:59:00Z").contains("fresh");
        assertThat(facts.get(1).text()).contains("ONLINE").contains("2026-10-20T08:00:00Z");
        for (Statement s : facts) {
            assertThat(s.refs()).isNotEmpty();
            assertThat(refs(c)).containsAll(s.refs());
        }
        assertThat(c.dropped()).isZero();
        assertThat(c.statements().stream().filter(s -> s.kind() == Kind.LIMIT).map(Statement::text)).contains("This is the last observation the platform recorded, not a live check.");
        assertThat(c.statements().stream().map(Statement::id).collect(Collectors.toSet())).as("unique ids").hasSize(c.statements().size());
    }

    @Test
    void p6_182_aStatementWhoseReferencesDoNotResolveIsDroppedNeverShown() {
        EvidenceResult broken = result(LookupType.LATEST_TELEMETRY, EvidenceStatus.OBSERVED, DEVICE, List.of(EvidenceItem.present("telemetry:OTHER", "reading", 1.0)),
                List.of(row("readingId", "r1", "capabilityKey", "temperature", "value", 74.5, "unit", "C", "deviceEventTime", "2026-10-20T09:59:00Z", "freshness", "FRESH")), null);
        AnswerComposer.Composed c = AnswerComposer.compose(List.of(broken), null, null);
        assertThat(c.statements().stream().filter(s -> s.kind() == Kind.FACT)).isEmpty();
        assertThat(c.dropped()).isEqualTo(1);
    }

    @Test
    void p6_183_p6_115_missingEvidenceIsStatedAsMissingWithTheNotProofLimitAndNeverAsAPhysicalCondition() {
        EvidenceResult none = result(LookupType.LATEST_TELEMETRY, EvidenceStatus.INSUFFICIENT_EVIDENCE, DEVICE, List.of(), List.of(),
                "No reading was stored in the last 24 hours.", "Missing data is not proof of any physical condition: it does not show that the device is off, broken, idle or healthy.");
        EvidenceResult unknown = result(LookupType.DEVICE_STATE, EvidenceStatus.NOT_FOUND, new Entity("device", "x", null), List.of(), List.of(), "NOT_FOUND: no device has this id");
        AnswerComposer.Composed c = AnswerComposer.compose(List.of(none, unknown), null, null);
        String all = c.statements().stream().map(Statement::text).collect(Collectors.joining(" ")).toLowerCase();
        assertThat(c.statements().stream().filter(s -> s.kind() == Kind.FACT)).isEmpty();
        assertThat(all).contains("too little to answer").contains("no reading was stored").contains("no stored record was found").contains("missing data is not proof");
        assertThat(all).doesNotContain("is offline").doesNotContain("is broken").doesNotContain("is healthy");
    }

    @Test
    void p6_110_deliveryEvidenceKeepsPublicationAcknowledgementTimeoutAndConvergenceSeparate() {
        String id = "c1";
        Entity entity = new Entity("command", id, null);
        List<EvidenceItem> evidence = List.of(EvidenceItem.present("command:c1/status", "command status", "ACCEPTED_FOR_DISPATCH"), EvidenceItem.absent("command:c1/publishedAt", "publication time"),
                EvidenceItem.absent("command:c1/ack/status", "acknowledgement status"));
        List<Map<String, Object>> rows = List.of(row("section", "ACCEPTANCE", "status", "ACCEPTED_FOR_DISPATCH", "acceptedAt", "2026-10-20T09:00:00Z"), row("section", "PUBLICATION", "publishedAt", null),
                row("section", "ACK", "ackStatus", null), row("section", "TIMEOUT", "ackDeadlineAt", "2026-10-20T09:00:30Z", "timedOut", "false"));
        AnswerComposer.Composed c = AnswerComposer.compose(List.of(result(LookupType.DELIVERY_EVIDENCE, EvidenceStatus.OBSERVED, entity, evidence, rows, null,
                "A published command is not acknowledged; an acknowledgement is not execution.")), null, null);
        List<String> facts = c.statements().stream().filter(s -> s.kind() == Kind.FACT).map(Statement::text).toList();
        assertThat(facts).anyMatch(t -> t.startsWith("Acceptance:"));
        assertThat(facts).anyMatch(t -> t.equals("Publication: no publication time is recorded for this command."));
        assertThat(facts).anyMatch(t -> t.equals("Acknowledgement: none is recorded."));
        assertThat(facts).anyMatch(t -> t.startsWith("Timeout:") && t.contains("was not"));
        assertThat(String.join(" ", facts).toLowerCase()).doesNotContain("executed").doesNotContain("actuated").doesNotContain("succeeded");
    }

    @Test
    void p6_112_anAnomalyObservationIsWorkedAsAnObservationWithItsEstablishedAndUncertainParts() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("findingId", "f1", "capabilityKey", "temperature", "category", "FLATLINE", "status", "OBSERVED", "reason", "the last 40 readings are identical",
                "known", List.of("The readings did not change."), "uncertain", List.of("The cause is not known."), "confirmedFault", false)));
        AnswerComposer.Composed c = AnswerComposer.compose(List.of(result(LookupType.ANOMALIES, EvidenceStatus.DERIVED, DEVICE, List.of(EvidenceItem.present("finding:f1", "anomaly finding status", "OBSERVED")), rows, null,
                "Findings are statistical observations on stored readings, never a confirmed fault.")), null, null);
        String text = c.statements().get(0).text();
        assertThat(text).startsWith("Observation (FLATLINE, temperature): OBSERVED.").contains("Established: The readings did not change.").contains("Not established: The cause is not known.");
        assertThat(c.statements().get(0).refs()).containsExactly("finding:f1");
        assertThat(c.statements().stream().anyMatch(s -> s.kind() == Kind.LIMIT && s.text().contains("never a confirmed fault"))).isTrue();
    }

    @Test
    void p6_182_theSameLimitIsStatedOnceEvenWhenSeveralLookupsCarryIt() {
        String limit = "This is a read of stored platform facts at one moment; it does not control or test anything.";
        EvidenceResult a = result(LookupType.CONNECTIVITY, EvidenceStatus.OBSERVED, DEVICE, List.of(EvidenceItem.present("connectivity:d1/status", "s", "ONLINE")), List.of(row("status", "ONLINE", "lastChangedAt", "t")), null, limit);
        EvidenceResult b = result(LookupType.CONNECTIVITY, EvidenceStatus.OBSERVED, DEVICE, List.of(EvidenceItem.present("connectivity:d1/status", "s", "ONLINE")), List.of(row("status", "ONLINE", "lastChangedAt", "t")), null, limit);
        AnswerComposer.Composed c = AnswerComposer.compose(List.of(a, b), null, null);
        assertThat(c.statements().stream().filter(s -> s.text().equals(limit))).hasSize(1);
    }
}
