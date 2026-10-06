package org.lucee.lucli.cli.commands;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.lucee.lucli.StringOutput;
import org.lucee.lucli.LuCLI;
import org.lucee.lucli.paths.LucliPaths;
import org.lucee.lucli.secrets.LocalSecretStore;
import org.lucee.lucli.secrets.LucliSecretProviderSupport;
import org.lucee.lucli.secrets.SecretStore;
import org.lucee.lucli.secrets.SecretStoreException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Secrets management CLI entrypoint.
 */
@Command(
    name = "secrets",
    aliases = "secret",
    description = "Manage secrets for LuCLI projects",
    mixinStandardHelpOptions = true,
    subcommands = {
        SecretsCommand.InitCommand.class,
        SecretsCommand.SetCommand.class,
        SecretsCommand.ListCommand.class,
        SecretsCommand.RmCommand.class,
        SecretsCommand.GetCommand.class,
        SecretsCommand.ProviderCommand.class
    }
)
public class SecretsCommand implements Callable<Integer> {

    @ParentCommand
    private LuCLI parent;

    @Override
    public Integer call() throws Exception {
        // Show help when no subcommand is provided
        new picocli.CommandLine(this).usage(System.out);
        return 0;
    }

    static Path getDefaultStorePath() {
        return LucliPaths.resolve().secretsStoreFile();
    }
    static String selectedProvider() {
        return LucliSecretProviderSupport.getSelectedProviderName();
    }

    static boolean selectedProviderIsLocal() {
        return LucliSecretProviderSupport.isLocalProviderName(selectedProvider());
    }

    static int unsupportedWriteProvider(String operation) {
        String provider = selectedProvider();
        System.err.println(
            "Provider '" + provider + "' does not support '" + operation + "' from lucli secrets.\n" +
            "Switch to '" + LucliSecretProviderSupport.LOCAL_PROVIDER_NAME + "' with:\n" +
            "  lucli secrets provider use " + LucliSecretProviderSupport.LOCAL_PROVIDER_NAME
        );
        return 1;
    }

    static char[] readLocalPassphrase(char[] provided, String prompt) throws Exception {
        char[] passphrase = LucliSecretProviderSupport.resolvePassphrase(provided, prompt);
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalStateException(
                "No passphrase available for local secret store. Set " +
                LucliSecretProviderSupport.DEFAULT_PASSPHRASE_ENV +
                " or run with an interactive console."
            );
        }
        return passphrase;
    }

    static LocalSecretStore createStore(char[] passphrase) throws SecretStoreException {
        return new LocalSecretStore(getDefaultStorePath(), passphrase);
    }

    @Command(name = "init", description = "Initialize the local encrypted secret store")
    static class InitCommand implements Callable<Integer> {

        @ParentCommand
        private SecretsCommand parent;

        @Option(names = "--reset", description = "Re-initialize the store (DANGEROUS: deletes existing secrets)")
        private boolean reset;

        @Override
        public Integer call() throws Exception {
            if (!selectedProviderIsLocal()) {
                StringOutput.Quick.warning(
                    "Selected provider is '" + selectedProvider() + "'. " +
                    "Initializing local store for '" + LucliSecretProviderSupport.LOCAL_PROVIDER_NAME + "'."
                );
            }
            Path storePath = getDefaultStorePath();
            if (java.nio.file.Files.exists(storePath) && !reset) {
                System.err.println("Secret store already exists at " + storePath + ". Use --reset to recreate it (will delete existing secrets).");
                return 1;
            }
            java.io.Console console = System.console();
            if (console == null) {
                System.err.println("No console available to read passphrase securely.");
                return 1;
            }
            char[] p1 = console.readPassword("Create secrets passphrase: ");
            char[] p2 = console.readPassword("Confirm secrets passphrase: ");
            if (!java.util.Arrays.equals(p1, p2)) {
                System.err.println("Passphrases do not match.");
                return 1;
            }
            try {
                // Creating a new store will initialize and persist using the passphrase
                new LocalSecretStore(storePath, p1);
                System.out.println("Initialized local secret store at " + storePath);
                return 0;
            } catch (SecretStoreException e) {
                System.err.println("Failed to initialize secret store: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "set", description = "Set or update a secret value")
    static class SetCommand implements Callable<Integer> {

        @ParentCommand
        private SecretsCommand parent;

        @Parameters(paramLabel = "NAME", description = "Logical name of the secret")
        private String name;

        @Option(names = "--description", description = "Description for this secret")
        private String description;

        @Override
        public Integer call() throws Exception {
            if (!selectedProviderIsLocal()) {
                return unsupportedWriteProvider("set");
            }
            java.io.Console console = System.console();
            if (console == null) {
                System.err.println("No console available to read secret value securely.");
                return 1;
            }
            char[] passphrase = readLocalPassphrase(null, "Enter secrets passphrase: ");
            char[] value = console.readPassword("Enter secret value for '%s': ", name);
            try {
                SecretStore store = createStore(passphrase);
                store.put(name, value, description);
                System.out.println("Stored secret '" + name + "'.");
                return 0;
            } catch (SecretStoreException e) {
                System.err.println("Failed to store secret: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "list", description = "List stored secrets (names and metadata only)")
    static class ListCommand implements Callable<Integer> {

        @ParentCommand
        private SecretsCommand parent;

        @Override
        public Integer call() throws Exception {
            if (!selectedProviderIsLocal()) {
                return unsupportedWriteProvider("list");
            }
            char[] passphrase = readLocalPassphrase(null, "Enter secrets passphrase: ");
            try {
                SecretStore store = createStore(passphrase);
                List<SecretStore.SecretMetadata> all = store.list();
                if (all.isEmpty()) {
                    System.out.println("No secrets stored yet.");
                    return 0;
                }
                for (SecretStore.SecretMetadata meta : all) {
                    System.out.println("- " + meta.name() +
                        (meta.description() != null && !meta.description().isEmpty() ? " : " + meta.description() : ""));
                }
                return 0;
            } catch (SecretStoreException e) {
                System.err.println("Failed to list secrets: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "rm", description = "Remove a stored secret")
    static class RmCommand implements Callable<Integer> {

        @ParentCommand
        private SecretsCommand parent;

        @Parameters(paramLabel = "NAME", description = "Logical name of the secret to remove")
        private String name;

        @Option(names = "-f", description = "Do not prompt for confirmation")
        private boolean force;

        @Override
        public Integer call() throws Exception {
            if (!selectedProviderIsLocal()) {
                return unsupportedWriteProvider("rm");
            }
            if (!force) {
                java.io.Console console = System.console();
                if (console == null) {
                    System.err.println("No console to confirm deletion. Use -f to force.");
                    return 1;
                }
                String answer = console.readLine("Delete secret '%s'? (y/N): ", name);
                if (answer == null || !answer.trim().toLowerCase().startsWith("y")) {
                    System.out.println("Aborted.");
                    return 0;
                }
            }
            char[] passphrase = readLocalPassphrase(null, "Enter secrets passphrase: ");
            try {
                SecretStore store = createStore(passphrase);
                store.delete(name);
                System.out.println("Removed secret '" + name + "'.");
                return 0;
            } catch (SecretStoreException e) {
                System.err.println("Failed to remove secret: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "get", description = "Retrieve a secret value")
    static class GetCommand implements Callable<Integer> {

        @ParentCommand
        private SecretsCommand parent;

        @Parameters(paramLabel = "NAME", description = "Logical name of the secret")
        private String name;

        @Option(names = "--show", description = "Print the secret value to stdout (use with care)")
        private boolean show;

        @Override
        public Integer call() throws Exception {
            if (!show) {
                System.err.println("By default, '" + org.lucee.lucli.LuCLI.cliName() + " secrets get' does not print raw values. Use --show if you really need to see it.");
                return 1;
            }
            String provider = selectedProvider();
            if (LucliSecretProviderSupport.isLocalProviderName(provider)) {
                char[] passphrase = readLocalPassphrase(null, "Enter secrets passphrase: ");
                try {
                    SecretStore store = createStore(passphrase);
                    Optional<char[]> value = store.get(name);
                    if (value.isEmpty()) {
                        System.err.println("Secret '" + name + "' not found.");
                        return 1;
                    }
                    // Intentionally print directly without additional formatting to reduce risk of logs copying
                    System.out.println(new String(value.get()));
                    return 0;
                } catch (SecretStoreException e) {
                    System.err.println("Failed to retrieve secret: " + e.getMessage());
                    return 1;
                }
            }

            try {
                String value = LucliSecretProviderSupport.readSecretViaLuceeProvider(provider, name);
                if (value == null) {
                    System.err.println("Secret '" + name + "' not found for provider '" + provider + "'.");
                    return 1;
                }
                System.out.println(value);
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to retrieve secret from provider '" + provider + "': " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(
        name = "provider",
        description = "Manage secret providers",
        subcommands = {
            ProviderCommand.ListProvidersCommand.class,
            ProviderCommand.UseProviderCommand.class,
            ProviderCommand.CurrentProviderCommand.class,
            picocli.CommandLine.HelpCommand.class
        }
    )
    static class ProviderCommand implements Callable<Integer> {

        @ParentCommand
        private SecretsCommand parent;

        @Override
        public Integer call() throws Exception {
            new picocli.CommandLine(this).usage(System.out);
            return 0;
        }
        @Command(name = "list", description = "List known/available secret providers")
        static class ListProvidersCommand implements Callable<Integer> {

            @Override
            public Integer call() {
                String selected = selectedProvider();
                System.out.println("Selected provider: " + selected);
                System.out.println();
                System.out.println("Known providers:");
                System.out.println("- " + LucliSecretProviderSupport.LOCAL_PROVIDER_NAME +
                    " (LuCLI encrypted local store)");

                if (Files.exists(getDefaultStorePath())) {
                    System.out.println("  local store file: " + getDefaultStorePath());
                } else {
                    System.out.println("  local store file: (not initialized)");
                }

                if (!LucliSecretProviderSupport.isLocalProviderName(selected)) {
                    System.out.println("- " + selected + " (selected; resolved via Lucee SecretProviderGet)");
                }
                System.out.println();
                System.out.println("Tip: set provider with `lucli secrets provider use <name>`.");
                return 0;
            }
        }

        @Command(name = "use", description = "Set selected secret provider in ~/.lucli/settings.json")
        static class UseProviderCommand implements Callable<Integer> {

            @Parameters(index = "0", paramLabel = "NAME", description = "Provider name to use")
            private String providerName;

            @Override
            public Integer call() {
                LucliSecretProviderSupport.setSelectedProviderName(providerName);
                String normalized = LucliSecretProviderSupport.getSelectedProviderName();
                StringOutput.Quick.success("Selected secret provider set to '" + normalized + "'.");
                return 0;
            }
        }

        @Command(name = "current", description = "Show currently selected secret provider")
        static class CurrentProviderCommand implements Callable<Integer> {

            @Override
            public Integer call() {
                System.out.println(LucliSecretProviderSupport.getSelectedProviderName());
                return 0;
            }
        }
    }
}