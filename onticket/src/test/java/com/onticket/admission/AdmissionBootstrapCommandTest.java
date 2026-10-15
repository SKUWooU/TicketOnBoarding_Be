package com.onticket.admission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class AdmissionBootstrapCommandTest {
    @TempDir Path directory;
    final ArrayList<String> output = new ArrayList<>();
    final AtomicReference<AdmissionSettings> received = new AtomicReference<>();
    Path policy(String additional) throws Exception {
        var path = directory.resolve("admission.properties");
        Files.writeString(path, """
                onticket.admission.enabled=true
                onticket.admission.concert-id=LOAD-TEST-cli
                onticket.admission.concert-time-id=7
                onticket.admission.generation=cli-g1
                onticket.admission.opens-at=2030-01-01T00:00:00Z
                """ + additional);
        return path;
    }
    String[] args(Path path, String generation) {
        return new String[]{"--policy-file=" + path, "--confirm-new-generation=" + generation};
    }
    @Test void explicitConfirmationAndBoundedPolicyAreRequiredBeforeAction() throws Exception {
        var path = policy("");
        assertThat(AdmissionBootstrapCommand.run(args(path, "wrong"), received::set, output::add)).isEqualTo(2);
        assertThat(received.get()).isNull();
        assertThat(AdmissionBootstrapCommand.run(args(path, "cli-g1"), received::set, output::add)).isZero();
        assertThat(received.get().policy().maxActive()).isEqualTo(2);
        assertThat(output).containsExactly("INVALID_ADMISSION_BOOTSTRAP_REQUEST", "ADMISSION_BOOTSTRAP_CREATED");
    }
    @Test void unexpectedFlagsMissingFilesAndDuplicateFlagsDoNotRunAction() throws Exception {
        var path = policy("");
        for (var options : new String[][] {new String[]{"--policy-file=" + path},
                args(directory.resolve("missing"), "cli-g1"),
                new String[]{"--policy-file=" + path, "--policy-file=" + path, "--confirm-new-generation=cli-g1"},
                new String[]{"--host=external", "--confirm-new-generation=cli-g1"}}) {
            assertThat(AdmissionBootstrapCommand.run(options, received::set, output::add)).isEqualTo(2);
        }
        assertThat(received.get()).isNull();
    }
    @Test void unrelatedSensitiveConfigurationAndInvalidPolicyAreRejectedWithoutEcho() throws Exception {
        var path = policy("jwt.secret=do-not-log-this\n");
        assertThat(AdmissionBootstrapCommand.run(args(path, "cli-g1"), received::set, output::add)).isEqualTo(2);
        path = policy("onticket.admission.max-active=1001\n");
        assertThat(AdmissionBootstrapCommand.run(args(path, "cli-g1"), received::set, output::add)).isEqualTo(2);
        assertThat(received.get()).isNull();
        assertThat(output.toString()).doesNotContain("do-not-log-this", path.toString());
    }
    @Test void operationalFailureHasNonzeroExitAndNoRawCause() throws Exception {
        var path = policy("");
        int code = AdmissionBootstrapCommand.run(args(path, "cli-g1"), settings -> {
            throw new IllegalStateException("token-sensitive raw Redis failure");
        }, output::add);
        assertThat(code).isEqualTo(1);
        assertThat(output).hasSize(1);
        assertThat(output.getFirst()).startsWith("ADMISSION_BOOTSTRAP_FAILED").doesNotContain("token-sensitive");
    }
}
