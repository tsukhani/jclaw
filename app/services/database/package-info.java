/**
 * JCLAW-1165: database backup, restore and repair. Types in this package default to
 * non-null; NullAway enforces it on the Gradle {@code compileJava}. The {@code services}
 * prefix covers this package, but {@code @NullMarked} does not reach subpackages, so it
 * is declared here again.
 */
@NullMarked
package services.database;

import org.jspecify.annotations.NullMarked;
