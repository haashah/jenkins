/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.security;

import com.google.common.collect.ImmutableSet;
import hudson.ExtensionList;
import hudson.Main;
import hudson.remoting.ClassFilter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Customized version of {@link ClassFilter#DEFAULT}.
 * First of all, {@link CustomClassFilter}s are given the first right of decision.
 * Then delegates to {@link ClassFilter#STANDARD} for its blacklist.
 * A class not mentioned in the blacklist is permitted unless it is defined in some third-party library
 * (as opposed to {@code jenkins-core.jar}, a plugin JAR, or test code during {@link Main#isUnitTest})
 * yet is not mentioned in {@code whitelisted-classes.txt}.
 */
@Restricted(NoExternalUse.class)
public class ClassFilterImpl extends ClassFilter {

    private static final Logger LOGGER = Logger.getLogger(ClassFilterImpl.class.getName());

    /**
     * Register this implementation as the default in the system.
     */
    public static void register() {
        if (Main.isUnitTest && Jenkins.class.getProtectionDomain().getCodeSource().getLocation() == null) {
            mockOff();
            return;
        }
        ClassFilter.setDefault(new ClassFilterImpl());
    }

    /**
     * Undo {@link #register}.
     */
    public static void unregister() {
        ClassFilter.setDefault(ClassFilter.STANDARD);
    }

    private static void mockOff() {
        LOGGER.warning("Disabling class filtering since we appear to be in a special test environment, perhaps Mockito/PowerMock");
        ClassFilter.setDefault(ClassFilter.NONE); // even Method on the standard blacklist is going to explode
    }

    private ClassFilterImpl() {}

    /** Whether a given class is blacklisted. */
    private final Map<Class<?>, Boolean> cache = Collections.synchronizedMap(new WeakHashMap<>());
    /** Whether a given code source location is whitelisted. */
    private final Map<String, Boolean> codeSourceCache = Collections.synchronizedMap(new HashMap<>());
    /** Names of classes outside Jenkins core or plugins which have a special serial form but are considered safe. */
    static final Set<String> WHITELISTED_CLASSES;
    static {
        try (InputStream is = ClassFilterImpl.class.getResourceAsStream("whitelisted-classes.txt")) {
            WHITELISTED_CLASSES = ImmutableSet.copyOf(IOUtils.readLines(is, StandardCharsets.UTF_8));
        } catch (IOException x) {
            throw new ExceptionInInitializerError(x);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean isBlacklisted(Class _c) {
        for (CustomClassFilter f : ExtensionList.lookup(CustomClassFilter.class)) {
            Boolean r = f.permits(_c);
            if (r != null) {
                LOGGER.log(Level.FINER, "{0} specifies a policy for {1}: {2}", new Object[] {f, _c.getName(), r});
                return !r;
            }
        }
        return cache.computeIfAbsent(_c, c -> {
            if (ClassFilter.STANDARD.isBlacklisted(c)) { // currently never true: only the name overload is overridden
                return true;
            }
            String name = c.getName();
            if (Main.isUnitTest && name.contains("$$EnhancerByMockitoWithCGLIB$$")) {
                mockOff();
                return false;
            }
            if (c.isArray()) {
                LOGGER.log(Level.FINE, "permitting {0} since it is an array", name);
                return false;
            }
            if (Throwable.class.isAssignableFrom(c)) {
                LOGGER.log(Level.FINE, "permitting {0} since it is a throwable", name);
                return false;
            }
            if (Enum.class.isAssignableFrom(c)) { // Class.isEnum seems to be false for, e.g., java.util.concurrent.TimeUnit$6
                LOGGER.log(Level.FINE, "permitting {0} since it is an enum", name);
                return false;
            }
            CodeSource codeSource = c.getProtectionDomain().getCodeSource();
            URL location = codeSource != null ? codeSource.getLocation() : null;
            if (location != null) {
                if (isLocationWhitelisted(location.toString())) {
                    LOGGER.log(Level.FINE, "permitting {0} due to its location in {1}", new Object[] {name, location});
                    return false;
                }
            } else {
                ClassLoader loader = c.getClassLoader();
                if (loader != null && loader.getClass().getName().equals("hudson.remoting.RemoteClassLoader")) {
                    LOGGER.log(Level.FINE, "permitting {0} since it was loaded by a remote class loader", name);
                    return false;
                }
            }
            if (WHITELISTED_CLASSES.contains(name)) {
                LOGGER.log(Level.FINE, "tolerating {0} by whitelist", name);
                return false;
            }
            LOGGER.log(Level.WARNING, "{0} in {1} might be dangerous, so rejecting; see https://jenkins.io/redirect/class-filter/", new Object[] {name, location != null ? location : "JRE"});
            return true;
        });
    }

    private static final Pattern CLASSES_JAR = Pattern.compile("(file:/.+/)WEB-INF/lib/classes[.]jar");
    private boolean isLocationWhitelisted(String _loc) {
        return codeSourceCache.computeIfAbsent(_loc, loc -> {
            if (loc.equals(Jenkins.class.getProtectionDomain().getCodeSource().getLocation().toString())) {
                LOGGER.log(Level.FINE, "{0} seems to be the location of Jenkins core, OK", loc);
                return true;
            }
            if (loc.equals(ClassFilter.class.getProtectionDomain().getCodeSource().getLocation().toString())) {
                LOGGER.log(Level.FINE, "{0} seems to be the location of Remoting, OK", loc);
                return true;
            }
            if (loc.matches("file:/.+[.]jar")) {
                try (JarFile jf = new JarFile(new File(new URI(loc)), false)) {
                    Manifest mf = jf.getManifest();
                    if (mf != null) {
                        if (isPluginManifest(mf)) {
                            LOGGER.log(Level.FINE, "{0} seems to be a Jenkins plugin, OK", loc);
                            return true;
                        } else {
                            LOGGER.log(Level.FINE, "{0} does not look like a Jenkins plugin", loc);
                        }
                    } else {
                        LOGGER.log(Level.FINE, "ignoring {0} with no manifest", loc);
                    }
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, "problem checking " + loc, x);
                }
            }
            Matcher m = CLASSES_JAR.matcher(loc);
            if (m.matches()) {
                // Cf. ClassicPluginStrategy.createClassJarFromWebInfClasses: handle legacy plugin format with unpacked WEB-INF/classes/
                try {
                    File manifestFile = new File(new URI(m.group(1) + "META-INF/MANIFEST.MF"));
                    if (manifestFile.isFile()) {
                        try (InputStream is = new FileInputStream(manifestFile)) {
                            if (isPluginManifest(new Manifest(is))) {
                                LOGGER.log(Level.FINE, "{0} looks like a Jenkins plugin based on {1}, OK", new Object[] {loc, manifestFile});
                                return true;
                            } else {
                                LOGGER.log(Level.FINE, "{0} does not look like a Jenkins plugin", manifestFile);
                            }
                        }
                    } else {
                        LOGGER.log(Level.FINE, "{0} has no matching {1}", new Object[] {loc, manifestFile});
                    }
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, "problem checking " + loc, x);
                }
            }
            if (Main.isUnitTest) {
                if (loc.endsWith("/target/classes/")) {
                    LOGGER.log(Level.FINE, "{0} seems to be current plugin classes, OK", loc);
                    return true;
                }
                if (loc.endsWith("/target/test-classes/") || loc.endsWith("-tests.jar")) {
                    LOGGER.log(Level.FINE, "{0} seems to be test classes, OK", loc);
                    return true;
                }
                if (loc.matches(".+/jenkins-test-harness-.+[.]jar")) {
                    LOGGER.log(Level.FINE, "{0} seems to be jenkins-test-harness, OK", loc);
                    return true;
                }
            }
            LOGGER.log(Level.FINE, "{0} is not recognized; rejecting", loc);
            return false;
        });
    }

    private static boolean isPluginManifest(Manifest mf) {
        Attributes attr = mf.getMainAttributes();
        return attr.getValue("Short-Name") != null && (attr.getValue("Plugin-Version") != null || attr.getValue("Jenkins-Version") != null);
    }

    @Override
    public boolean isBlacklisted(String name) {
        if (Main.isUnitTest && name.contains("$$EnhancerByMockitoWithCGLIB$$")) {
            mockOff();
            return false;
        }
        for (CustomClassFilter f : ExtensionList.lookup(CustomClassFilter.class)) {
            Boolean r = f.permits(name);
            if (r != null) {
                LOGGER.log(Level.FINER, "{0} specifies a policy for {1}: {2}", new Object[] {f, name, r});
                return !r;
            }
        }
        // could apply a cache if the pattern search turns out to be slow
        if (ClassFilter.STANDARD.isBlacklisted(name)) {
            LOGGER.log(Level.WARNING, "rejecting {0} according to standard blacklist; see https://jenkins.io/redirect/class-filter/", name);
            return true;
        } else {
            return false;
        }
    }

}
