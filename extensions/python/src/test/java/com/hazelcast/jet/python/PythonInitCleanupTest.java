/*
 * Copyright 2020 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.jet.python;

import com.hazelcast.internal.nio.IOUtil;
import com.hazelcast.jet.SimpleTestInClusterSupport;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.test.TestSources;
import com.hazelcast.test.annotation.NightlyTest;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletionException;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static com.hazelcast.jet.python.PythonTransforms.mapUsingPythonBatch;
import static com.hazelcast.test.HazelcastTestSupport.assumeThatNoWindowsOS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@Category({NightlyTest.class})
public class PythonInitCleanupTest extends SimpleTestInClusterSupport {

    private static final String ECHO_HANDLER_FUNCTION
            = "def handle(input_list):\n"
            + "    return ['echo-%s' % i for i in input_list]\n";

    private static final String FAILING_FUNCTION
            = "def handle(input_list):\n"
            + "    assert 1 == 2\n"
            + "    return ['echo-%s' % i for i in input_list]\n";

    private File baseDir;

    @BeforeClass
    public static void beforeClass() {
        initialize(2, null);
        assumeThatNoWindowsOS();
    }

    @Before
    public void before() throws Exception {
        baseDir = createTempDirectory();
        installFileToBaseDir(ECHO_HANDLER_FUNCTION, "echo.py");
    }

    @After
    public void after() {
        IOUtil.delete(baseDir);
    }

    @Test
    public void initIsRunBeforeJob() throws IOException {
        // Given
        String baseDirStr = baseDir.toString();
        String outcomeFilename = "init_outcome.txt";
        installFileToBaseDir(String.format(
                "echo 'Init script running'%n"
                + "sleep 10%n"
                + "echo 'init.sh' executed > %s/%s%n",
                baseDirStr, outcomeFilename), "init.sh");

        PythonServiceConfig cfg = new PythonServiceConfig()
                .setBaseDir(baseDir.toString())
                .setHandlerModule("echo")
                .setHandlerFunction("handle");

        Pipeline p = Pipeline.create();
        // When
        p.readFrom(TestSources.items("1"))
                .map(t -> {
                    Path expectedInitFile = Paths.get(baseDirStr, outcomeFilename);
                    // Then
                    assertTrue("file which should be created by init.sh does not exist",
                            Files.exists(expectedInitFile));
                    return t;
                })
                .apply(mapUsingPythonBatch(cfg)).setLocalParallelism(2)
                .writeTo(Sinks.logger());

        instance().newJob(p).join();
    }

    @Test
    public void cleanupIsRunAfterJob() throws IOException {
        // Given
        String baseDirStr = baseDir.toString();
        String outcomeFilename = "cleanup_outcome.txt";
        installFileToBaseDir(
                prepareSimpleCleanupSh(baseDirStr, outcomeFilename),
                "cleanup.sh");

        PythonServiceConfig cfg = new PythonServiceConfig()
                .setBaseDir(baseDir.toString())
                .setHandlerModule("echo")
                .setHandlerFunction("handle");

        Pipeline p = Pipeline.create();
        // When
        p.readFrom(TestSources.items("1"))
                .map(t -> {
                    Thread.sleep(5_000);
                    Path expectedInitFile = Paths.get(baseDirStr, outcomeFilename);
                    // Then
                    assertFalse("file which should be created by cleanup.sh exists during job execution",
                            Files.exists(expectedInitFile));
                    return t;
                })
                .apply(mapUsingPythonBatch(cfg)).setLocalParallelism(2)
                .writeTo(Sinks.logger());

        instance().newJob(p).join();

        assertTrue("Cleanup script didn't run", new File(baseDir, outcomeFilename).isFile());
    }

    @Test
    public void initFailureCausesJobFailure() throws IOException {
        // Given
        installFileToBaseDir("exit 1", "init.sh");
        PythonServiceConfig cfg = new PythonServiceConfig()
                .setBaseDir(baseDir.toString())
                .setHandlerModule("echo")
                .setHandlerFunction("handle");

        Pipeline p = Pipeline.create();
        // When
        p.readFrom(TestSources.items("1"))
                .apply(mapUsingPythonBatch(cfg)).setLocalParallelism(2)
                .writeTo(Sinks.logger());

        // Then
        try {
            instance().newJob(p).join();
            fail();
        } catch (CompletionException ex) {
            // expected
        }
    }

    @Test
    public void cleanupExecutedIfInitFailed() throws IOException {
        // Given
        installFileToBaseDir("exit 1", "init.sh");
        String baseDirStr = baseDir.toString();
        String outcomeFilename = "cleanup_outcome.txt";
        installFileToBaseDir(
                prepareSimpleCleanupSh(baseDirStr, outcomeFilename),
                "cleanup.sh");
        PythonServiceConfig cfg = new PythonServiceConfig()
                .setBaseDir(baseDir.toString())
                .setHandlerModule("echo")
                .setHandlerFunction("handle");

        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items("1"))
                .apply(mapUsingPythonBatch(cfg)).setLocalParallelism(2)
                .writeTo(Sinks.logger());

        // When
        try {
            instance().newJob(p).join();
            fail();
        } catch (CompletionException ex) {
            // expected
        }

        // Then
        assertTrue("Cleanup script didn't run", new File(baseDir, outcomeFilename).isFile());
    }

    @Test
    public void cleanupExecutedIfPythonFailed() throws IOException {
        // Given
        installFileToBaseDir(FAILING_FUNCTION, "failing.py");
        String baseDirStr = baseDir.toString();
        String outcomeFilename = "cleanup_outcome.txt";
        installFileToBaseDir(
                prepareSimpleCleanupSh(baseDirStr, outcomeFilename),
                "cleanup.sh");
        PythonServiceConfig cfg = new PythonServiceConfig()
                .setBaseDir(baseDir.toString())
                .setHandlerModule("failing")
                .setHandlerFunction("handle");

        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items("1"))
                .apply(mapUsingPythonBatch(cfg)).setLocalParallelism(2)
                .writeTo(Sinks.logger());

        // When
        try {
            instance().newJob(p).join();
            fail();
        } catch (CompletionException ex) {
            // expected
        }

        // Then
        assertTrue("Cleanup script didn't run", new File(baseDir, outcomeFilename).isFile());
    }

    @Test
    public void cleanupExecutedIfJobFailed() throws IOException {
        // Given
        String baseDirStr = baseDir.toString();
        String outcomeFilename = "cleanup_outcome.txt";
        installFileToBaseDir(
                prepareSimpleCleanupSh(baseDirStr, outcomeFilename),
                "cleanup.sh");
        PythonServiceConfig cfg = new PythonServiceConfig()
                .setBaseDir(baseDir.toString())
                .setHandlerModule("echo")
                .setHandlerFunction("handle");

        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items("1"))
                .map(t -> {
                    assertTrue("expected failure", false);
                    return t;
                })
                .apply(mapUsingPythonBatch(cfg)).setLocalParallelism(2)
                .writeTo(Sinks.logger());

        // When
        try {
            instance().newJob(p).join();
            fail();
        } catch (CompletionException ex) {
            // expected
        }

        // Then
        assertTrue("Cleanup script didn't run", new File(baseDir, outcomeFilename).isFile());
    }

    @Test
    public void initAndCleanupNotExecutedIfHandlerFileIsUsed() throws IOException {
        // Given
        String baseDirStr = baseDir.toString();
        String outcomeInitFilename = "init_outcome.txt";
        installFileToBaseDir(
                prepareSimpleInitSh(baseDirStr, outcomeInitFilename),
                "init.sh");
        String outcomeCleanupFilename = "cleanup_outcome.txt";
        installFileToBaseDir(
                prepareSimpleCleanupSh(baseDirStr, outcomeCleanupFilename),
                "cleanup.sh");
        PythonServiceConfig cfg = new PythonServiceConfig()
                .setHandlerFile(baseDir + "/echo.py")
                .setHandlerFunction("handle");

        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items("1"))
                .apply(mapUsingPythonBatch(cfg)).setLocalParallelism(2)
                .writeTo(Sinks.logger());

        // When
        instance().newJob(p).join();

        // Then
        assertFalse("init script ran", new File(baseDir, outcomeCleanupFilename).exists());
        assertFalse("Cleanup script ran", new File(baseDir, outcomeCleanupFilename).exists());
    }

    private String prepareSimpleInitSh(String baseDirStr, String outcomeFilename) {
        return String.format(
                "echo 'Init script running'%n"
                + "echo 'init.sh' executed > %s/%s%n", baseDirStr, outcomeFilename);
    }

    private String prepareSimpleCleanupSh(String baseDirStr, String outcomeFilename) {
        return String.format(
                "echo 'Cleanup script running'%n"
                + "echo 'cleanup.sh' executed > %s/%s%n", baseDirStr, outcomeFilename);
    }

    private void installFileToBaseDir(String contents, String filename) throws IOException {
        try (InputStream in = new ByteArrayInputStream(contents.getBytes(UTF_8))) {
            Files.copy(in, new File(baseDir, filename).toPath());
        }
    }

}
