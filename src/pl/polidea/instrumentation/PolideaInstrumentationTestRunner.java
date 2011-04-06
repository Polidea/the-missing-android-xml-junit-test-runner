package pl.polidea.instrumentation;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;
import android.os.Bundle;
import android.test.AndroidTestRunner;
import android.test.InstrumentationTestRunner;
import android.util.Log;

/**
 * Test runner that should produce Junit-compatible test results. It can be used
 * to produce output that is parseable by a CI reporting tool which understands
 * Junit XML format (Jenkins, Hudson, Bamboo ...).
 * 
 * @author potiuk
 * 
 */
public class PolideaInstrumentationTestRunner extends InstrumentationTestRunner {

    private static final String TAG = PolideaInstrumentationTestRunner.class.getSimpleName();
    private static final String DEFAULT_JUNIT_FILE_POSTFIX = "-TEST.xml";
    private String junitOutputDirectory = null;
    private String junitOutputFilePostfix = null;
    private boolean junitOutputEnabled;
    private boolean justCount;

    public static class TestInfo {
        public Package thePackage;
        public Class< ? extends TestCase> testCase;
        public String name;
        public Throwable error;
        public AssertionFailedError failure;
        public long time;

        @Override
        public String toString() {
            return name + "[" + testCase.getClass() + "] <" + thePackage + ">. Time: " + time + " ms. E<" + error
                    + ">, F <" + failure + ">";
        }
    }

    public static class TestPackageInfo {
        public Package thePackage;
        public Map<Class< ? extends TestCase>, TestCaseInfo> testCaseList = new LinkedHashMap<Class< ? extends TestCase>, TestCaseInfo>();
    }

    public static class TestCaseInfo {
        public Package thePackage;
        public Class< ? extends TestCase> testCaseClass;
        public Map<String, TestInfo> testMap = new LinkedHashMap<String, TestInfo>();
    }

    private final LinkedHashMap<Package, TestCaseInfo> caseMap = new LinkedHashMap<Package, TestCaseInfo>();
    /**
     * The last test class we executed code from.
     */
    private boolean outputEnabled;
    private AndroidTestRunner runner;
    private boolean logOnly;

    private class JunitTestListener implements TestListener {

        /**
         * The minimum time we expect a test to take.
         */
        private static final int MINIMUM_TIME = 100;
        private final ThreadLocal<Long> startTime = new ThreadLocal<Long>();

        @Override
        public void startTest(final Test test) {
            if (test instanceof TestCase) {
                Thread.currentThread().setContextClassLoader(test.getClass().getClassLoader());
                startTime.set(System.currentTimeMillis());
            }
        }

        @Override
        public void endTest(final Test t) {
            Log.d(TAG, "Ending test " + t);
            if (t instanceof TestCase) {
                final TestCase testCase = (TestCase) t;
                cleanup(testCase);
                /*
                 * Make sure all tests take at least MINIMUM_TIME to complete.
                 * If they don't, we wait a bit. The Cupcake Binder can't handle
                 * too many operations in a very short time, which causes
                 * headache for the CTS.
                 */
                final long timeTaken = System.currentTimeMillis() - startTime.get();
                getTestInfo(testCase).time = timeTaken;
                if (timeTaken < MINIMUM_TIME) {
                    try {
                        Thread.sleep(MINIMUM_TIME - timeTaken);
                    } catch (final InterruptedException ignored) {
                        // We don't care.
                    }
                }
            }
            Log.d(TAG, "Ended test " + t);
        }

        @Override
        public void addError(final Test test, final Throwable t) {
            if (test instanceof TestCase) {
                getTestInfo((TestCase) test).error = t;
            }
        }

        @Override
        public void addFailure(final Test test, final AssertionFailedError f) {
            if (test instanceof TestCase) {
                getTestInfo((TestCase) test).failure = f;
            }
        }

        /**
         * Nulls all non-static reference fields in the given test class. This
         * method helps us with those test classes that don't have an explicit
         * tearDown() method. Normally the garbage collector should take care of
         * everything, but since JUnit keeps references to all test cases, a
         * little help might be a good idea.
         * 
         * Note! This is copied from InstrumentationCoreTestRunner in android
         * code
         */
        private void cleanup(final TestCase test) {
            Log.d(TAG, "Cleaning up: " + test);
            Class< ? > clazz = test.getClass();

            while (clazz != TestCase.class) {
                final Field[] fields = clazz.getDeclaredFields();
                for (final Field field : fields) {
                    final Field f = field;
                    if (!f.getType().isPrimitive() && !Modifier.isStatic(f.getModifiers())) {
                        try {
                            f.setAccessible(true);
                            f.set(test, null);
                        } catch (final Exception ignored) {
                            // Nothing we can do about it.
                        }
                    }
                }

                clazz = clazz.getSuperclass();
            }
            Log.d(TAG, "Cleaned up: " + test);
        }
    }

    private synchronized TestInfo getTestInfo(final TestCase testCase) {
        final Class< ? extends TestCase> clazz = testCase.getClass();
        final Package thePackage = clazz.getPackage();
        final String name = testCase.getName();
        TestCaseInfo caseInfo = caseMap.get(thePackage);
        if (caseInfo == null) {
            caseInfo = new TestCaseInfo();
            caseInfo.testCaseClass = testCase.getClass();
            caseInfo.thePackage = thePackage;
            caseMap.put(thePackage, caseInfo);
        }
        TestInfo ti = caseInfo.testMap.get(name);
        if (ti == null) {
            ti = new TestInfo();
            ti.name = name;
            ti.testCase = testCase.getClass();
            ti.thePackage = thePackage;
            caseInfo.testMap.put(name, ti);
        }
        return ti;
    }

    public PrintWriter startFile(final Package p) throws IOException {
        Log.d(TAG, "Starting Package " + p);
        final File outputFile = new File(getJunitOutputFilePath(p));
        Log.d(TAG, "Writing to file " + outputFile);
        final PrintWriter outputFileWriter = new PrintWriter(outputFile, "UTF-8");
        outputFileWriter.write("<testsuites>\n");
        return outputFileWriter;
    }

    private void endFile(final PrintWriter p) {
        Log.d(TAG, "closing file");
        p.write("</testsuites>\n");
        p.flush();
        p.close();
    }

    protected String getTimestamp() {
        final long time = System.currentTimeMillis();
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        return sdf.format(time);
    }

    private void writeClassToFile(final PrintWriter printWriter, final TestCaseInfo tci) {
        final Package thePackage = tci.thePackage;
        final Class< ? extends TestCase> clazz = tci.testCaseClass;
        final int tests = tci.testMap.size();
        final String timestamp = getTimestamp();
        int errors = 0;
        int failures = 0;
        int time = 0;
        for (final TestInfo testInfo : tci.testMap.values()) {
            if (testInfo.error != null) {
                errors++;
            }
            if (testInfo.failure != null) {
                failures++;
            }
            time += testInfo.time;
        }
        final String suiteHeader = "    <testsuite " + "errors=\"" + errors + "\" " + "failures=\"" + failures + "\" "
                + "name=\"" + clazz.getName() + "\" " + "package=\"" + thePackage + "\" " + "tests=\"" + tests + "\" "
                + "time=\"" + time / 1000.0 + "\" " + "timestamp=\"" + timestamp + "\" " + ">\n";
        Log.d(TAG, suiteHeader);
        printWriter.write(suiteHeader);
        for (final TestInfo testInfo : tci.testMap.values()) {
            writeTestInfo(printWriter, testInfo);
        }
        printWriter.write("        <properties/>\n");
        printWriter.write("        <system-out>\n");
        printWriter.write("        </system-out>\n");
        printWriter.write("        <system-err>\n");
        printWriter.write("        </system-err>\n");
        printWriter.write("    </testsuite>\n");
    }

    private void writeTestInfo(final PrintWriter printWriter, final TestInfo testInfo) {
        final String testCase = "            <testcase classname=\"" + testInfo.testCase.getName() + "\" name=\""
                + testInfo.name + "\" time=\"" + testInfo.time / 1000.0 + "\">\n";
        printWriter.write(testCase);
        if (testInfo.error != null) {
            printWriter.write("                <error><![CDATA[\n");
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw, true);
            testInfo.error.printStackTrace(pw);
            printWriter.write(sw.toString());
            printWriter.write("]]>             </error>\n");
        }
        if (testInfo.failure != null) {
            printWriter.write("                <failure><![CDATA[\n");
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw, true);
            testInfo.failure.printStackTrace(pw);
            printWriter.write(sw.toString());
            printWriter.write("]]>             </failure>\n");
        }
        printWriter.write("            </testcase>\n");
    }

    private String getJunitOutputFilePath(final Package p) {
        return junitOutputDirectory + File.separator + p.getName() + junitOutputFilePostfix;
    }

    private void setOutputProperties() {
        if (junitOutputDirectory == null) {
            junitOutputDirectory = getTargetContext().getFilesDir().getAbsolutePath();
        }
        if (junitOutputFilePostfix == null) {
            junitOutputFilePostfix = DEFAULT_JUNIT_FILE_POSTFIX;
        }
    }

    private boolean getBooleanArgument(final Bundle arguments, final String tag, final boolean defaultValue) {
        final String tagString = arguments.getString(tag);
        if (tagString == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(tagString);
    }

    @Override
    public void onCreate(final Bundle arguments) {
        Log.d(TAG, "Creating the Test Runner with arguments: " + arguments.keySet());
        if (arguments != null) {
            junitOutputEnabled = getBooleanArgument(arguments, "junitXmlOutput", true);
            junitOutputDirectory = arguments.getString("junitOutputDirectory");
            junitOutputFilePostfix = arguments.getString("junitOutputFilePostFix");
            justCount = getBooleanArgument(arguments, "count", false);
            logOnly = getBooleanArgument(arguments, "log", false);
        }
        setOutputProperties();
        deleteOldFiles();
        super.onCreate(arguments);
    }

    private void deleteOldFiles() {
        for (final File f : new File(junitOutputDirectory).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(final File dir, final String filename) {
                return filename.endsWith(junitOutputFilePostfix);
            }
        })) {
            f.delete();
        }
    }

    @Override
    public void finish(final int resultCode, final Bundle results) {
        Log.d(TAG, "Finishing test");
        if (outputEnabled) {
            Log.d(TAG, "Packages: " + caseMap.size());
            for (final Package p : caseMap.keySet()) {
                Log.d(TAG, "Processing package " + p);
                try {
                    final PrintWriter pw = startFile(p);
                    try {
                        final TestCaseInfo tc = caseMap.get(p);
                        writeClassToFile(pw, tc);
                    } finally {
                        endFile(pw);
                    }
                } catch (final IOException e) {
                    Log.e(TAG, "Error: " + e, e);
                }
            }
        }
        super.finish(resultCode, results);
    }

    @Override
    protected AndroidTestRunner getAndroidTestRunner() {
        Log.d(TAG, "Getting android test runner");
        runner = super.getAndroidTestRunner();
        if (junitOutputEnabled && !justCount && !logOnly) {
            Log.d(TAG, "JUnit test output enabled");
            outputEnabled = true;
            runner.addTestListener(new JunitTestListener());
        } else {
            outputEnabled = false;
            Log.d(TAG, "JUnit test output disabled");
        }
        return runner;
    }
}
