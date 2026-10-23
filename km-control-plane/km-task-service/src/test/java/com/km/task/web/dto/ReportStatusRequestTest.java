package com.km.task.web.dto;

import com.km.task.domain.TaskStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上报报文的校验契约。
 *
 * <p>只验一件事: <b>traceId 缺失或空白时校验必须失败</b>。
 *
 * <p>为什么值得单独一个测试: 这一条是"执行身份不能靠猜"的唯一执行点。
 * 少了它, 报文照样能反序列化成功, 然后 Java 只能拿任务表上"最近一次执行"
 * 的 trace 去盖章 —— 而那个推断在重试之后一定出错, 且不会抛任何异常。
 *
 * <p>直接调 Validator 而不起 MockMvc: 这里验的是 DTO 上的约束注解, 不是
 * HTTP 绑定。后者由 Spring 负责, 测它只能测出"Spring 没坏"。
 */
class ReportStatusRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 缺traceId时校验失败() {
        ReportStatusRequest request = new ReportStatusRequest(
                null, TaskStatus.THINKING, null, null);

        Set<ConstraintViolation<ReportStatusRequest>> violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("traceId")),
                "traceId 缺失却没有被拦下 —— 这条上报的身份只能靠猜了");
    }

    @Test
    void 空白traceId时也校验失败() {
        // 空串能过 @NotBlank 吗? 不能。但这里钉住它 —— 因为调用方从 JSON 里
        // 传 "" 是最常见的"看起来给了、其实没给"。
        ReportStatusRequest request = new ReportStatusRequest(
                "   ", TaskStatus.THINKING, null, null);

        Set<ConstraintViolation<ReportStatusRequest>> violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("traceId")));
    }

    @Test
    void 字段齐全时通过() {
        ReportStatusRequest request = new ReportStatusRequest(
                "trace-1", TaskStatus.THINKING, null, null);

        assertTrue(validator.validate(request).isEmpty());
    }
}
