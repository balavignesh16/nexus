package com.nexus.rule.snapshot;

import com.nexus.rule.domain.Rule;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One immutable active version pinned by the snapshot, with its runtime-contract diagnostics. */
public record CapturedRule(Rule rule,UUID scopeDeviceId,boolean scopeMissing,List<Map<String,Object>> diagnostics) {
    public boolean executable(){return !scopeMissing&&diagnostics.isEmpty();}
}
