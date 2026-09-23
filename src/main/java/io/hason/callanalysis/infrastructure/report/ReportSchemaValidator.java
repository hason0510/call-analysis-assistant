package io.hason.callanalysis.infrastructure.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.hason.callanalysis.domain.report.CallReport;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

/**
 * Kiểm tra report có đúng schema v1 không.
 *
 * Sprint 1 dùng để bảo đảm report do rule sinh ra luôn hợp lệ. Sprint 2 sẽ dùng chính
 * validator này làm lớp Guardrails cho đầu ra của AI (MVP mục 6.1 T6, ca kiểm thử G02).
 */
@Component
public class ReportSchemaValidator {

    private static final String SCHEMA = "schema/report-v1.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema schema;

    public ReportSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream in = new ClassPathResource(SCHEMA).getInputStream()) {
            this.schema = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Không nạp được " + SCHEMA, e);
        }
    }

    public record Result(boolean valid, List<String> errors) {
        public Result {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    public Result validate(CallReport report) {
        return validate(objectMapper.valueToTree(report));
    }

    public Result validate(JsonNode json) {
        Set<ValidationMessage> messages = schema.validate(json);
        if (messages.isEmpty()) {
            return new Result(true, List.of());
        }
        return new Result(false, messages.stream().map(ValidationMessage::getMessage).sorted().toList());
    }

    public String toJson(CallReport report) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được report", e);
        }
    }
}
