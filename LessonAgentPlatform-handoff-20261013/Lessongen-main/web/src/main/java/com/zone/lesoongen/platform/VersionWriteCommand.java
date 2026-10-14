package com.zone.lesoongen.platform;

import java.util.List;

public record VersionWriteCommand(String lessonId, String expectedCurrentVersionId, String content,
        LessonDocument document, String parseStatus, String parserName, List<String> warnings,
        String sourceModule, String nativeReference, String operationKey, String requestFingerprint) {}
