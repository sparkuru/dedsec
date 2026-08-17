package io.github.wkyuu.dedsec.target.targets;

public final class DynamicTarget {
    private final String loaderName;

    public DynamicTarget(String loaderName) {
        this.loaderName = loaderName;
    }

    public String identify(String input) {
        return loaderName + ":" + input;
    }
}
