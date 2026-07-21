import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guardrails, enforced as ordinary unit tests via ArchUnit.
 *
 * <p>The static audit waves keep re-finding the same class of debt: the project
 * invests in a canonical seam (HttpFactories, GsonHolder, the OkHttp-only outbound
 * stack), then new code hand-rolls the same logic beside the available import and
 * the "single source of truth" quietly erodes. Import hygiene is the one dimension
 * that never regresses precisely because it is <em>build-enforced</em> (Spotless +
 * a pre-push grep gate) rather than left to reviewer memory. This test extends that
 * same "make it a gate, not a convention" treatment to a few high-value seams.
 *
 * <p>ArchUnit reads compiled bytecode, so these rules need no Play runtime — they
 * import the app's class files directly and assert against constructor calls and
 * package dependencies. A violation fails the check exactly like any other test.
 *
 * <p>Rules 1 and 2 currently hold clean. Rule 3 carries an explicit, visible list
 * of the three pre-existing {@code new Gson()} offenders the wave-5 audit found;
 * the rule guards every <em>other</em> class from regressing, and each excluded
 * entry should be deleted as that class migrates to {@code GsonHolder.GSON}.
 * When a baseline like this grows past a handful of sites, switch the rule to
 * {@link com.tngtech.archunit.library.freeze.FreezingArchRule#freeze} instead,
 * which manages the baseline in a committed violation store.
 */
class ArchitectureTest extends UnitTest {

    /** The app's compiled production classes, read from disk (ArchUnit works on bytecode). */
    private static final JavaClasses APP_CLASSES = importAppClasses();

    /**
     * Import ONLY the Gradle main-source output — {@code build/classes/java/main} — which the
     * {@code org.playframework.play1} {@code :playAutotest} task refreshes via its {@code :compileJava}
     * dependency (and {@code ./gradlew test} likewise). This is deliberately a fixed, app-only path
     * rather than the running JVM's {@code CodeSource}: under Play's FirePhoque test harness the app
     * classes are loaded from a broad, stale precompiled/framework location, so resolving via
     * {@code CodeSource} would scan the wrong bytecode entirely. Reading the Gradle output keeps the
     * rules scoped to freshly-compiled {@code app/} classes under every runner.
     */
    private static JavaClasses importAppClasses() {
        Path mainClasses = Paths.get("build/classes/java/main");
        if (!Files.isDirectory(mainClasses)) {
            throw new IllegalStateException("ArchitectureTest: " + mainClasses.toAbsolutePath()
                    + " not found — it is populated by :compileJava (a :playAutotest dependency); "
                    + "run the rules via `play autotest` or `./gradlew test`.");
        }
        return new ClassFileImporter().importPath(mainClasses);
    }

    /** Matches a call to {@code new <ownerFqn>(...)}; when {@code noArgOnly}, only the
     * zero-parameter constructor. Bytecode owner names use {@code $} for nested classes. */
    private static DescribedPredicate<JavaConstructorCall> constructorCall(String ownerFqn, boolean noArgOnly) {
        return DescribedPredicate.describe(
                "a call to new " + ownerFqn + (noArgOnly ? "()" : "(...)"),
                call -> {
                    var target = call.getTarget();
                    if (!target.getOwner().getName().equals(ownerFqn)) {
                        return false;
                    }
                    return !noArgOnly || target.getRawParameterTypes().isEmpty();
                });
    }

    /**
     * Outbound OkHttp clients must be provisioned through {@code HttpFactories}
     * (JCLAW-185..188), which owns the shared connection pools and virtual-thread
     * dispatcher. The two documented exceptions build their own tuned clients:
     * {@code SsrfGuard} (per-request DNS allow-list) and {@code TelegramBotApiHttpClients}
     * (the Telegram SDK's stack). Deriving a per-call client via {@code someFactoryClient
     * .newBuilder()} is fine — it reuses the pool — so this rule targets only the
     * from-scratch {@code new OkHttpClient.Builder()} constructor.
     */
    @Test
    void okHttpClientsAreProvisionedThroughHttpFactories() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName("utils.HttpFactories")
                .and().doNotHaveFullyQualifiedName("utils.SsrfGuard")
                .and().doNotHaveFullyQualifiedName("channels.TelegramBotApiHttpClients")
                .should().callConstructorWhere(constructorCall("okhttp3.OkHttpClient$Builder", true))
                .because("outbound OkHttp clients must come from HttpFactories (JCLAW-185..188); "
                        + "SsrfGuard and the Telegram SDK are the documented exceptions");
        rule.check(APP_CLASSES);
    }

    /**
     * Outbound HTTP in app/ is OkHttp-only; the JDK {@code java.net.http.HttpClient}
     * stack was removed in the OkHttp migration (JCLAW-185..188) to avoid the LM Studio
     * h2c-upgrade hang and to keep a single virtual-thread-clean client. This guards
     * against the second stack creeping back in.
     */
    @Test
    void noJdkHttpClientInApp() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("java.net.http..")
                .because("outbound HTTP is OkHttp-only in app/ (JCLAW-185..188); the JDK HttpClient was removed");
        rule.check(APP_CLASSES);
    }

    /**
     * A default-configured {@code new Gson()} silently bypasses {@code GsonHolder.GSON}'s
     * wire-format contract — {@code serializeNulls()}, the ISO-8601 {@code Instant} adapter,
     * and the deliberate HTML-escape setting (JCLAW-686/730). Purpose-built {@code GsonBuilder}
     * instances (e.g. the pretty-printer, the compact serializer) are legitimate and not
     * matched here; only the bare no-arg constructor is.
     *
     * <p>This rule is fully strict — the wave-5 remediation migrated the last three
     * offenders ({@code DiarizeAudioTool}, {@code AcpHarnessProbe}, {@code VideoInterpretationClient})
     * to {@code GsonHolder.GSON}, so any bare {@code new Gson()} anywhere but the holder now fails.
     */
    @Test
    void defaultGsonConstructionGoesThroughGsonHolder() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName("utils.GsonHolder")
                .should().callConstructorWhere(constructorCall("com.google.gson.Gson", true))
                .because("a bare `new Gson()` bypasses GsonHolder.GSON's serializeNulls / "
                        + "Instant-adapter / HTML-escape contract (JCLAW-686/730)");
        rule.check(APP_CLASSES);
    }

    /**
     * Every model-status/prefetch store must extend {@code ModelPrefetchStore}, the shared base
     * that owns the {@code State} enum + {@code wireName()} frontend-lockstep contract, the
     * single-flight prefetch primitive, and the status ladder (wave-5 DRY consolidation). This
     * stops a third {@code *ModelStore} from re-copying that machine the way {@code AsrModelStore}
     * and {@code DiarizeModelStore} once did — the exact duplication the base was extracted to end.
     */
    @Test
    void modelStoresExtendTheSharedPrefetchBase() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("ModelStore")
                .should().beAssignableTo("services.transcription.ModelPrefetchStore")
                .because("a *ModelStore must reuse ModelPrefetchStore's shared status/prefetch machine, "
                        + "not re-copy it (wave-5 DRY consolidation)");
        rule.check(APP_CLASSES);
    }
}
