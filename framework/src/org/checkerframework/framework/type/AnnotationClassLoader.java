package org.checkerframework.framework.type;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.framework.util.AnnotationBuilder;
import org.checkerframework.javacutil.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.tools.Diagnostic.Kind;

/*>>>
import org.checkerframework.checker.nullness.qual.Nullable;
 */

/**
 * This class assists the {@link AnnotatedTypeFactory} by reflectively looking
 * up the list of annotation class names in each checker's qual directory, and
 * then loading and returning it as a set of annotation classes. It can also
 * look up and load annotation classes from external directories that are passed
 * as arguments to checkers that have extension capabilities such as the
 * Subtyping Checker, Fenum Checker, and Units Checker.
 *
 * To load annotations using this class, their directory structure and package
 * structure must be identical.
 *
 * Only annotation classes that have the {@link Target} meta-annotation with the
 * value of {@link ElementType#TYPE_USE} (and optionally
 * {@link ElementType#TYPE_PARAMETER}) are loaded. If it has other
 * {@link ElementType} values, it won't be loaded. Other annotation classes must
 * be manually listed in a checker's annotated type factory by overriding
 * {@link AnnotatedTypeFactory#createSupportedTypeQualifiers()}.
 *
 * Checker writers may wish to subclass this class if they wish to implement
 * some custom rules to filter or process loaded annotation classes, by
 * providing an override implementation of
 * {@link #isSupportedAnnotationClass(Class)}. See
 * {@link org.checkerframework.checker.units.UnitsAnnotationClassLoader
 * UnitsAnnotationClassLoader} for an example.
 *
 * @author Jeff Luo
 */
public class AnnotationClassLoader {
    // For issuing errors to the user
    private BaseTypeChecker checker;

    // For loading from a source package directory
    private final String packageName;
    private final String packageNameWithSlashes;
    private final List<String> fullyQualifiedPackageNameSegments;
    private static final String QUAL_PACKAGE_SUFFIX = ".qual";

    // For loading from a Jar file
    private static final String JAR_SUFFIX = ".jar";
    private static final String CLASS_SUFFIX = ".class";

    // For loading from external directories
    private static final String JAVA_SUFFIX = ".java";

    // Constants
    private static final char DOT = '.';
    private static final char SLASH = '/';

    /**
     * Processing Env used to create an {@link AnnotationBuilder}, which is in
     * turn used to build the annotation mirror from the loaded class.
     */
    protected ProcessingEnvironment processingEnv;

    // Stores the resource URL of the qual directory of a checker class
    private URL resourceURL;

    // Stores set of the loaded annotation classes
    private final Set<Class<? extends Annotation>> loadedAnnotations;

    /**
     * Constructor for loading annotations defined for a checker.
     *
     * @param checker
     *            a {@link BaseTypeChecker} or its subclass
     */
    public AnnotationClassLoader(BaseTypeChecker checker) {
        this.checker = checker;
        processingEnv = checker.getProcessingEnvironment();

        // package name must use dots, this is later prepended to annotation
        // class names as we load the classes using the class loader
        packageName = checker.getClass().getPackage().getName() + QUAL_PACKAGE_SUFFIX;

        // the package name with dots replaced by slashes will be used to scan file directories
        packageNameWithSlashes = packageName.replace(DOT, SLASH);

        // each component of the fully qualified package name will be used later to recursively descend from
        // a root directory to see if the package exists in some particular root directory
        fullyQualifiedPackageNameSegments = new ArrayList<String>();

        fullyQualifiedPackageNameSegments.addAll(
                Arrays.asList(Pattern.compile(Character.toString(DOT), Pattern.LITERAL).split(packageName)));

        // create the data structure to hold all loaded annotation classes
        loadedAnnotations = new HashSet<Class<? extends Annotation>>();

        // Debug use, uncomment if needed to see all of the classpaths (bootclasspath, extension classpath, and classpath)
        // printPaths();

        // default value for resourceURL
        resourceURL = null;

        // obtain all classpaths
        Set<String> paths = getClasspaths();

        // In checkers, there will be a resource URL for the qual directory. But
        // when called in the framework (eg GeneralAnnotatedTypeFactory), there
        // won't be a resourceURL since there isn't a qual directory

        // each path from the set of classpaths will be checked to see if it
        // contains the qual directory of a checker, if so, the the first
        // directory or jar that contains the package will be used as the source
        // for loading classes from the qual package
        //
        // if either a directory or a jar contains the package, resourceURL will be updated to
        // refer to that source, otherwise resourceURL remains as null
        // TODO: prefer file directory (typically in build directory) over jars? this would help with development builds
        for(String path : paths)
        {
            // temporary URL variable
            URL url = null;

            // see if the current classpath segment is a jar or a directory
            if(path.endsWith(JAR_SUFFIX)) {
                // current classpath segment is a jar
                url = getJarURL(path);

                // see if the jar contains the package
                if(url != null && containsPackage(url))
                {
                    resourceURL = url;
                    break;
                }
            } else {
                // current classpath segment is a directory
                url = getDirectoryURL(path);

                // see if the directory contains the package
                if(url != null && containsPackage(url))
                {
                    // append a slash if necessary
                    if(!path.endsWith(Character.toString(SLASH)))
                    {
                        path += SLASH;
                    }

                    // update URL to the qual directory
                    url = getDirectoryURL(path + packageNameWithSlashes);

                    resourceURL = url;
                    break;
                }
            }
        }

        // load the annotation classes using reflective lookup
        loadBundledAnnotationClasses();
    }

    /**
     * Checks to see if the jar or directory referred by the URL contains the
     * qual package of a specific checker
     *
     * @param url a URL referring to either a jar or a directory
     * @return true if the jar or the directory contains the qual package, false
     *         otherwise
     */
    private final boolean containsPackage(URL url)
    {
        // see whether the resource URL has a protocol of jar or file
        if (url.getProtocol().equals("jar")) {
            // try to open up the jar file
            try {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                JarFile jarFile = connection.getJarFile();

                // check to see if the jar file contains the package
                return checkJarForPackage(jarFile);
            } catch (IOException e) {
                // do nothing for missing or un-openable Jar files
            }
        } else if (url.getProtocol().equals("file")) {
            // open up the directory
            File rootDir = new File(url.getFile());

            // check to see if the directory contains the package
            return checkDirForPackage(rootDir, fullyQualifiedPackageNameSegments.iterator());
        }

        return false;
    }

    /**
     * Checks to see if the jar file contains the qual package of a specific
     * checker
     *
     * @param jar a jar file
     * @return true if the jar file contains the qual package, false otherwise
     */
    private final boolean checkJarForPackage(JarFile jar) {
        Enumeration<JarEntry> jarEntries = jar.entries();

        // loop through the entries in the jar
        while (jarEntries.hasMoreElements()) {
            JarEntry je = jarEntries.nextElement();

            // each entry is the fully qualified path and file name to a particular
            // artifact in the jar file (eg a class file)
            // if the jar has the package, one of the entry's name will begin with
            // the package name in slash notation
            String entryName = je.getName();
            if(entryName.startsWith(packageNameWithSlashes))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks to see if the current directory contains the qual package through
     * recursion currentDir starts at the root directory (a directory passed in
     * as part of the classpaths), the iterator goes through each segment of the
     * fully qualified package name (each segment is separated by a dot)
     *
     * Each step of the recursion checks to see if there's a subdirectory in the
     * current directory which has a name matching the package name segment, if
     * so, it recursively descends into that subdirectory to check the next
     * package name segment
     *
     * If there's no more segments left, then we've found the qual directory of
     * interest
     *
     * If we've checked every subdirectory and none of them match the current
     * package name segment, then the qual directory of interest does not exist
     * in the given root directory (at the beginning of recursion)
     *
     * @param currentDir current directory
     * @param pkgNames
     *            an iterator which provides each segment of the fully qualified
     *            qual package name
     * @return true if the qual package exists within the root directory, false
     *         otherwise
     */
    private final boolean checkDirForPackage(File currentDir, Iterator<String> pkgNames)
    {
        // if the iterator has no more package name segments, then we've found
        // the qual directory of interest
        if(!pkgNames.hasNext())
        {
            return true;
        }
        // if the file doesn't exist or it isn't a directory, return false
        if (currentDir == null || !currentDir.isDirectory())
        {
            return false;
        }

        // if it isn't empty, dequeue one segment of the fully qualified package name
        String currentPackageDirName = pkgNames.next();

        // scan current directory to see if there's a sub-directory that has a
        // matching name as the package name segment
        for (File file : currentDir.listFiles()) {
            if(file.isDirectory() && file.getName().equals(currentPackageDirName))
            {
                // if so, recursively descend and look at the next segment of the package name
                return checkDirForPackage(file, pkgNames);
            }
        }

        // if no sub-directory has a matching name, then that means there isn't a matching qual package
        return false;
    }

    /**
     * Given an absolute path to a directory, this method will return a URL
     * reference to that directory
     *
     * @param absolutePathToDirectory an absolute path to a directory
     * @return a URL reference to the directory, or null if the URL is malformed
     */
    private URL getDirectoryURL(String absolutePathToDirectory)
    {
        URL directoryURL = null;

        try {
            directoryURL = new File(absolutePathToDirectory).toURI().toURL();
        } catch (MalformedURLException e) {
            processingEnv.getMessager().printMessage(Kind.NOTE, "Directory URL " + absolutePathToDirectory + " is malformed");
        }
        return directoryURL;
    }

    /**
     * Given an absolute path to a jar file, this method will return a URL
     * reference to that jar file
     *
     * @param absolutePathToJarFile an absolute path to a jar file
     * @return a URL reference to the jar file, or null if the URL is malformed
     */
    private URL getJarURL(String absolutePathToJarFile)
    {
        URL jarURL = null;

        try {
            jarURL = new URL("jar:file:"+ absolutePathToJarFile + "!/");
        } catch (MalformedURLException e) {
            processingEnv.getMessager().printMessage(Kind.NOTE, "Jar URL " + absolutePathToJarFile + " is malformed");
        }

        return jarURL;
    }

    /**
     * Obtains and returns a set of the classpaths from compiler options, system environment variables, and by
     * examining the classloader to see what paths it has access to
     * @return an immutable set of the classpaths
     */
    private Set<String> getClasspaths()
    {
        Set<String> paths = new HashSet<String>();

        // add all paths in Xbootclasspath
        paths.addAll(Arrays.asList(System.getProperty("sun.boot.class.path").split(":")));

        // add all extension paths
        paths.addAll(Arrays.asList(System.getProperty("java.ext.dirs").split(":")));

        // add all paths in CLASSPATH, -cp, and -classpath
        paths.addAll(Arrays.asList(System.getProperty("java.class.path").split(":")));

        // add all paths that are examined by the classloader
        ClassLoader applicationClassLoader = this.checker.getClass().getClassLoader();
        if (applicationClassLoader == null) {
            // if the application classloader for the checker isn't available, then
            // use the System application classloader
            applicationClassLoader = ClassLoader.getSystemClassLoader();
        }
        URL[] urls = ((URLClassLoader) applicationClassLoader).getURLs();
        for(int i=0; i < urls.length; i++) {
            paths.add(urls[i].getFile().toString());
        }

        return Collections.unmodifiableSet(paths);
    }

    /**
     * Debug Use
     * Displays all classpaths
     */
    private void printPaths()
    {
        // all paths in Xbootclasspath
        String[] bootclassPaths = System.getProperty("sun.boot.class.path").split(":");
        processingEnv.getMessager().printMessage(Kind.NOTE, "bootclass path:");
        for(String path : bootclassPaths)
        {
            processingEnv.getMessager().printMessage(Kind.NOTE, "\t" + path);
        }

        // all extension paths
        String[] extensionDirs = System.getProperty("java.ext.dirs").split(":");
        processingEnv.getMessager().printMessage(Kind.NOTE, "extension dirs:");
        for(String path : extensionDirs)
        {
            processingEnv.getMessager().printMessage(Kind.NOTE, "\t" + path);
        }

        // all paths in CLASSPATH, -cp, and -classpath
        String[] javaclassPaths = System.getProperty("java.class.path").split(":");
        processingEnv.getMessager().printMessage(Kind.NOTE, "java classpaths:");
        for(String path : javaclassPaths)
        {
            processingEnv.getMessager().printMessage(Kind.NOTE, "\t" + path);
        }

        // add all paths that are examined by the classloader
        ClassLoader applicationClassLoader = this.checker.getClass().getClassLoader();
        if (applicationClassLoader == null) {
            processingEnv.getMessager().printMessage(Kind.NOTE, "Using System application classloader!");
            applicationClassLoader = ClassLoader.getSystemClassLoader();
        }
        processingEnv.getMessager().printMessage(Kind.NOTE, "classloader examined paths:");
        URL[] urls = ((URLClassLoader) applicationClassLoader).getURLs();
        for(int i=0; i < urls.length; i++) {
            processingEnv.getMessager().printMessage(Kind.NOTE, "\t" + urls[i].getFile());
        }
    }

    /**
     * Gets the set of the loaded annotation classes. Note that the returned set
     * from this method is mutable. This method is intended to be called within
     * {@link AnnotatedTypeFactory#createSupportedTypeQualifiers()
     * createSupportedTypeQualifiers()} (or it's helper methods) to help define
     * the set of supported qualifiers.
     * {@link AnnotatedTypeFactory#createSupportedTypeQualifiers()
     * createSupportedTypeQualifiers()} must return an immutable set, and it is
     * the responsibility of that method (or helper methods it calls) to convert
     * the set returned by this method, along with any additional annotation
     * classes, into an immutable set.
     *
     * @return the set of loaded annotation classes
     */
    public final Set<Class<? extends Annotation>> getLoadedAnnotationClasses() {
        return loadedAnnotations;
    }

    /**
     * Loads type annotations located in the qual directory of a checker
     * (referenced by the resourceURL) via reflective annotation class name lookup.
     */
    private void loadBundledAnnotationClasses() {
        if (resourceURL == null) {
            // if there's no resourceURL, then there's nothing we can load
            return;
        }

        // retrieve the annotation class names
        Set<String> annoFiles = getBundledAnnotationNames();

        // loop through each class name & load the class
        for (String fileName : annoFiles) {
            String annoName = packageName + DOT + fileName;

            Class<? extends Annotation> annoClass = loadAnnotationClass(annoName);
            if (annoClass != null) {
                loadedAnnotations.add(annoClass);
            }
        }
    }

    /**
     * Retrieves the annotation class file names from the qual directory of a
     * checker
     *
     * @return a set of fully qualified class names of the annotations
     */
    private final Set<String> getBundledAnnotationNames() {
        Set<String> results = null;

        // see whether the resource URL has a protocol of jar or file
        if (resourceURL.getProtocol().equals("jar")) {
            // if the checker class file is contained within a jar, then the
            // resource URL for the qual directory will have the protocol
            // "jar". This means the whole checker is loaded as a jar file.

            // open up that jar file and extract annotation class names
            try {
                JarURLConnection connection = (JarURLConnection) resourceURL.openConnection();
                JarFile jarFile = connection.getJarFile();

                // get class names inside the jar file within the particular
                // package
                results = getBundledAnnotationNamesFromJar(jarFile);
            } catch (IOException e) {
                ErrorReporter.errorAbort("AnnotatedTypeLoader: cannot open the Jar file " + resourceURL.getFile());
            }
        } else if (resourceURL.getProtocol().equals("file")) {
            // if the checker class file is found within the file system itself
            // within some directory (usually development build directories),
            // then process the package as a file directory in the file system
            // and load the annotations contained in the qual directory

            results = new HashSet<String>();
            // open up the directory
            File packageDir = new File(resourceURL.getFile());
            for (File file : packageDir.listFiles()) {
                String fileName = file.getName();
                // filter for just class files
                if (fileName.endsWith(CLASS_SUFFIX)) {
                    String annotationClassName = fileName.substring(0, fileName.lastIndexOf('.'));
                    results.add(annotationClassName);
                }
            }
        }

        return results;
    }

    /**
     * Retrieves the annotation class file names from the qual directory
     * contained inside a jar
     *
     * @param jar
     *            the JarFile containing the annotation class files
     * @return a set of fully qualified class names of the annotations
     */
    private final Set<String> getBundledAnnotationNamesFromJar(JarFile jar) {
        Set<String> annos = new HashSet<String>();

        // get an enumeration iterator for all the content entries in the jar
        // file
        Enumeration<JarEntry> jarEntries = jar.entries();

        // enumerate through the entries
        while (jarEntries.hasMoreElements()) {
            JarEntry je = jarEntries.nextElement();
            // filter out directories and non-class files
            if (je.isDirectory() || !je.getName().endsWith(CLASS_SUFFIX)) {
                continue;
            }

            // get rid of the .class suffix
            String className = je.getName().substring(0, je.getName().lastIndexOf('.'));
            // convert path notation to class notation
            className = className.replace(SLASH, DOT);

            // filter for qual package
            if (className.startsWith(packageName)) {
                // remove qual package prefix, keeping only the class name
                className = className.substring((packageName + DOT).length());
                // add to set
                annos.add(className);
            }
        }

        return annos;
    }

    /**
     * This method takes as input the canonical name of an external annotation
     * class and loads and returns that class via the class loader.
     *
     * @param annoName
     *            canonical name of an external annotation class, e.g.
     *            "myproject.qual.myannotation"
     * @return the loaded annotation class
     */
    public /*@Nullable*/ Class<? extends Annotation> loadExternalAnnotationClass(String annoName) {
        try {
            final Class<? extends Annotation> annoClass = Class.forName(annoName).asSubclass(Annotation.class);
            return annoClass;
        } catch (ClassNotFoundException e) {
            checker.userErrorAbort(checker.getClass().getSimpleName()
                    + ": could not load class for annotation: " + annoName
                    + "; ensure that your classpath is correct");
        } catch (ClassCastException e) {
            checker.userErrorAbort(checker.getClass().getSimpleName()
                    + ": the loaded class: " + annoName
                    + " is not an annotation, ensure it is defined correctly");
        }
        return null;
    }

    /**
     * This method takes as input a fully qualified path to a directory, and
     * loads and returns the set of all annotation classes from that directory.
     *
     * @param dirName
     *            absolute path to a directory containing annotation classes
     * @return a set of annotation classes
     */
    public /*@Nullable*/ Set<Class<? extends Annotation>> loadExternalAnnotationClassesFromDirectory(String dirName) {
        File rootDirectory = new File(dirName);

        Set<Class<? extends Annotation>> typeQualifiers = loadExternalAnnotationClassesFromDirectory(dirName, rootDirectory);

        return typeQualifiers;
    }

    /**
     * Loads all annotations from the current directory, and recursively
     * descends and loads annotations from sub-directories.
     *
     * @param rootDirectory
     *            a string storing the absolute path of the root directory of a
     *            set of externally defined annotations, which is subtracted
     *            from class names to retrieve each class's fully qualified
     *            class names
     * @param currentDirectory
     *            a {@link File} object representing the current subdirectory of
     *            the root directory
     * @return a set of annotation classes of the annotations in the root
     *         directory or its subdirectories
     */
    private /*@Nullable*/ Set<Class<? extends Annotation>> loadExternalAnnotationClassesFromDirectory(
            final String rootDirectory, File currentDirectory) {
        Set<Class<? extends Annotation>> annotations = new HashSet<Class<? extends Annotation>>();

        File[] files = currentDirectory.listFiles();
        for (File file : files) {
            if (file.isFile()) {
                // load the annotation
                // Full file name, including path to file
                String fullFileName = file.getAbsolutePath();
                // Simple file name
                String fileName = fullFileName.substring(fullFileName.lastIndexOf(File.separator) + 1, fullFileName.length());
                // Path to file
                String filePath = fullFileName.substring(0, fullFileName.lastIndexOf(File.separator));
                // Package name
                String qualPackageName = "";
                if (!filePath.equals(rootDirectory)) {
                    qualPackageName = filePath.substring(rootDirectory.length() + 1, filePath.length()).replace(SLASH, DOT) + DOT;
                }
                // Annotation name, which is the same as the file name but with
                // extension removed
                String annotationName = fileName;
                if (fileName.lastIndexOf(DOT) != -1) {
                    annotationName = fileName.substring(0, fileName.lastIndexOf(DOT));
                }

                // Fully qualified annotation name
                String fullyQualifiedAnnoName = qualPackageName + annotationName;

                // Filter for a java file
                if (fileName.endsWith(JAVA_SUFFIX)) {
                    // Load the class
                    Class<? extends Annotation> annotationClass = loadAnnotationClass(fullyQualifiedAnnoName);
                    if (annotationClass != null) {
                        // If successfully loaded, add to annotations set
                        annotations.add(annotationClass);
                    }
                }
            } else if (file.isDirectory()) {
                // Descend into the directory and recursively load annotations
                annotations.addAll(loadExternalAnnotationClassesFromDirectory(rootDirectory, file));
            }
        }

        return annotations;
    }

    /**
     * Loads the class indicated by the fullyQualifiedClassName, and checks to
     * see if it is an annotation that is supported by a checker.
     *
     * @param fullyQualifiedClassName
     *            the fully qualified name of the class
     * @return the loaded annotation class if it is defined with
     *         ElementType.TYPE_USE and is a supported annotation, null
     *         otherwise
     */
    private /*@Nullable*/ Class<? extends Annotation> loadAnnotationClass(String fullyQualifiedClassName) {
        Class<?> cls = null;

        try {
            // load the class
            cls = Class.forName(fullyQualifiedClassName);
        } catch (ClassNotFoundException e) {
            // do nothing: projects can have annotation class files and regular
            // source files located within the same directory, and as such when
            // it tires to load an uncompiled source file, it will throw
            // ClassNotFoundException
        }

        // ensure that the freshly loaded class is an annotation, and has
        // the @Target annotation
        if (cls != null && cls.isAnnotation() && cls.getAnnotation(Target.class) != null) {
            // retrieve the set of ElementTypes in the @Target
            // meta-annotation and check to see if this annotation is
            // supported for automatic loading
            if (AnnotatedTypes.hasTypeQualifierElementTypes(cls.getAnnotation(Target.class).value())) {
                // if it is supported, then subclass it as an Annotation
                // class
                Class<? extends Annotation> annoClass = cls.asSubclass(Annotation.class);

                // see if the annotation is supported by a checker
                if (isSupportedAnnotationClass(annoClass)) {
                    return annoClass;
                }
            }
        }

        return null;
    }

    /**
     * Checks to see whether a particular annotation class is supported.
     *
     * Every subclass of AnnotatedTypeLoader can override this method to
     * indicate whether a particular annotation is supported by its checker.
     *
     * @param annoClass
     *            an annotation class
     * @return true if the annotation is supported, false if it isn't
     */
    protected boolean isSupportedAnnotationClass(Class<? extends Annotation> annoClass) {
        if (loadedAnnotations.contains(annoClass)) {
            // if it has already been checked before, return true
            return true;
        } else {
            // The standard way to see if an annotation is supported is to build
            // it's annotation mirror if there's no problems building the
            // mirror, then it is supported
            AnnotationBuilder builder = new AnnotationBuilder(processingEnv, annoClass);
            AnnotationMirror annoMirroResult = builder.build();
            // TODO: build() internally will error abort if it fails, can we
            // gracefully resume here?
            return (annoMirroResult != null);
        }
    }
}
