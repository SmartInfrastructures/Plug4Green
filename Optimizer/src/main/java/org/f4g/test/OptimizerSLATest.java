package org.f4g.test;

import entropy.configuration.*;
import entropy.plan.PlanException;
import entropy.plan.TimedReconfigurationPlan;
import entropy.plan.choco.ChocoCustomRP;
import entropy.plan.durationEvaluator.MockDurationEvaluator;
import entropy.vjob.Ban;
import entropy.vjob.DefaultVJob;
import entropy.vjob.VJob;
import junit.framework.Assert;
import org.f4g.cost_estimator.NetworkCost;
import org.f4g.entropy.plan.constraint.DefaultVcpuPcpuMapping;
import org.f4g.entropy.plan.constraint.F4GCPUOverbookingConstraint2;
import org.f4g.entropy.plan.constraint.SpareCPUs2;
import org.f4g.entropy.plan.constraint.VcpuPcpuMapping;
import org.f4g.optimizer.CloudTraditional.OptimizerEngineCloudTraditional;
import org.f4g.optimizer.CloudTraditional.SLAReader;
import org.f4g.optimizer.ICostEstimator;
import org.f4g.optimizer.utils.Utils;
import org.f4g.schema.actions.AbstractBaseActionType;
import org.f4g.schema.actions.ActionRequestType;
import org.f4g.schema.actions.MoveVMActionType;
import org.f4g.schema.actions.PowerOffActionType;
import org.f4g.schema.allocation.*;
import org.f4g.schema.allocation.ObjectFactory;
import org.f4g.schema.constraints.optimizerconstraints.*;
import org.f4g.schema.constraints.optimizerconstraints.ClusterType.Cluster;
import org.f4g.schema.constraints.optimizerconstraints.PolicyType.Policy;
import org.f4g.schema.constraints.optimizerconstraints.QoSDescriptionType.MaxVirtualCPUPerCore;
import org.f4g.schema.metamodel.*;
import org.jscience.economics.money.Money;
import org.jscience.physics.measures.Measure;

import javax.measure.quantities.Duration;
import javax.measure.quantities.Energy;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.util.*;

import static javax.measure.units.SI.JOULE;

/**
 * {To be completed; use html notation, if necessary}
 * 
 * 
 * @author TS
 */
public class OptimizerSLATest extends OptimizerTest {

	/**
	 * Construction of the optimizer
	 * 
	 * @author TS
	 */
	protected void setUp() throws Exception {
		super.setUp();

		SLAGenerator slaGenerator = new SLAGenerator();

		PeriodType period = new PeriodType(
				begin, end, null, null, new LoadType(null, null));

		PolicyType.Policy pol = new Policy();
		pol.getPeriodVMThreshold().add(period);

		List<Policy> polL = new LinkedList<Policy>();
		polL.add(pol);

		PolicyType vmMargins = new PolicyType(polL);
		vmMargins.getPolicy().add(pol);
		optimizer = new OptimizerEngineCloudTraditional(new MockController(),
				new MockPowerCalculator(), new NetworkCost(),
				slaGenerator.createVirtualMachineType(), vmMargins, makeSimpleFed(vmMargins, null));

	}

	/**
	 * Destruction
	 * 
	 * @author TS
	 */
	protected void tearDown() throws Exception {
		super.tearDown();
		optimizer = null;
	}

	/**
	 * Test global optimization with one VM per servers and no load
	 * 
	 * @author Ts
	 */
	public void testHDDGlobal() {

		// generate one VM per server
		// VMs ressource usage is 0
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(2);
		modelGenerator.setNB_VIRTUAL_MACHINES(4);

		// VM settings
		modelGenerator.setCPU_USAGE(0.0);
		modelGenerator.setNB_CPU(1);
		modelGenerator.setNETWORK_USAGE(0);
		modelGenerator.setSTORAGE_USAGE(0);
		modelGenerator.setMEMORY_USAGE(0);
		modelGenerator.VM_TYPE = "myVM";

		// servers settings
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(4);
		modelGenerator.setRAM_SIZE(560);
		modelGenerator.setSTORAGE_SIZE(1000); // Not enough space to have 8 VMs
											  // on one server (200GB HDD
											  // each)

		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("myVM");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1),
				new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(1),
				new MemoryUsageType(0), new IoRateType(0),
				new NetworkUsageType(0)));

		optimizer.getVmTypes().getVMType().add(type1);

		String sep = System.getProperty("file.separator");
		SLAReader sla = new SLAReader("resources" + sep
				+ "SlaClusterConstraintsHDD.xml");
		optimizer.setClusters(sla.getCluster());

		FIT4GreenType modelManyServersNoLoad = modelGenerator
				.createPopulatedFIT4GreenType();

		optimizer.runGlobalOptimization(modelManyServersNoLoad);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertTrue(moves.size() == 0);
		assertTrue(powerOffs.size() == 0);

		// no duplicate moves or power offs should be found
		Set<MoveVMActionType> moveSet = new HashSet<MoveVMActionType>(moves);
		Set<PowerOffActionType> powerOffSet = new HashSet<PowerOffActionType>(
				powerOffs);
		assertTrue(moveSet.size() == 0);
		assertTrue(powerOffSet.size() == 0);

	}

	/**
	 * Test global optimization with one VM per servers and no load
	 * 
	 * @author Ts
	 */
	public void testSpareCPUs() {

		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(2);
		modelGenerator.setNB_VIRTUAL_MACHINES(1);
		modelGenerator.setCORE(2);
	
		FIT4GreenType model = modelGenerator.createPopulatedFIT4GreenType();

		//TEST 1: no Spare CPU
		optimizer.runGlobalOptimization(model);

		assertEquals(getPowerOffs().size(), 1);

		//TEST 2: absolute CPU
		optimizer.getPolicies().getPolicy().get(0).getPeriodVMThreshold().get(0).getLoad().setSpareCPUs(new SpareCPUs(1, UnitType.ABSOLUTE));
		optimizer.runGlobalOptimization(model);

		assertEquals(getPowerOffs().size(), 0);

		//TEST 3: relative CPU
		optimizer.getPolicies().getPolicy().get(0).getPeriodVMThreshold().get(0).getLoad().setSpareCPUs(new SpareCPUs(50, UnitType.RELATIVE));
		optimizer.runGlobalOptimization(model);

		assertEquals(getPowerOffs().size(), 0);
		
		//TEST 4: relative CPU
		optimizer.getPolicies().getPolicy().get(0).getPeriodVMThreshold().get(0).getLoad().setSpareCPUs(new SpareCPUs(0, UnitType.RELATIVE));
		optimizer.runGlobalOptimization(model);

		assertEquals(getPowerOffs().size(), 1);
	}

	/**
	 * Test global optimization for Max Server CPU load (F4GCPUConstraint)
	 * 
	 * @author Ts
	 */
	public void testCPUGlobal() {

		// generate one VM per server
		// VMs ressource usage is 0
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(2);
		modelGenerator.setNB_VIRTUAL_MACHINES(4);

		// VM settings
		modelGenerator.setCPU_USAGE(20); // set to 80% in SLA therefore not
										 // enough space for 8 VMs on one
										 // server
		modelGenerator.setNB_CPU(1);
		modelGenerator.setNETWORK_USAGE(0);
		modelGenerator.setSTORAGE_USAGE(0);
		modelGenerator.setMEMORY_USAGE(0);
		modelGenerator.VM_TYPE = "m1.small";

		// servers settings
		modelGenerator.setCPU(4);
		modelGenerator.setCORE(4);
		modelGenerator.setRAM_SIZE(560);
		modelGenerator.setSTORAGE_SIZE(10000000);


		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1),
				new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(20),
				new MemoryUsageType(0), new IoRateType(0),
				new NetworkUsageType(0)));
		optimizer.getVmTypes().getVMType().add(type1);

		FIT4GreenType modelManyServersNoLoad = modelGenerator
				.createPopulatedFIT4GreenType();

		String sep = System.getProperty("file.separator");
		SLAReader sla = new SLAReader("resources" + sep
				+ "SlaClusterConstraintsCPULoad.xml");
		optimizer.setClusters(sla.getCluster());

		optimizer.runGlobalOptimization(modelManyServersNoLoad);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertTrue(moves.size() == 0);
		assertTrue(powerOffs.size() == 0);

		// no duplicate moves or power offs should be found
		Set<MoveVMActionType> moveSet = new HashSet<MoveVMActionType>(moves);
		Set<PowerOffActionType> powerOffSet = new HashSet<PowerOffActionType>(
				powerOffs);
		assertTrue(moveSet.size() == 0);
		assertTrue(powerOffSet.size() == 0);

	}

	/**
	 * Test global optimization with one VM per servers and no load
	 * 
	 * @author Ts
	 */
	public void testOverbookingGlobal() {

		// generate one VM per server
		// VMs ressource usage is 0
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(4);
		modelGenerator.setNB_VIRTUAL_MACHINES(4); // 16 VMs total

		// VM settings
		modelGenerator.setCPU_USAGE(0.0);
		modelGenerator.setNB_CPU(1);
		modelGenerator.setNETWORK_USAGE(0);
		modelGenerator.setSTORAGE_USAGE(0);
		modelGenerator.setMEMORY_USAGE(0);
		modelGenerator.VM_TYPE = "m1.small";

		// servers settings
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(4);
		modelGenerator.setRAM_SIZE(560);
		modelGenerator.setSTORAGE_SIZE(10000000);

		VMTypeType vmTypes = new VMTypeType();

		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1),
				new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(1),   //only 1% of CPU usage
				new MemoryUsageType(0), new IoRateType(0),
				new NetworkUsageType(0)));
		vmTypes.getVMType().add(type1);

		FIT4GreenType model = modelGenerator.createPopulatedFIT4GreenType();

		PeriodType period = new PeriodType(
				begin, end, null, null, new LoadType(null, null));

		PolicyType.Policy pol = new Policy();
		pol.getPeriodVMThreshold().add(period);

		List<Policy> polL = new LinkedList<Policy>();
		polL.add(pol);

		PolicyType vmMargins = new PolicyType(polL);
		vmMargins.getPolicy().add(pol);
		// TEST 1: without overbooking setting

		OptimizerEngineCloudTraditional myOptimizer = new OptimizerEngineCloudTraditional(
				new MockController(), new MockPowerCalculator(),
				new NetworkCost(), vmTypes, vmMargins, makeSimpleFed(vmMargins, model));
		myOptimizer.runGlobalOptimization(model);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertEquals(moves.size(), 12); // everyone on the same server

		// TEST 2 with overbooking setting = 1

		SLAType slas = SLAGenerator.createDefaultSLA();
		QoSDescriptionType qos = new QoSDescriptionType();
		MaxVirtualCPUPerCore mvCPU = new MaxVirtualCPUPerCore();
		qos.setMaxVirtualCPUPerCore(mvCPU);
		qos.getMaxVirtualCPUPerCore().setValue((float) 1.0);
		slas.getSLA().get(0).setCommonQoSRelatedMetrics(qos);

		ClusterType clusters = createDefaultCluster(
				modelGenerator.MAX_NB_SERVERS, slas.getSLA(), polL);
		myOptimizer.setClusters(clusters);
		myOptimizer.setVmTypes(vmTypes);
		myOptimizer.setSla(slas);
		myOptimizer.runGlobalOptimization(model);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response2 = actionRequest.getActionList();

		moves.clear();
		powerOffs.clear();

		for (JAXBElement<? extends AbstractBaseActionType> action : response2
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertEquals(moves.size(), 0); //no Overbooking (==1) -> 1 core per
		// VM / 4 core per server -> max VMs per server = 4 -> no moves

		// TEST 3 with overbooking setting = 2

		myOptimizer.getSla().getSLA().get(0).getCommonQoSRelatedMetrics()
				.getMaxVirtualCPUPerCore().setValue((float) 2.0);

		myOptimizer.runGlobalOptimization(model);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response3 = actionRequest.getActionList();

		moves.clear();
		powerOffs.clear();

		for (JAXBElement<? extends AbstractBaseActionType> action : response3
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertEquals(moves.size(), 8); // Overbooking 2 -> 1 core per VM / 4
										// core per server -> max VMs per server
										// = 8 -> 8 moves

		// TEST 4 with overbooking setting = 1.5

		myOptimizer.getSla().getSLA().get(0).getCommonQoSRelatedMetrics()
				.getMaxVirtualCPUPerCore().setValue((float) 1.5);

		myOptimizer.runGlobalOptimization(model);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response4 = actionRequest.getActionList();

		moves.clear();
		powerOffs.clear();

		for (JAXBElement<? extends AbstractBaseActionType> action : response4
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertEquals(moves.size(), 4); // Overbooking 1.5 -> 6 VCPUs per server,
										// 4 moves

		// TEST 5 with overbooking setting = 2, mixed VMs

		modelGenerator.setNB_SERVERS(4);
		modelGenerator.setNB_VIRTUAL_MACHINES(4); // 16 VMs total
		model = modelGenerator.createPopulatedFIT4GreenType();

		VMTypeType.VMType type2 = new VMTypeType.VMType();
		type2.setName("m1.medium");
		type2.setCapacity(new CapacityType(new NrOfCpusType(2),
				new RAMSizeType(1), new StorageCapacityType(1)));
		type2.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(0.01),
				new MemoryUsageType(0), new IoRateType(0),
				new NetworkUsageType(0)));
		vmTypes.getVMType().add(type2);

		List<VirtualMachineType> vms = Utils.getAllVMs(model);
		vms.get(0).setCloudVmType("m1.medium");
		vms.get(1).setCloudVmType("m1.medium");
		vms.get(2).setCloudVmType("m1.medium");
		vms.get(3).setCloudVmType("m1.medium");

		myOptimizer.setVmTypes(vmTypes);
		myOptimizer.getSla().getSLA().get(0).getCommonQoSRelatedMetrics()
				.getMaxVirtualCPUPerCore().setValue((float) 2.0);

		myOptimizer.runGlobalOptimization(model);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response5 = actionRequest.getActionList();

		moves.clear();
		powerOffs.clear();

		for (JAXBElement<? extends AbstractBaseActionType> action : response5
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertEquals(moves.size(), 4); // Overbooking 2 -> first server is full
										// (no move) because au medium size. 4
										// small VMs move to fill a second
										// server.

	}

	/**
	 * Test global optimization with one VM per servers and no load
	 * 
	 * @author Ts
	 */
	public void testVirtualLoadPerCoreGlobal() {

		// generate one VM per server
		// VMs ressource usage is 0
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(2);
		modelGenerator.setNB_VIRTUAL_MACHINES(4);

		// VM settings
		modelGenerator.setCPU_USAGE(0.0);
		modelGenerator.setNB_CPU(1);
		modelGenerator.setNETWORK_USAGE(0);
		modelGenerator.setSTORAGE_USAGE(0);
		modelGenerator.setMEMORY_USAGE(0);
		modelGenerator.VM_TYPE = "m1.small";

		// servers settings
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(4);
		modelGenerator.setRAM_SIZE(560);
		modelGenerator.setSTORAGE_SIZE(10000000);

		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1),
				new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(0.2),
				new MemoryUsageType(0), new IoRateType(0),
				new NetworkUsageType(0)));
		optimizer.getVmTypes().getVMType().add(type1);

		FIT4GreenType modelManyServersNoLoad = modelGenerator
				.createPopulatedFIT4GreenType();
		String sep = System.getProperty("file.separator");
		SLAReader sla = new SLAReader("resources" + sep
				+ "SlaClusterConstraints.xml");
		optimizer.setClusters(sla.getCluster());
		optimizer.setSla(sla.getSLAs());
		optimizer.runGlobalOptimization(modelManyServersNoLoad);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertTrue(moves.size() == 0);
		assertTrue(powerOffs.size() == 0);

		// no duplicate moves or power offs should be found
		Set<MoveVMActionType> moveSet = new HashSet<MoveVMActionType>(moves);
		Set<PowerOffActionType> powerOffSet = new HashSet<PowerOffActionType>(
				powerOffs);
		assertTrue(moveSet.size() == 0);
		assertTrue(powerOffSet.size() == 0);

	}

	/**
	 * Test global optimization with one VM per servers and no load
	 * 
	 * @author Ts
	 */
	public void testMaxVMperServerGlobal() {

		// generate one VM per server
		// VMs ressource usage is 0
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(2);
		modelGenerator.setNB_VIRTUAL_MACHINES(2);

		// VM settings
		modelGenerator.setCPU_USAGE(0.0);
		modelGenerator.setNB_CPU(1);
		modelGenerator.setNETWORK_USAGE(0);
		modelGenerator.setSTORAGE_USAGE(0);
		modelGenerator.setMEMORY_USAGE(0);
		modelGenerator.VM_TYPE = "m1.small";

		// servers settings
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(4);
		modelGenerator.setRAM_SIZE(560);
		modelGenerator.setSTORAGE_SIZE(1000); // Not enough space to have 8 VMs
												// on one server (200GB HDD
												// each)

		VMTypeType vmTypes = new VMTypeType();

		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1),
				new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(1),
				new MemoryUsageType(0), new IoRateType(0),
				new NetworkUsageType(0)));
		vmTypes.getVMType().add(type1);

		((OptimizerEngineCloudTraditional) optimizer).setVmTypes(vmTypes);
		String sep = System.getProperty("file.separator");
		SLAReader sla = new SLAReader("resources" + sep
				+ "SlaClusterConstraints2.xml");
		optimizer.setClusters(sla.getCluster());
		optimizer.setSla(sla.getSLAs());

		FIT4GreenType modelManyServersNoLoad = modelGenerator
				.createPopulatedFIT4GreenType();

		optimizer.runGlobalOptimization(modelManyServersNoLoad);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertTrue(moves.size() == 2);
		assertTrue(powerOffs.size() == 1);

		// no duplicate moves or power offs should be found
		Set<MoveVMActionType> moveSet = new HashSet<MoveVMActionType>(moves);
		Set<PowerOffActionType> powerOffSet = new HashSet<PowerOffActionType>(
				powerOffs);
		assertTrue(moveSet.size() == 2);
		assertTrue(powerOffSet.size() == 1);
	}

	/**
	 * Test global optimization with one VM per servers and no load
	 * 
	 * @author Ts
	 */
	public void testMemoryOverbookingGlobal() {

		// generate one VM per server
		// VMs ressource usage is 0
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(2);
		modelGenerator.setNB_VIRTUAL_MACHINES(4);

		// VM settings
		modelGenerator.setCPU_USAGE(0.0);
		modelGenerator.setNB_CPU(1);
		modelGenerator.setNETWORK_USAGE(0);
		modelGenerator.setSTORAGE_USAGE(0);
		modelGenerator.setMEMORY_USAGE(50);
		modelGenerator.VM_TYPE = "m1.small";

		// servers settings
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(1);
		modelGenerator.setRAM_SIZE(100);
		modelGenerator.setSTORAGE_SIZE(1000); // Not enough space to have 8 VMs
												// on one server (200GB HDD
												// each)

		VMTypeType vmTypes = new VMTypeType();
		PeriodType period = new PeriodType(
				begin, end, null, null, new LoadType(null, null));

		PolicyType.Policy pol = new Policy();
		pol.getPeriodVMThreshold().add(period);

		List<Policy> polL = new LinkedList<Policy>();
		polL.add(pol);

		PolicyType vmMargins = new PolicyType(polL);
		vmMargins.getPolicy().add(pol);
		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1),
				new RAMSizeType(50), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(1),
				new MemoryUsageType(50), new IoRateType(0),
				new NetworkUsageType(0)));
		vmTypes.getVMType().add(type1);

		
		//TODO: this xml file is outdated
		((OptimizerEngineCloudTraditional) optimizer).setVmTypes(vmTypes);
		String sep = System.getProperty("file.separator");
		SLAReader sla = new SLAReader("resources" + sep
				+ "SlaClusterConstraints2.xml");
		optimizer.setClusters(sla.getCluster());
		optimizer.setSla(sla.getSLAs());

		FIT4GreenType modelManyServersNoLoad = modelGenerator
				.createPopulatedFIT4GreenType();

		optimizer.runGlobalOptimization(modelManyServersNoLoad);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}
		
		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());
//		assertTrue(moves.size() == 1);
//		assertTrue(powerOffs.size() == 0);

		// no duplicate moves or power offs should be found
		Set<MoveVMActionType> moveSet = new HashSet<MoveVMActionType>(moves);
		Set<PowerOffActionType> powerOffSet = new HashSet<PowerOffActionType>(
				powerOffs);
//		log.debug("moves=" + moveSet.size());
//		log.debug("powerOffs=" + powerOffSet.size());
		assertTrue(moveSet.size() == 1);
		assertTrue(powerOffSet.size() == 1);
	}

	/**
	 * 
	 * @author Ts
	 */
	public void testVLoadPerCoreGlobal() {

		// generate one VM per server
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(2);
		modelGenerator.setNB_VIRTUAL_MACHINES(2);

		// VM settings
		modelGenerator.VM_TYPE = "m1.small";

		// servers settings
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(2);
		modelGenerator.setRAM_SIZE(560);
		modelGenerator.setSTORAGE_SIZE(1000);

		VMTypeType type = new VMTypeType();
		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1),
				new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(50),
				new MemoryUsageType(0), new IoRateType(0),
				new NetworkUsageType(0)));
		type.getVMType().add(type1);
		
		optimizer.setVmTypes(type);
		
		String sep = System.getProperty("file.separator");
		SLAReader sla = new SLAReader("resources" + sep
				+ "SlaClusterConstraintsCoreLoad.xml");
		optimizer.setClusters(sla.getCluster());
		optimizer.setSla(sla.getSLAs());
		optimizer.setFederation(sla.getFeds());

		FIT4GreenType myModel = modelGenerator.createPopulatedFIT4GreenType();

		//clearing one servers
		//myModel.getSite().get(0).getDatacenter().get(0).getRack().get(0).getRackableServer().get(0).getNativeOperatingSystem().getHostedHypervisor().get(0).getVirtualMachine().clear();
		optimizer.runGlobalOptimization(myModel);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertEquals(moves.size(), 1);
		assertEquals(powerOffs.size(), 0);

		// no duplicate moves or power offs should be found
		Set<MoveVMActionType> moveSet = new HashSet<MoveVMActionType>(moves);
		Set<PowerOffActionType> powerOffSet = new HashSet<PowerOffActionType>(
				powerOffs);
		assertTrue(moveSet.size() == 1);
		assertTrue(powerOffSet.size() == 0);
	}


	/**
	 * Test VM Payback Time
	 * 
	 * @author cdupont
	 */
	public void testVMPaybackTimeGlobal() {

		class MyNetworkCost implements ICostEstimator {

			@Override
			public boolean dispose() {return false;}

			@Override
			public Measure<Duration> moveDownTimeCost(NetworkNodeType fromServer,
					NetworkNodeType toServer, VirtualMachineType VM,
					FIT4GreenType model) {
				return null;
			}

			@Override
			public Measure<Energy> moveEnergyCost(NetworkNodeType fromServer,
					NetworkNodeType toServer, VirtualMachineType VM,
					FIT4GreenType model) {
				
				return Measure.valueOf(1000, JOULE);
			}

			@Override
			public Measure<Money> moveFinancialCost(NetworkNodeType fromServer,
					NetworkNodeType toServer, VirtualMachineType VM,
					FIT4GreenType model) {
				return null;
			}
			
		}
		
		// generate one VM per server
		// VMs ressource usage is 0
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(4);
		modelGenerator.setNB_VIRTUAL_MACHINES(4); // 16 VMs total

		// VM settings
		modelGenerator.setCPU_USAGE(0.0);
		modelGenerator.setNB_CPU(1);
		modelGenerator.setNETWORK_USAGE(0);
		modelGenerator.setSTORAGE_USAGE(0);
		modelGenerator.setMEMORY_USAGE(0);
		modelGenerator.VM_TYPE = "m1.small";

		// servers settings
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(4);
		modelGenerator.setRAM_SIZE(560);
		modelGenerator.setSTORAGE_SIZE(10000000);

		VMTypeType vmTypes = new VMTypeType();
		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1),
				new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(0.01),
				new MemoryUsageType(0), new IoRateType(0),
				new NetworkUsageType(0)));
		vmTypes.getVMType().add(type1);

		FIT4GreenType model = modelGenerator.createPopulatedFIT4GreenType();

		PeriodType period = new PeriodType(
				begin, end, null, null, new LoadType(new SpareCPUs(0, UnitType.ABSOLUTE), null));

		PolicyType.Policy pol = new Policy();
		pol.getPeriodVMThreshold().add(period);

		List<Policy> polL = new LinkedList<Policy>();
		polL.add(pol);

		PolicyType pols = new PolicyType(polL);
		pols.getPolicy().add(pol);
		
		// TEST 1: without payback time setting

		OptimizerEngineCloudTraditional myOptimizer = new OptimizerEngineCloudTraditional(
				new MockController(), new MockPowerCalculator(),
				new MyNetworkCost(), vmTypes, pols, makeSimpleFed(pols, model));
		myOptimizer.runGlobalOptimization(model);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertEquals(moves.size(), 12); // everyone on the same server

		
		// TEST 2: with payback time = 10 min

		SLAType slas = SLAGenerator.createDefaultSLA();
				
		pol.setVMMigrationPaybacktime(10);
		ClusterType clusters = createDefaultCluster(
				modelGenerator.MAX_NB_SERVERS, slas.getSLA(), polL);
		
		FederationType fed = makeSimpleFed(pols, model);
		for(Cluster cs : clusters.getCluster()) {
			BoundedClustersType bc = new BoundedClustersType();
			BoundedClustersType.Cluster bcc = new BoundedClustersType.Cluster();
			bcc.setIdref(cs);	
			bc.getCluster().add(bcc);				
			fed.setBoundedCluster(bc);
			
		}
		myOptimizer.setFederation(fed);
		myOptimizer.setClusters(clusters);
		myOptimizer.setVmTypes(vmTypes);
		myOptimizer.setSla(slas);
		myOptimizer.runGlobalOptimization(model);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		ActionRequestType.ActionList response2 = actionRequest.getActionList();

		moves.clear();
		powerOffs.clear();

		for (JAXBElement<? extends AbstractBaseActionType> action : response2
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		//payback time = 600s, move = 100J, power saved = 2.5W (A server is 10W, we have to move 4 VMs to switch is off, 
		// all servers energy profile are equivalent).
		// 2.5*600 > 1000, move allowed
		assertEquals(moves.size(), 12);
		
		
		// TEST 3: with payback time = 1 min
		
		myOptimizer.getPolicies().getPolicy().get(0).setVMMigrationPaybacktime(1);
		
		myOptimizer.runGlobalOptimization(model);
		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		ActionRequestType.ActionList response3 = actionRequest.getActionList();

		moves.clear();
		powerOffs.clear();

		for (JAXBElement<? extends AbstractBaseActionType> action : response3
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		//payback time = 60s, move = 100J, power saved = 2.5W (A server is 10W, we have to move 4 VMs to switch is off, 
		// all servers energy profile are equivalent).
		// 2.5*60 < 1000, move disallowed
		assertEquals(moves.size(), 0);
		

	}
	
	
	/**
	 * Test global optimization with one VM per servers and no load
	 * 
	 * @author Ts
	 */
	// public void testCapacityGlobal() {
	//
	// //generate one VM per server
	// //VMs ressource usage is 0
	// ModelGenerator modelGenerator = new ModelGenerator();
	// modelGenerator.setNB_SERVERS(2);
	// modelGenerator.setNB_VIRTUAL_MACHINES(4);
	//
	// //VM settings
	// modelGenerator.setCPU_USAGE(0.0);
	// modelGenerator.setNB_CPU(1);
	// modelGenerator.setNETWORK_USAGE(0);
	// modelGenerator.setSTORAGE_USAGE(0);
	// modelGenerator.setMEMORY_USAGE(0);
	// modelGenerator.VM_TYPE = "m1.small";
	//
	// //servers settings
	// modelGenerator.setCPU(1);
	// modelGenerator.setCORE(4);
	// modelGenerator.setRAM_SIZE(560*1024);
	// modelGenerator.setSTORAGE_SIZE(1000); //Not enough space to have 8 VMs on
	// one server (200GB HDD each)
	//
	// VMTypeType vmTypes = new VMTypeType();
	//
	// VMTypeType.VMType type1 = new VMTypeType.VMType();
	// type1.setName("m1.small");
	// type1.setCapacity(new CapacityType(new NrOfCpusType(1), new
	// RAMSizeType(1), new StorageCapacityType(1)));
	// type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(0.1), new
	// MemoryUsageType(0), new IoRateType(0), new NetworkUsageType(0)));
	// vmTypes.getVMType().add(type1);
	//
	// ((OptimizerEngineCloudTraditional)optimizer).setVmTypes(vmTypes);
	//
	// // SLAReader sla = new SLAReader("resources\\SLAClusterConstraints.xml");
	// // optimizer.setClusterType(sla.getCluster());
	// // optimizer.setSlaType(sla.getSLAs());
	//
	// ConstraintReader cp = new
	// ConstraintReader("resources\\PlacementConstraints.xml");
	// List<Capacity> c =
	// cp.CP.getDataCentre().getTargetSys().getConstraint().getCapacity();
	// System.out.println(c.get(0).getMaxNbOfVMs());
	// System.out.println(c.get(0).getServerName().get(0) + " + " +
	// c.get(0).getServerName().get(1));
	//
	// FIT4GreenType modelManyServersNoLoad =
	// modelGenerator.createPopulatedFIT4GreenType();
	//
	// optimizer.runGlobalOptimization(modelManyServersNoLoad);
	//
	// try {
	// actionRequestAvailable.acquire();
	// } catch (InterruptedException e) {
	// // TODO Auto-generated catch block
	// e.printStackTrace();
	// }
	//
	// ActionRequestType.ActionList response = actionRequest.getActionList();
	//
	// List <MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
	// List <PowerOffActionType> powerOffs = new
	// ArrayList<PowerOffActionType>();
	//
	// for (JAXBElement<? extends AbstractBaseActionType> action :
	// response.getAction()){
	// if (action.getValue() instanceof MoveVMActionType)
	// moves.add((MoveVMActionType)action.getValue());
	// if (action.getValue() instanceof PowerOffActionType)
	// powerOffs.add((PowerOffActionType)action.getValue());
	// }
	//
	//
	// log.debug("moves=" + moves.size());
	// log.debug("powerOffs=" + powerOffs.size());
	//
	// assertTrue(moves.size()==0);
	// assertTrue(powerOffs.size()==0);
	//
	// //no duplicate moves or power offs should be found
	// Set<MoveVMActionType> moveSet = new HashSet<MoveVMActionType>(moves);
	// Set<PowerOffActionType> powerOffSet = new
	// HashSet<PowerOffActionType>(powerOffs);
	// assertTrue(moveSet.size()==0);
	// assertTrue(powerOffSet.size()==0);
	//
	// }

	/**
	 * helper function
	 */
	protected AllocationRequestType createAllocationRequestCloud(String VMType) {

		AllocationRequestType request = new AllocationRequestType();

		CloudVmAllocationType cloudAlloc = new CloudVmAllocationType();
		cloudAlloc.setVmType(VMType);

		// Simulates a CloudVmAllocationType operation
		JAXBElement<CloudVmAllocationType> operationType = (new ObjectFactory())
				.createCloudVmAllocation(cloudAlloc);

		request.setRequest(operationType);

		return request;
	}

	// public void testCapacityReader(){
	// ConstraintReader cp = new
	// ConstraintReader("resources\\PlacementConstraints.xml");
	// List<Capacity> c =
	// cp.CP.getDataCentre().getTargetSys().getConstraint().getCapacity();
	// System.out.println(c.get(0).getMaxNbOfVMs());
	// System.out.println(c.get(0).getServerName().get(0) + " + " +
	// c.get(0).getServerName().get(1));
	// assertEquals(4, c.get(0).getMaxNbOfVMs());
	// }

	/**
	 * Test allocation success
	 */
	public void testAvgCPUOverbookingAllocation() {
		
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(10);
		modelGenerator.setNB_VIRTUAL_MACHINES(2);
	
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(2); //2 cores
		modelGenerator.setRAM_SIZE(1024);
		
		FIT4GreenType model = modelGenerator.createPopulatedFIT4GreenType();
				
		modelGenerator.setVM_TYPE("m1.small");
		
		VMTypeType VMs = new VMTypeType();
		
		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1), new RAMSizeType(12), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(50), new MemoryUsageType(0), new IoRateType(0), new NetworkUsageType(0)));
		VMs.getVMType().add(type1);
				
		optimizer.setVmTypes(VMs);
		String sep = System.getProperty("file.separator");
		SLAReader sla = new SLAReader("resources" + sep
				+ "SlaClusterConstraintsAvgCPUOverbooking2.xml");
		optimizer.setClusters(sla.getCluster());
		optimizer.setSla(sla.getSLAs());
		System.out.println("Name: " + sla.getCluster().getCluster().get(0).getName());
		System.out.println("ID: " + sla.getCluster().getCluster().get(0).getId());
		
		AllocationRequestType allocationRequest = createAllocationRequestCloud("m1.small");
		System.out.println("AllocCID: " + (((CloudVmAllocationType) allocationRequest.getRequest().getValue()).getClusterId()));
		AllocationResponseType response = optimizer.allocateResource(allocationRequest, model);
		CloudVmAllocationResponseType VMAllocResponse = (CloudVmAllocationResponseType) response.getResponse().getValue();
		System.out.println("NodeID: " );//+ VMAllocResponse.getNodeId());
		//New VM should be allocated on first server		
		assertEquals(VMAllocResponse.getNodeId(),"id100000");
	}
	
	


	/**
	 * Test allocation with constraints not satisfied
	 */
	public void test2ClustersOverbookingGlobal() {
		
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(8);
		modelGenerator.setNB_VIRTUAL_MACHINES(1);
		
		modelGenerator.setCPU(2);
		modelGenerator.setCORE(1); 
		modelGenerator.setRAM_SIZE(100);//24
		
		FIT4GreenType model = modelGenerator.createPopulatedFIT4GreenType();
				
		modelGenerator.setVM_TYPE("m1.small");
		
		VMTypeType VMs = new VMTypeType();
		
		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1), new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(1), new MemoryUsageType(0), new IoRateType(0), new NetworkUsageType(0)));
		VMs.getVMType().add(type1);
				
		optimizer.setVmTypes(VMs);
		
		SLAType slas = new SLAType();
		
		QoSDescriptionType qos = new QoSDescriptionType();
		MaxVirtualCPUPerCore mvCPU = new MaxVirtualCPUPerCore();
		qos.setMaxVirtualCPUPerCore(mvCPU);
		qos.getMaxVirtualCPUPerCore().setValue((float) 1.0);
				
		SLAType.SLA sla = new SLAType.SLA();
		slas.getSLA().add(sla);
		sla.setCommonQoSRelatedMetrics(qos);
		BoundedSLAsType bSlas = new BoundedSLAsType();
		bSlas.getSLA().add(new BoundedSLAsType.SLA(sla));	
		
		PolicyType.Policy policy = new PolicyType.Policy();
		
		BoundedPoliciesType bPolicies = new BoundedPoliciesType();
		bPolicies.getPolicy().add(new BoundedPoliciesType.Policy(policy));	
		
		List<String> nodeName = new ArrayList<String>();
		nodeName.add("id0");
		nodeName.add("id100000");
		nodeName.add("id200000");
		nodeName.add("id300000");
		List<Cluster> cluster = new ArrayList<ClusterType.Cluster>();
		cluster.add(new Cluster("c1", new NodeControllerType(nodeName) , bSlas, bPolicies, "idc1"));
		nodeName = new ArrayList<String>();
		nodeName.add("id400000");
		nodeName.add("id500000");
		nodeName.add("id600000");
		nodeName.add("id700000");
		cluster.add(new Cluster("c2", new NodeControllerType(nodeName) , bSlas, bPolicies, "idc2"));
		ClusterType clusters = new ClusterType(cluster);
		
		optimizer.setClusters(clusters);
		
		//TEST 1 
				
		model = modelGenerator.createPopulatedFIT4GreenType();
			
		optimizer.runGlobalOptimization(model);
		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());

		assertEquals(moves.size(), 4);
	}
	
	public void testDelayBetweenMoveGlobal() {
		ModelGenerator modelGenerator = new ModelGenerator();
		modelGenerator.setNB_SERVERS(2);
		modelGenerator.setNB_VIRTUAL_MACHINES(4);
		
		modelGenerator.setCPU(1);
		modelGenerator.setCORE(4); 
		modelGenerator.setRAM_SIZE(24);
		
		FIT4GreenType model = modelGenerator.createPopulatedFIT4GreenType();
				
		modelGenerator.setVM_TYPE("m1.small");
		
		VMTypeType VMs = new VMTypeType();
		
		VMTypeType.VMType type1 = new VMTypeType.VMType();
		type1.setName("m1.small");
		type1.setCapacity(new CapacityType(new NrOfCpusType(1), new RAMSizeType(1), new StorageCapacityType(1)));
		type1.setExpectedLoad(new ExpectedLoadType(new CpuUsageType(10), new MemoryUsageType(1), new IoRateType(0), new NetworkUsageType(0)));
		VMs.getVMType().add(type1);
				
		optimizer.setVmTypes(VMs);
		
		
		DatatypeFactory factory;
		try {
			TimeZone gmt = TimeZone.getTimeZone("GMT");
			GregorianCalendar gc = (GregorianCalendar) GregorianCalendar.getInstance(gmt);
			factory = DatatypeFactory.newInstance();
			javax.xml.datatype.Duration duration = factory.newDuration(false, 0, 0, 0, 0,
					4, 0); // 4 Minutes negative Duration
			XMLGregorianCalendar VMLastTimeMove = factory.newXMLGregorianCalendar(gc);
			VMLastTimeMove.add(duration);
			List<VirtualMachineType> vms = Utils.getAllVMs(model);
			for (VirtualMachineType vmt : vms){
				vmt.setLastMigrationTimestamp(VMLastTimeMove);
			}	
//			XMLGregorianCalendar VMLastTimeMove2 = (XMLGregorianCalendar) VMLastTimeMove.clone();
//			VMLastTimeMove2.add(duration);
//			vms.get(0).setLastMigrationTimestamp(VMLastTimeMove2);
//			vms.get(1).setLastMigrationTimestamp(VMLastTimeMove2);
//			vms.get(2).setLastMigrationTimestamp(VMLastTimeMove2);
//			vms.get(3).setLastMigrationTimestamp(VMLastTimeMove2);
		} catch (DatatypeConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		String sep = System.getProperty("file.separator");
		SLAReader sla = new SLAReader("resources" + sep
				+ "SlaClusterConstraintsDelayBetweenMove.xml");
		optimizer.setClusters(sla.getCluster());
		optimizer.setSla(sla.getSLAs());
		optimizer.setFederation(sla.getFeds());
		
//		sla.getPolicies().getPolicy().get(0).getDelayBetweenMove();
		
//		int delayTimeBetweenMove = 5;
//		
//		try {
//			factory = DatatypeFactory.newInstance();
//			Duration duration = factory.newDuration(false, 0, 0, 0, 0,
//					delayTimeBetweenMove, 0); // negative Duration
//			XMLGregorianCalendar earliestLastTimeMove = new XMLGregorianCalendarImpl(
//					(GregorianCalendar) java.util.GregorianCalendar
//							.getInstance());
//			earliestLastTimeMove.add(duration);
//			System.out.println(earliestLastTimeMove.toString());
//		} catch (DatatypeConfigurationException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}		
		
		

		

		optimizer.runGlobalOptimization(model);

		try {
			actionRequestAvailable.acquire();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		ActionRequestType.ActionList response = actionRequest.getActionList();

		List<MoveVMActionType> moves = new ArrayList<MoveVMActionType>();
		List<PowerOffActionType> powerOffs = new ArrayList<PowerOffActionType>();

		for (JAXBElement<? extends AbstractBaseActionType> action : response
				.getAction()) {
			if (action.getValue() instanceof MoveVMActionType)
				moves.add((MoveVMActionType) action.getValue());
			if (action.getValue() instanceof PowerOffActionType)
				powerOffs.add((PowerOffActionType) action.getValue());
		}

		log.debug("moves=" + moves.size());
		log.debug("powerOffs=" + powerOffs.size());

		assertTrue(moves.size() == 0);
		assertTrue(powerOffs.size() == 0);

		// no duplicate moves or power offs should be found
		Set<MoveVMActionType> moveSet = new HashSet<MoveVMActionType>(moves);
		Set<PowerOffActionType> powerOffSet = new HashSet<PowerOffActionType>(
				powerOffs);
		assertTrue(moveSet.size() == 0);
		assertTrue(powerOffSet.size() == 0);
	}

    /**
     * Unit tests to check F4GCPUOverbooking2.
     * @author Fabien Hermenier
     */
    public void testCPUOverbooking2() {
        Configuration src = new SimpleConfiguration();
        Node n1 = new SimpleNode("N1", 5, 30, 30);
        Node n2 = new SimpleNode("N2", 5, 30, 30);
        Node n3 = new SimpleNode("N3", 5, 30, 30);
        src.addOnline(n1);
        src.addOnline(n2);
        src.addOnline(n3);
        for (int i = 0; i < 15; i++) {
            VirtualMachine vm = new SimpleVirtualMachine("VM" + i, 1, 1, 1);
            src.setRunOn(vm, src.getOnlines().get(0)); //Only on the first node
        }
        ChocoCustomRP rp = new ChocoCustomRP(new MockDurationEvaluator(1, 2, 3, 4, 5, 6, 7, 8 , 9));
        rp.setRepairMode(false);
        rp.setTimeLimit(0);
        rp.doOptimize(false);
        VJob v = new DefaultVJob("v");

        List<VJob> vjobs = new ArrayList<VJob>();
        vjobs.add(v);

        try {
        TimedReconfigurationPlan res = rp.compute(src, src.getRunnings(),
                        src.getWaitings(),
                        src.getSleepings(),
                        new SimpleManagedElementSet<VirtualMachine>(),
                        new SimpleManagedElementSet<Node>(),
                        new SimpleManagedElementSet<Node>(),
                        vjobs);
            Assert.assertEquals(res.size(), 0);
        } catch (PlanException e) {
            Assert.fail(e.getMessage());
        }
        //x.reset();

        v.addConstraint(new F4GCPUOverbookingConstraint2(src.getOnlines(), 2D));
        v.addConstraint(new Ban(src.getAllVirtualMachines(), new SimpleManagedElementSet<Node>(n3))); //Prevent the use of node3
        //Overbooking factor of 2, so 2 nodes will be used. Ideally, 10 VMs on n1, 4 VMs on n2
        try {
         //   x  = new DefaultVcpuPcpuMapping(rp.getModel());
            TimedReconfigurationPlan res = rp.compute(src, src.getRunnings(),
                    src.getWaitings(),
                    src.getSleepings(),
                    new SimpleManagedElementSet<VirtualMachine>(),
                    new SimpleManagedElementSet<Node>(),
                    new SimpleManagedElementSet<Node>(),
                    vjobs);
            Assert.assertEquals(5, res.size());
            Configuration dst = res.getDestination();
            Assert.assertEquals(10, dst.getRunnings(n1).size());
            Assert.assertEquals(5, dst.getRunnings(n2).size());
            //Control of the pCPU usage
            VcpuPcpuMapping mapping = DefaultVcpuPcpuMapping.getInstances();
            Assert.assertEquals(5, mapping.getPcpuUsage(n1).getVal());
            Assert.assertEquals(3, mapping.getPcpuUsage(n2).getVal()); //3 but the last is "half full"
            //x.reset();
        } catch (PlanException e) {
            Assert.fail(e.getMessage());
        }
        DefaultVcpuPcpuMapping.getInstances().reset();
        vjobs.clear();
        v = new DefaultVJob("v");
        v.addConstraint(new F4GCPUOverbookingConstraint2(src.getOnlines(), 1D)); //No cpu overbooking. 5 VMs per node
        vjobs.add(v);
        try {
            TimedReconfigurationPlan res = rp.compute(src, src.getRunnings(),
                    src.getWaitings(),
                    src.getSleepings(),
                    new SimpleManagedElementSet<VirtualMachine>(),
                    new SimpleManagedElementSet<Node>(),
                    new SimpleManagedElementSet<Node>(),
                    vjobs);
            Assert.assertEquals(10, res.size());
            Configuration dst = res.getDestination();
            Assert.assertEquals(5, dst.getRunnings(n1).size());
            Assert.assertEquals(10, dst.getRunnings(n1).size() + dst.getRunnings(n2).size());
            Assert.assertTrue(dst.getRunnings(n3).size() <= 5 && dst.getRunnings(n2).size() <= 5);
        } catch (PlanException e) {
            Assert.fail(e.getMessage());
        }
    }

    /**
     * Unit tests to check SpareCPU2.
     * @author Fabien Hermenier
     */
    public void testSpareCPU2() {
        Configuration src = new SimpleConfiguration();
        Node n1 = new SimpleNode("N1", 4, 30, 30);
        Node n2 = new SimpleNode("N2", 4, 30, 30);
        Node n3 = new SimpleNode("N3", 4, 30, 30);
        src.addOnline(n1);
        src.addOnline(n2);
        src.addOnline(n3);
        for (int i = 0; i < 20; i++) {
            VirtualMachine vm = new SimpleVirtualMachine("VM" + i, 1, 1, 1);
            src.setRunOn(vm, src.getOnlines().get(0)); //Only on the first node
        }
        ChocoCustomRP rp = new ChocoCustomRP(new MockDurationEvaluator(1, 2, 3, 4, 5, 6, 7, 8 , 9));
        rp.setRepairMode(false);
        rp.setTimeLimit(0);
        rp.doOptimize(false);
        VJob v = new DefaultVJob("v");

        List<VJob> vjobs = new ArrayList<VJob>();
        vjobs.add(v);

        v.addConstraint(new F4GCPUOverbookingConstraint2(src.getOnlines(), 2D)); //at most 2 vCPU per CPU on each node
        ManagedElementSet<Node> s = new SimpleManagedElementSet<Node>(n1);
        s.add(n2);
        v.addConstraint(new SpareCPUs2(s, 2)); //Let 2 free pCPU on n1 + n2. So at most 12 vCPU on the 2 nodes (cumulated)
        //n3 will then support the last VMs.
        try {
            TimedReconfigurationPlan res = rp.compute(src, src.getRunnings(),
                    src.getWaitings(),
                    src.getSleepings(),
                    new SimpleManagedElementSet<VirtualMachine>(),
                    new SimpleManagedElementSet<Node>(),
                    new SimpleManagedElementSet<Node>(),
                    vjobs);
            Configuration dst = res.getDestination();
            Assert.assertEquals(12, dst.getRunnings(n1).size() + dst.getRunnings(n2).size());
            Assert.assertEquals(8, dst.getRunnings(n3).size());
        } catch (PlanException e) {
            Assert.fail(e.getMessage());
        }
        DefaultVcpuPcpuMapping.getInstances().reset();
    }
}
