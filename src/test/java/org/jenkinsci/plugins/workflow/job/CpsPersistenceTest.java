package org.jenkinsci.plugins.workflow.job;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests a series of failure cases where WorkflowJob interacts with the Cps persistence model in workflow-cps plugin.
 * This verifies that the WorkflowJob end of the persistence world is handling basic cases correctly.
 */
public class CpsPersistenceTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    /** Execution bombed out due to some sort of irrecoverable persistence issue. */
    static void assertNulledExecution(WorkflowRun run) throws Exception {
        if (run.isBuilding()) {
            System.out.println("Run initially building, going to wait a second to see if it finishes, run="+run);
            Thread.sleep(1000);
        }
        Assert.assertFalse(run.isBuilding());
        Assert.assertNotNull(run.getResult());
        FlowExecution fe = run.getExecution();
        Assert.assertNull(fe);
    }

    private static void setCpsDoneFlag(CpsFlowExecution exec, boolean doneState) throws Exception {
        Field doneField = exec.getClass().getDeclaredField("done");
        doneField.setAccessible(true);
        doneField.setBoolean(exec, doneState);
    }

    private static boolean getCpsDoneFlag(CpsFlowExecution exec) throws Exception {
        Field doneField = exec.getClass().getDeclaredField("done");
        doneField.setAccessible(true);
        return doneField.getBoolean(exec);
    }

    private static Stack<BlockStartNode> getCpsBlockStartNodes(CpsFlowExecution exec) throws Exception {
        Field startField = exec.getClass().getDeclaredField("startNodes");
        startField.setAccessible(true);
        Object ob = startField.get(exec);
        if (ob instanceof Stack) {
            @SuppressWarnings("unchecked")
            Stack<BlockStartNode> result = (Stack<BlockStartNode>)ob;
            return result;
        }
        return null;
    }

    /** Verifies all the assumptions about a cleanly finished build. */
    static void assertCompletedCleanly(WorkflowRun run) throws Exception {
        if (run.isBuilding()) {
            System.out.println("Run initially building, going to wait a second to see if it finishes, run="+run);
            Thread.sleep(1000);
        }
        Assert.assertFalse(run.isBuilding());
        Assert.assertNotNull(run.getResult());
        FlowExecution fe = run.getExecution();
        FlowExecutionList.get().forEach(f -> {
            if (fe != null && f == fe) {
                Assert.fail("FlowExecution still in FlowExecutionList!");
            }
        });
        Assert.assertTrue("Queue not empty after completion!", Queue.getInstance().isEmpty());

        if (fe instanceof CpsFlowExecution) {
            CpsFlowExecution cpsExec = (CpsFlowExecution)fe;
            Assert.assertTrue(cpsExec.isComplete());
            Assert.assertEquals(Boolean.TRUE, getCpsDoneFlag(cpsExec));
            Assert.assertEquals(1, cpsExec.getCurrentHeads().size());
            Assert.assertTrue(cpsExec.isComplete());
            Assert.assertTrue(cpsExec.getCurrentHeads().get(0) instanceof FlowEndNode);
            Stack<BlockStartNode> starts = getCpsBlockStartNodes(cpsExec);
            Assert.assertTrue(starts == null || starts.isEmpty());
            Thread.sleep(1000); // TODO seems to be flaky
            Assert.assertFalse(cpsExec.blocksRestart());
        } else {
            System.out.println("WARNING: no FlowExecutionForBuild");
        }
    }



    static void assertCleanInProgress(WorkflowRun run) throws Exception {
        Assert.assertTrue(run.isBuilding());
        Assert.assertNull(run.getResult());
        FlowExecution fe = run.getExecution();
        AtomicBoolean hasExecutionInList = new AtomicBoolean(false);
        FlowExecutionList.get().forEach(f -> {
            if (fe != null && f == fe) {
                hasExecutionInList.set(true);
            }
        });
        if (!hasExecutionInList.get()) {
            Assert.fail("Build completed but should still show in FlowExecutionList");
        }
        CpsFlowExecution cpsExec = (CpsFlowExecution)fe;
        Assert.assertFalse(cpsExec.isComplete());
        Assert.assertEquals(Boolean.FALSE, getCpsDoneFlag(cpsExec));
        Assert.assertFalse(cpsExec.getCurrentHeads().get(0) instanceof FlowEndNode);
        Stack<BlockStartNode> starts = getCpsBlockStartNodes(cpsExec);
        Assert.assertTrue(starts != null && !starts.isEmpty());
    }

    static void assertResultMatchExecutionAndRun(WorkflowRun run, Result[] executionAndBuildResult) {
        Assert.assertEquals(executionAndBuildResult[0], ((CpsFlowExecution) run.getExecution()).getResult());
        Assert.assertEquals(executionAndBuildResult[1], run.getResult());
    }

    /** Create and run a basic build before we mangle its persisted contents.  Stores job number to jobIdNumber index 0. */
    private static WorkflowRun runBasicBuild(JenkinsRule j, String jobName, int[] jobIdNumber, FlowDurabilityHint hint) throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, jobName);
        job.setDefinition(new CpsFlowDefinition("echo 'doSomething'", true));
        job.addProperty(new DurabilityHintJobProperty(hint));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        jobIdNumber[0] = run.getNumber();
        assertCompletedCleanly(run);
        return run;
    }

    /** Create and run a basic build before we mangle its persisted contents.  Stores job number to jobIdNumber index 0. */
    private static WorkflowRun runBasicBuild(JenkinsRule j, String jobName, int[] jobIdNumber) throws Exception {
        return runBasicBuild(j, jobName, jobIdNumber, FlowDurabilityHint.MAX_SURVIVABILITY);
    }

    /** Sets up a running build that is waiting on input. */
    private static WorkflowRun runBasicPauseOnInput(JenkinsRule j, String jobName, int[] jobIdNumber, FlowDurabilityHint durabilityHint) throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, jobName);
        job.setDefinition(new CpsFlowDefinition("input 'pause'", true));
        job.addProperty(new DurabilityHintJobProperty(durabilityHint));

        WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
        ListenableFuture<FlowExecution> listener = run.getExecutionPromise();
        FlowExecution exec = listener.get();
        while(exec.getCurrentHeads().isEmpty() || (exec.getCurrentHeads().get(0) instanceof FlowStartNode)) {  // Wait until input step starts
            System.out.println("Waiting for input step to begin");
            Thread.sleep(50);
        }
        while(run.getAction(InputAction.class) == null) {  // Wait until input step starts
            System.out.println("Waiting for input action to get attached to run");
            Thread.sleep(50);
        }
        Thread.sleep(100L);  // A little extra buffer for persistence etc
        if (durabilityHint != FlowDurabilityHint.PERFORMANCE_OPTIMIZED) {
            CpsFlowExecution execution = (CpsFlowExecution) run.getExecution();
            Method m = execution.getClass().getDeclaredMethod("getProgramDataFile");
            m.setAccessible(true);
            File f = (File) m.invoke(execution);
            while (!Files.exists(f.toPath())) {
                System.out.println("Waiting for program to be persisted");
                Thread.sleep(50);
            }
        }
        jobIdNumber[0] = run.getNumber();
        return run;
    }

    private static WorkflowRun runBasicPauseOnInput(JenkinsRule j, String jobName, int[] jobIdNumber) throws Exception {
        return runBasicPauseOnInput(j, jobName, jobIdNumber, FlowDurabilityHint.MAX_SURVIVABILITY);
    }

    private static InputStepExecution getInputStepExecution(WorkflowRun run, String inputMessage) throws Exception {
        InputAction ia = run.getAction(InputAction.class);
        List<InputStepExecution> execList = ia.getExecutions();
        return execList.stream().filter(e -> inputMessage.equals(e.getInput().getMessage())).findFirst().orElse(null);
    }

    final  static String DEFAULT_JOBNAME = "testJob";

    /** Simulates something happening badly during final shutdown, which may cause build to not appear done. */
    @Test
    public void completedFinalFlowNodeNotPersisted() {
        final int[] build = new int[1];
        final Result[] executionAndBuildResult = new Result[2];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicBuild(j, DEFAULT_JOBNAME, build);
            String finalId = run.getExecution().getCurrentHeads().get(0).getId();

            // Hack but deletes the file from disk
            CpsFlowExecution cpsExec = (CpsFlowExecution) run.getExecution();
            File f = new File(cpsExec.getStorageDir(), finalId+".xml");
            Files.delete(f.toPath());
            executionAndBuildResult[0] = ((CpsFlowExecution) run.getExecution()).getResult();
            executionAndBuildResult[1] = run.getResult();
        });
        story.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            //            assertNulledExecution(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
            assertResultMatchExecutionAndRun(run, executionAndBuildResult);
        });
    }
    /** Perhaps there was a serialization error breaking the FlowGraph persistence for non-durable mode. */
    @Test
    public void completedNoNodesPersisted() {
        final int[] build = new int[1];
        final Result[] executionAndBuildResult = new Result[2];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicBuild(j, DEFAULT_JOBNAME, build);
            FileUtils.deleteDirectory(((CpsFlowExecution) run.getExecution()).getStorageDir());
            executionAndBuildResult[0] = ((CpsFlowExecution) run.getExecution()).getResult();
            executionAndBuildResult[1] = run.getResult();
        });
        story.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            // assertNulledExecution(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
            assertResultMatchExecutionAndRun(run, executionAndBuildResult);
        });
    }

    /** Simulates case where done flag was not persisted. */
    @Test
    public void completedButWrongDoneStatus() {
        final int[] build = new int[1];
        final Result[] executionAndBuildResult = new Result[2];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicBuild(j, DEFAULT_JOBNAME, build);
            String finalId = run.getExecution().getCurrentHeads().get(0).getId();

            // Hack but deletes the FlowNodeStorage from disk
            CpsFlowExecution cpsExec = (CpsFlowExecution) run.getExecution();
            setCpsDoneFlag(cpsExec, false);
            run.save();
            executionAndBuildResult[0] = ((CpsFlowExecution) run.getExecution()).getResult();
            executionAndBuildResult[1] = run.getResult();
        });
        story.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
            assertResultMatchExecutionAndRun(run, executionAndBuildResult);
        });
    }

    @Test
    public void inProgressNormal() {
        final int[] build = new int[1];
        story.then( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCleanInProgress(run);
            InputStepExecution exec = getInputStepExecution(run, "pause");
            exec.doProceedEmpty();
            j.waitForCompletion(run);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }

    @Test
    public void inProgressMaxPerfCleanShutdown() {
        final int[] build = new int[1];
        story.then( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build, FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
            // SHOULD still save at end via persist-at-shutdown hooks
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCleanInProgress(run);
            InputStepExecution exec = getInputStepExecution(run, "pause");
            exec.doProceedEmpty();
            j.waitForCompletion(run);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }

    @Test
    public void inProgressMaxPerfDirtyShutdown() {
        final int[] build = new int[1];
        final String[] finalNodeId = new String[1];
        story.thenWithHardShutdown( j -> {
            runBasicPauseOnInput(j, DEFAULT_JOBNAME, build, FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
            // SHOULD still save at end via persist-at-shutdown hooks
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            Thread.sleep(1000);
            j.waitForCompletion(run);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.FAILURE, run.getResult());
            finalNodeId[0] = run.getExecution().getCurrentHeads().get(0).getId();
        });
        story.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(finalNodeId[0], run.getExecution().getCurrentHeads().get(0).getId());
            // JENKINS-50199, verify it doesn't try to resume again
        });
    }

    @Test
    public void inProgressButFlowNodesLost() {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            CpsFlowExecution cpsExec = (CpsFlowExecution) run.getExecution();
            FileUtils.deleteDirectory(((CpsFlowExecution) run.getExecution()).getStorageDir());
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
        });
    }

    /** Build okay but program fails to load */
    @Test
    public void inProgressButProgramLoadFailure() {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            CpsFlowExecution cpsExec = (CpsFlowExecution) run.getExecution();
            Method m = cpsExec.getClass().getDeclaredMethod("getProgramDataFile");
            m.setAccessible(true);
            File f = (File) m.invoke(cpsExec);
            Files.delete(f.toPath());
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
        });
    }

    /** Build okay but then the start nodes get screwed up */
    @Test
    public void inProgressButStartBlocksLost() {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            CpsFlowExecution cpsExec = (CpsFlowExecution) run.getExecution();
            getCpsBlockStartNodes(cpsExec).push(new FlowStartNode(cpsExec, cpsExec.iotaStr()));
            run.save();
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
        });
    }

    /** Replicates case where builds resume when the should not due to build's completion not being saved. */
    @Test
    @Issue("JENKINS-50199")
    public void completedExecutionButRunIncomplete() {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            CpsFlowExecution cpsExec = (CpsFlowExecution) run.getExecution();
            InputStepExecution exec = getInputStepExecution(run, "pause");
            exec.doProceedEmpty();
            j.waitForCompletion(run);


            // Set run back to being incomplete as if persistence failed
            Field completedField = run.getClass().getDeclaredField("completed");
            completedField.setAccessible(true);
            completedField.set(run, false);

            Field resultField = Run.class.getDeclaredField("result");
            resultField.setAccessible(true);
            resultField.set(run, null);

            run.save();
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
        story.then( j->{ // Once more, just to be sure it sticks
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }

    @Issue("JENKINS-50888")  // Tried to modify build without lazy load being triggered
    @Test public void modifyBeforeLazyLoad() {
        story.then(r -> {  // Normal build
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("echo 'dosomething'", true));
            r.buildAndAssertSuccess(p);
        });
        story.then(r -> {  // But wait, we try to modify the build without loading the execution
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            b.setDescription("Bob");
            b.save();  // Before the JENKINS-50888 fix this would trigger an IOException
        });
        story.then( r-> {  // Verify that the FlowExecutionOwner can trigger lazy-load correctly
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            Assert.assertEquals("Bob", b.getDescription());
            Assert.assertEquals("4", b.getExecution().getCurrentHeads().get(0).getId());
        });
    }
}
