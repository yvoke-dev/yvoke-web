package de.palsoftware.yvoke.rag.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the MCP {@code ToolAnnotations} behavioural hints for a published tool class or
 * {@code @Tool} method. Every tool published by {@code McpToolsConfig} must declare this annotation
 * (on the {@code @Tool} method or its declaring class); registration fails fast at startup when it
 * is missing so a new mutating tool can never silently advertise {@code readOnlyHint = true}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface McpToolHints {

    boolean readOnly();

    boolean destructive();

    boolean idempotent();

    boolean openWorld() default false;
}
