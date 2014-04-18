##Introduction
Standard android test runner does not produce a very usable output from tests, if you want to process the output and produce some useful reports. 
This is for example where you run android tests using Jenkins/Hudson/Bamboo. All these are capable of parsing standard XML junit output and presenting it in a nice way. 

##The runner
The missing junit test runner provides that capability. As opposed to some other projects, the runner is built as an Instrumentation Test runner, 
thanks to that you can use all the android standard command line parameters, that you would normally use. You can run only small or only big tests, 
you can run emma coverage tests and whatever else android test framework lets you do.

##Running
The test runner should be added to the test project. Either as a .jar file (added in libs directory) or as external library (standard android external library approach). 

In order to use the runner you need to:

1. add it to AndroidManifest.xml of your test project:
  ```
      <instrumentation android:name="pl.polidea.instrumentation.PolideaInstrumentationTestRunner"
                       android:targetPackage="pl.polidea.somepackage"
                       android:label="Tests for pl.polidea.somepackage"/>
  ```

2. run the tests by specifying the runner on command line, for example
    ```
        adb shell am instrument -w somepackage/pl.polidea.instrumentation.PolideaInstrumentationTestRunner
    ```
    or with extra parameters:
    ```
        adb shell am instrument -e junitSplitLevel class -w somepackage/pl.polidea.instrumentation.PolideaInstrumentationTestRunner
    ```

More info about running test runners can be found [here](http://developer.android.com/guide/developing/testing/testing_otheride.html#RunTestsCommand).

You can also use standard android test tasks. Your build.xml might look like:
```
        <project name="Test" default="help">
            <property file="local.properties" />
            <property file="build.properties" />
            <property file="default.properties" />
            <import file="${sdk.dir}/tools/ant/pre_setup.xml" />
            <property name="test.runner" value="pl.polidea.instrumentation.PolideaInstrumentationTestRunner" />
            <setup import="no" />
            <import file="${sdk.dir}/tools/ant/test_rules.xml" />
        </project>
``` 
and then you can execute "ant run-tests" or "ant coverage".

##Supported parameters
The parameters supported by runner are:
    
| *Parameter* | *Description* |
|-------------|---------------|
| junitXmlOutput | boolean ("true"/"false") indicating whether XML Junit output should be produced at all. Default is true |
| junitOutputDirectory | string specifying in which directory the XML files should be placed. Be careful when setting this parameter. TestRunner deletes all the files matching postfix and single filename before running from this directory. Default is the on-device local "files" directory for the TESTED application (not TESTING application!). Usually it is /data/data/`<`package`>`/files |
| junitOutputFilePostfix | string specifying what is the postfix of files created. Default value is "-TEST.xml". The files are always prefixed with package name with the exception of top-level, root package |
| junitNoPackagePrefix | string specifying what is the prefix in case test is in top-level directory (i.e. has no package). Default value is "NO_PACKAGE" |
| junitSplitLevel | string specifying what splitting will be applied. The runner can split the test results into several files: either per class, package or it can produce a single big file for all tests run. Allowed value are "class", "package" or "none". Default value is "package" |
| junitSingleFileName | string specifying what name will be given to output file in case the split is "none". Default value is ALL-TEST.xml |

##Getting the JUnit results 
XML files are generated on the device (or emulator) in /data/data/`<YOUR_APP_PACKAGE>`/files/ and you need to download the files after test using adb pull in order to process them. 
This can be done with command line or step similar to the following added to the build:
```
  <exec executable="${adb}" failonerror="true" dir="junit-results">
    <arg line="${adb.device.arg}" />
    <arg value="pull" />
    <arg value="/data/data/${tested.manifest.package}/files/" />
  </exec>
```

There is one file generated per each package containing test case classes (potentially containing multiple test case classes).

##Analysing the results
The XML produced by the runner is compatible with standard junit generated files. It can be displayed by various plugins of CI servers (Jenkins/Hudson/Bamboo). 
You can also import it into Junit view of eclipse and display the results there (including ability to click-to-go-to-source code)
