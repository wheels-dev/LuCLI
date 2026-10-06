package org.lucee.lucli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LuCLI#rewriteModuleHelpOrReserved(String[])}.
 *
 * <p>When a binary is invoked under a module alias (e.g. {@code wheels}),
 * LuCLI prepends the module name as the first positional and routes the rest
 * through {@code routeCommand}. A bare positional token that matches one of
 * LuCLI's ROOT subcommand names ({@code help}, {@code server}, {@code run},
 * …) was hijacked by picocli and dispatched to that root subcommand instead
 * of the module — so {@code wheels generate controller StaticPages help}
 * printed the root usage and generated nothing.</p>
 */
class LuCLIModuleHelpPreprocessTest {

    @Test
    void bareHelpVerb_rightAfterModule_rewritesToModulesRunHelp() {
        assertArrayEquals(
            new String[]{"modules", "run", "wheels", "--help"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "help"})
        );
    }

    @Test
    void bareHelpVerb_withSubcommand_rewritesAndAppendsHelp() {
        assertArrayEquals(
            new String[]{"modules", "run", "wheels", "migrate", "--help"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "help", "migrate"})
        );
    }

    @Test
    void dashHelp_anywhere_rewritesToModulesRun() {
        assertArrayEquals(
            new String[]{"modules", "run", "wheels", "migrate", "--help"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "migrate", "--help"})
        );
    }

    @Test
    void reservedHelpToken_inPositionalPosition_rewritesToModulesRun() {
        assertArrayEquals(
            new String[]{"modules", "run", "wheels", "generate", "controller", "StaticPages", "help"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "generate", "controller", "StaticPages", "help"})
        );
    }

    @Test
    void reservedServerToken_inPositionalPosition_rewritesToModulesRun() {
        assertArrayEquals(
            new String[]{"modules", "run", "wheels", "generate", "controller", "StaticPages", "server"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "generate", "controller", "StaticPages", "server"})
        );
    }

    @Test
    void reservedRunToken_inPositionalPosition_rewritesToModulesRun() {
        assertArrayEquals(
            new String[]{"modules", "run", "wheels", "generate", "controller", "StaticPages", "run"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "generate", "controller", "StaticPages", "run"})
        );
    }

    @Test
    void ordinaryPositionalTokens_areLeftUnchanged() {
        assertArrayEquals(
            new String[]{"wheels", "generate", "controller", "StaticPages", "home"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "generate", "controller", "StaticPages", "home"})
        );
    }

    @Test
    void rootSubcommandAtLeadingPosition_isLeftUnchanged() {
        // `wheels server start` intentionally invokes LuCLI's root `server`
        // command, not a module subcommand — the reserved token is leading,
        // so it must not be rewritten.
        assertArrayEquals(
            new String[]{"wheels", "server", "start"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "server", "start"})
        );
    }

    // Root options before a reserved root subcommand: the subcommand is still the
    // leading token, so it runs as a root command (e.g. `wheels --timing cfml 'x'`).
    @Test
    void rootFlagBeforeReservedSubcommand_isNotHandedToTheModule() {
        String[] args = {"wheels", "--timing", "cfml", "writeOutput(1)"};
        assertArrayEquals(args, LuCLI.rewriteModuleHelpOrReserved(args));
    }

    @Test
    void rootOptionWithSeparateValueBeforeReservedSubcommand_isNotHandedToTheModule() {
        String[] args = {"wheels", "--env", "prod", "server", "start"};
        assertArrayEquals(args, LuCLI.rewriteModuleHelpOrReserved(args));
    }

    @Test
    void rootOptionWithInlineValueBeforeReservedSubcommand_isNotHandedToTheModule() {
        String[] args = {"wheels", "--timeout=30", "-v", "run", "x.cfm"};
        assertArrayEquals(args, LuCLI.rewriteModuleHelpOrReserved(args));
    }

    @Test
    void rootFlagBeforeModuleSubcommand_reservedPositionalStillRewritten() {
        assertArrayEquals(
            new String[]{"modules", "run", "wheels", "--verbose", "generate", "controller", "StaticPages", "help"},
            LuCLI.rewriteModuleHelpOrReserved(new String[]{"wheels", "--verbose", "generate", "controller", "StaticPages", "help"})
        );
    }

    @Test
    void leadingPositionSkipsRootOptionsAndTheirValues() {
        assertEquals(3, LuCLI.leadingPositionAfterRootOptions(new String[]{"wheels", "-t", "--verbose", "cfml"}));
        assertEquals(3, LuCLI.leadingPositionAfterRootOptions(new String[]{"wheels", "-e", "prod", "server"}));
        assertEquals(2, LuCLI.leadingPositionAfterRootOptions(new String[]{"wheels", "--envfile=.env", "repl"}));
        assertEquals(2, LuCLI.leadingPositionAfterRootOptions(new String[]{"wheels", "--env"}));
        assertEquals(1, LuCLI.leadingPositionAfterRootOptions(new String[]{"wheels", "--help"}));
    }
}
