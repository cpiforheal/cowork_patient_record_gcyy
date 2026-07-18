package com.coshare.patientrecord.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class InventoryStageConsumptionWorker {

    private static final Logger log = LoggerFactory.getLogger(InventoryStageConsumptionWorker.class);
    private final InventoryStageConsumptionService service;

    public InventoryStageConsumptionWorker(InventoryStageConsumptionService service) {
        this.service = service;
    }

    @Scheduled(
        fixedDelayString = "${inventory.consumption.poll-delay-ms:5000}",
        initialDelayString = "${inventory.consumption.initial-delay-ms:10000}"
    )
    public void poll() {
        for (int index = 0; index < 20; index++) {
            String commandId = service.nextPendingId();
            if (commandId == null) return;
            try {
                service.processCommand(commandId);
            } catch (Exception error) {
                log.error("inventory consumption command failed: {}", commandId, error);
                try {
                    service.markUnexpectedFailure(commandId, error);
                } catch (Exception markError) {
                    log.error("failed to persist inventory consumption error: {}", commandId, markError);
                    return;
                }
            }
        }
    }
}
