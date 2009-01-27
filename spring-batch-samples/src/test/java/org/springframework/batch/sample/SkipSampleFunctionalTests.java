package org.springframework.batch.sample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobParametersNotFoundException;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.sample.domain.trade.internal.ItemTrackingTradeItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.jdbc.SimpleJdbcTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Error is encountered during writing - transaction is rolled back and the
 * error item is skipped on second attempt to process the chunk.
 * 
 * @author Robert Kasanicky
 * @author Dan Garrette
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "/skipSample-job-launcher-context.xml" })
public class SkipSampleFunctionalTests {

	private SimpleJdbcTemplate simpleJdbcTemplate;

	@Autowired
	private JobOperator jobOperator;

	@Autowired
	private ItemTrackingTradeItemWriter itemTrackingWriter;

	@Autowired
	public void setDataSource(DataSource dataSource) {
		this.simpleJdbcTemplate = new SimpleJdbcTemplate(dataSource);
	}

	@Before
	public void setUp() {
		simpleJdbcTemplate.update("DELETE from TRADE");
		simpleJdbcTemplate.update("DELETE from CUSTOMER");
		for (int i = 1; i < 10; i++) {
			simpleJdbcTemplate.update("INSERT INTO CUSTOMER VALUES (" + i + ", 0, 'customer" + i + "', 100000)");
		}
		simpleJdbcTemplate.update("DELETE from ERROR_LOG");

		itemTrackingWriter.clearItems();
		itemTrackingWriter.setWriteFailureISIN("UK21341EAH47");
	}

	/**
	 * LAUNCH 1 <br>
	 * <br>
	 * step1
	 * <ul>
	 * <li>The step name is saved to the job execution context.
	 * <li>Read five records from flat file and insert them into the TRADE
	 * table.
	 * <li>One record will be invalid, and it will be skipped. Four records
	 * will be written to the database.
	 * <li>The skip will result in an exit status that directs the job to run
	 * the error logging step.
	 * </ul>
	 * errorPrint1
	 * <ul>
	 * <li>The error logging step will log one record using the step name from
	 * the job execution context.
	 * </ul>
	 * step2
	 * <ul>
	 * <li>The step name is saved to the job execution context.
	 * <li>Read four records from the TRADE table and processes them.
	 * <li>One record will be invalid, and it will be skipped. Three records
	 * will be stored in the writer's "items" property.
	 * <li>The skip will result in an exit status that directs the job to run
	 * the error logging step.
	 * </ul>
	 * errorPrint2
	 * <ul>
	 * <li>The error logging step will log one record using the step name from
	 * the job execution context.
	 * </ul>
	 * <br>
	 * <br>
	 * LAUNCH 2 <br>
	 * <br>
	 * step1
	 * <ul>
	 * <li>The step name is saved to the job execution context.
	 * <li>Read five records from flat file and insert them into the TRADE
	 * table.
	 * <li>No skips will occur.
	 * <li>The exist status of SUCCESS will direct the job to step2.
	 * </ul>
	 * errorPrint1
	 * <ul>
	 * <li>This step does not occur. No error records are logged.
	 * </ul>
	 * step2
	 * <ul>
	 * <li>The step name is saved to the job execution context.
	 * <li>Read five records from the TRADE table and processes them.
	 * <li>No skips will occur.
	 * <li>The exist status of SUCCESS will direct the job to end.
	 * </ul>
	 * errorPrint2
	 * <ul>
	 * <li>This step does not occur. No error records are logged.
	 * </ul>
	 */
	@Transactional
	@Test
	public void testJobIncrementing() {
		//
		// Launch 1
		//
		long id1 = this.launchJobWithIncrementer();
		Map<String, Object> execution1 = this.getJobExecution(id1);
		assertEquals("COMPLETED", execution1.get("STATUS"));

		this.validateLaunchWithSkips();

		//
		// Clear the data
		//
		setUp();

		//
		// Launch 2
		//
		long id2 = this.launchJobWithIncrementer();
		Map<String, Object> execution2 = this.getJobExecution(id2);
		assertEquals("COMPLETED", execution2.get("STATUS"));

		this.validateLaunchWithoutSkips();

		//
		// Make sure that the launches were separate executions and separate
		// instances
		//
		assertTrue(id1 != id2);
		assertTrue(!execution1.get("JOB_INSTANCE_ID").equals(execution2.get("JOB_INSTANCE_ID")));
	}

	private void validateLaunchWithSkips() {
		// Step1: 5 input records, 1 skipped => 4 written to output
		assertEquals(4, SimpleJdbcTestUtils.countRowsInTable(simpleJdbcTemplate, "TRADE"));

		// Step2: 4 input records, 1 skipped => 3 written to output
		assertEquals(3, itemTrackingWriter.getItems().size());

		// Both steps contained skips
		assertEquals(2, SimpleJdbcTestUtils.countRowsInTable(simpleJdbcTemplate, "ERROR_LOG"));

		for (int i = 1; i <= 2; i++) {
			assertEquals(1, simpleJdbcTemplate.queryForInt(
					"SELECT Count(*) from ERROR_LOG where JOB_NAME = ? and STEP_NAME = ?", "skipJob", "step" + i));
		}
	}

	private void validateLaunchWithoutSkips() {
		// Step1: 5 input records => 5 written to output
		assertEquals(5, SimpleJdbcTestUtils.countRowsInTable(simpleJdbcTemplate, "TRADE"));

		// Step2: 5 input records => 5 written to output
		assertEquals(5, itemTrackingWriter.getItems().size());

		// Neither step contained skips
		assertEquals(0, SimpleJdbcTestUtils.countRowsInTable(simpleJdbcTemplate, "ERROR_LOG"));
	}

	private Map<String, Object> getJobExecution(long jobExecutionId) {
		return simpleJdbcTemplate.queryForMap("SELECT * from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
				jobExecutionId);
	}

	/**
	 * Launch the entire job, including all steps, in order.
	 * 
	 * @return JobExecution, so that the test may validate the exit status
	 */
	public long launchJobWithIncrementer() {
		try {
			return this.jobOperator.startNextInstance("skipJob");
		}
		catch (NoSuchJobException e) {
			throw new RuntimeException(e);
		}
		catch (JobExecutionAlreadyRunningException e) {
			throw new RuntimeException(e);
		}
		catch (JobParametersNotFoundException e) {
			throw new RuntimeException(e);
		}
		catch (JobRestartException e) {
			throw new RuntimeException(e);
		}
		catch (JobInstanceAlreadyCompleteException e) {
			throw new RuntimeException(e);
		}
	}
}
