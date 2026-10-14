package com.onticket.admission;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/** Standalone trusted local operator command, not a Spring application or HTTP route. */
public final class AdmissionBootstrapCommand {
    private static final Set<String> ALLOWED = Set.of("onticket.admission.enabled", "onticket.admission.concert-id",
            "onticket.admission.concert-time-id", "onticket.admission.generation", "onticket.admission.opens-at",
            "onticket.admission.max-active", "onticket.admission.max-waiting", "onticket.admission.waiting-ttl",
            "onticket.admission.admission-ttl", "onticket.admission.batch-size", "onticket.admission.redis-port");
    private AdmissionBootstrapCommand() { }

    public static void main(String[] args) {
        int status = run(args, System.out::println);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args, Consumer<String> output) {
        return run(args, AdmissionBootstrapCommand::bootstrap, output);
    }

    static int run(String[] args, Consumer<AdmissionSettings> action, Consumer<String> output) {
        AdmissionSettings settings;
        try {
            Map<String, String> options = new HashMap<>();
            for (String arg : args) {
                int separator = arg.indexOf('=');
                if (separator <= 0 || separator == arg.length() - 1
                        || options.putIfAbsent(arg.substring(0, separator), arg.substring(separator + 1)) != null) {
                    throw new IllegalArgumentException();
                }
            }
            if (!options.keySet().equals(Set.of("--policy-file", "--confirm-new-generation"))) throw new IllegalArgumentException();
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(options.get("--policy-file")), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            if (!ALLOWED.containsAll(properties.stringPropertyNames())) throw new IllegalArgumentException();
            var values = new HashMap<String, Object>(); properties.forEach((key, value) -> values.put(key.toString(), value));
            settings = new Binder(new MapConfigurationPropertySource(values))
                    .bind("onticket.admission", Bindable.of(AdmissionSettings.class)).orElseThrow(IllegalArgumentException::new);
            settings.policy();
            if (!settings.generation().equals(options.get("--confirm-new-generation"))) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            output.accept("INVALID_ADMISSION_BOOTSTRAP_REQUEST"); return 2;
        }
        try {
            action.accept(settings);
            output.accept("ADMISSION_BOOTSTRAP_CREATED"); return 0;
        } catch (RuntimeException unavailable) {
            // No stack trace, file contents, policy, credentials or Redis exception cause.
            output.accept("ADMISSION_BOOTSTRAP_FAILED: namespace must be fresh and Redis available"); return 1;
        }
    }

    private static void bootstrap(AdmissionSettings settings) {
        var factory = AdmissionRedisConnections.loopback(settings.redisPort());
        try {
            factory.afterPropertiesSet(); factory.start();
            var redis = new StringRedisTemplate(factory);
            new RedisAdmissionStore(redis, settings.policy()).bootstrapFresh();
        } finally { factory.destroy(); }
    }
}
