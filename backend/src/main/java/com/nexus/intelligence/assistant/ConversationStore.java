package com.nexus.intelligence.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Short-lived, in-memory conversation state: which device, rule, command and capability the last turn established, and how many turns there have been. Only
 * identities are kept, never facts: every turn looks the facts up again, so a follow-up cannot repeat a stale reading. Bounded in conversations, turns and age,
 * least recently used first out. Nothing is persisted and nothing is shared between conversations; there are no user accounts, so a conversation id is a handle,
 * not an identity.
 */
public final class ConversationStore {
    static final int MAX_CONVERSATIONS = 200;
    static final int MAX_TURNS = 20;
    static final Duration MAX_AGE = Duration.ofHours(1);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    public static final class Conversation {
        private final String id;
        private Instant touched;
        private int turns;
        private DeterministicRouter.Context context = DeterministicRouter.Context.EMPTY;
        private final List<String> questions = new ArrayList<>();

        Conversation(String id, Instant now) {
            this.id = id;
            this.touched = now;
        }

        public String id() {
            return id;
        }

        public synchronized int turns() {
            return turns;
        }

        public synchronized DeterministicRouter.Context context() {
            return context;
        }

        /** Records one turn; returns its number, or -1 when the conversation is full. */
        public synchronized int record(String question, DeterministicRouter.Context after, Instant now) {
            if (turns >= MAX_TURNS) return -1;
            turns++;
            touched = now;
            context = after;
            questions.add(question.length() > 200 ? question.substring(0, 200) : question);
            return turns;
        }
    }

    private final Clock clock;
    private final Map<String, Conversation> conversations = new LinkedHashMap<>(16, 0.75f, true);

    public ConversationStore(Clock clock) {
        this.clock = clock;
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The conversation with this id, or a new one when the id is null or unknown. An id that is not well formed is refused. */
    public synchronized Conversation open(String id) {
        Instant now = clock.instant();
        expire(now);
        if (id != null && !validId(id)) throw new IllegalArgumentException("a conversation id is 8 to 64 letters, digits, underscores or hyphens");
        if (id != null && conversations.containsKey(id)) return conversations.get(id);
        String fresh = id != null ? id : UUID.randomUUID().toString();
        while (conversations.size() >= MAX_CONVERSATIONS) {
            Iterator<String> eldest = conversations.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        Conversation c = new Conversation(fresh, now);
        conversations.put(fresh, c);
        return c;
    }

    public synchronized int size() {
        return conversations.size();
    }

    private void expire(Instant now) {
        conversations.values().removeIf(c -> Duration.between(c.touched, now).compareTo(MAX_AGE) > 0);
    }
}
