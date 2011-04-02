package pl.polidea.instrumentation;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

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
    private static final String DEFAULT_JUNIT_FILE_NAME = "TEST-all.xml";
    private String junitOutputFilePath = null;
    private boolean junitOutputEnabled;

    private static final String TAG = PolideaInstrumentationTestRunner.class.getSimpleName();

    /**
     * File where test results are written
     */
    private File outputFile;
    /**
     * The start time of our current test in System.currentTimeMillis().
     */
    private long startTime;
    /**
     * The last test class we executed code from.
     */
    private Class< ? extends TestCase> lastClass;
    private PrintWriter outputFileWriter;

    private class JunitTestListener implements TestListener {

        /**
         * The minimum time we expect a test to take.
         */
        private static final int MINIMUM_TIME = 100;

        @SuppressWarnings("unchecked")
        @Override
        public void startTest(final Test test) {
            if (test instanceof TestCase) {
                if (test.getClass() != lastClass) {
                    endSingleClass();
                    lastClass = (Class< ? extends TestCase>) test.getClass();
                }
                Thread.currentThread().setContextClassLoader(test.getClass().getClassLoader());
                startTime = System.currentTimeMillis();
            }
        }

        @Override
        public void endTest(final Test test) {
            if (test instanceof TestCase) {
                cleanup((TestCase) test);
                /*
                 * Make sure all tests take at least MINIMUM_TIME to complete.
                 * If they don't, we wait a bit. The Cupcake Binder can't handle
                 * too many operations in a very short time, which causes
                 * headache for the CTS.
                 */
                final long timeTaken = System.currentTimeMillis() - startTime;

                if (timeTaken < MINIMUM_TIME) {
                    try {
                        Thread.sleep(MINIMUM_TIME - timeTaken);
                    } catch (final InterruptedException ignored) {
                        // We don't care.
                    }
                }
            }
        }

        @Override
        public void addError(final Test test, final Throwable t) {
            // This space intentionally left blank.
        }

        @Override
        public void addFailure(final Test test, final AssertionFailedError t) {
            // This space intentionally left blank.
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
        }

    }

    private void endSingleClass() {
        if (lastClass == null) {
            return;
        }
        final Class< ? extends TestCase> clazz = lastClass;
        if (outputFile != null) {
            final int errors = 0;
            final int failures = 0;
            final String thePackage = clazz.getPackage().getName();
            final String name = clazz.getSimpleName();
            final int tests = 10;
            final String timestamp = "TIMESTAMP";
            final int time = 10;
            outputFileWriter.write("<testsuite " + "errors=\"" + errors + "\" " + "failures=\"" + failures + "\" "
                    + "name=\"" + name + "\" " + "package=\"" + thePackage + "\" " + "tests=\"" + tests + "\" "
                    + "time=\"" + time + "\" " + "timestamp=\"" + timestamp + "\" " + ">\n");
            outputFileWriter.write("<properties/>\n");
            outputFileWriter.write("<system-out>\n");
            outputFileWriter.write("</system-out>\n");
            outputFileWriter.write("<system-err>\n");
            outputFileWriter.write("</system-err>\n");
            outputFileWriter.write("</testsuite>\n");
        }
    }

    private void startFile() {
        outputFile = new File(getJunitOutputFilePath());
        Log.d(TAG, "Writing to file " + outputFile);
        try {
            outputFileWriter = new PrintWriter(outputFile, "UTF-8");
            outputFileWriter.write("<testsuites>\n");
        } catch (final IOException e) {
            Log.e(TAG, "Cannot open file for writing " + e, e);
            outputFile = null;
        }
    }

    private void endFile() {
        if (outputFile != null) {
            Log.d(TAG, "closing file " + outputFile);
            outputFileWriter.write("</testsuites>\n");
            outputFileWriter.flush();
            outputFileWriter.close();
        } else {
            Log.d(TAG, "Not writing  - file null");
        }
    }

    private String getJunitOutputFilePath() {
        if (junitOutputFilePath == null) {
            return getTargetContext().getFilesDir().getAbsolutePath() + File.separator + DEFAULT_JUNIT_FILE_NAME;
        } else {
            return junitOutputFilePath;
        }
    }

    @Override
    public void onCreate(final Bundle arguments) {
        if (arguments != null) {
            junitOutputEnabled = arguments.getBoolean("junitXmlOutput", true);
            junitOutputFilePath = arguments.getString("junitOutputFile");
        }
        super.onCreate(arguments);
    }

    @Override
    public void finish(final int resultCode, final Bundle results) {
        endSingleClass();
        endFile();
        super.finish(resultCode, results);
    }

    @Override
    public void onStart() {
        startFile();
        super.onStart();
    }

    @Override
    protected AndroidTestRunner getAndroidTestRunner() {
        Log.d(TAG, "Getting android test runner");
        final AndroidTestRunner runner = super.getAndroidTestRunner();
        if (junitOutputEnabled) {
            Log.d(TAG, "JUnit test output enabled");
            runner.addTestListener(new JunitTestListener());
        } else {
            Log.d(TAG, "JUnit test output disabled");
        }
        return runner;
    }
}
