package com.nokia.workflow.wrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * Finds wrappers on the classpath through {@link ServiceLoader}.
 *
 * <p>A wrapper whose component jar is not on the classpath (e.g. CLICR when only ciq-processor is deployed)
 * cannot be loaded; it is skipped and listed in {@link #unavailable()} instead of breaking every other wrapper.
 */
public final class WrapperRegistry {

    private final Map<String, ComponentWrapper> byName = new TreeMap<String, ComponentWrapper>();
    private final List<String> unavailable = new ArrayList<String>();

    public WrapperRegistry(ClassLoader loader) {
        Iterator<ComponentWrapper> it = ServiceLoader.load(ComponentWrapper.class, loader).iterator();
        while (true) {
            ComponentWrapper w;
            try {
                if (!it.hasNext()) {
                    break;
                }
                w = it.next();
            } catch (ServiceConfigurationError e) {
                unavailable.add(String.valueOf(e.getMessage()));
                continue;
            } catch (LinkageError e) {   // the wrapper class needs a component class that is not on the classpath
                unavailable.add(String.valueOf(e));
                continue;
            }
            String key = w.name().toUpperCase(Locale.ROOT);
            if (byName.containsKey(key)) {
                throw new IllegalStateException("Two wrappers are named " + w.name() + ": "
                        + byName.get(key).getClass().getName() + " and " + w.getClass().getName());
            }
            byName.put(key, w);
        }
    }

    public WrapperRegistry() {
        this(Thread.currentThread().getContextClassLoader());
    }

    /** The wrapper for a component name (case-insensitive), or {@code null}. */
    public ComponentWrapper find(String name) {
        return name == null ? null : byName.get(name.trim().toUpperCase(Locale.ROOT));
    }

    public Collection<ComponentWrapper> all() {
        return byName.values();
    }

    /** Wrappers listed on the classpath that could not be loaded, with the reason. */
    public List<String> unavailable() {
        return Collections.unmodifiableList(unavailable);
    }
}
