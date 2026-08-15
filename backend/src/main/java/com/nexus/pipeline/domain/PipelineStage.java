package com.nexus.pipeline.domain;

public enum PipelineStage {
    MQTT_IN(0),
    TELEMETRY(1),
    TWIN(2),
    RULE(3),
    ACTION(4),
    ALERT(5),
    ANALYTICS(6);

    private final int index;

    PipelineStage(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public PipelineStage getPrevious() {
        if (this.index == 0) return null;
        for (PipelineStage s : values()) {
            if (s.index == this.index - 1) return s;
        }
        return null;
    }

    public PipelineStage getNext() {
        for (PipelineStage s : values()) {
            if (s.index == this.index + 1) return s;
        }
        return null;
    }
}
