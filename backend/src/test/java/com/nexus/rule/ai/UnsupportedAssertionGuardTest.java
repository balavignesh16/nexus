package com.nexus.rule.ai;

import com.nexus.rule.ai.UnsupportedAssertionGuard.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Edge behaviour of the closed word list; the corpus-level measurements are in the evaluation suites. */
class UnsupportedAssertionGuardTest {
    @Test
    void p6_077_eachClassIsRecognisedInPlainWording() {
        assertThat(UnsupportedAssertionGuard.violation("The motor turned on.")).isEqualTo(Kind.PHYSICAL_ACTUATION);
        assertThat(UnsupportedAssertionGuard.violation("The device received the command.")).isEqualTo(Kind.RECEIPT_OR_EXECUTION);
        assertThat(UnsupportedAssertionGuard.violation("The rule was approved by an operator.")).isEqualTo(Kind.HUMAN_APPROVAL);
        assertThat(UnsupportedAssertionGuard.violation("The sensor is faulty.")).isEqualTo(Kind.FAULT_OR_CAUSE);
        assertThat(UnsupportedAssertionGuard.violation(null)).isNull();
        assertThat(UnsupportedAssertionGuard.violation("")).isNull();
    }

    @Test
    void p6_077_aDenialOrADoubtIsNotAnAssertionButAnAssertionAfterNotOnlyIs() {
        assertThat(UnsupportedAssertionGuard.violation("This is not evidence of physical actuation.")).isNull();
        assertThat(UnsupportedAssertionGuard.violation("Whether the device received the command is unknown.")).isNull();
        assertThat(UnsupportedAssertionGuard.violation("Nothing here shows that the command was executed by the device.")).isNull();
        assertThat(UnsupportedAssertionGuard.violation("The command was not only sent, the device received the command.")).isEqualTo(Kind.RECEIPT_OR_EXECUTION);
        // a denial in an earlier sentence does not excuse a later assertion
        assertThat(UnsupportedAssertionGuard.violation("No error was recorded. The motor turned on.")).isEqualTo(Kind.PHYSICAL_ACTUATION);
    }

    @Test
    void p6_084_invisibleCharactersWidthAndCaseDoNotHideAWording() {
        assertThat(UnsupportedAssertionGuard.violation("The motor turned​ on.")).isEqualTo(Kind.PHYSICAL_ACTUATION);
        assertThat(UnsupportedAssertionGuard.violation("THE MOTOR TURNED ON")).isEqualTo(Kind.PHYSICAL_ACTUATION);
        assertThat(UnsupportedAssertionGuard.violation("The motor ｔｕｒｎｅｄ on.")).isEqualTo(Kind.PHYSICAL_ACTUATION);
        assertThat(UnsupportedAssertionGuard.violation("The device received the command.")).isEqualTo(Kind.RECEIPT_OR_EXECUTION);
    }

    @Test
    void p6_077_recordedConnectivityAndDeliveryWordingIsNotARefusedAssertion() {
        assertThat(UnsupportedAssertionGuard.violation("The recorded connectivity status of the device is OFFLINE.")).isNull();
        assertThat(UnsupportedAssertionGuard.violation("A SUCCESS status means the command was published to the MQTT broker.")).isNull();
        assertThat(UnsupportedAssertionGuard.violation("The observed value was 34.7.")).isNull();
    }
}
