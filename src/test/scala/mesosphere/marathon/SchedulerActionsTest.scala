package mesosphere.marathon

import akka.Done
import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, RootGroup, Timestamp}
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.verifyNoMoreInteractions
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class SchedulerActionsTest extends AkkaUnitTest {
  "SchedulerActions" should {

    "Task reconciliation sends known running and staged tasks and empty list" in {
      val f = new Fixture
      val app = AppDefinition(id = AbsolutePathId("/myapp"), role = "*")
      val rootGroup: RootGroup = RootGroup(apps = Map((app.id, app)))
      val runningInstance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val stagedInstance = TestInstanceBuilder.newBuilder(app.id).addTaskStaged().getInstance()

      val stagedInstanceWithSlaveId = TestInstanceBuilder.newBuilder(app.id)
        .addTaskWithBuilder().taskStaged().build()
        .withAgentInfo(agentId = Some("slave 1"))
        .getInstance()

      val instances = Seq(runningInstance, stagedInstance, stagedInstanceWithSlaveId)
      f.instanceTracker.instancesBySpec() returns Future.successful(InstancesBySpec.forInstances(instances))
      f.groupRepo.root() returns Future.successful(rootGroup)

      f.scheduler.reconcileTasks(f.driver).futureValue(5.seconds)

      val statuses = Set(
        runningInstance,
        stagedInstance,
        stagedInstanceWithSlaveId
      ).flatMap(_.tasksMap.values).flatMap(_.status.mesosStatus)

      verify(f.driver, withinTimeout()).reconcileTasks(statuses.asJavaCollection)
      verify(f.driver).reconcileTasks(java.util.Arrays.asList())
    }

    "Task reconciliation only one empty list, when no tasks are present in Marathon" in {
      val f = new Fixture

      f.instanceTracker.instancesBySpec() returns Future.successful(InstancesBySpec.empty)
      f.groupRepo.root() returns Future.successful(RootGroup())

      f.scheduler.reconcileTasks(f.driver).futureValue

      verify(f.driver, times(1)).reconcileTasks(java.util.Arrays.asList())
    }

    "Kill orphaned task" in {
      val f = new Fixture
      val app = AppDefinition(id = AbsolutePathId("/myapp"), role = "*")
      val orphanedApp = AppDefinition(id = AbsolutePathId("/orphan"), role = "*")
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val orphanedInstance = TestInstanceBuilder.newBuilder(orphanedApp.id).addTaskRunning().getInstance()

      f.instanceTracker.instancesBySpec() returns Future.successful(InstancesBySpec.forInstances(instance, orphanedInstance))
      val rootGroup: RootGroup = RootGroup(apps = Map((app.id, app)))
      f.groupRepo.root() returns Future.successful(rootGroup)

      f.scheduler.reconcileTasks(f.driver).futureValue(5.seconds)

      verify(f.instanceTracker, withinTimeout()).setGoal(orphanedInstance.instanceId, Goal.Decommissioned, GoalChangeReason.Orphaned)
    }

    "Scale up correctly in case of lost tasks (active queue)" in {
      val f = new Fixture

      Given("An active queue and unreachable tasks")
      val app = MarathonTestHelper.makeBasicApp().copy(instances = 15)

      val unreachableInstances = Seq.fill(5)(TestInstanceBuilder.newBuilder(app.id).addTaskUnreachableInactive().getInstance())
      val runnningInstances = Seq.fill(10)(TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance())
      f.instanceTracker.specInstances(eq(app.id), eq(false))(any[ExecutionContext]) returns Future.successful(unreachableInstances ++ runnningInstances)

      When("the app is scaled")
      f.scheduler.scale(app).futureValue

      Then("5 tasks should be placed onto the launchQueue")
      verify(f.queue, times(1)).add(app, 5)
    }

    "Scale up with some tasks in launch queue" in {
      val f = new Fixture

      Given("an app with 10 instances and an active queue with 4 tasks")
      val app = MarathonTestHelper.makeBasicApp().copy(instances = 10)

      val scheduledInstances = Seq.fill(4)(Instance.scheduled(app))
      f.instanceTracker.specInstances(eq(app.id), eq(false))(any) returns Future.successful(scheduledInstances)

      When("app is scaled")
      f.scheduler.scale(app).futureValue

      Then("6 more tasks are added to the queue")
      verify(f.queue, times(1)).add(app, 6)
    }

    "Scale up with enough tasks in launch queue" in {
      val f = new Fixture

      Given("an app with 10 instances and an active queue with 10 tasks")
      val app = MarathonTestHelper.makeBasicApp().copy(instances = 10)
      val scheduledInstances = Seq.fill(10)(Instance.scheduled(app))
      f.instanceTracker.specInstances(eq(app.id), eq(false))(any) returns Future.successful(scheduledInstances)

      When("app is scaled")
      f.scheduler.scale(app).futureValue

      Then("no tasks are added to the queue")
      verify(f.queue, never).add(eq(app), any[Int])
    }

    // This test was an explicit wish by Matthias E.
    "Scale up with too many tasks in launch queue" in {
      val f = new Fixture

      Given("an app with 10 instances and an active queue with 10 tasks")
      val app = MarathonTestHelper.makeBasicApp().copy(instances = 10)
      val scheduledInstances = Seq.fill(15)(Instance.scheduled(app))
      f.instanceTracker.specInstances(eq(app.id), eq(false))(any) returns Future.successful(scheduledInstances)

      When("app is scaled")
      f.scheduler.scale(app).futureValue

      Then("no tasks are added to the queue")
      verify(f.queue, never).add(eq(app), any[Int])
    }

    // This scenario is the following:
    // - There's an active queue and Marathon has 10 running + 5 staged tasks
    // - Marathon receives StatusUpdates for 5 previously LOST tasks
    // - A scale is initiated and Marathon realizes there are 5 tasks over capacity
    // => We expect Marathon to kill the 5 staged tasks
    "Kill staged tasks in correct order in case lost tasks reappear" in {
      val f = new Fixture

      Given("an active queue, staged tasks and 5 overCapacity")
      val app = MarathonTestHelper.makeBasicApp().copy(instances = 5)

      def stagedInstance(stagedAt: Long) = TestInstanceBuilder.newBuilder(app.id).addTaskStaged(Timestamp.apply(stagedAt)).getInstance()
      def runningInstance() = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()

      val staged_2 = stagedInstance(2L)
      val staged_3 = stagedInstance(3L)
      val tasks: Seq[Instance] = Seq(
        runningInstance(),
        stagedInstance(1L),
        runningInstance(),
        staged_3,
        runningInstance(),
        staged_2,
        runningInstance()
      )

      f.instanceTracker.specInstances(app.id, false) returns Future.successful(tasks)
      When("the app is scaled")
      f.scheduler.scale(app).futureValue

      And("the youngest STAGED tasks are killed")
      verify(f.instanceTracker, withinTimeout()).setGoal(staged_2.instanceId, Goal.Decommissioned, GoalChangeReason.OverCapacity)
      verify(f.instanceTracker, withinTimeout()).setGoal(staged_3.instanceId, Goal.Decommissioned, GoalChangeReason.OverCapacity)
      verifyNoMoreInteractions(f.driver)
    }

    "Kill running tasks in correct order in case of lost tasks" in {
      val f = new Fixture

      Given("an inactive queue, running tasks and some overCapacity")
      val app: AppDefinition = MarathonTestHelper.makeBasicApp().copy(instances = 5)
      def runningInstance(stagedAt: Long) = {
        val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning(stagedAt = Timestamp.apply(stagedAt), startedAt = Timestamp.apply(stagedAt)).getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }

      val running_6 = runningInstance(stagedAt = 6L)
      val running_7 = runningInstance(stagedAt = 7L)
      val instances = Seq(
        runningInstance(stagedAt = 3L),
        running_7,
        runningInstance(stagedAt = 1L),
        runningInstance(stagedAt = 4L),
        runningInstance(stagedAt = 5L),
        running_6,
        runningInstance(stagedAt = 2L)
      )

      f.instanceTracker.specInstances(app.id, false) returns Future.successful(instances)
      When("the app is scaled")
      f.scheduler.scale(app).futureValue

      And("the youngest RUNNING tasks are killed")
      verify(f.instanceTracker, withinTimeout()).setGoal(running_7.instanceId, Goal.Decommissioned, GoalChangeReason.OverCapacity)
      verify(f.instanceTracker, withinTimeout()).setGoal(running_6.instanceId, Goal.Decommissioned, GoalChangeReason.OverCapacity)
      verifyNoMoreInteractions(f.driver)
    }

    "Kill staged and running tasks in correct order in case of lost tasks" in {
      val f = new Fixture

      Given("an active queue, running tasks and some overCapacity")
      val app = MarathonTestHelper.makeBasicApp().copy(instances = 3)

      def stagedInstance(stagedAt: Long) = {
        val instance = TestInstanceBuilder.newBuilder(app.id).addTaskStaged(Timestamp.apply(stagedAt)).getInstance()
        val state = instance.state.copy(condition = Condition.Staging)
        instance.copy(state = state)
      }
      def runningInstance(stagedAt: Long) = {
        val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning(stagedAt = Timestamp.apply(stagedAt), startedAt = Timestamp.apply(stagedAt)).getInstance()
        val state = instance.state.copy(condition = Condition.Running)
        instance.copy(state = state)
      }

      val staged_1 = stagedInstance(1L)
      val running_4 = runningInstance(stagedAt = 4L)
      val tasks: Seq[Instance] = Seq(
        runningInstance(stagedAt = 3L),
        running_4,
        staged_1,
        runningInstance(stagedAt = 1L),
        runningInstance(stagedAt = 2L)
      )

      f.instanceTracker.specInstances(app.id, false) returns Future.successful(tasks)

      When("the app is scaled")
      f.scheduler.scale(app).futureValue

      And("all STAGED tasks plus the youngest RUNNING tasks are killed")
      verify(f.instanceTracker, withinTimeout()).setGoal(staged_1.instanceId, Goal.Decommissioned, GoalChangeReason.OverCapacity)
      verify(f.instanceTracker, withinTimeout()).setGoal(running_4.instanceId, Goal.Decommissioned, GoalChangeReason.OverCapacity)
      verifyNoMoreInteractions(f.driver)
    }

    import scala.language.implicitConversions
    implicit def durationToPatienceConfigTimeout(d: FiniteDuration): PatienceConfiguration.Timeout = {
      PatienceConfiguration.Timeout(Span(d.toMillis, Millis))
    }

    class Fixture {
      val queue = mock[LaunchQueue]
      val groupRepo = mock[GroupRepository]
      val instanceTracker = mock[InstanceTracker]
      instanceTracker.setGoal(any, any, any).returns(Future.successful(Done))
      instanceTracker.instanceUpdates returns Source.empty
      val driver = mock[SchedulerDriver]
      val killService = mock[KillService]
      val clock = new SettableClock()

      queue.add(any, any) returns Future.successful(Done)

      val scheduler = new SchedulerActions(
        groupRepo,
        mock[HealthCheckManager],
        instanceTracker,
        queue,
        system.eventStream,
        killService
      )
    }
  }
}
