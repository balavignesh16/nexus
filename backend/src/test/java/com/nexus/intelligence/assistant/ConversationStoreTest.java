package com.nexus.intelligence.assistant;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P6-184: conversation state is identities only, bounded in number, turns and age, and never shared between conversations. */
class ConversationStoreTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void p6_184_aConversationRemembersOnlyIdentitiesAndIsBoundedInTurns() {
        ConversationStore store = new ConversationStore(clock);
        ConversationStore.Conversation c = store.open(null);
        assertThat(ConversationStore.validId(c.id())).isTrue();
        assertThat(c.context()).isEqualTo(DeterministicRouter.Context.EMPTY);
        UUID device = UUID.randomUUID();
        assertThat(c.record("q1", new DeterministicRouter.Context(device, "Boiler 1", null, null, "temperature"), now.get())).isEqualTo(1);
        assertThat(store.open(c.id()).context().deviceId()).isEqualTo(device);
        for (int i = 2; i <= ConversationStore.MAX_TURNS; i++) c.record("q" + i, c.context(), now.get());
        assertThat(c.record("one too many", c.context(), now.get())).isEqualTo(-1);
        assertThat(c.turns()).isEqualTo(ConversationStore.MAX_TURNS);
    }

    @Test
    void p6_184_conversationsAreSeparateBoundedInNumberAndExpire() {
        ConversationStore store = new ConversationStore(clock);
        ConversationStore.Conversation a = store.open("conversation-a1"), b = store.open("conversation-b2");
        a.record("q", new DeterministicRouter.Context(UUID.randomUUID(), "A", null, null, null), now.get());
        assertThat(b.context()).as("no state is shared").isEqualTo(DeterministicRouter.Context.EMPTY);
        for (int i = 0; i < ConversationStore.MAX_CONVERSATIONS + 20; i++) store.open("conversation-" + i + "-xx");
        assertThat(store.size()).isLessThanOrEqualTo(ConversationStore.MAX_CONVERSATIONS);
        ConversationStore.Conversation old = store.open("conversation-old1");
        old.record("q", new DeterministicRouter.Context(UUID.randomUUID(), "Old", null, null, null), now.get());
        now.set(now.get().plus(ConversationStore.MAX_AGE).plusSeconds(1));
        assertThat(store.open("conversation-old1").context()).as("an expired conversation starts empty").isEqualTo(DeterministicRouter.Context.EMPTY);
    }

    @Test
    void p6_184_anIdThatIsNotWellFormedIsRefused() {
        ConversationStore store = new ConversationStore(clock);
        for (String bad : new String[] {"short", "has space in it", "../../etc/passwd", "a".repeat(65), "semi;colon;id!"})
            assertThatThrownBy(() -> store.open(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.open("0123456789abcdef").id()).isEqualTo("0123456789abcdef");
    }
}
