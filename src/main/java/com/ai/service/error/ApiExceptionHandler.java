package com.ai.service.error;

import com.ai.service.codex.CodexBusyException;
import com.ai.service.codex.CodexException;
import com.ai.service.codex.CodexTimeoutException;
import com.ai.service.conversation.ConversationBusyException;
import com.ai.service.image.ImageRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed", request);
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(CodexBusyException.class)
    ProblemDetail handleBusy(CodexBusyException exception, HttpServletRequest request) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), request);
    }

    @ExceptionHandler(ImageRequestException.class)
    ProblemDetail handleImageRequest(ImageRequestException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "reference image upload is too large", request);
    }

    @ExceptionHandler(ConversationBusyException.class)
    ProblemDetail handleConversationBusy(ConversationBusyException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(CodexTimeoutException.class)
    ProblemDetail handleTimeout(CodexTimeoutException exception, HttpServletRequest request) {
        return problem(HttpStatus.GATEWAY_TIMEOUT, exception.getMessage(), request);
    }

    @ExceptionHandler(CodexException.class)
    ProblemDetail handleCodex(CodexException exception, HttpServletRequest request) {
        log.error("Codex request failed", exception);
        return problem(HttpStatus.BAD_GATEWAY, exception.getMessage(), request);
    }

    private ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
