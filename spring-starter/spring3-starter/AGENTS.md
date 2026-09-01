# AGENTS.md

This file refines the root and `spring-starter/` rules for `spring-starter/spring3-starter/`.

## Platform rules

- This module targets Spring Boot 3.x and Java 17.
- Use `jakarta.servlet` APIs, not `javax.servlet`.
- Keep dependency and annotation usage compatible with Spring Framework 6 / Boot 3 conventions.

## Change boundaries

- Maintain parity with `../spring2-starter` for user-visible behavior, default beans, properties, and interceptor wiring unless the platform requires a different implementation detail.
- This module may use Java 17 language features when they materially help, but avoid divergence that makes Spring 2 parity difficult to maintain without a clear reason.
- Web masking, auto-configuration ordering, and bean conditions should stay explicit and test-backed.

## Testing focus

- Prefer `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am test` as the primary acceptance command.
- Update Spring Boot 3 auto-configuration and integration tests when changing bean registration, servlet/web masking, or interceptor execution.
- Watch for regressions around `jakarta` imports, web auto-configuration conditions, and single-registration guarantees.
