package com.seniordev.ai;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FixResult(
    String explanation,
    @JsonAlias({"fixedContent", "fixCode", "fixed_code", "code"}) String fixCode,
    List<String> affectedFiles,
    String confidence,
    List<ErrorEntry> errorsFound
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorEntry(int line, String message) {}
}
