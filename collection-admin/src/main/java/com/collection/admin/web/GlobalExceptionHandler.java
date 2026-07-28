package com.collection.admin.web;

import com.collection.admin.web.validation.ScriptTemplateValidator;
import com.collection.admin.web.validation.TemplateValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 管理后台 API 统一异常封装。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TemplateValidationException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateValidation(
            TemplateValidationException e) {
        Map<String, Object> body =
                ApiResponse.failure("TEMPLATE_VALIDATION_FAILED", e.getMessage());
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ScriptTemplateValidator.Issue issue : e.getErrors()) {
            errors.add(issue.toMap());
        }
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (e.getWarnings() != null) {
            for (ScriptTemplateValidator.Issue issue : e.getWarnings()) {
                warnings.add(issue.toMap());
            }
        }
        body.put("errors", errors);
        body.put("warnings", warnings);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = ApiResponse.failure("VALIDATION_ERROR", "Validation failed");
        List<Map<String, Object>> details = new ArrayList<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", fieldError.getField());
            row.put("reason", fieldError.getDefaultMessage());
            details.add(row);
        }
        body.put("details", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e) {
        Map<String, Object> body = ApiResponse.failure("VALIDATION_ERROR", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        String code =
                e.getStatus() == HttpStatus.CONFLICT ? "VERSION_CONFLICT" : e.getStatus().name();
        Map<String, Object> body = ApiResponse.failure(code, e.getReason());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        Map<String, Object> body = ApiResponse.failure("INTERNAL_ERROR", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
