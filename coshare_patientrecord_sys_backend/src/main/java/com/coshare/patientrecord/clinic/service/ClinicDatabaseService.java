package com.coshare.patientrecord.clinic.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.repository.ClinicDatabaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class ClinicDatabaseService {

    private final ClinicDatabaseRepository repository;

    public ClinicDatabaseService(ClinicDatabaseRepository repository) {
        this.repository = repository;
    }

    public ObjectNode readDb() {
        return repository.readDb();
    }

    public ObjectNode readDbForUser(SessionUser user) {
        return repository.readDbForUser(user);
    }

    public ObjectNode prepareWritePayload(JsonNode payload, SessionUser user) {
        return repository.prepareWritePayload(payload, user);
    }

    @Transactional
    public ObjectNode patchDb(JsonNode patch) {
        return repository.patchDb(patch);
    }

    @Transactional
    public ObjectNode mergeDb(JsonNode incomingDb) {
        return repository.mergeDb(incomingDb);
    }

    public ObjectNode maintenanceStatus(Map<String, Object> fileStatus) {
        return repository.maintenanceStatus(fileStatus);
    }

    @Transactional
    public ObjectNode createSnapshot() {
        return repository.createSnapshot();
    }

    public ArrayNode findDuplicatePatients() {
        return repository.findDuplicatePatients();
    }

    public List<String> referencedStoragePaths() {
        return repository.referencedStoragePaths();
    }

    public boolean canReadStoragePath(String storagePath, SessionUser user) {
        return repository.canReadStoragePath(storagePath, user);
    }

    public ArrayNode patientTimeline(String patientId, SessionUser user) {
        return repository.patientTimeline(patientId, user);
    }

    @Transactional
    public String writeDb(JsonNode db) {
        return repository.writeDb(db);
    }
}
