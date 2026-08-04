package com.dede.maven;

import com.dede.DedeApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Separate Spring Boot bootstrap for the Maven plugin, deliberately NOT reusing
 * {@link DedeApplication} directly.
 *
 * DedeApplication defines a CommandLineRunner bean that -- as of the CLI exit-code
 * fix -- calls System.exit() when it finishes. If this class scanned and picked up
 * DedeApplication itself, that bean would be registered and would run on context
 * startup, and System.exit() would kill the *consuming project's entire Maven JVM*
 * mid-build instead of failing this one Mojo cleanly. Excluding DedeApplication from
 * the component scan means its @Bean methods are never registered at all.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = "com.dede",
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DedeApplication.class)
)
public class MojoBootstrapConfig {
}
