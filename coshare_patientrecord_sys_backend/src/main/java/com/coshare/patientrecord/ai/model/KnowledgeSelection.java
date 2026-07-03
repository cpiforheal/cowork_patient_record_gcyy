package com.coshare.patientrecord.ai.model;

import java.util.List;

public record KnowledgeSelection(List<KnowledgeItem> items, List<String> titles) {}
