package de.swfdtf.e2e.support;

import org.junit.jupiter.api.Assumptions;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Canonical JUnit 5 tag constants and pending-wave verification helpers for the KAGAYAKI
 * opaque-box E2E test suite.
 *
 * <p>Pre-M7 baseline runs filter tests via JUnit 5 {@link #TAG_BASELINE} (for example, using a
 * temporary Gradle init script with {@code useJUnitPlatform { includeTags 'baseline' }} or JUnit
 * ConsoleLauncher {@code --include-tag=baseline}), because Gradle's {@code e2eTest} task does not
 * forward {@code -Djunit.jupiter.tags.include} or {@code -Dkagayaki.e2e.allowPendingSkip=true} to
 * forked test worker JVMs.
 */
public final class E2ePendingFeatureAssumptions {

    /** JUnit tag for Tier 1 primary happy-path end-to-end tests. */
    public static final String TAG_TIER1 = "tier1";

    /** JUnit tag for Tier 2 boundary, security, and negative end-to-end tests. */
    public static final String TAG_TIER2 = "tier2";

    /** JUnit tag for Tier 3 pairwise cross-feature combination tests. */
    public static final String TAG_TIER3 = "tier3";

    /** JUnit tag for Tier 4 realistic end-to-end application workload tests. */
    public static final String TAG_TIER4 = "tier4";

    /** JUnit tag for foundational opaque-box tests runnable against the baseline codebase. */
    public static final String TAG_BASELINE = "baseline";

    /** JUnit tag for tests targeting Wave 2-7 features prior to final milestone integration. */
    public static final String TAG_PENDING_WAVE = "pending-wave";

    /** System property enabling explicit assumption-skipping only during pre-M7 baseline runs. */
    public static final String ALLOW_PENDING_SKIP_PROPERTY = "kagayaki.e2e.allowPendingSkip";

    private E2ePendingFeatureAssumptions() {
    }

    /**
     * Returns whether programmatic pending-wave assumption skipping is explicitly enabled via
     * {@code -Dkagayaki.e2e.allowPendingSkip=true} inside the test worker JVM.
     *
     * <p>Note: pre-M7 Gradle verification should filter via JUnit {@code @Tag("baseline")} rather
     * than relying on this property, unless the build forwards it to the forked {@code e2eTest}
     * worker JVM.
     *
     * @return {@code true} only when explicit skip mode is enabled
     */
    public static boolean isPendingSkipAllowed() {
        return Boolean.getBoolean(ALLOW_PENDING_SKIP_PROPERTY);
    }

    /**
     * Verifies a Wave-gated readiness predicate. By default (including M7 acceptance), a {@code false}
     * result fails the test via {@link AssertionError}; only when
     * {@code -Dkagayaki.e2e.allowPendingSkip=true} is forwarded to the test JVM does it convert to a
     * JUnit assumption abort. Pre-M7 baseline runs should filter by {@link #TAG_BASELINE} via JUnit
     * tag selection.
     *
     * @param goalId     roadmap goal identifier (e.g., {@code "#0033"})
     * @param detail     human-readable requirement description
     * @param readyCheck readiness predicate to evaluate
     */
    public static void assumePendingOrFail(String goalId, String detail, BooleanSupplier readyCheck) {
        Objects.requireNonNull(goalId, "goalId must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(readyCheck, "readyCheck must not be null");

        boolean ready = readyCheck.getAsBoolean();
        if (ready) {
            return;
        }
        String message = "Pending Wave feature " + goalId + " not satisfied: " + detail;
        if (isPendingSkipAllowed()) {
            Assumptions.abort(message);
        }
        throw new AssertionError(message);
    }
}
