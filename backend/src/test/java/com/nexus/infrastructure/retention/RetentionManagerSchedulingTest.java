package com.nexus.infrastructure.retention;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RetentionManagerSchedulingTest {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void retentionCleanupJobIsRegisteredAsScheduledTask() {
        Set<ScheduledTask> scheduledTasks = scheduledTaskHolder.getScheduledTasks();

        boolean retentionTaskRegistered = scheduledTasks.stream()
                .anyMatch(task -> task.getTask().toString().contains("RetentionManager"));

        assertThat(retentionTaskRegistered)
                .as("RetentionManager.cleanupOldData() must be registered as a scheduled task once "
                        + "@EnableScheduling is active. If this fails, scheduling is not wired up and the "
                        + "retention job will never run.")
                .isTrue();
    }
}
