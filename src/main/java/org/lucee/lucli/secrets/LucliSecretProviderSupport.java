package org.lucee.lucli.secrets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.lucee.lucli.LuceeScriptEngine;
import org.lucee.lucli.Settings;
import org.lucee.lucli.paths.LucliPaths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared helper methods for LuCLI <-> Lucee secret provider interoperability.
 */
public final class LucliSecretProviderSupport {

    public static final String LOCAL_PROVIDER_NAME = LucliLocalSecretProvider.DEFAULT_PROVIDER_NAME;
    public static final String LOCAL_PROVIDER_CLASS = LucliLocalSecretProvider.class.getName();
    public static final String DEFAULT_PASSPHRASE_ENV = LucliLocalSecretProvider.DEFAULT_PASSPHRASE_ENV;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LucliSecretProviderSupport() {
    }

    public static Path getLocalStorePath() {
        return LucliPaths.resolve().secretsStoreFile();
    }

    public static boolean hasLocalStoreFile() {
        return Files.exists(getLocalStorePath());
    }

    public static String normalizeProviderName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return LOCAL_PROVIDER_NAME;
        }
        return value.trim();
    }

    public static boolean isLocalProviderName(String value) {
        String normalized = normalizeProviderName(value).toLowerCase(Locale.ROOT);
        return LOCAL_PROVIDER_NAME.equals(normalized) || "local".equals(normalized);
    }

    public static String getSelectedProviderName() {
        Settings settings = new Settings();
        return normalizeProviderName(settings.getSelectedSecretProvider());
    }

    public static void setSelectedProviderName(String providerName) {
        Settings settings = new Settings();
        settings.setSelectedSecretProvider(normalizeProviderName(providerName));
    }

    public static ObjectNode createLocalProviderDefinition(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : OBJECT_MAPPER;
        ObjectNode local = mapper.createObjectNode();
        local.put("class", LOCAL_PROVIDER_CLASS);

        ObjectNode custom = mapper.createObjectNode();
        custom.put("file", getLocalStorePath().toAbsolutePath().normalize().toString());
        custom.put("passphraseEnv", DEFAULT_PASSPHRASE_ENV);
        custom.put("allowPrompt", false);
        local.set("custom", custom);
        return local;
    }

    /**
     * Ensure a fallback local secret provider exists in CFConfig when no provider is defined.
     *
     * <p>Rules:
     * <ul>
     *   <li>If no local store exists, configuration is returned unchanged.</li>
     *   <li>If secretProvider is already present and non-empty, configuration is unchanged.</li>
     *   <li>Otherwise, a {@code lucli-local} provider entry is injected.</li>
     * </ul>
     */
    public static JsonNode ensureFallbackSecretProvider(JsonNode cfConfig, ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : OBJECT_MAPPER;
        if (!hasLocalStoreFile()) {
            return cfConfig;
        }

        ObjectNode base;
        if (cfConfig == null || cfConfig.isNull()) {
            base = mapper.createObjectNode();
        } else if (cfConfig.isObject()) {
            base = ((ObjectNode) cfConfig).deepCopy();
        } else {
            // Non-object CFConfig is unexpected, but preserve behaviour by replacing with object.
            base = mapper.createObjectNode();
        }

        JsonNode existingProviders = base.get("secretProvider");
        boolean hasProviders = existingProviders != null
            && existingProviders.isObject()
            && existingProviders.size() > 0;

        if (!hasProviders) {
            ObjectNode providers = mapper.createObjectNode();
            providers.set(LOCAL_PROVIDER_NAME, createLocalProviderDefinition(mapper));
            base.set("secretProvider", providers);
        }

        return base;
    }

    public static String getFallbackSecretProviderJson() {
        if (!hasLocalStoreFile()) {
            return "";
        }
        ObjectNode providers = OBJECT_MAPPER.createObjectNode();
        providers.set(LOCAL_PROVIDER_NAME, createLocalProviderDefinition(OBJECT_MAPPER));
        return providers.toString();
    }

    public static LocalSecretStore openLocalStore(char[] passphrase) throws SecretStoreException {
        return new LocalSecretStore(getLocalStorePath(), passphrase);
    }

    public static char[] resolvePassphrase(char[] provided, String prompt) {
        if (provided != null && provided.length > 0) {
            return provided;
        }
        String env = System.getenv(DEFAULT_PASSPHRASE_ENV);
        if (env != null && !env.isBlank()) {
            return env.toCharArray();
        }
        if (System.console() != null) {
            char[] prompted = System.console().readPassword("%s", prompt);
            if (prompted != null && prompted.length > 0) {
                return prompted;
            }
        }
        return null;
    }

    public static String readSecretViaLuceeProvider(String providerName, String secretName) throws Exception {
        String provider = normalizeProviderName(providerName);
        String escapedProvider = escapeForCfml(provider);
        String escapedName = escapeForCfml(secretName);

        String script = """
__lucliSecretReadError = "";
__lucliSecretReadValue = javacast("null", "");
try {
    __tmpSecretValue = SecretProviderGet('%s', '%s');
    if (isNull(__tmpSecretValue)) {
        __lucliSecretReadValue = javacast("null", "");
    } else {
        __lucliSecretReadValue = toString(__tmpSecretValue);
    }
}
catch(any e) {
    __lucliSecretReadError = e.message ?: "Secret provider read failed";
}
""".formatted(escapedName, escapedProvider);

        LuceeScriptEngine engine = LuceeScriptEngine.getInstance();
        engine.eval(script);
        Object errObj = engine.getEngine().get("__lucliSecretReadError");
        String error = errObj != null ? String.valueOf(errObj).trim() : "";
        if (!error.isEmpty()) {
            throw new IllegalStateException(error);
        }
        Object valueObj = engine.getEngine().get("__lucliSecretReadValue");
        return valueObj != null ? String.valueOf(valueObj) : null;
    }

    public static String readSecretFromLocalStore(
        String secretName,
        char[] passphraseOverride,
        boolean required,
        String prompt
    ) throws Exception {
        char[] passphrase = resolvePassphrase(passphraseOverride, prompt);
        if (passphrase == null || passphrase.length == 0) {
            if (required) {
                throw new IllegalStateException(
                    "No passphrase available for local secret store. Set " + DEFAULT_PASSPHRASE_ENV +
                    " or run interactively."
                );
            }
            return null;
        }

        if (!hasLocalStoreFile()) {
            if (required) {
                throw new IllegalStateException(
                    "Local secret store not found at " + getLocalStorePath() +
                    ". Run '" + org.lucee.lucli.LuCLI.cliName() + " secrets init' first."
                );
            }
            return null;
        }

        LocalSecretStore store = openLocalStore(passphrase);
        Optional<char[]> value = store.get(secretName);
        if (value.isEmpty()) {
            if (required) {
                throw new IllegalStateException("Secret '" + secretName + "' not found in local secret store.");
            }
            return null;
        }
        return new String(value.get());
    }

    private static String escapeForCfml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''").replace("#", "##");
    }
}
