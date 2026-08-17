package io.github.wkyuu.dedsec.target.targets;

import java.util.Objects;

public class HookTargets {
    private final String seed;

    public HookTargets(String seed) {
        this.seed = Objects.requireNonNull(seed, "seed");
    }

    public String greet(String name) {
        return seed + ":hello:" + name;
    }

    public int merge(int left, int right) {
        return left + right;
    }

    public String merge(String left, String right) {
        return left + ":" + right;
    }

    public static String staticToken(String input) {
        return "static:" + input;
    }

    public final String finalLabel(String value) {
        return seed + ":final:" + value;
    }

    public synchronized int synchronizedIncrement(int value) {
        return value + 1;
    }

    public String failIfBlank(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return value;
    }
}
