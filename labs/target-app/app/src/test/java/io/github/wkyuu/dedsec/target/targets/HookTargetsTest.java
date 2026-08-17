package io.github.wkyuu.dedsec.target.targets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class HookTargetsTest {
    @Test
    public void exposesDeterministicInstanceTargets() {
        HookTargets targets = new HookTargets("test");

        assertEquals("test:hello:user", targets.greet("user"));
        assertEquals("test:final:value", targets.finalLabel("value"));
        assertEquals(11, targets.synchronizedIncrement(10));
    }

    @Test
    public void exposesDistinctOverloads() {
        HookTargets targets = new HookTargets("test");

        assertEquals(16, targets.merge(7, 9));
        assertEquals("left:right", targets.merge("left", "right"));
        assertEquals("static:input", HookTargets.staticToken("input"));
    }

    @Test
    public void exposesAnExceptionTarget() {
        HookTargets targets = new HookTargets("test");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> targets.failIfBlank("")
        );
        assertEquals("value must not be blank", error.getMessage());
    }
}
