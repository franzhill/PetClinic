package com.airbus.ebcs.tools.profiling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;


/**
 * A configurable helper to scan the current thread's stack trace and extract
 * meaningful application-level stack frames (caller classes and methods)
 * while skipping infrastructure layers.
 *
 * <p>Usage:
 * <pre>
 * StackTraceFilter scanner = StackTraceFilter.builder()
 *     .ignoredPackages(List.of("java.", "jakarta.", "org.springframework.", "com.airbus.ebcs.tools.profiling"))
 *     .ignoredClasses(List.of("com.airbus.ebcs.SomeUtility"))
 *     .build();
 *
 * List<StackTraceElement> callers = scanner.findCallers(List.of("com.airbus.ebcs.rc", "com.airbus.ebcs"));
 * String label = scanner.callersToString(callers); // "EventRC.bulk -> PurchasingService.findById"
 * </pre>
 */
public final class StackTraceFilter 
{

    /**
     * Packages or package prefixes of infrastructure libraries and frameworks whose stack 
     * trace elements should be ignored when identifying/filtering for meaningful stack frames.
     */
    private final List<String> ignoredPackages;

    /**
     * Fully qualified class names within the application that are not meaningful as business-level callers.
     * These are usually utility classes or known profiling interceptors.
     * 
     * We want fast lookups and don’t care about order, so Set is ideal (over List)
     */
    private final Set<String> ignoredClasses;


    /**
     * Package prefixes for application-level callers to look for in 
     * in the call stack 
     */
    private final List<String> orderedPackagePrefixes;


    public StackTraceFilter(List<String> ignoredPackages, 
                            Set<String> ignoredClasses,
                            List<String> orderedPackagePrefixes)
    {   // defensive copy the provided list/set
        this.ignoredPackages        = List.copyOf(ignoredPackages == null ? List.of() : ignoredPackages);
        this.ignoredClasses         = Set.copyOf(ignoredClasses  == null ? Set.of()  : ignoredClasses);
        this.orderedPackagePrefixes = List.copyOf(orderedPackagePrefixes == null ? List.of() : orderedPackagePrefixes);
    }

    public static Builder builder() {
        return new Builder();
    }


    /**
     * Finds the first stack frame whose class starts with the given prefix and is not ignored.
     */
    public Optional<StackTraceElement> findFirstMatching(String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isBlank()) return Optional.empty();
        return Arrays.stream(Thread.currentThread().getStackTrace())
            .filter(e -> {
                String cn = e.getClassName();
                return cn.startsWith(packagePrefix) && !isIgnored(cn) && !isProxyish(cn);
            })
            .findFirst();
    }


   /**
    *  
    * Finds application-level callers in the call stack belonging to 
    * the default list of packages of this StackTraceFilter.
    */
    public List<StackTraceElement> findCallers()
    {
        return findCallers(this.orderedPackagePrefixes);
    }

    /**
     * Finds application-level callers in the call stack belonging to 
     * the provided list of packages. 
     * 
     * <p>For each package prefix, the first matching stack frame is selected 
     * (if any) and appended to the result in the same order as the provided 
     * prefixes. Duplicates (same StackTraceElement) are not repeated.
     *
     * <p>Example:
     * <pre>
     * findCallers(List.of("com.acme.myapp.rest_controller", "com.acme.myapp"))
     * // => [controllerEntry, firstAppCaller]
     * </pre>
     *
     * @param orderedPackagePrefixes ordered package or package prefixes to search for (and retain)
     * @return ordered list of matching frames (never null; may be empty or size <= number of prefixes)
     */
    public List<StackTraceElement> findCallers(List<String> orderedPackagePrefixes) {
        if (orderedPackagePrefixes == null || orderedPackagePrefixes.isEmpty()) return List.of();

        List<StackTraceElement> frames = Arrays.asList(Thread.currentThread().getStackTrace());
        List<StackTraceElement> result = new ArrayList<>(orderedPackagePrefixes.size());
        Set<StackTraceElement> seen = new HashSet<>();

        for (String prefix : orderedPackagePrefixes)
        {
            if (prefix == null || prefix.isBlank()) continue;

            frames.stream()
                .filter(e -> {
                    String cn = e.getClassName();
                    return cn.startsWith(prefix) && !isIgnored(cn) && !isProxyish(cn);
                })
                .findFirst()
                .ifPresent(e -> { if (seen.add(e)) result.add(e); });
        }
        return result;
    }


    /**
     * Formats callers into a human-readable string like: "ControllerClass.method -> AppClass.method"
     * 
     * <p>If only one element is present, returns just that element.
     * If the list is empty, returns {@code "unknown"}.
     *
     * <p>Example:
     * <pre>
     * List<StackTraceElement> callers = List.of(
     *     new StackTraceElement("com.acme.myapp.rc.EventRC", "bulk", "EventRC.java", 42),
     *     new StackTraceElement("com.acme.myapp.service.query.impl.PurchasingEntityQueryServiceImpl",
     *                            "findBeanByBcsIssueId", "PurchasingEntityQueryServiceImpl.java", 87)
     * );
     * String result = callersToString(callers);
     * // result = "EventRC.bulk -> PurchasingEntityQueryServiceImpl.findBeanByBcsIssueId"
     * </pre>
     *
     * @param callers ordered list of callers to format
     * @return a string representation of the callers, joined with {@code " -> "}
     */
    public String callersToString(List<StackTraceElement> callers) 
    {
        if (callers == null || callers.isEmpty()) return "unknown";
        List<String> parts = new ArrayList<>(callers.size());
        for (StackTraceElement e : callers) {
            parts.add(simpleName(e.getClassName()) + "." + e.getMethodName());
        }
        return String.join(" -> ", parts);
    }


    /**
     * Determines if the given class name should be excluded from consideration
     * as a business-level caller.
     *
     * @param className the fully qualified class name to evaluate
     * @return true if the class is considered part of infrastructure or explicitly ignored
     */
    private boolean isIgnored(String className) 
    {   return    ignoredPackages.stream().anyMatch(className::startsWith)
               || ignoredClasses.contains(className);
    }


    /** 
     * Filters out common proxy/synthetic names (kept minimal; you can expand if needed). 
     */
    private static boolean isProxyish(String className) 
    {   return className.contains("$$") || className.contains(".Generated");
    }


    /**
     * Extracts the simple (unqualified) class name from a fully qualified class name.
     * 
     * <p>For example, "com.acme.myapp.MyService" -> "MyService".
     *
     * @param fqcn the fully qualified class name; may not be null
     * @return the class name without the package prefix
     */
    private static String simpleName(String fqcn) 
    {   int i = fqcn.lastIndexOf('.');
        return i >= 0 ? fqcn.substring(i + 1) : fqcn;
    }


    /**
     *  Builder for {@link StackTraceFilter}. 
     */
    /**
     *  Builder for {@link StackTraceFilter}.
     */
    public static final class Builder {
        private final List<String> ignoredPackages = new ArrayList<>();
        private final Set<String>  ignoredClasses  = new HashSet<>();
        private final List<String> orderedPackagePrefixes = new ArrayList<>();

        public Builder ignoredPackages(List<String> pkgs) {
            if (pkgs != null) ignoredPackages.addAll(pkgs);
            return this;
        }

        public Builder addIgnoredPackage(String pkg) {
            if (pkg != null && !pkg.isBlank()) ignoredPackages.add(pkg);
            return this;
        }

        public Builder ignoredClasses(Collection<String> classes) {
            if (classes != null) ignoredClasses.addAll(classes);
            return this;
        }

        public Builder addIgnoredClass(String fqcn) {
            if (fqcn != null && !fqcn.isBlank()) ignoredClasses.add(fqcn);
            return this;
        }

        /** Sets the ordered package prefixes to keep (first match per prefix is returned, in order). */
        public Builder orderedPackagePrefixes(List<String> prefixes) {
            if (prefixes != null) orderedPackagePrefixes.addAll(prefixes);
            return this;
        }

        public Builder addOrderedPackagePrefix(String prefix) {
            if (prefix != null && !prefix.isBlank()) orderedPackagePrefixes.add(prefix);
            return this;
        }

        public StackTraceFilter build() {
            return new StackTraceFilter(ignoredPackages, ignoredClasses, orderedPackagePrefixes);
        }

    }
}