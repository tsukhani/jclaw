/**
 * JCLAW-34: OpenTelemetry export. Types in this package default to non-null; only what
 * carries {@code @Nullable} may be null. NullAway enforces it on the Gradle
 * {@code compileJava} — the {@code services} prefix covers this package, but
 * {@code @NullMarked} does not reach subpackages, so it is declared here again.
 */
@NullMarked
package services.telemetry;

import org.jspecify.annotations.NullMarked;
