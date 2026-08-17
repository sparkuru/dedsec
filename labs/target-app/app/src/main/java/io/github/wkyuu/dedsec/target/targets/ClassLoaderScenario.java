package io.github.wkyuu.dedsec.target.targets;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import dalvik.system.PathClassLoader;

public final class ClassLoaderScenario {
    private ClassLoaderScenario() {
    }

    public static String run(Context context) throws ReflectiveOperationException {
        ClassLoader appLoader = context.getClassLoader();
        ClassLoader bootLoader = appLoader.getParent();
        PathClassLoader isolatedLoader = new PathClassLoader(context.getApplicationInfo().sourceDir, bootLoader);
        Class<?> targetClass = Class.forName(DynamicTarget.class.getName(), true, isolatedLoader);
        Constructor<?> constructor = targetClass.getConstructor(String.class);
        Method identify = targetClass.getMethod("identify", String.class);
        Object target = constructor.newInstance("isolated");
        Object result = identify.invoke(target, "payload");
        boolean isolated = targetClass.getClassLoader() != DynamicTarget.class.getClassLoader();
        return "isolated=" + isolated + " result=" + result;
    }
}
