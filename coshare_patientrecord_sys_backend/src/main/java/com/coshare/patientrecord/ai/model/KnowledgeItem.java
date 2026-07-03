package com.coshare.patientrecord.ai.model;

import java.util.List;

public record KnowledgeItem(String id, String title, List<String> tags, String content) {}
