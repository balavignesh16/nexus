package com.nexus.twin.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VerificationDeadlineWorker {
    private static final Logger log=LoggerFactory.getLogger(VerificationDeadlineWorker.class);
    private final VerificationService verification;
    public VerificationDeadlineWorker(VerificationService verification){this.verification=verification;}
    @Scheduled(fixedDelayString="${nexus.twin.verification-scan-ms:30000}")
    public void reconcile(){
        try {
            for(var candidate:verification.expired(100))try{verification.deadline(candidate);}
            catch(RuntimeException failure){log.error("Verification deadline reconciliation failed for {}",candidate,failure);}
        } catch(RuntimeException failure){log.warn("Verification deadline scan unavailable: {}",failure.getMessage());}
    }
}
