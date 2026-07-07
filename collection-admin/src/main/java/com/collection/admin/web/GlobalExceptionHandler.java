package com.collection.admin.web;

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

/** 管理后台 API 统一异常封装。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        Map<String, Object> body = ApiResponse.failure("INTERNAL_ERROR", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

