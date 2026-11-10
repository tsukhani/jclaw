package utils;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Whether a JVM is the AOT cache's training run, which {@code play aot-gen} boots and stops on spare ports between a
 * restart's two JVMs. JDK types only: a {@code conf/play.plugins} plugin reads it, and such a plugin compiles before
 * the application.
 */
public final class AotTraining {

    private AotTraining() {}

    /** Whether these JVM options record the AOT cache, as {@code play aot-gen} starts its training run. */
    public static boolean isTrainingRun(List<String> jvmArgs) {
        return jvmArgs.stream().anyMatch(arg -> arg.startsWith("-XX:AOTCacheOutput=") || arg.equals("-XX:AOTMode=record"));
    }

    public static boolean thisJvm() {
        return isTrainingRun(ManagementFactory.getRuntimeMXBean().getInputArguments());
    }
}
