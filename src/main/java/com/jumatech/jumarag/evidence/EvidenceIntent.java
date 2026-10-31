package com.jumatech.jumarag.evidence;

public enum EvidenceIntent {
    GENERAL,
    OFFICIAL_ONLY,
    IMPLEMENTATION_ONLY,
    OFFICIAL_AND_IMPLEMENTATION;

    public static EvidenceIntent defaulted(EvidenceIntent intent) {
        return intent == null ? GENERAL : intent;
    }
}
