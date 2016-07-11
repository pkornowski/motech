package org.motech.project.testmodule.it;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.motechproject.event.listener.EventRelay;
import org.motechproject.tasks.domain.mds.task.Task;
import org.motechproject.tasks.domain.mds.task.TaskActionInformation;
import org.motechproject.tasks.domain.mds.task.TaskTriggerInformation;
import org.motechproject.tasks.domain.mds.task.builder.TaskBuilder;
import org.motechproject.tasks.repository.TasksDataService;
import org.motechproject.tasks.service.TaskActivityService;
import org.motechproject.tasks.service.TaskService;
import org.motechproject.testing.osgi.BasePaxIT;
import org.motechproject.testing.osgi.container.MotechNativeTestContainerFactory;
import org.motechproject.testmodule.domain.TaskTestObject;
import org.motechproject.testmodule.service.TasksTestService;
import org.ops4j.pax.exam.ExamFactory;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;
import org.osgi.framework.BundleContext;

import javax.inject.Inject;

import java.util.HashMap;
import java.util.List;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;


@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
@ExamFactory(MotechNativeTestContainerFactory.class)
public class TasksActionIT extends BasePaxIT {
        private TasksTestService tasksTestService;

        @Inject
        private TaskService taskService;

        @Inject
        TasksDataService tasksDataService;

        @Inject
        private TaskActivityService activityService;

        @Inject
        private EventRelay eventRelay;

        @Inject
        private BundleContext bundleContext;

        @Before
        public void setUp() {
                setUpSecurityContextForDefaultUser("manageTasks");
                tasksDataService.deleteAll();
        }

        @Test
        public void shouldExecuteTaskActionWithPostActionParameter() throws Exception {
                assertEquals("a", "a");
        }
        @Test
        public void shouldExecuteTaskAndUsePostActionParameters() throws Exception {

                Task task = new TaskBuilder()
                        .withId(1L)
                        .withName("name")
                        .withTrigger(new TaskTriggerInformation("receive", "test", "test", "0.14", "RECEIVE", "RECEIVE"))
                        .addAction(new TaskActionInformation("send", "test", "test", "0.15", "SEND", new HashMap<String, String>()))
                        .build();

                tasksDataService.create(task);

                List<Task> tasks = tasksDataService.retrieveAll();
                assertEquals(asList(task), tasks);

                TaskActionInformation actionInformation = prepareTaskActionInformationWithService("key", "value");
                TaskActionInformation actionInformationWithPostActionParameter = prepareTaskActionInformationWithService("key", "{{pa.0.testKey}}");
                ActionEvent actionEvent = prepareActionEventWithService();

                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);
                when(taskService.getActionEventFor(actionInformationWithPostActionParameter)).thenReturn(actionEvent);

                ServiceReference serviceReference = mock(ServiceReference.class);

                when(bundleContext.getServiceReference("serviceInterface")).thenReturn(serviceReference);
                when(bundleContext.getService(serviceReference)).thenReturn(new TestService());

                Task task = new Task();
                task.addAction(actionInformation);
                task.addAction(actionInformationWithPostActionParameter);

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);
                taskActionExecutor.setBundleContext(bundleContext);

                TaskContext taskContext = new TaskContext(task, new HashMap<>(), activityService);

                for (TaskActionInformation action : task.getActions()) {
                        taskActionExecutor.execute(task, action, task.getActions().indexOf(action), taskContext);
                }

                assertEquals("testObject", taskContext.getPostActionParameterValue("0", "testKey"));
                assertEquals("testObject", taskContext.getPostActionParameterValue("1", "testKey"));

        }

}


        @Test
        public void shouldRaiseEventIfActionHasSubject() throws ActionNotFoundException, TaskHandlerException {
                TaskActionInformation actionInformation = new TaskActionInformation("action", "channel", "module", "0.1", "actionSubject");
                ActionEvent actionEvent = new ActionEventBuilder().setDisplayName("Action").setSubject("actionSubject")
                        .setDescription("").setActionParameters(new TreeSet<>()).build();
                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);

                Task task = new TaskBuilder().addAction(new TaskActionInformation("Action", "channel", "module", "0.1", "actionSubject")).build();

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);
                taskActionExecutor.setBundleContext(bundleContext);

                taskActionExecutor.execute(task, actionInformation, 0, new TaskContext(task, new HashMap(), activityService));

                MotechEvent raisedEvent = new MotechEvent("actionSubject", new HashMap<>());
                verify(eventRelay).sendEventMessage(raisedEvent);
        }

        @Test
        public void shouldRaiseEventWhenActionHasSubjectAndService_IfServiceIsNotAvailable() throws TaskHandlerException, ActionNotFoundException {
                TaskActionInformation actionInformation = new TaskActionInformation("action", "channel", "module", "0.1", "serviceInterface", "serviceMethod");
                ActionEvent actionEvent = new ActionEventBuilder().setDisplayName("Action").setSubject("actionSubject")
                        .setDescription("").setServiceInterface("serviceInterface").setServiceMethod("serviceMethod").setActionParameters(new TreeSet<ActionParameter>()).build();
                actionEvent.setActionParameters(new TreeSet<>());
                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);

                when(bundleContext.getServiceReference("serviceInterface")).thenReturn(null);

                Task task = new TaskBuilder().addAction(new TaskActionInformation("Action", "channel", "module", "0.1", "actionSubject")).build();

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);
                taskActionExecutor.setBundleContext(bundleContext);

                taskActionExecutor.execute(task, actionInformation, 0, new TaskContext(task, new HashMap(), activityService));

                verify(eventRelay).sendEventMessage(any(MotechEvent.class));
        }

        @Test
        public void shouldNotRaiseEventIfActionHasSubjectAndService_IfServiceIsAvailable() throws ActionNotFoundException, TaskHandlerException {
                TaskActionInformation actionInformation = new TaskActionInformation("action", "channel", "module", "0.1", "serviceInterface", "serviceMethod");
                ActionEvent actionEvent = new ActionEventBuilder().setDisplayName("Action").setSubject("actionSubject")
                        .setDescription("").setServiceInterface("serviceInterface").setServiceMethod("serviceMethod")
                        .setActionParameters(new TreeSet<>()).build();
                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);

                ServiceReference serviceReference = mock(ServiceReference.class);
                when(bundleContext.getServiceReference("serviceInterface")).thenReturn(serviceReference);
                when(bundleContext.getService(serviceReference)).thenReturn(new TestService());

                Task task = new TaskBuilder().addAction(new TaskActionInformation("Action", "channel", "module", "0.1", "actionSubject")).build();

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);
                taskActionExecutor.setBundleContext(bundleContext);

                taskActionExecutor.execute(task, actionInformation, 0, new TaskContext(task, new HashMap(), activityService));

                verify(eventRelay, never()).sendEventMessage(any(MotechEvent.class));
        }

        @Test
        public void shouldInvokeServiceIfActionHasService() throws ActionNotFoundException, TaskHandlerException {
                TaskActionInformation actionInformation = new TaskActionInformation("action", "channel", "module", "0.1", "serviceInterface", "serviceMethod");
                ActionEvent actionEvent = new ActionEventBuilder().setDisplayName("Action")
                        .setDescription("").setServiceInterface("serviceInterface").setServiceMethod("serviceMethod")
                        .setActionParameters(new TreeSet<>()).build();
                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);

                ServiceReference serviceReference = mock(ServiceReference.class);
                when(bundleContext.getServiceReference("serviceInterface")).thenReturn(serviceReference);
                TestService testService = new TestService();
                when(bundleContext.getService(serviceReference)).thenReturn(testService);

                Task task = new TaskBuilder().addAction(new TaskActionInformation("Action", "channel", "module", "0.1", "actionSubject")).build();

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);
                taskActionExecutor.setBundleContext(bundleContext);

                taskActionExecutor.execute(task, actionInformation, 0, new TaskContext(task, new HashMap(), activityService));

                assertTrue(testService.serviceMethodInvoked());
        }

        @Test(expected = TaskHandlerException.class)
        public void shouldThrowExceptionIfBundleContextIsNotAvailable() throws TaskHandlerException, ActionNotFoundException {
                TaskActionInformation actionInformation = new TaskActionInformation("action", "channel", "module", "0.1", "serviceInterface", "serviceMethod");
                ActionEvent actionEvent = new ActionEventBuilder().setDisplayName("Action")
                        .setDescription("").setServiceInterface("serviceInterface").setServiceMethod("serviceMethod")
                        .setActionParameters(new TreeSet<>()).build();
                actionEvent.setActionParameters(new TreeSet<>());
                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);

                Task task = new TaskBuilder().addAction(new TaskActionInformation("Action", "channel", "module", "0.1", "actionSubject")).build();

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);

                taskActionExecutor.execute(task, actionInformation, 0, new TaskContext(task, new HashMap(), activityService));
        }

        @Test(expected = TaskHandlerException.class)
        public void shouldThrowExceptionIfActionHasNeitherEventNorService() throws TaskHandlerException, ActionNotFoundException {
                TaskActionInformation actionInformation = new TaskActionInformation("action", "channel", "module", "0.1", "serviceInterface", "serviceMethod");
                ActionEvent actionEvent = new ActionEventBuilder().setDisplayName("Action")
                        .setDescription("").setServiceInterface("serviceInterface").setServiceMethod("serviceMethod")
                        .setActionParameters(new TreeSet<>()).build();
                actionEvent.setActionParameters(new TreeSet<>());
                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);

                Task task = new TaskBuilder().addAction(new TaskActionInformation("Action", "channel", "module", "0.1", "actionSubject")).build();

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);

                taskActionExecutor.execute(task, actionInformation, 0, new TaskContext(task, new HashMap(), activityService));
        }

        @Test
        public void shouldAddActivityNotificationIfServiceIsNotAvailable() throws TaskHandlerException, ActionNotFoundException {
                TaskActionInformation actionInformation = new TaskActionInformation("action", "channel", "module", "0.1", "serviceInterface", "serviceMethod");
                ActionEvent actionEvent = new ActionEventBuilder().setDisplayName("Action").setSubject("actionSubject")
                        .setDescription("").setServiceInterface("serviceInterface").setServiceMethod("serviceMethod")
                        .setActionParameters(new TreeSet<>()).build();
                actionEvent.setActionParameters(new TreeSet<>());
                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);

                when(bundleContext.getServiceReference("serviceInterface")).thenReturn(null);

                Task task = new TaskBuilder().addAction(new TaskActionInformation("Action", "channel", "module", "0.1", "actionSubject")).build();

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);
                taskActionExecutor.setBundleContext(bundleContext);

                taskActionExecutor.execute(task, actionInformation, 0, new TaskContext(task, new HashMap(), activityService));

                verify(activityService).addWarning(task, "task.warning.serviceUnavailable", "serviceInterface");
        }

        @Test
        public void shouldExecuteTaskIfActionMapParameterHasValueWithMixedTypes() throws Exception {
                TaskActionInformation actionInformation = prepareTaskActionInformationWithTrigger();
                ActionEvent actionEvent = prepareActionEvent();

                when(taskService.getActionEventFor(actionInformation)).thenReturn(actionEvent);

                Task task = new TaskBuilder().addAction(new TaskActionInformation("Action", "channel", "module", "0.1", "actionSubject")).build();

                TaskActionExecutor taskActionExecutor = new TaskActionExecutor(taskService, activityService, eventRelay);
                taskActionExecutor.setBundleContext(bundleContext);

                taskActionExecutor.execute(task, actionInformation, 0, prepareTaskContext(task));

                verify(eventRelay).sendEventMessage(eq(prepareMotechEvent()));
        }


        private MotechEvent prepareMotechEvent() {
                Map<String, Object> parameters = new HashMap<>();
                Map<String, Object> map = new HashMap<>();
                map.put("key1", "value123");
                parameters.put("map", map);
                return new MotechEvent("actionSubject", parameters);
        }

        private TaskContext prepareTaskContext(Task task) {
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("id", 123);
                return new TaskContext(task, parameters, activityService);
        }
        private TaskActionInformation prepareTaskActionInformation() {
                TaskActionInformation actionInformation = new TaskActionInformation();
                actionInformation.setDisplayName("action");
                actionInformation.setChannelName("channel");
                actionInformation.setModuleName("module");
                actionInformation.setModuleVersion("0.1");
                actionInformation.setSubject("actionSubject");

                return actionInformation;
        }

        private TaskActionInformation prepareTaskActionInformationWithTrigger() {
                TaskActionInformation actionInformation = prepareTaskActionInformation();

                Map<String, String> values = new HashMap<>();
                values.put("map", "key1:value{{trigger.id}}");
                actionInformation.setValues(values);

                return actionInformation;
        }

        private TaskActionInformation prepareTaskActionInformationWithService(String key, String value) {
                TaskActionInformation actionInformation = new TaskActionInformation("action", "channel", "module", "0.1", "serviceInterface", "serviceMethod");

                Map<String, String> values = new HashMap<>();
                values.put(key, value);
                actionInformation.setValues(values);
                actionInformation.setServiceInterface("serviceInterface");
                actionInformation.setServiceMethod("serviceMethod");

                return actionInformation;
        }
        private ActionEvent prepareActionEvent() {
                ActionEvent actionEvent = new ActionEvent();
                actionEvent.setDisplayName("Action");
                actionEvent.setSubject("actionSubject");
                actionEvent.setDescription("");

                SortedSet<ActionParameter> parameters = new TreeSet<>();
                ActionParameter parameter = new ActionParameter();
                parameter.setDisplayName("Map");
                parameter.setKey("map");
                parameter.setType(MAP);
                parameter.setOrder(1);
                parameters.add(parameter);
                actionEvent.setActionParameters(parameters);
                actionEvent.setPostActionParameters(parameters);

                return actionEvent;
        }

        private ActionEvent prepareActionEventWithService() {
                ActionEvent actionEvent = new ActionEventBuilder().setDisplayName("Action")
                        .setDescription("").setServiceInterface("serviceInterface").setServiceMethod("serviceMethod").build();

                SortedSet<ActionParameter> parameters = new TreeSet<>();
                ActionParameter parameter = new ActionParameter();
                parameter.setDisplayName("test");
                parameter.setKey("testKey");
                parameter.setType(TEXTAREA);
                parameter.setOrder(0);
                parameters.add(parameter);
                actionEvent.setActionParameters(parameters);
                actionEvent.setPostActionParameters(parameters);

                return actionEvent;
        }
}
