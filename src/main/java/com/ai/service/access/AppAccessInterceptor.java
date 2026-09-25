package com.ai.service.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
class AppAccessInterceptor implements HandlerInterceptor {

    static final String APP_CODE_HEADER = "X-APP-CODE";

    private final byte[] expectedCode;

    AppAccessInterceptor(AppAccessProperties properties) {
        this.expectedCode = properties.code().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        Enumeration<String> headerValues = request.getHeaders(APP_CODE_HEADER);
        if (headerValues == null || !headerValues.hasMoreElements()) {
            throw new AppAccessDeniedException();
        }

        String suppliedCode = headerValues.nextElement();
        if (headerValues.hasMoreElements() || !matches(suppliedCode)) {
            throw new AppAccessDeniedException();
        }
        return true;
    }

    private boolean matches(String suppliedCode) {
        return suppliedCode != null && MessageDigest.isEqual(
                expectedCode, suppliedCode.getBytes(StandardCharsets.UTF_8));
    }
}
