# AGENTS.md

This file refines the root and `spring-starter/` rules for `spring-starter/spring2-starter/`.

## Platform rules

- This module targets Spring Boot 2.x and Java 8.
- Use `javax.servlet` APIs, not `jakarta.servlet`.
- Keep dependency and annotation usage compatible with Spring Framework 5 / Boot 2 conventions.

## Change boundaries

- Maintain parity with `../spring3-starter` for user-visible behavior, default beans, properties, and interceptor wiring unless the platform requires a different implementation detail.
- If copying logic from Spring 3, convert imports and annotations carefully instead of doing blind text substitution.
- Keep optional web integration truly optional; do not make MVC/servlet dependencies mandatory for non-web users.

## Testing focus

- Update Spring Boot 2 auto-configuration tests when changing bean conditions or defaults.
- Update integration tests when interceptor registration or execution order changes.
- Watch for regressions around duplicate interceptor registration and `javax` classpath conditions.
