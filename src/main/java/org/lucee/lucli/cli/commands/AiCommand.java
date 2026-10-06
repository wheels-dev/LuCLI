package org.lucee.lucli.cli.commands;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import org.lucee.lucli.LuCLI;
import org.lucee.lucli.LuceeScriptEngine;
import org.lucee.lucli.StringOutput;
import org.lucee.lucli.paths.LucliPaths;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * AI command integration that relies on endpoints managed by the local Lucee instance.
 *
 * LuCLI stores only lightweight local defaults and skill configuration paths.
 */
@Command(
    name = "ai",
    description = "Use Lucee AI endpoints and prompts",
    mixinStandardHelpOptions = true,
    subcommands = {
        AiCommand.ConfigCommand.class,
        AiCommand.PromptCommand.class,
        AiCommand.ListCommand.class,
        AiCommand.TestCommand.class,
        AiCommand.SkillCommand.class,
        CommandLine.HelpCommand.class
    }
)
public class AiCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESPONSE_PROCESS_COOKIES_MARKER = "ResponseProcessCookies";
    private static final String DEFAULT_SYSTEM_MESSAGE = "Keep all answers as short as possible";
    private static final String DEFAULT_PROVIDER_TIMEOUT_MS = "5000";
    private static final String DEFAULT_PROVIDER_DEFAULT_MODE = "exception";
    private static final String OPENAI_ENGINE_CLASS = "lucee.runtime.ai.openai.OpenAIEngine";
    private static final String CLAUDE_ENGINE_CLASS = "lucee.runtime.ai.anthropic.ClaudeEngine";
    private static final String GEMINI_ENGINE_CLASS = "lucee.runtime.ai.google.GeminiEngine";
    private static final Set<String> OPENAI_COMPATIBLE_PROVIDER_TYPES = Set.of(
        "openai",
        "copilot",
        "deepseek",
        "grok",
        "ollama",
        "perplexity",
        "other"
    );
    private static final Set<String> APIKEY_PROVIDER_TYPES = Set.of("claude", "gemini");
    private static final Map<String, ProviderDefaults> GUIDED_PROVIDER_DEFAULTS = Map.of(
        "openai", new ProviderDefaults(OPENAI_ENGINE_CLASS, "gpt-4o", null),
        "copilot", new ProviderDefaults(OPENAI_ENGINE_CLASS, "gpt-4.1", null),
        "claude", new ProviderDefaults(CLAUDE_ENGINE_CLASS, "claude-sonnet-4-5", "https://api.anthropic.com/v1/"),
        "deepseek", new ProviderDefaults(OPENAI_ENGINE_CLASS, "deepseek-chat", null),
        "gemini", new ProviderDefaults(GEMINI_ENGINE_CLASS, "gemini-2.5-pro", "https://generativelanguage.googleapis.com/v1/"),
        "grok", new ProviderDefaults(OPENAI_ENGINE_CLASS, "grok-2-latest", null),
        "ollama", new ProviderDefaults(OPENAI_ENGINE_CLASS, "llama3.1", "http://localhost:11434/v1"),
        "perplexity", new ProviderDefaults(OPENAI_ENGINE_CLASS, "sonar", null)
    );
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".tif", ".tiff"
    );


    @Override
    public Integer call() throws Exception {
        new CommandLine(this).usage(System.out);
        return 0;
    }


    @Command(
        name = "config",
        aliases = {"configure"},
        description = "Manage Lucee AI config defaults and endpoint entries",
        mixinStandardHelpOptions = true,
        subcommands = {
            ConfigCommand.DefaultsCommand.class,
            ConfigCommand.AddCommand.class,
            ConfigCommand.ListCommand.class,
            CommandLine.HelpCommand.class
        }
    )
    static class ConfigCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        @Command(
            name = "defaults",
            description = "View/update LuCLI AI defaults",
            mixinStandardHelpOptions = true
        )
        static class DefaultsCommand implements Callable<Integer> {
            @Option(names = "--default-endpoint", description = "Default Lucee endpoint name")
            private String defaultEndpoint;

            @Option(names = "--default-model", description = "Default model hint used by LuCLI prompt templates")
            private String defaultModel;

            @Option(names = "--show", description = "Show current AI defaults")
            private boolean show;

            @Override
            public Integer call() throws Exception {
                AiLocalConfig config = loadAiLocalConfig();
                boolean changed = false;

                if (!isBlank(defaultEndpoint)) {
                    config.defaultEndpoint = defaultEndpoint.trim();
                    changed = true;
                }
                if (!isBlank(defaultModel)) {
                    config.defaultModel = defaultModel.trim();
                    changed = true;
                }

                if (changed) {
                    saveAiLocalConfig(config);
                    StringOutput.Quick.success("Saved AI defaults.");
                }

                if (show || changed || (!changed && isBlank(defaultEndpoint) && isBlank(defaultModel))) {
                    printAiDefaults(config);
                } else {
                    new CommandLine(this).usage(System.out);
                }
                return 0;
            }
        }

        @Command(
            name = "add",
            description = "Add/update an AI endpoint entry via CFConfig import",
            mixinStandardHelpOptions = true
        )
        static class AddCommand implements Callable<Integer> {
            @Option(names = "--name", description = "Endpoint name/key (e.g., mychatgpt)")
            private String name;

            @Option(names = "--class", description = "AI engine class (defaults by provider type)")
            private String className;
            @Option(names = "--type", defaultValue = "openai", description = "Provider type (openai, claude, gemini, copilot, deepseek, grok, ollama, perplexity, other)")
            private String type;

            @Option(names = "--url", description = "Optional custom endpoint URL")
            private String url;

            @Option(names = "--secret-key", description = "Secret/API key (supports placeholders like #env:CHATGPT_SECRET_KEY#)")
            private String secretKey;

            @Option(names = "--model", description = "Model name (e.g., gpt-4o)")
            private String model;

            @Option(names = "--message", defaultValue = DEFAULT_SYSTEM_MESSAGE, description = "System message")
            private String message;

            @Option(names = "--timeout", description = "Timeout in milliseconds")
            private Integer timeout;

            @Option(names = "--default-mode", defaultValue = DEFAULT_PROVIDER_DEFAULT_MODE, description = "Default mode for this endpoint entry")
            private String defaultMode;

            @Option(names = "--json", description = "Print imported structure as JSON")
            private boolean json;
            @Option(names = "--quiet", description = "Suppress printing imported config payload (useful for CI)")
            private boolean quiet;

            @Option(names = "--show", description = "Show full API key values in output (otherwise masked)")
            private boolean showSecrets;
            @Option(names = "--guided", description = "Interactive guided setup for AI connection fields")
            private boolean guided;

            @Option(names = "--test-after-save", description = "Run a lightweight connection test after saving")
            private boolean testAfterSave;

            @Override
            public Integer call() throws Exception {
                boolean shouldTestAfterSave = testAfterSave;

                if (guided) {
                    java.io.Console console = System.console();
                    if (console == null) {
                        StringOutput.Quick.error("No console available for guided mode. Re-run without --guided and pass flags directly.");
                        return 1;
                    }

                    Boolean guidedTestChoice = promptGuidedValues(console, shouldTestAfterSave);
                    if (guidedTestChoice == null) {
                        StringOutput.Quick.info("Aborted.");
                        return 0;
                    }
                    shouldTestAfterSave = guidedTestChoice;
                }
                if (isBlank(name)) {
                    StringOutput.Quick.error("Endpoint name is required.");
                    return 1;
                }
                type = normalizeProviderType(type);
                className = resolveEngineClass(className, type);

                AddConfigRequest request = new AddConfigRequest();
                request.name = name.trim();
                request.className = className;
                request.type = type;
                request.url = url;
                request.secretKey = secretKey;
                request.model = model;
                request.message = message;
                request.timeout = timeout;
                request.defaultMode = defaultMode;
                if (request.timeout != null && !shouldIncludeTimeoutField(request.type, request.className)) {
                    StringOutput.Quick.warning("Ignoring --timeout for OpenAI-compatible providers because it is sent as an unsupported request argument.");
                }

                PromptResult result = executeConfigAdd(request);
                StringOutput.Quick.success("AI endpoint config saved in Lucee local config: " + request.name);
                if (!quiet) {
                    if (json) {
                        String sourceJson = firstNonBlank(result.json, result.text);
                        System.out.println(prettyJsonOrRaw(redactSecretKeysInJsonString(sourceJson, showSecrets)));
                    } else if (!isBlank(result.text)) {
                        String sourceJson = firstNonBlank(result.json, result.text);
                        System.out.println(prettyJsonOrRaw(redactSecretKeysInJsonString(sourceJson, showSecrets)));
                    }
                }

                if (shouldTestAfterSave) {
                    return runConnectionTest(request.name);
                }
                return 0;
            }

            private Boolean promptGuidedValues(java.io.Console console, boolean currentTestChoice) throws Exception {
                StringOutput.Quick.info("Guided AI connection setup");

                name = promptRequired(console, "Endpoint name", name);
                type = normalizeProviderType(promptWithDefault(console, "Provider type", type));
                applyGuidedProviderDefaults(type);
                className = promptWithDefault(console, "Engine class", className);
                model = promptOptional(console, "Model", model);
                secretKey = promptSecret(console, "Secret/API key (supports #env:...# placeholders)", secretKey);
                url = promptOptional(console, "Custom URL", url);
                message = promptWithDefault(console, "System message", message);
                timeout = promptInteger(console, "Timeout (ms)", timeout, Integer.valueOf(DEFAULT_PROVIDER_TIMEOUT_MS));
                defaultMode = promptWithDefault(console, "Default mode", defaultMode);

                System.out.println();
                System.out.println("Summary:");
                System.out.println("  name: " + fallback(name));
                System.out.println("  type: " + fallback(type));
                System.out.println("  class: " + fallback(className));
                System.out.println("  model: " + fallback(model));
                System.out.println("  secretKey: " + (isBlank(secretKey) ? "(unset)" : "(set)"));
                System.out.println("  url: " + fallback(url));
                System.out.println("  timeout: " + (timeout == null ? "(unset)" : timeout));
                System.out.println("  default: " + fallback(defaultMode));
                System.out.println();

                if (!promptYesNo(console, "Save this AI connection?", true)) {
                    return null;
                }

                if (!currentTestChoice) {
                    currentTestChoice = promptYesNo(console, "Test connection after save?", false);
                }
                return currentTestChoice;
            }

            private void applyGuidedProviderDefaults(String providerType) {
                ProviderDefaults defaults = GUIDED_PROVIDER_DEFAULTS.get(providerType);
                if (defaults == null) {
                    return;
                }

                className = applyDefaultIfUnset(className, defaults.className);
                model = applyDefaultIfUnset(model, defaults.model);
                url = applyDefaultIfUnset(url, defaults.url);

                List<String> hints = new ArrayList<>();
                if (!isBlank(defaults.className)) {
                    hints.add("class=" + defaults.className);
                }
                if (!isBlank(defaults.model)) {
                    hints.add("model=" + defaults.model);
                }
                if (!isBlank(defaults.url)) {
                    hints.add("url=" + defaults.url);
                }
                if (!hints.isEmpty()) {
                    StringOutput.Quick.info("Suggested defaults for '" + providerType + "': " + String.join(", ", hints));
                }
            }

            private static String applyDefaultIfUnset(String currentValue, String defaultValue) {
                if (!isBlank(currentValue) || isBlank(defaultValue)) {
                    return currentValue;
                }
                return defaultValue;
            }


            private Integer runConnectionTest(String endpoint) {
                try {
                    PromptRequest request = new PromptRequest();
                    request.endpoint = endpoint;
                    request.text = "Respond with the word: pong";
                    request.files = new ArrayList<>();

                    PromptResult result = executePrompt(request);
                    StringOutput.Quick.success("AI endpoint test completed for '" + endpoint + "'.");

                    if (json) {
                        if (!isBlank(result.json)) {
                            System.out.println(prettyJsonOrRaw(result.json));
                        } else {
                            Map<String, Object> envelope = new LinkedHashMap<>();
                            envelope.put("result", result.text);
                            System.out.println(prettyJsonOrRaw(toJson(envelope)));
                        }
                    } else if (!isBlank(result.text)) {
                        System.out.println(result.text);
                    }
                    return 0;
                } catch (Exception e) {
                    return printFriendlyAiFailure("AI endpoint test failed", endpoint, e);
                }
            }

            private static String promptRequired(java.io.Console console, String label, String currentValue) {
                String value = promptWithDefault(console, label, currentValue);
                while (isBlank(value)) {
                    value = promptWithDefault(console, label, value);
                    if (isBlank(value)) {
                        System.out.println("Value is required.");
                    }
                }
                return value.trim();
            }

            private static String promptOptional(java.io.Console console, String label, String currentValue) {
                String value = promptWithDefault(console, label + " (optional)", currentValue);
                return isBlank(value) ? null : value.trim();
            }

            private static String promptWithDefault(java.io.Console console, String label, String currentValue) {
                String prompt = isBlank(currentValue) ? "%s: " : "%s [%s]: ";
                String input = isBlank(currentValue)
                    ? console.readLine(prompt, label)
                    : console.readLine(prompt, label, currentValue);

                if (input == null) {
                    return currentValue;
                }

                String trimmed = input.trim();
                if (trimmed.isEmpty()) {
                    return currentValue;
                }
                return trimmed;
            }

            private static String promptSecret(java.io.Console console, String label, String currentValue) {
                char[] input = console.readPassword("%s%s: ", label, isBlank(currentValue) ? " (optional)" : " (optional, press Enter to keep current)");
                if (input == null || input.length == 0) {
                    return currentValue;
                }
                return new String(input).trim();
            }

            private static Integer promptInteger(java.io.Console console, String label, Integer currentValue, Integer fallbackValue) {
                Integer effectiveDefault = currentValue != null ? currentValue : fallbackValue;
                while (true) {
                    String raw = promptWithDefault(console, label, effectiveDefault == null ? null : effectiveDefault.toString());
                    if (isBlank(raw)) {
                        if (effectiveDefault != null) {
                            return effectiveDefault;
                        }
                        System.out.println("Value is required.");
                        continue;
                    }
                    try {
                        return Integer.valueOf(raw.trim());
                    } catch (NumberFormatException nfe) {
                        System.out.println("Please enter a valid integer.");
                    }
                }
            }

            private static boolean promptYesNo(java.io.Console console, String label, boolean defaultValue) {
                while (true) {
                    String suffix = defaultValue ? " [Y/n]: " : " [y/N]: ";
                    String raw = console.readLine("%s%s", label, suffix);
                    if (raw == null || raw.trim().isEmpty()) {
                        return defaultValue;
                    }
                    String normalized = raw.trim().toLowerCase(Locale.ROOT);
                    if (normalized.equals("y") || normalized.equals("yes")) {
                        return true;
                    }
                    if (normalized.equals("n") || normalized.equals("no")) {
                        return false;
                    }
                    System.out.println("Please answer y or n.");
                }
            }

            private static String toJson(Object value) {
                try {
                    return MAPPER.writeValueAsString(value);
                } catch (Exception e) {
                    return String.valueOf(value);
                }
            }
        }

        @Command(
            name = "list",
            description = "List AI endpoint entries from Lucee server CFConfig",
            mixinStandardHelpOptions = true
        )
        static class ListCommand implements Callable<Integer> {
            @Option(names = "--name", description = "Filter by endpoint name (repeatable)")
            private List<String> names = new ArrayList<>();

            @Option(names = "--json", description = "Output as JSON")
            private boolean json;

            @Option(names = "--show", description = "Show full API key values (otherwise masked)")
            private boolean showSecrets;

            @Override
            public Integer call() throws Exception {
                Path cfConfigFile = luceeCfConfigFile();
                if (!Files.exists(cfConfigFile) || !Files.isRegularFile(cfConfigFile)) {
                    if (json) {
                        System.out.println("{}");
                    } else {
                        StringOutput.Quick.info("No Lucee CFConfig file found: " + cfConfigFile);
                    }
                    return 0;
                }

                ObjectNode allEntries = readLuceeAiEntries(cfConfigFile);
                LinkedHashSet<String> requested = normalizeNames(names);
                ObjectNode result = requested.isEmpty()
                    ? allEntries
                    : filterAiEntries(allEntries, requested);

                if (json) {
                    JsonNode output = redactedCopy(result, showSecrets);
                    System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
                    return 0;
                }

                if (!requested.isEmpty()) {
                    for (String name : requested) {
                        if (!allEntries.has(name)) {
                            StringOutput.Quick.warning("AI provider not found: " + name);
                        }
                    }
                }

                if (result.isEmpty()) {
                    StringOutput.Quick.info("No AI providers configured.");
                    return 0;
                }

                printAiConfigEntries(result, null, showSecrets);
                return 0;
            }
        }
    }

    private static String renderPromptResult(PromptResult result, boolean jsonOutput) throws Exception {
        if (jsonOutput) {
            if (!isBlank(result.json)) {
                return prettyJsonOrRaw(result.json);
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("result", result.text);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        }
        if (!isBlank(result.text)) {
            return result.text;
        }
        return null;
    }

    private static int emitPromptOutput(String output, Path outputFile, boolean force) {
        if (output == null) {
            return 0;
        }

        System.out.println(output);
        if (outputFile == null) {
            return 0;
        }

        try {
            writePromptOutputFile(outputFile, output, force);
            return 0;
        } catch (Exception e) {
            StringOutput.Quick.error("Failed to write prompt output file: " + e.getMessage());
            return 1;
        }
    }

    private static void writePromptOutputFile(Path outputFile, String output, boolean force) throws IOException {
        Path normalized = normalizePath(outputFile);
        if (Files.exists(normalized) && Files.isDirectory(normalized)) {
            throw new IOException("Output path is a directory: " + normalized);
        }
        if (Files.exists(normalized) && !force) {
            throw new IOException("Output file already exists: " + normalized + " (use --force to overwrite)");
        }

        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(normalized, output + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    @Command(
        name = "prompt",
        description = "Run one-shot prompt using a Lucee-managed endpoint",
        mixinStandardHelpOptions = true
    )
    static class PromptCommand implements Callable<Integer> {

        @Option(names = "--text", description = "Prompt text (plain text or @file)")
        private String text;
        @Option(names = "--image", description = "Image file path (repeatable)")
        private List<Path> images = new ArrayList<>();

        @Option(names = "--rules-file", description = "Rules file to attach as instructions (repeatable)")
        private List<Path> rulesFiles = new ArrayList<>();

        @Option(names = "--rules-folder", description = "Folder containing rules files to attach (repeatable)")
        private List<Path> rulesFolders = new ArrayList<>();

        @Option(names = "--endpoint", description = "Lucee endpoint name")
        private String endpointName;

        @Option(names = "--model", description = "Model hint (stored in request context)")
        private String model;

        @Option(names = "--system", description = "High-priority instructions for the AI (plain text or @file)")
        private String systemInstruction;

        @Option(names = "--temperature", description = "Temperature")
        private Double temperature;

        @Option(names = "--timeout", description = "Socket timeout in milliseconds")
        private Integer timeoutMillis;

        @Option(names = "--skill", description = "Skill name or explicit skill file path")
        private String skillName;

        @Option(names = "--skills-path", description = "Command-local skill search path (repeatable)")
        private List<Path> commandSkillPaths = new ArrayList<>();

        @Option(names = "--json", description = "Output raw JSON when possible")
        private boolean json;

        @Option(names = "--dry-run", description = "Show resolved prompt payload and exit without sending request")
        private boolean dryRun;
        @Option(names = "--estimate", description = "Show rough token/cost estimate and exit without sending request")
        private boolean estimate;
        @Option(names = "--assume-output-tokens", defaultValue = "400", description = "Assumed completion tokens used by --estimate")
        private Integer assumedOutputTokens;
        @Option(names = "--input-price-per-1m", description = "Optional input token USD price per 1M tokens for --estimate")
        private Double inputPricePerMillion;
        @Option(names = "--output-price-per-1m", description = "Optional output token USD price per 1M tokens for --estimate")
        private Double outputPricePerMillion;
        @Option(names = "--output-file", description = "Write rendered output to file")
        private Path outputFile;

        @Option(names = "--force", description = "Overwrite output file if it already exists")
        private boolean force;

        @Override
        public Integer call() throws Exception {
            AiLocalConfig config = loadAiLocalConfig();

            SkillDefinition skill = null;
            if (!isBlank(skillName)) {
                skill = resolveSkill(skillName.trim(), commandSkillPaths, LuCLI.verbose);
                if (skill == null) {
                    StringOutput.Quick.error("Skill not found: " + skillName);
                    return 1;
                }
            }

            String endpoint = firstNonBlank(endpointName, config.defaultEndpoint);
            if (isBlank(endpoint)) {
                StringOutput.Quick.error("No endpoint specified. Use --endpoint or set --default-endpoint via '" + org.lucee.lucli.LuCLI.cliName() + " ai config'.");
                return 1;
            }

            String finalText = firstNonBlank(readPromptText(text), skill != null ? skill.text : null);
            String baseSystem = firstNonBlank(readSystemInstruction(systemInstruction), skill != null ? skill.system : null);
            String finalModel = firstNonBlank(model, skill != null ? skill.model : null, config.defaultModel);
            Double finalTemperature = firstNonNull(temperature, skill != null ? skill.temperature : null);
            Integer finalTimeout = firstNonNull(timeoutMillis, skill != null ? skill.timeoutMillis : null);
            List<Path> normalizedImages = normalizeAndValidateImages(images);
            List<Path> normalizedRuleFiles = normalizeAndCollectRuleFiles(rulesFiles, rulesFolders);
            String rulesBlock = buildRulesInstructionBlock(normalizedRuleFiles);
            String finalSystem = mergeInstructionBlocks(baseSystem, rulesBlock);

            if (isBlank(finalText) && normalizedImages.isEmpty()) {
                StringOutput.Quick.error("Prompt content is empty. Provide --text and/or at least one --image.");
                return 1;
            }

            if (!isBlank(finalModel) && LuCLI.verbose) {
                StringOutput.Quick.warning("Model override is a hint in this pass; active endpoint/model selection is controlled by Lucee endpoint config.");
            }
            if (!normalizedRuleFiles.isEmpty() && LuCLI.verbose) {
                LuCLI.verbose("Attached %d rules file(s).", normalizedRuleFiles.size());
            }

            PromptRequest request = new PromptRequest();
            request.endpoint = endpoint;
            request.model = finalModel;
            request.system = finalSystem;
            request.text = finalText;
            request.temperature = finalTemperature;
            request.timeoutMillis = finalTimeout;
            request.files = normalizedImages.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .collect(Collectors.toList());
            if (estimate) {
                PromptEstimate promptEstimate = buildPromptEstimate(
                    request,
                    firstNonNull(assumedOutputTokens, Integer.valueOf(400)),
                    inputPricePerMillion,
                    outputPricePerMillion
                );
                return emitPromptOutput(renderPromptEstimate(promptEstimate, json), outputFile, force);
            }

            if (dryRun) {
                String dryRunOutput = renderPromptDryRun(request, normalizedRuleFiles, skill, json);
                return emitPromptOutput(dryRunOutput, outputFile, force);
            }
            PromptResult result;
            try {
                result = executePrompt(request);
            } catch (Exception e) {
                return printFriendlyAiFailure("AI prompt failed", endpoint, e);
            }
            String renderedOutput = renderPromptResult(result, json);
            return emitPromptOutput(renderedOutput, outputFile, force);
        }
    }

    @Command(
        name = "list",
        description = "List AI providers/endpoints configured in Lucee server CFConfig",
        mixinStandardHelpOptions = true
    )
    static class ListCommand implements Callable<Integer> {

        @Option(names = "--name", description = "Filter by endpoint/provider name (repeatable)")
        private List<String> endpointNames = new ArrayList<>();
        @Option(names = "--refresh", description = "Compatibility flag (currently no-op)")
        private boolean refresh;

        @Option(names = "--json", description = "Output as JSON")
        private boolean json;

        @Option(names = "--show", description = "Show full API key values (otherwise masked)")
        private boolean showSecrets;

        @Override
        public Integer call() throws Exception {
            Path cfConfigFile = luceeCfConfigFile();
            if (!Files.exists(cfConfigFile) || !Files.isRegularFile(cfConfigFile)) {
                if (json) {
                    System.out.println("{}");
                } else {
                    StringOutput.Quick.info("No Lucee CFConfig file found: " + cfConfigFile);
                }
                return 0;
            }
            AiLocalConfig config = loadAiLocalConfig();
            ObjectNode allEntries = readLuceeAiEntries(cfConfigFile);
            LinkedHashSet<String> requested = normalizeNames(endpointNames);
            ObjectNode result = requested.isEmpty()
                ? allEntries
                : filterAiEntries(allEntries, requested);
            if (refresh && (LuCLI.verbose || LuCLI.debug)) {
                StringOutput.Quick.warning("--refresh is currently ignored because Lucee AIGetMetaData does not support a refresh argument.");
            }
            if (json) {
                JsonNode output = redactedCopy(result, showSecrets);
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
                return 0;
            }
            if (!requested.isEmpty()) {
                for (String name : requested) {
                    if (!allEntries.has(name)) {
                        StringOutput.Quick.warning("AI provider not found: " + name);
                    }
                }
            }
            if (result.isEmpty()) {
                StringOutput.Quick.info("No AI providers configured.");
                return 0;
            }
            printAiConfigEntries(result, config.defaultEndpoint, showSecrets);
            return 0;
        }
    }

    @Command(
        name = "test",
        description = "Run a lightweight prompt against a Lucee-managed endpoint",
        mixinStandardHelpOptions = true
    )
    static class TestCommand implements Callable<Integer> {

        @Option(names = "--endpoint", description = "Endpoint name")
        private String endpointName;

        @Option(names = "--text", description = "Test prompt text", defaultValue = "Respond with the word: pong")
        private String promptText;

        @Override
        public Integer call() throws Exception {
            AiLocalConfig config = loadAiLocalConfig();
            String endpoint = firstNonBlank(endpointName, config.defaultEndpoint);
            if (isBlank(endpoint)) {
                StringOutput.Quick.error("No endpoint specified. Use --endpoint or set a default endpoint.");
                return 1;
            }

            PromptRequest request = new PromptRequest();
            request.endpoint = endpoint;
            request.text = firstNonBlank(promptText, "Respond with the word: pong");
            request.files = new ArrayList<>();
            PromptResult result;
            try {
                result = executePrompt(request);
            } catch (Exception e) {
                return printFriendlyAiFailure("AI endpoint test failed", endpoint, e);
            }
            StringOutput.Quick.success("AI endpoint test completed for '" + endpoint + "'.");
            if (!isBlank(result.text)) {
                System.out.println(result.text);
            }
            return 0;
        }
    }

    @Command(
        name = "skill",
        description = "Manage AI skills and skill search paths",
        mixinStandardHelpOptions = true,
        subcommands = {
            SkillCommand.PathCommand.class,
            SkillCommand.ListSkillsCommand.class,
            CommandLine.HelpCommand.class
        }
    )
    static class SkillCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        @Command(
            name = "path",
            description = "Manage global skill search paths",
            subcommands = {
                PathCommand.AddPathCommand.class,
                PathCommand.RemovePathCommand.class,
                PathCommand.ListPathsCommand.class,
                CommandLine.HelpCommand.class
            }
        )
        static class PathCommand implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                new CommandLine(this).usage(System.out);
                return 0;
            }

            @Command(name = "add", description = "Add a global skill path")
            static class AddPathCommand implements Callable<Integer> {
                @Parameters(index = "0", paramLabel = "<dir>", description = "Directory to add")
                private Path directory;

                @Override
                public Integer call() throws Exception {
                    Path normalized = normalizePath(directory);
                    if (!Files.isDirectory(normalized)) {
                        StringOutput.Quick.error("Skill path is not a directory: " + normalized);
                        return 1;
                    }

                    SkillPathsConfig config = loadSkillPathsConfig();
                    List<String> paths = config.paths == null ? new ArrayList<>() : new ArrayList<>(config.paths);
                    String value = normalized.toString();
                    if (!paths.contains(value)) {
                        paths.add(value);
                    }
                    config.paths = paths;
                    saveSkillPathsConfig(config);
                    StringOutput.Quick.success("Added skill path: " + value);
                    return 0;
                }
            }

            @Command(name = "remove", description = "Remove a global skill path")
            static class RemovePathCommand implements Callable<Integer> {
                @Parameters(index = "0", paramLabel = "<dir>", description = "Directory to remove")
                private Path directory;

                @Override
                public Integer call() throws Exception {
                    Path normalized = normalizePath(directory);
                    SkillPathsConfig config = loadSkillPathsConfig();
                    List<String> paths = config.paths == null ? new ArrayList<>() : new ArrayList<>(config.paths);
                    boolean removed = paths.remove(normalized.toString());
                    config.paths = paths;
                    saveSkillPathsConfig(config);
                    if (removed) {
                        StringOutput.Quick.success("Removed skill path: " + normalized);
                    } else {
                        StringOutput.Quick.warning("Skill path not found: " + normalized);
                    }
                    return 0;
                }
            }

            @Command(name = "list", description = "List global skill paths")
            static class ListPathsCommand implements Callable<Integer> {
                @Option(names = "--json", description = "Output as JSON")
                private boolean json;

                @Override
                public Integer call() throws Exception {
                    SkillPathsConfig config = loadSkillPathsConfig();
                    List<String> paths = config.paths == null ? new ArrayList<>() : config.paths;

                    if (json) {
                        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config));
                        return 0;
                    }

                    if (paths.isEmpty()) {
                        StringOutput.Quick.info("No global skill paths configured.");
                        return 0;
                    }
                    for (String p : paths) {
                        System.out.println(p);
                    }
                    return 0;
                }
            }
        }

        @Command(name = "list", description = "List discoverable skills across effective paths")
        static class ListSkillsCommand implements Callable<Integer> {

            @Option(names = "--skills-path", description = "Command-local skill path override (repeatable)")
            private List<Path> commandSkillPaths = new ArrayList<>();

            @Option(names = "--json", description = "Output as JSON")
            private boolean json;

            @Override
            public Integer call() throws Exception {
                Map<String, SkillDefinition> skills = discoverSkillDefinitions(commandSkillPaths, LuCLI.verbose);
                if (skills.isEmpty()) {
                    StringOutput.Quick.info("No skills discovered.");
                    return 0;
                }

                if (json) {
                    System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(skills));
                    return 0;
                }

                for (Map.Entry<String, SkillDefinition> entry : skills.entrySet()) {
                    SkillDefinition skill = entry.getValue();
                    String source = isBlank(skill.source) ? "(unknown source)" : skill.source;
                    String line = entry.getKey() + "  [" + source + "]";
                    if (!isBlank(skill.model)) {
                        line = line + " model=" + skill.model;
                    }
                    System.out.println(line);
                }
                return 0;
            }
        }
    }

    // ---------- Lucee execution helpers ----------

    private static PromptResult executePrompt(PromptRequest request) throws Exception {
        String payload = MAPPER.writeValueAsString(request);
        String script = buildPromptScript(payload);

        LuceeScriptEngine engine = LuceeScriptEngine.getInstance();
        evalPromptScriptWithFilteredStderr(engine, script);

        Object text = engine.getEngine().get("__lucliAiResultText");
        Object json = engine.getEngine().get("__lucliAiResultJson");

        PromptResult result = new PromptResult();
        result.text = text != null ? String.valueOf(text) : "";
        result.json = json != null ? String.valueOf(json) : "";
        return result;
    }

    private static void evalPromptScriptWithFilteredStderr(LuceeScriptEngine engine, String script) throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        Exception evalError = null;

        try (PrintStream capture = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            engine.eval(script);
        } catch (Exception e) {
            evalError = e;
        } finally {
            System.setErr(originalErr);
        }

        flushFilteredPromptStderr(capturedErr.toString(StandardCharsets.UTF_8), originalErr);

        if (evalError != null) {
            throw evalError;
        }
    }

    private static void flushFilteredPromptStderr(String captured, PrintStream originalErr) {
        if (isBlank(captured)) {
            return;
        }

        String[] lines = captured.split("\\R");
        for (String line : lines) {
            if (isBlank(line)) {
                continue;
            }

            if (line.contains(RESPONSE_PROCESS_COOKIES_MARKER)) {
                if (LuCLI.verbose || LuCLI.debug) {
                    LuCLI.verbose("⚠️ HTTP cookie warning (suppressed from normal output): %s", line.trim());
                }
                continue;
            }

            originalErr.println(line);
        }
    }

    private static PromptResult executeConfigAdd(AddConfigRequest request) throws Exception {
        ObjectNode importPayload = buildAiConfigImportPayload(request);
        String payload = MAPPER.writeValueAsString(importPayload);
        String escapedPayload = escapeForSingleQuotedCfml(payload);

        String script = """
cfg = deserializeJSON('%s');

configImport(
    data=cfg,
    password=request.SERVERADMINPASSWORD,
    type="server",
    flushExistingData=false
);

__lucliAiResultJson = serializeJSON(cfg);
__lucliAiResultText = __lucliAiResultJson;
""".formatted(escapedPayload);

        LuceeScriptEngine engine = LuceeScriptEngine.getInstance();
        engine.eval(script);

        Object text = engine.getEngine().get("__lucliAiResultText");
        Object json = engine.getEngine().get("__lucliAiResultJson");

        PromptResult result = new PromptResult();
        result.text = text != null ? String.valueOf(text) : "";
        result.json = json != null ? String.valueOf(json) : "";
        return result;
    }
    static ObjectNode buildAiConfigImportPayload(AddConfigRequest request) {
        String providerType = normalizeProviderType(request.type);
        String engineClass = resolveEngineClass(request.className, providerType);
        boolean geminiTemplate = isGeminiTemplate(providerType, engineClass);

        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("class", engineClass);
        ObjectNode custom = geminiTemplate
            ? buildGeminiCustomConfig(request)
            : buildStandardCustomConfig(request, providerType, engineClass);

        entry.set("custom", custom);
        if (geminiTemplate) {
            if (shouldIncludeGeminiDefaultMode(request.defaultMode)) {
                putIfNotBlank(entry, "default", request.defaultMode);
            }
        } else {
            putIfNotBlank(entry, "default", request.defaultMode);
        }

        ObjectNode ai = MAPPER.createObjectNode();
        ai.set(request.name, entry);

        ObjectNode cfg = MAPPER.createObjectNode();
        cfg.set("ai", ai);
        return cfg;
    }
    private static ObjectNode buildStandardCustomConfig(AddConfigRequest request, String providerType, String engineClass) {
        ObjectNode custom = MAPPER.createObjectNode();
        putIfNotBlank(custom, "message", request.message);
        putIfNotBlank(custom, "model", request.model);
        putIfNotBlank(custom, "url", request.url);
        if (request.timeout != null && shouldIncludeTimeoutField(providerType, engineClass)) {
            custom.put("timeout", request.timeout);
        }

        String apiKeyFieldName = shouldUseApiKeyField(providerType, engineClass) ? "apiKey" : "secretKey";
        putIfNotBlank(custom, apiKeyFieldName, request.secretKey);

        if (shouldIncludeTypeField(providerType, engineClass)) {
            putIfNotBlank(custom, "type", providerType);
        }
        return custom;
    }

    private static ObjectNode buildGeminiCustomConfig(AddConfigRequest request) {
        ObjectNode custom = MAPPER.createObjectNode();
        custom.put("connectTimeout", "2000");
        custom.put("beta", "true");
        custom.put("message", geminiMessageValue(request.message));
        putIfNotBlank(custom, "model", request.model);
        custom.put("temperature", "0.7");
        putIfNotBlank(custom, "apikey", request.secretKey);
        custom.put("socketTimeout", "20000");
        custom.put("conversationSizeLimit", "100");
        return custom;
    }

    private static String geminiMessageValue(String message) {
        if (isBlank(message)) {
            return "";
        }
        String trimmed = message.trim();
        if (DEFAULT_SYSTEM_MESSAGE.equals(trimmed)) {
            return "";
        }
        return trimmed;
    }

    private static boolean isGeminiTemplate(String providerType, String engineClass) {
        String normalizedProviderType = normalizeProviderType(providerType);
        if ("gemini".equals(normalizedProviderType)) {
            return true;
        }
        if (isBlank(engineClass)) {
            return false;
        }
        return GEMINI_ENGINE_CLASS.equalsIgnoreCase(engineClass.trim());
    }

    private static boolean shouldIncludeGeminiDefaultMode(String defaultMode) {
        if (isBlank(defaultMode)) {
            return false;
        }
        return !DEFAULT_PROVIDER_DEFAULT_MODE.equalsIgnoreCase(defaultMode.trim());
    }

    static String resolveEngineClass(String requestedClassName, String providerType) {
        if (!isBlank(requestedClassName)) {
            return requestedClassName.trim();
        }
        String normalizedProviderType = normalizeProviderType(providerType);
        return switch (normalizedProviderType) {
            case "claude" -> CLAUDE_ENGINE_CLASS;
            case "gemini" -> GEMINI_ENGINE_CLASS;
            default -> OPENAI_ENGINE_CLASS;
        };
    }

    static String normalizeProviderType(String value) {
        if (isBlank(value)) {
            return "openai";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean shouldUseApiKeyField(String providerType, String engineClass) {
        String normalizedProviderType = normalizeProviderType(providerType);
        if (APIKEY_PROVIDER_TYPES.contains(normalizedProviderType)) {
            return true;
        }
        if (isBlank(engineClass)) {
            return false;
        }
        String normalizedClassName = engineClass.trim();
        return CLAUDE_ENGINE_CLASS.equalsIgnoreCase(normalizedClassName)
            || GEMINI_ENGINE_CLASS.equalsIgnoreCase(normalizedClassName);
    }

    private static boolean shouldIncludeTypeField(String providerType, String engineClass) {
        String normalizedProviderType = normalizeProviderType(providerType);
        if (isBlank(engineClass)) {
            return false;
        }
        if (!OPENAI_ENGINE_CLASS.equalsIgnoreCase(engineClass.trim())) {
            return false;
        }
        if (APIKEY_PROVIDER_TYPES.contains(normalizedProviderType)) {
            return false;
        }
        return OPENAI_COMPATIBLE_PROVIDER_TYPES.contains(normalizedProviderType) || !isBlank(normalizedProviderType);
    }

    private static boolean shouldIncludeTimeoutField(String providerType, String engineClass) {
        String normalizedProviderType = normalizeProviderType(providerType);
        if (OPENAI_COMPATIBLE_PROVIDER_TYPES.contains(normalizedProviderType)) {
            return false;
        }
        if (isBlank(engineClass)) {
            return true;
        }
        return !OPENAI_ENGINE_CLASS.equalsIgnoreCase(engineClass.trim());
    }

    private static void putIfNotBlank(ObjectNode node, String fieldName, String value) {
        if (!isBlank(value)) {
            node.put(fieldName, value.trim());
        }
    }


    private static String buildPromptScript(String payloadJson) {
        String escapedPayload = escapeForSingleQuotedCfml(payloadJson);
        return """
payload = deserializeJSON('%s');

aiSession = createAISession(name=payload.endpoint);
questionText = payload.text ?: "";
if (structKeyExists(payload, "system") && len(trim(payload.system))) {
    if (len(trim(questionText))) {
        questionText = "[Instructions]\\n" & payload.system & "\\n\\n[Task]\\n" & questionText;
    } else {
        questionText = "[Instructions]\\n" & payload.system;
    }
}

question = "";
if (structKeyExists(payload, "files") && isArray(payload.files) && arrayLen(payload.files) > 0) {
    question = [];
    if (len(trim(questionText))) {
        arrayAppend(question, questionText);
    }
    for (filePath in payload.files) {
        arrayAppend(question, fileReadBinary(filePath));
    }
} else {
    question = questionText;
}

aiResponse = inquiryAISession(aiSession, question);

if (isSimpleValue(aiResponse)) {
    __lucliAiResultText = toString(aiResponse);
    __lucliAiResultJson = "";
} else {
    __lucliAiResultJson = serializeJSON(aiResponse);
    __lucliAiResultText = __lucliAiResultJson;
}
""".formatted(escapedPayload);
    }

    // ---------- File + skill resolution ----------

    private static List<Path> normalizeAndValidateImages(List<Path> images) {
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> validated = new ArrayList<>();
        for (Path path : images) {
            Path normalized = normalizePath(path);
            if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
                throw new IllegalArgumentException("File not found: " + normalized);
            }
            if (!isImageFile(normalized)) {
                throw new IllegalArgumentException("Unsupported file type for first-pass multimodal support: " + normalized);
            }
            validated.add(normalized);
        }
        return validated;
    }

    private static List<Path> normalizeAndCollectRuleFiles(List<Path> rulesFiles, List<Path> rulesFolders) throws IOException {
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();

        if (rulesFiles != null) {
            for (Path path : rulesFiles) {
                Path normalized = normalizePath(path);
                if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
                    throw new IllegalArgumentException("Rules file not found: " + normalized);
                }
                ordered.add(normalized);
            }
        }

        if (rulesFolders != null) {
            for (Path folder : rulesFolders) {
                Path normalizedFolder = normalizePath(folder);
                if (!Files.exists(normalizedFolder) || !Files.isDirectory(normalizedFolder)) {
                    throw new IllegalArgumentException("Rules folder not found: " + normalizedFolder);
                }

                try (Stream<Path> stream = Files.walk(normalizedFolder)) {
                    List<Path> discovered = stream
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .collect(Collectors.toList());
                    ordered.addAll(discovered);
                }
            }
        }

        return new ArrayList<>(ordered);
    }

    private static String buildRulesInstructionBlock(List<Path> ruleFiles) throws IOException {
        if (ruleFiles == null || ruleFiles.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        int included = 0;

        for (Path path : ruleFiles) {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (isBlank(content)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("Rule source: ").append(path).append("\n");
            builder.append(content.trim());
            included++;
        }

        if (included == 0) {
            return null;
        }

        return "Apply the following rules when producing your answer:\n\n" + builder;
    }

    private static String mergeInstructionBlocks(String primary, String secondary) {
        if (isBlank(primary)) {
            return secondary;
        }
        if (isBlank(secondary)) {
            return primary;
        }
        return primary.trim() + "\n\n" + secondary.trim();
    }

    private static String buildComposedQuestionText(String system, String text) {
        String normalizedText = isBlank(text) ? "" : text;
        if (isBlank(system)) {
            return normalizedText;
        }
        if (isBlank(normalizedText)) {
            return "[Instructions]\n" + system;
        }
        return "[Instructions]\n" + system + "\n\n[Task]\n" + normalizedText;
    }

    private static String renderPromptDryRun(PromptRequest request, List<Path> normalizedRuleFiles, SkillDefinition skill, boolean jsonOutput) throws Exception {
        ObjectNode envelope = buildPromptDryRunEnvelope(request, normalizedRuleFiles, skill);
        if (jsonOutput) {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        }

        StringBuilder output = new StringBuilder();
        output.append("AI prompt dry-run (no request sent)\n");
        output.append("  endpoint: ").append(fallback(request.endpoint)).append('\n');
        output.append("  model: ").append(fallback(request.model)).append('\n');
        output.append("  temperature: ").append(request.temperature == null ? "(unset)" : request.temperature).append('\n');
        output.append("  timeout: ").append(request.timeoutMillis == null ? "(unset)" : request.timeoutMillis).append('\n');
        if (skill != null) {
            output.append("  skill: ").append(fallback(skill.name)).append(isBlank(skill.source) ? "" : " (" + skill.source + ")").append('\n');
        }

        if (normalizedRuleFiles == null || normalizedRuleFiles.isEmpty()) {
            output.append("  rules files: (none)\n");
        } else {
            output.append("  rules files:\n");
            for (Path path : normalizedRuleFiles) {
                output.append("    - ").append(path.toAbsolutePath().normalize()).append('\n');
            }
        }

        if (request.files == null || request.files.isEmpty()) {
            output.append("  image files: (none)\n");
        } else {
            output.append("  image files:\n");
            for (String path : request.files) {
                output.append("    - ").append(path).append('\n');
            }
        }

        String composed = envelope.path("composedQuestionText").asText("");
        output.append('\n');
        output.append("[Composed question text]\n");
        if (isBlank(composed)) {
            output.append("(empty)");
        } else {
            output.append(composed);
        }
        return output.toString();
    }

    private static PromptEstimate buildPromptEstimate(
        PromptRequest request,
        Integer assumedOutputTokens,
        Double inputPricePerMillion,
        Double outputPricePerMillion
    ) {
        PromptEstimate estimate = new PromptEstimate();
        estimate.endpoint = request.endpoint;
        estimate.model = request.model;
        estimate.assumedOutputTokens = Math.max(0, firstNonNull(assumedOutputTokens, Integer.valueOf(400)));
        estimate.inputPricePerMillion = inputPricePerMillion;
        estimate.outputPricePerMillion = outputPricePerMillion;

        String composedQuestion = buildComposedQuestionText(request.system, request.text);
        estimate.composedTextTokens = estimateTokens(composedQuestion);
        estimate.jsonEnvelopeTokens = estimateJsonEnvelopeTokens(request);

        List<ImageEstimate> imageEstimates = new ArrayList<>();
        int imageTokens = 0;
        if (request.files != null) {
            for (String filePath : request.files) {
                ImageEstimate imageEstimate = estimateImageTokens(filePath);
                imageEstimates.add(imageEstimate);
                imageTokens += imageEstimate.estimatedTokens;
            }
        }
        estimate.images = imageEstimates;
        estimate.imageTokens = imageTokens;

        estimate.estimatedInputTokens = estimate.composedTextTokens + estimate.jsonEnvelopeTokens + estimate.imageTokens;
        estimate.estimatedTotalTokens = estimate.estimatedInputTokens + estimate.assumedOutputTokens;

        if (inputPricePerMillion != null && outputPricePerMillion != null) {
            double inputCost = (estimate.estimatedInputTokens / 1_000_000d) * inputPricePerMillion;
            double outputCost = (estimate.assumedOutputTokens / 1_000_000d) * outputPricePerMillion;
            estimate.estimatedCostUsd = inputCost + outputCost;
        }
        return estimate;
    }

    private static String renderPromptEstimate(PromptEstimate estimate, boolean jsonOutput) throws Exception {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("estimate", true);
        envelope.put("warning", "Rough estimate only. Tokenization and billing vary by provider/model; final billed usage may differ.");
        envelope.put("endpoint", fallback(estimate.endpoint));
        if (isBlank(estimate.model)) {
            envelope.putNull("model");
        } else {
            envelope.put("model", estimate.model);
        }
        envelope.put("composedTextTokens", estimate.composedTextTokens);
        envelope.put("jsonEnvelopeTokens", estimate.jsonEnvelopeTokens);
        envelope.put("imageTokens", estimate.imageTokens);
        envelope.put("estimatedInputTokens", estimate.estimatedInputTokens);
        envelope.put("assumedOutputTokens", estimate.assumedOutputTokens);
        envelope.put("estimatedTotalTokens", estimate.estimatedTotalTokens);
        if (estimate.inputPricePerMillion == null) {
            envelope.putNull("inputPricePerMillion");
        } else {
            envelope.put("inputPricePerMillion", estimate.inputPricePerMillion);
        }
        if (estimate.outputPricePerMillion == null) {
            envelope.putNull("outputPricePerMillion");
        } else {
            envelope.put("outputPricePerMillion", estimate.outputPricePerMillion);
        }
        if (estimate.estimatedCostUsd == null) {
            envelope.putNull("estimatedCostUsd");
        } else {
            envelope.put("estimatedCostUsd", estimate.estimatedCostUsd);
        }

        ArrayNode images = envelope.putArray("images");
        for (ImageEstimate image : estimate.images) {
            ObjectNode imageNode = images.addObject();
            imageNode.put("path", image.path);
            imageNode.put("estimatedTokens", image.estimatedTokens);
            if (image.width == null || image.height == null) {
                imageNode.putNull("width");
                imageNode.putNull("height");
            } else {
                imageNode.put("width", image.width);
                imageNode.put("height", image.height);
            }
            imageNode.put("method", image.method);
        }

        if (jsonOutput) {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        }

        StringBuilder output = new StringBuilder();
        output.append("AI prompt estimate (rough)\n");
        output.append("  WARNING: Rough estimate only. Final billed usage may differ by provider/model.\n");
        output.append("  endpoint: ").append(fallback(estimate.endpoint)).append('\n');
        output.append("  model: ").append(fallback(estimate.model)).append('\n');
        output.append("  text tokens (composed): ").append(estimate.composedTextTokens).append('\n');
        output.append("  json envelope tokens: ").append(estimate.jsonEnvelopeTokens).append('\n');
        output.append("  image tokens: ").append(estimate.imageTokens).append('\n');
        output.append("  estimated input tokens: ").append(estimate.estimatedInputTokens).append('\n');
        output.append("  assumed output tokens: ").append(estimate.assumedOutputTokens).append('\n');
        output.append("  estimated total tokens: ").append(estimate.estimatedTotalTokens).append('\n');

        if (estimate.estimatedCostUsd != null) {
            output.append("  estimated cost (USD): ").append(String.format(Locale.US, "%.6f", estimate.estimatedCostUsd)).append('\n');
        } else {
            output.append("  estimated cost (USD): (set both --input-price-per-1m and --output-price-per-1m to compute)\n");
        }

        if (!estimate.images.isEmpty()) {
            output.append("  image breakdown:\n");
            for (ImageEstimate image : estimate.images) {
                output.append("    - ").append(image.path).append(": ").append(image.estimatedTokens).append(" tokens");
                if (image.width != null && image.height != null) {
                    output.append(" (").append(image.width).append("x").append(image.height).append(")");
                }
                output.append(" [").append(image.method).append("]\n");
            }
        }
        return output.toString();
    }

    private static int estimateJsonEnvelopeTokens(PromptRequest request) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("endpoint", fallback(request.endpoint));
        envelope.put("model", fallback(request.model));
        envelope.put("hasSystem", !isBlank(request.system));
        envelope.put("hasText", !isBlank(request.text));
        if (request.temperature == null) {
            envelope.putNull("temperature");
        } else {
            envelope.put("temperature", request.temperature);
        }
        if (request.timeoutMillis == null) {
            envelope.putNull("timeoutMillis");
        } else {
            envelope.put("timeoutMillis", request.timeoutMillis);
        }
        ArrayNode files = envelope.putArray("files");
        if (request.files != null) {
            for (String file : request.files) {
                files.add(file);
            }
        }
        return estimateTokens(envelope.toString());
    }

    private static ImageEstimate estimateImageTokens(String filePath) {
        ImageEstimate estimate = new ImageEstimate();
        estimate.path = filePath;
        estimate.method = "fallback";
        estimate.estimatedTokens = 1105;

        try {
            BufferedImage image = ImageIO.read(Paths.get(filePath).toFile());
            if (image == null) {
                return estimate;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int tilesX = (int) Math.ceil(width / 512.0d);
            int tilesY = (int) Math.ceil(height / 512.0d);
            int tiles = Math.max(1, tilesX * tilesY);
            estimate.width = width;
            estimate.height = height;
            estimate.estimatedTokens = 85 + (tiles * 170);
            estimate.method = "512px-tile-heuristic";
            return estimate;
        } catch (Exception e) {
            return estimate;
        }
    }

    private static int estimateTokens(String text) {
        if (isBlank(text)) {
            return 0;
        }

        String normalized = text.trim();
        int characters = normalized.length();
        int words = normalized.split("\\s+").length;

        double byCharacters = characters / 4.0d;
        double byWords = words / 0.75d;
        return Math.max(1, (int) Math.ceil((byCharacters + byWords) / 2.0d));
    }

    private static ObjectNode buildPromptDryRunEnvelope(PromptRequest request, List<Path> normalizedRuleFiles, SkillDefinition skill) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("dryRun", true);
        envelope.put("endpoint", request.endpoint);
        if (isBlank(request.model)) {
            envelope.putNull("model");
        } else {
            envelope.put("model", request.model);
        }
        if (request.temperature == null) {
            envelope.putNull("temperature");
        } else {
            envelope.put("temperature", request.temperature);
        }
        if (request.timeoutMillis == null) {
            envelope.putNull("timeoutMillis");
        } else {
            envelope.put("timeoutMillis", request.timeoutMillis);
        }

        if (isBlank(request.system)) {
            envelope.putNull("system");
        } else {
            envelope.put("system", request.system);
        }
        if (isBlank(request.text)) {
            envelope.putNull("text");
        } else {
            envelope.put("text", request.text);
        }

        if (skill != null) {
            ObjectNode skillNode = envelope.putObject("skill");
            skillNode.put("name", isBlank(skill.name) ? "(unnamed)" : skill.name);
            if (isBlank(skill.source)) {
                skillNode.putNull("source");
            } else {
                skillNode.put("source", skill.source);
            }
        } else {
            envelope.putNull("skill");
        }

        ArrayNode rules = envelope.putArray("rulesFiles");
        if (normalizedRuleFiles != null) {
            for (Path path : normalizedRuleFiles) {
                rules.add(path.toAbsolutePath().normalize().toString());
            }
        }

        ArrayNode images = envelope.putArray("imageFiles");
        if (request.files != null) {
            for (String filePath : request.files) {
                images.add(filePath);
            }
        }

        String composed = buildComposedQuestionText(request.system, request.text);
        envelope.put("composedQuestionText", composed == null ? "" : composed);

        if (request.files == null || request.files.isEmpty()) {
            envelope.put("questionType", "text");
            envelope.put("questionPreview", composed == null ? "" : composed);
        } else {
            envelope.put("questionType", "multimodal");
            ArrayNode questionPreview = envelope.putArray("questionPreview");
            if (!isBlank(composed)) {
                ObjectNode textPart = questionPreview.addObject();
                textPart.put("type", "text");
                textPart.put("text", composed);
            }
            for (String filePath : request.files) {
                ObjectNode imagePart = questionPreview.addObject();
                imagePart.put("type", "imageBinaryFile");
                imagePart.put("path", filePath);
            }
        }

        return envelope;
    }

    private static boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static SkillDefinition resolveSkill(String skillNameOrPath, List<Path> commandSkillPaths, boolean verbose) throws Exception {
        if (isBlank(skillNameOrPath)) {
            return null;
        }

        Path directPath = tryAsPath(skillNameOrPath);
        if (directPath != null && Files.isRegularFile(directPath)) {
            SkillDefinition direct = loadSkillFromFile(directPath);
            direct.source = directPath.toString();
            if (isBlank(direct.name)) {
                direct.name = stripExtension(directPath.getFileName().toString());
            }
            return direct;
        }

        String requestedName = skillNameOrPath.trim();
        SkillDefinition winner = null;

        for (Path dir : buildEffectiveSkillPathOrder(commandSkillPaths)) {
            Path candidate = dir.resolve(requestedName + ".json");
            if (Files.isRegularFile(candidate)) {
                SkillDefinition current = loadSkillFromFile(candidate);
                current.source = candidate.toString();
                if (isBlank(current.name)) {
                    current.name = stripExtension(candidate.getFileName().toString());
                }
                if (winner == null) {
                    winner = current;
                } else if (verbose) {
                    StringOutput.Quick.warning("Skill '" + requestedName + "' shadowed by earlier path. Ignoring: " + candidate);
                }
            }
        }

        if (winner != null) {
            return winner;
        }

        SkillsConfig namedSkills = loadSkillsConfig();
        if (namedSkills.skills != null && namedSkills.skills.containsKey(requestedName)) {
            SkillDefinition fromNamed = namedSkills.skills.get(requestedName);
            fromNamed.name = requestedName;
            fromNamed.source = LucliPaths.resolve().aiSkillsFile().toString();
            return fromNamed;
        }

        return null;
    }

    private static Map<String, SkillDefinition> discoverSkillDefinitions(List<Path> commandSkillPaths, boolean verbose) throws Exception {
        Map<String, SkillDefinition> discovered = new LinkedHashMap<>();

        for (Path dir : buildEffectiveSkillPathOrder(commandSkillPaths)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }

            List<Path> jsonFiles;
            try (Stream<Path> stream = Files.list(dir)) {
                jsonFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
            }

            for (Path file : jsonFiles) {
                SkillDefinition skill = loadSkillFromFile(file);
                if (isBlank(skill.name)) {
                    skill.name = stripExtension(file.getFileName().toString());
                }
                skill.source = file.toString();

                if (discovered.containsKey(skill.name)) {
                    if (verbose) {
                        StringOutput.Quick.warning("Skill '" + skill.name + "' shadowed by earlier path. Ignoring: " + file);
                    }
                    continue;
                }
                discovered.put(skill.name, skill);
            }
        }

        SkillsConfig namedSkills = loadSkillsConfig();
        if (namedSkills.skills != null) {
            for (Map.Entry<String, SkillDefinition> entry : namedSkills.skills.entrySet()) {
                if (discovered.containsKey(entry.getKey())) {
                    if (verbose) {
                        StringOutput.Quick.warning("Skill '" + entry.getKey() + "' from skills.json is shadowed by path-based skill.");
                    }
                    continue;
                }
                SkillDefinition copy = entry.getValue();
                copy.name = entry.getKey();
                copy.source = LucliPaths.resolve().aiSkillsFile().toString();
                discovered.put(entry.getKey(), copy);
            }
        }

        return discovered;
    }

    private static List<Path> buildEffectiveSkillPathOrder(List<Path> commandSkillPaths) throws Exception {
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();

        if (commandSkillPaths != null) {
            for (Path path : commandSkillPaths) {
                Path normalized = normalizePath(path);
                if (Files.isDirectory(normalized)) {
                    ordered.add(normalized);
                }
            }
        }

        Path projectDefault = normalizePath(Paths.get(System.getProperty("user.dir"), ".lucli", "skills"));
        if (Files.isDirectory(projectDefault)) {
            ordered.add(projectDefault);
        }

        SkillPathsConfig global = loadSkillPathsConfig();
        if (global.paths != null) {
            for (String value : global.paths) {
                if (isBlank(value)) {
                    continue;
                }
                Path normalized = normalizePath(Paths.get(value));
                if (Files.isDirectory(normalized)) {
                    ordered.add(normalized);
                }
            }
        }

        return new ArrayList<>(ordered);
    }

    private static SkillDefinition loadSkillFromFile(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), SkillDefinition.class);
    }

    // ---------- Local persistence ----------

    private static AiLocalConfig loadAiLocalConfig() throws Exception {
        Path settingsFile = LucliPaths.resolve().aiSettingsFile();
        if (Files.exists(settingsFile)) {
            return MAPPER.readValue(settingsFile.toFile(), AiLocalConfig.class);
        }

        // Backward compatibility for earlier first-pass naming.
        Path legacyFile = LucliPaths.resolve().aiDir().resolve("endpoints.json");
        if (Files.exists(legacyFile)) {
            return MAPPER.readValue(legacyFile.toFile(), AiLocalConfig.class);
        }

        return new AiLocalConfig();
    }

    private static void saveAiLocalConfig(AiLocalConfig config) throws Exception {
        Path file = LucliPaths.resolve().aiSettingsFile();
        ensureParentDirectory(file);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), config);
    }

    private static SkillPathsConfig loadSkillPathsConfig() throws Exception {
        Path file = LucliPaths.resolve().aiSkillPathsFile();
        if (!Files.exists(file)) {
            return new SkillPathsConfig();
        }
        SkillPathsConfig config = MAPPER.readValue(file.toFile(), SkillPathsConfig.class);
        if (config.paths == null) {
            config.paths = new ArrayList<>();
        }
        return config;
    }

    private static void saveSkillPathsConfig(SkillPathsConfig config) throws Exception {
        Path file = LucliPaths.resolve().aiSkillPathsFile();
        ensureParentDirectory(file);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), config);
    }

    private static SkillsConfig loadSkillsConfig() throws Exception {
        Path file = LucliPaths.resolve().aiSkillsFile();
        if (!Files.exists(file)) {
            return new SkillsConfig();
        }
        SkillsConfig config = MAPPER.readValue(file.toFile(), SkillsConfig.class);
        if (config.skills == null) {
            config.skills = new LinkedHashMap<>();
        }
        return config;
    }

    private static void ensureParentDirectory(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
    private static Path luceeCfConfigFile() {
        return LucliPaths.resolve().home()
            .resolve("lucee-server")
            .resolve("lucee-server")
            .resolve("context")
            .resolve(".CFConfig.json");
    }

    private static ObjectNode readLuceeAiEntries(Path cfConfigFile) throws IOException {
        JsonNode root = MAPPER.readTree(cfConfigFile.toFile());
        JsonNode ai = root == null ? null : root.get("ai");
        if (ai == null || ai.isNull() || !ai.isObject()) {
            return MAPPER.createObjectNode();
        }
        return ((ObjectNode) ai).deepCopy();
    }

    private static ObjectNode filterAiEntries(ObjectNode allEntries, Set<String> names) {
        ObjectNode filtered = MAPPER.createObjectNode();
        for (String name : names) {
            JsonNode value = allEntries.get(name);
            if (value != null) {
                filtered.set(name, value);
            }
        }
        return filtered;
    }

    private static LinkedHashSet<String> normalizeNames(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                normalized.add(value.trim());
            }
        }
        return normalized;
    }

    // ---------- Misc helpers ----------
    private static void printAiConfigEntries(ObjectNode entries) {
        printAiConfigEntries(entries, null, false);
    }

    private static void printAiConfigEntries(ObjectNode entries, String defaultEndpoint) {
        printAiConfigEntries(entries, defaultEndpoint, false);
    }

    private static void printAiConfigEntries(ObjectNode entries, String defaultEndpoint, boolean showSecrets) {
        java.util.Iterator<Map.Entry<String, JsonNode>> iterator = entries.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            JsonNode custom = value.path("custom");

            String className = nodeText(value, "class");
            String type = providerTypeForDisplay(nodeText(custom, "type"), className);
            String model = nodeText(custom, "model");
            String secretKey = nodeTextAny(custom, "secretKey", "apiKey", "apikey");
            String defaultMode = nodeText(value, "default");

            String label = (!isBlank(defaultEndpoint) && name.equals(defaultEndpoint))
                ? name + " (default)"
                : name;
            System.out.println(label);
            System.out.println("  class: " + fallback(className));
            System.out.println("  type: " + fallback(type));
            System.out.println("  model: " + fallback(model));
            System.out.println("  secretKey: " + formatSecretValue(secretKey, showSecrets));
            System.out.println("  default: " + fallback(defaultMode));
        }
    }

    private static String nodeText(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static String nodeTextAny(JsonNode node, String... fields) {
        if (fields == null) {
            return null;
        }
        for (String field : fields) {
            String value = nodeText(node, field);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String providerTypeForDisplay(String configuredType, String className) {
        if (!isBlank(configuredType)) {
            return configuredType;
        }
        if (isBlank(className)) {
            return null;
        }
        String normalizedClassName = className.trim();
        if (CLAUDE_ENGINE_CLASS.equalsIgnoreCase(normalizedClassName)) {
            return "claude";
        }
        if (GEMINI_ENGINE_CLASS.equalsIgnoreCase(normalizedClassName)) {
            return "gemini";
        }
        if (OPENAI_ENGINE_CLASS.equalsIgnoreCase(normalizedClassName)) {
            return "openai";
        }
        return null;
    }

    private static String fallback(String value) {
        return isBlank(value) ? "(unset)" : value;
    }

    private static String formatSecretValue(String value, boolean showSecrets) {
        if (isBlank(value)) {
            return "(unset)";
        }
        return showSecrets ? value : maskSecretValue(value);
    }

    private static String maskSecretValue(String value) {
        if (isBlank(value)) {
            return "(unset)";
        }
        String trimmed = value.trim();
        int length = trimmed.length();
        if (length <= 2) {
            return "*".repeat(length);
        }
        if (length <= 6) {
            return trimmed.substring(0, 1) + "..." + trimmed.substring(length - 1);
        }
        int visiblePrefix = Math.min(4, Math.max(1, length / 4));
        int visibleSuffix = Math.min(4, Math.max(1, length / 4));
        if (visiblePrefix + visibleSuffix >= length) {
            visiblePrefix = 1;
            visibleSuffix = 1;
        }
        return trimmed.substring(0, visiblePrefix) + "..." + trimmed.substring(length - visibleSuffix);
    }

    private static JsonNode redactedCopy(JsonNode node, boolean showSecrets) {
        if (showSecrets || node == null) {
            return node;
        }
        JsonNode copy = node.deepCopy();
        redactSecretKeysInPlace(copy);
        return copy;
    }

    private static String redactSecretKeysInJsonString(String rawJson, boolean showSecrets) {
        if (showSecrets || isBlank(rawJson)) {
            return rawJson;
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            JsonNode redacted = redactedCopy(root, false);
            return MAPPER.writeValueAsString(redacted);
        } catch (Exception e) {
            return rawJson;
        }
    }

    private static void redactSecretKeysInPlace(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                JsonNode value = field.getValue();
                if (isSensitiveAiConfigField(fieldName) && value != null && value.isTextual()) {
                    objectNode.put(fieldName, maskSecretValue(value.asText()));
                    continue;
                }
                if (value != null && (value.isObject() || value.isArray())) {
                    redactSecretKeysInPlace(value);
                }
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                redactSecretKeysInPlace(child);
            }
        }
    }

    private static boolean isSensitiveAiConfigField(String fieldName) {
        if (isBlank(fieldName)) {
            return false;
        }
        String normalized = fieldName.trim().toLowerCase(Locale.ROOT);
        return "secretkey".equals(normalized) || "apikey".equals(normalized);
    }

    private static int printFriendlyAiFailure(String action, String endpoint, Exception error) {
        String details = gatherExceptionMessages(error);
        String lower = details.toLowerCase(Locale.ROOT);
        String endpointSuffix = isBlank(endpoint) ? "" : " for '" + endpoint + "'";

        if (lower.contains("insufficient_quota") || lower.contains("exceeded your current quota")) {
            StringOutput.Quick.error(action + endpointSuffix + ": provider quota exceeded (HTTP 429 insufficient_quota). Check your billing and usage limits.");
        } else if (lower.contains("status-code:429") || lower.contains("rate limit")) {
            StringOutput.Quick.error(action + endpointSuffix + ": provider rate limit reached (HTTP 429). Try again shortly.");
        } else if (lower.contains("status-code:401") || lower.contains("invalid_api_key") || lower.contains("unauthorized")) {
            StringOutput.Quick.error(action + endpointSuffix + ": authentication failed (HTTP 401). Check your API key/secret.");
        } else if (lower.contains("status-code:403") || lower.contains("forbidden")) {
            StringOutput.Quick.error(action + endpointSuffix + ": request forbidden (HTTP 403). Check provider permissions.");
        } else {
            StringOutput.Quick.error(action + endpointSuffix + ": " + truncate(details, 260));
        }

        if ((LuCLI.verbose || LuCLI.debug) && !isBlank(details)) {
            StringOutput.Quick.info("Provider error details: " + truncate(details, 600));
        }
        return 1;
    }

    private static String gatherExceptionMessages(Throwable throwable) {
        if (throwable == null) {
            return "";
        }

        StringBuilder combined = new StringBuilder();
        Set<Throwable> seen = new HashSet<>();
        Throwable current = throwable;

        while (current != null && !seen.contains(current)) {
            seen.add(current);
            String message = normalizeWhitespace(current.getMessage());
            if (isBlank(message)) {
                message = current.getClass().getSimpleName();
            }
            if (!isBlank(message)) {
                if (!combined.isEmpty()) {
                    combined.append(" | ");
                }
                combined.append(message);
            }
            current = current.getCause();
        }

        return normalizeWhitespace(combined.toString());
    }

    private static String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, Math.max(0, maxLength));
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private static void printAiDefaults(AiLocalConfig config) {
        System.out.println("AI defaults:");
        System.out.println("  defaultEndpoint: " + (isBlank(config.defaultEndpoint) ? "(unset)" : config.defaultEndpoint));
        System.out.println("  defaultModel: " + (isBlank(config.defaultModel) ? "(unset)" : config.defaultModel));
    }

    private static Path normalizePath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path is required.");
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path tryAsPath(String value) {
        try {
            return normalizePath(Paths.get(value));
        } catch (Exception e) {
            return null;
        }
    }
    private static String readPromptText(String value) throws IOException {
        return readTextOrFile(value, "Prompt text");
    }

    private static String readSystemInstruction(String value) throws IOException {
        return readTextOrFile(value, "System instruction");
    }

    private static String readTextOrFile(String value, String label) throws IOException {
        if (isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("@")) {
            return trimmed;
        }
        String rawPath = trimmed.substring(1).trim();
        if (rawPath.isEmpty()) {
            throw new IllegalArgumentException(label + " file path is empty after '@'.");
        }
        Path file = normalizePath(Paths.get(rawPath));
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException(label + " file not found: " + file);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String prettyJsonOrRaw(String rawJson) {
        if (isBlank(rawJson)) {
            return "";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(rawJson));
        } catch (Exception e) {
            return rawJson;
        }
    }

    private static String escapeForSingleQuotedCfml(String value) {
        return value
            .replace("#", "##")
            .replace("'", "''");
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx <= 0) {
            return filename;
        }
        return filename.substring(0, idx);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    // ---------- DTOs ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiLocalConfig {
        public String defaultEndpoint;
        public String defaultModel;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SkillPathsConfig {
        public List<String> paths = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SkillsConfig {
        public Map<String, SkillDefinition> skills = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SkillDefinition {
        public String name;
        public String source;
        public String system;
        public String text;
        public String model;
        public Double temperature;
        public Integer timeoutMillis;
    }

    static class AddConfigRequest {
        public String name;
        public String className;
        public String type;
        public String url;
        public String secretKey;
        public String model;
        public String message;
        public Integer timeout;
        public String defaultMode;
    }

    static class PromptRequest {
        public String endpoint;
        public String model;
        public String system;
        public String text;
        public Double temperature;
        public Integer timeoutMillis;
        public List<String> files = new ArrayList<>();
    }


    static class PromptResult {
        public String text;
        public String json;
    }

    static class PromptEstimate {
        public String endpoint;
        public String model;
        public int composedTextTokens;
        public int jsonEnvelopeTokens;
        public int imageTokens;
        public int estimatedInputTokens;
        public int assumedOutputTokens;
        public int estimatedTotalTokens;
        public Double inputPricePerMillion;
        public Double outputPricePerMillion;
        public Double estimatedCostUsd;
        public List<ImageEstimate> images = new ArrayList<>();
    }

    static class ImageEstimate {
        public String path;
        public Integer width;
        public Integer height;
        public int estimatedTokens;
        public String method;
    }

    static class ProviderDefaults {
        final String className;
        final String model;
        final String url;

        ProviderDefaults(String className, String model, String url) {
            this.className = className;
            this.model = model;
            this.url = url;
        }
    }
}
