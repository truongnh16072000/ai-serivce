package com.ai.service.access;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.access")
public record AppAccessProperties(@DefaultValue("work_planner") @NotBlank String code) {
}
