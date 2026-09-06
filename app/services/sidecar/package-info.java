/**
 * JCLAW-1149: types in this package default to non-null; only what carries
 * {@code @Nullable} may be null. NullAway enforces it on the Gradle {@code compileJava}
 * (see {@code build.gradle.kts}). {@code @NullMarked} does not reach subpackages, so a
 * new one needs its own {@code package-info.java}.
 */
@NullMarked
package services.sidecar;

import org.jspecify.annotations.NullMarked;
