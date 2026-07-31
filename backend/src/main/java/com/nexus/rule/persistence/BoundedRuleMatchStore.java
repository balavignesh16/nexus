package com.nexus.rule.persistence;

import com.nexus.rule.domain.RuleMatchedEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class BoundedRuleMatchStore {

    private static final int MAX_SIZE = 1000;
    private final ConcurrentLinkedDeque<RuleMatchedEvent> matches = new ConcurrentLinkedDeque<>();

    public void add(RuleMatchedEvent event) {
        matches.addFirst(event);
        if (matches.size() > MAX_SIZE) {
            matches.pollLast();
        }
    }

    public List<RuleMatchedEvent> getRecentMatches(int limit) {
        int actualLimit = Math.min(limit, matches.size());
        List<RuleMatchedEvent> result = new ArrayList<>(actualLimit);
        int count = 0;
        for (RuleMatchedEvent event : matches) {
            if (count >= actualLimit) {
                break;
            }
            result.add(event);
            count++;
        }
        return result;
    }
}
