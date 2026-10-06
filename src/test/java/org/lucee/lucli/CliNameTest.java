package org.lucee.lucli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lucee.lucli.profile.CliProfile;

/** User-facing hints name the binary the user actually ran. */
class CliNameTest {

    private CliProfile original;

    @BeforeEach
    void save() {
        original = LuCLI.getActiveProfile();
    }

    @AfterEach
    void restore() {
        LuCLI.setActiveProfile(original);
    }

    @Test
    void defaultProfileIsLucli() {
        LuCLI.setActiveProfile(CliProfile.forBinaryName("lucli"));
        assertEquals("lucli", LuCLI.cliName());
    }

    @Test
    void aliasedBinaryNameIsUsed() {
        LuCLI.setActiveProfile(CliProfile.forBinaryName("wheels"));
        assertEquals("wheels", LuCLI.cliName());
    }
}
