/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.driver.pipeline;

import static org.slf4j.LoggerFactory.getLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.onlab.osgi.ServiceDirectory;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onlab.util.KryoNamespace;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.driver.pipeline.OFDPA2GroupHandler.OfdpaNextGroup;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.NextGroup;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.behaviour.PipelinerContext;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleOperationsContext;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.criteria.EthTypeCriterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.flow.criteria.MplsBosCriterion;
import org.onosproject.net.flow.criteria.MplsCriterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.criteria.VlanIdCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions.OutputInstruction;
import org.onosproject.net.flow.instructions.L2ModificationInstruction;
import org.onosproject.net.flow.instructions.L2ModificationInstruction.L2SubType;
import org.onosproject.net.flow.instructions.L2ModificationInstruction.ModVlanIdInstruction;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveStore;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.group.DefaultGroupKey;
import org.onosproject.net.group.Group;
import org.onosproject.net.group.GroupKey;
import org.onosproject.net.group.GroupService;
import org.onosproject.store.serializers.KryoNamespaces;
import org.slf4j.Logger;

/**
 * Driver for Broadcom's OF-DPA v2.0 TTP.
 *
 */
public class OFDPA2Pipeline extends AbstractHandlerBehaviour implements Pipeliner {
    protected static final int PORT_TABLE = 0;
    protected static final int VLAN_TABLE = 10;
    protected static final int TMAC_TABLE = 20;
    protected static final int UNICAST_ROUTING_TABLE = 30;
    protected static final int MULTICAST_ROUTING_TABLE = 40;
    protected static final int MPLS_TABLE_0 = 23;
    protected static final int MPLS_TABLE_1 = 24;
    protected static final int BRIDGING_TABLE = 50;
    protected static final int ACL_TABLE = 60;
    protected static final int MAC_LEARNING_TABLE = 254;
    protected static final long OFPP_MAX = 0xffffff00L;

    private static final int HIGHEST_PRIORITY = 0xffff;
    protected static final int DEFAULT_PRIORITY = 0x8000;
    protected static final int LOWEST_PRIORITY = 0x0;

    private final Logger log = getLogger(getClass());
    private ServiceDirectory serviceDirectory;
    protected FlowRuleService flowRuleService;
    private CoreService coreService;
    protected GroupService groupService;
    protected FlowObjectiveStore flowObjectiveStore;
    protected DeviceId deviceId;
    protected ApplicationId driverId;
    protected DeviceService deviceService;
    protected static KryoNamespace appKryo = new KryoNamespace.Builder()
        .register(KryoNamespaces.API)
        .register(GroupKey.class)
        .register(DefaultGroupKey.class)
        .register(OfdpaNextGroup.class)
        .register(byte[].class)
        .register(ArrayDeque.class)
        .build();

    protected OFDPA2GroupHandler ofdpa2GroupHandler;

    private Set<IPCriterion> sentIpFilters = Collections.newSetFromMap(
                                               new ConcurrentHashMap<>());

    @Override
    public void init(DeviceId deviceId, PipelinerContext context) {
        this.serviceDirectory = context.directory();
        this.deviceId = deviceId;

        // Initialize OFDPA group handler
        ofdpa2GroupHandler = new OFDPA2GroupHandler();
        ofdpa2GroupHandler.init(deviceId, context);

        coreService = serviceDirectory.get(CoreService.class);
        flowRuleService = serviceDirectory.get(FlowRuleService.class);
        groupService = serviceDirectory.get(GroupService.class);
        flowObjectiveStore = context.store();
        deviceService = serviceDirectory.get(DeviceService.class);

        driverId = coreService.registerApplication(
                "org.onosproject.driver.OFDPA2Pipeline");

        initializePipeline();
    }

    protected void initializePipeline() {
        // OF-DPA does not require initializing the pipeline as it puts default
        // rules automatically in the hardware. However emulation of OFDPA in
        // software switches does require table-miss-entries.
    }

    //////////////////////////////////////
    //  Flow Objectives
    //////////////////////////////////////

    @Override
    public void filter(FilteringObjective filteringObjective) {
        if (filteringObjective.type() == FilteringObjective.Type.PERMIT) {
            processFilter(filteringObjective,
                          filteringObjective.op() == Objective.Operation.ADD,
                          filteringObjective.appId());
        } else {
            // Note that packets that don't match the PERMIT filter are
            // automatically denied. The DENY filter is used to deny packets
            // that are otherwise permitted by the PERMIT filter.
            // Use ACL table flow rules here for DENY filtering objectives
            log.debug("filter objective other than PERMIT currently not supported");
            fail(filteringObjective, ObjectiveError.UNSUPPORTED);
        }
    }

    @Override
    public void forward(ForwardingObjective fwd) {
        Collection<FlowRule> rules;
        FlowRuleOperations.Builder flowOpsBuilder = FlowRuleOperations.builder();

        rules = processForward(fwd);
        switch (fwd.op()) {
            case ADD:
                rules.stream()
                        .filter(Objects::nonNull)
                        .forEach(flowOpsBuilder::add);
                break;
            case REMOVE:
                rules.stream()
                        .filter(Objects::nonNull)
                        .forEach(flowOpsBuilder::remove);
                break;
            default:
                fail(fwd, ObjectiveError.UNKNOWN);
                log.warn("Unknown forwarding type {}", fwd.op());
        }

        flowRuleService.apply(flowOpsBuilder.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                pass(fwd);
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                fail(fwd, ObjectiveError.FLOWINSTALLATIONFAILED);
            }
        }));
    }

    @Override
    public void next(NextObjective nextObjective) {
        NextGroup nextGroup = flowObjectiveStore.getNextGroup(nextObjective.id());
        switch (nextObjective.op()) {
        case ADD:
            if (nextGroup != null) {
                log.warn("Cannot add next {} that already exists in device {}",
                         nextObjective.id(), deviceId);
                return;
            }
            log.debug("Processing NextObjective id{} in dev{} - add group",
                      nextObjective.id(), deviceId);
            ofdpa2GroupHandler.addGroup(nextObjective);
            break;
        case ADD_TO_EXISTING:
            if (nextGroup != null) {
                log.debug("Processing NextObjective id{} in dev{} - add bucket",
                          nextObjective.id(), deviceId);
                ofdpa2GroupHandler.addBucketToGroup(nextObjective, nextGroup);
            } else {
                // it is possible that group-chain has not been fully created yet
                log.debug("Waiting to add bucket to group for next-id:{} in dev:{}",
                          nextObjective.id(), deviceId);
                // by design only one pending bucket is allowed for the group
                ofdpa2GroupHandler.pendingBuckets.put(nextObjective.id(), nextObjective);
            }
            break;
        case REMOVE:
            if (nextGroup == null) {
                log.warn("Cannot remove next {} that does not exist in device {}",
                         nextObjective.id(), deviceId);
                return;
            }
            log.debug("Processing NextObjective id{}  in dev{} - remove group",
                      nextObjective.id(), deviceId);
            ofdpa2GroupHandler.removeGroup(nextObjective, nextGroup);
            break;
        case REMOVE_FROM_EXISTING:
            if (nextGroup == null) {
                log.warn("Cannot remove from next {} that does not exist in device {}",
                         nextObjective.id(), deviceId);
                return;
            }
            log.debug("Processing NextObjective id{} in dev{} - remove bucket",
                      nextObjective.id(), deviceId);
            ofdpa2GroupHandler.removeBucketFromGroup(nextObjective, nextGroup);
            break;
        default:
            log.warn("Unsupported operation {}", nextObjective.op());
        }
    }

    //////////////////////////////////////
    //  Flow handling
    //////////////////////////////////////

    /**
     * As per OFDPA 2.0 TTP, filtering of VLAN ids, MAC addresses (for routing)
     * and IP addresses configured on switch ports happen in different tables.
     * Note that IP filtering rules need to be added to the ACL table, as there
     * is no mechanism to send to controller via IP table.
     *
     * @param filt      the filtering objective
     * @param install   indicates whether to add or remove the objective
     * @param applicationId     the application that sent this objective
     */
    private void processFilter(FilteringObjective filt,
                               boolean install, ApplicationId applicationId) {
        // This driver only processes filtering criteria defined with switch
        // ports as the key
        PortCriterion portCriterion = null;
        EthCriterion ethCriterion = null;
        VlanIdCriterion vidCriterion = null;
        Collection<IPCriterion> ips = new ArrayList<IPCriterion>();
        if (!filt.key().equals(Criteria.dummy()) &&
                filt.key().type() == Criterion.Type.IN_PORT) {
            portCriterion = (PortCriterion) filt.key();
        } else {
            log.warn("No key defined in filtering objective from app: {}. Not"
                    + "processing filtering objective", applicationId);
            fail(filt, ObjectiveError.UNKNOWN);
            return;
        }
        // convert filtering conditions for switch-intfs into flowrules
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        for (Criterion criterion : filt.conditions()) {
            if (criterion.type() == Criterion.Type.ETH_DST) {
                ethCriterion = (EthCriterion) criterion;
            } else if (criterion.type() == Criterion.Type.VLAN_VID) {
                vidCriterion = (VlanIdCriterion) criterion;
            } else if (criterion.type() == Criterion.Type.IPV4_DST) {
                ips.add((IPCriterion) criterion);
            } else {
                log.error("Unsupported filter {}", criterion);
                fail(filt, ObjectiveError.UNSUPPORTED);
                return;
            }
        }

        VlanId assignedVlan = null;
        if (vidCriterion != null && vidCriterion.vlanId() == VlanId.NONE) {
            // untagged packets are assigned vlans in OF-DPA
            if (filt.meta() == null) {
                log.error("Missing metadata in filtering objective required "
                        + "for vlan assignment in dev {}", deviceId);
                fail(filt, ObjectiveError.BADPARAMS);
                return;
            }
            for (Instruction i : filt.meta().allInstructions()) {
                if (i instanceof ModVlanIdInstruction) {
                    assignedVlan = ((ModVlanIdInstruction) i).vlanId();
                }
            }
            if (assignedVlan == null) {
                log.error("Driver requires an assigned vlan-id to tag incoming "
                        + "untagged packets. Not processing vlan filters on "
                        + "device {}", deviceId);
                fail(filt, ObjectiveError.BADPARAMS);
                return;
            }
        }

        if (ethCriterion == null) {
            log.debug("filtering objective missing dstMac, cannot program TMAC table");
        } else {
            for (FlowRule tmacRule : processEthDstFilter(portCriterion, ethCriterion,
                                                         vidCriterion, assignedVlan,
                                                         applicationId)) {
                log.debug("adding MAC filtering rules in TMAC table: {} for dev: {}",
                          tmacRule, deviceId);
                ops = install ? ops.add(tmacRule) : ops.remove(tmacRule);
            }
        }

        if (ethCriterion == null || vidCriterion == null) {
            log.debug("filtering objective missing dstMac or vlan, cannot program"
                    + "Vlan Table");
        } else {
            for (FlowRule vlanRule : processVlanIdFilter(portCriterion, vidCriterion,
                                                         assignedVlan,
                                                         applicationId)) {
                log.debug("adding VLAN filtering rule in VLAN table: {} for dev: {}",
                          vlanRule, deviceId);
                ops = install ? ops.add(vlanRule) : ops.remove(vlanRule);
            }
        }

        for (IPCriterion ipaddr : ips) {
            // since we ignore port information for IP rules, and the same (gateway) IP
            // can be configured on multiple ports, we make sure that we send
            // only a single rule to the switch.
            if (!sentIpFilters.contains(ipaddr)) {
                sentIpFilters.add(ipaddr);
                log.debug("adding IP filtering rules in ACL table {} for dev: {}",
                          ipaddr, deviceId);
                TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
                TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
                selector.matchEthType(Ethernet.TYPE_IPV4);
                selector.matchIPDst(ipaddr.ip());
                treatment.setOutput(PortNumber.CONTROLLER);
                FlowRule rule = DefaultFlowRule.builder()
                        .forDevice(deviceId)
                        .withSelector(selector.build())
                        .withTreatment(treatment.build())
                        .withPriority(HIGHEST_PRIORITY)
                        .fromApp(applicationId)
                        .makePermanent()
                        .forTable(ACL_TABLE).build();
                ops = install ? ops.add(rule) : ops.remove(rule);
            }
        }

        // apply filtering flow rules
        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.info("Applied {} filtering rules in device {}",
                         ops.stages().get(0).size(), deviceId);
                pass(filt);
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.info("Failed to apply all filtering rules in dev {}", deviceId);
                fail(filt, ObjectiveError.FLOWINSTALLATIONFAILED);
            }
        }));

    }

    /**
     * Allows untagged packets into pipeline by assigning a vlan id.
     * Vlan assignment is done by the application.
     * Allows tagged packets into pipeline as per configured port-vlan info.
     *
     * @param portCriterion   port on device for which this filter is programmed
     * @param vidCriterion   vlan assigned to port, or NONE for untagged
     * @param assignedVlan   assigned vlan-id for untagged packets
     * @param applicationId  for application programming this filter
     * @return list of FlowRule for port-vlan filters
     */
    protected List<FlowRule> processVlanIdFilter(PortCriterion portCriterion,
                                                 VlanIdCriterion vidCriterion,
                                                 VlanId assignedVlan,
                                                 ApplicationId applicationId) {
        List<FlowRule> rules = new ArrayList<FlowRule>();
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
        selector.matchVlanId(vidCriterion.vlanId());
        treatment.transition(TMAC_TABLE);

        VlanId storeVlan = null;
        if (vidCriterion.vlanId() == VlanId.NONE) {
            // untagged packets are assigned vlans
            treatment.pushVlan().setVlanId(assignedVlan);
            // XXX ofdpa will require an additional vlan match on the assigned vlan
            // and it may not require the push. This is not in compliance with OF
            // standard. Waiting on what the exact flows are going to look like.
            storeVlan = assignedVlan;
        } else {
            storeVlan = vidCriterion.vlanId();
        }

        // ofdpa cannot match on ALL portnumber, so we need to use separate
        // rules for each port.
        List<PortNumber> portnums = new ArrayList<PortNumber>();
        if (portCriterion.port() == PortNumber.ALL) {
            for (Port port : deviceService.getPorts(deviceId)) {
                if (port.number().toLong() > 0 && port.number().toLong() < OFPP_MAX) {
                    portnums.add(port.number());
                }
            }
        } else {
            portnums.add(portCriterion.port());
        }

        for (PortNumber pnum : portnums) {
            // update storage
            ofdpa2GroupHandler.port2Vlan.put(pnum, storeVlan);
            Set<PortNumber> vlanPorts = ofdpa2GroupHandler.vlan2Port.get(storeVlan);
            if (vlanPorts == null) {
                vlanPorts = Collections.newSetFromMap(
                                    new ConcurrentHashMap<PortNumber, Boolean>());
                vlanPorts.add(pnum);
                ofdpa2GroupHandler.vlan2Port.put(storeVlan, vlanPorts);
            } else {
                vlanPorts.add(pnum);
            }
            // create rest of flowrule
            selector.matchInPort(pnum);
            FlowRule rule = DefaultFlowRule.builder()
                    .forDevice(deviceId)
                    .withSelector(selector.build())
                    .withTreatment(treatment.build())
                    .withPriority(DEFAULT_PRIORITY)
                    .fromApp(applicationId)
                    .makePermanent()
                    .forTable(VLAN_TABLE).build();
            rules.add(rule);
        }
        return rules;
    }

    /**
     * Allows routed packets with correct destination MAC to be directed
     * to unicast-IP routing table or MPLS forwarding table.
     *
     * @param portCriterion  port on device for which this filter is programmed
     * @param ethCriterion   dstMac of device for which is filter is programmed
     * @param vidCriterion   vlan assigned to port, or NONE for untagged
     * @param assignedVlan   assigned vlan-id for untagged packets
     * @param applicationId  for application programming this filter
     * @return list of FlowRule for port-vlan filters

     */
    protected List<FlowRule> processEthDstFilter(PortCriterion portCriterion,
                                                 EthCriterion ethCriterion,
                                                 VlanIdCriterion vidCriterion,
                                                 VlanId assignedVlan,
                                                 ApplicationId applicationId) {
        //handling untagged packets via assigned VLAN
        if (vidCriterion.vlanId() == VlanId.NONE) {
            vidCriterion = (VlanIdCriterion) Criteria.matchVlanId(assignedVlan);
        }
        // ofdpa cannot match on ALL portnumber, so we need to use separate
        // rules for each port.
        List<PortNumber> portnums = new ArrayList<PortNumber>();
        if (portCriterion.port() == PortNumber.ALL) {
            for (Port port : deviceService.getPorts(deviceId)) {
                if (port.number().toLong() > 0 && port.number().toLong() < OFPP_MAX) {
                    portnums.add(port.number());
                }
            }
        } else {
            portnums.add(portCriterion.port());
        }

        List<FlowRule> rules = new ArrayList<FlowRule>();
        for (PortNumber pnum : portnums) {
            // for unicast IP packets
            TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
            TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
            selector.matchInPort(pnum);
            selector.matchVlanId(vidCriterion.vlanId());
            selector.matchEthType(Ethernet.TYPE_IPV4);
            selector.matchEthDst(ethCriterion.mac());
            treatment.transition(UNICAST_ROUTING_TABLE);
            FlowRule rule = DefaultFlowRule.builder()
                    .forDevice(deviceId)
                    .withSelector(selector.build())
                    .withTreatment(treatment.build())
                    .withPriority(DEFAULT_PRIORITY)
                    .fromApp(applicationId)
                    .makePermanent()
                    .forTable(TMAC_TABLE).build();
            rules.add(rule);
            //for MPLS packets
            selector = DefaultTrafficSelector.builder();
            treatment = DefaultTrafficTreatment.builder();
            selector.matchInPort(pnum);
            selector.matchVlanId(vidCriterion.vlanId());
            selector.matchEthType(Ethernet.MPLS_UNICAST);
            selector.matchEthDst(ethCriterion.mac());
            treatment.transition(MPLS_TABLE_0);
            rule = DefaultFlowRule.builder()
                    .forDevice(deviceId)
                    .withSelector(selector.build())
                    .withTreatment(treatment.build())
                    .withPriority(DEFAULT_PRIORITY)
                    .fromApp(applicationId)
                    .makePermanent()
                    .forTable(TMAC_TABLE).build();
            rules.add(rule);
        }
        return rules;
    }

    private Collection<FlowRule> processForward(ForwardingObjective fwd) {
        switch (fwd.flag()) {
            case SPECIFIC:
                return processSpecific(fwd);
            case VERSATILE:
                return processVersatile(fwd);
            default:
                fail(fwd, ObjectiveError.UNKNOWN);
                log.warn("Unknown forwarding flag {}", fwd.flag());
        }
        return Collections.emptySet();
    }

    /**
     * In the OF-DPA 2.0 pipeline, versatile forwarding objectives go to the
     * ACL table.
     * @param fwd  the forwarding objective of type 'versatile'
     * @return     a collection of flow rules to be sent to the switch. An empty
     *             collection may be returned if there is a problem in processing
     *             the flow rule
     */
    private Collection<FlowRule> processVersatile(ForwardingObjective fwd) {
        log.info("Processing versatile forwarding objective");
        TrafficSelector selector = fwd.selector();

        EthTypeCriterion ethType =
                (EthTypeCriterion) selector.getCriterion(Criterion.Type.ETH_TYPE);
        if (ethType == null) {
            log.error("Versatile forwarding objective must include ethType");
            fail(fwd, ObjectiveError.BADPARAMS);
            return Collections.emptySet();
        }
        if (fwd.nextId() == null && fwd.treatment() == null) {
            log.error("Forwarding objective {} from {} must contain "
                    + "nextId or Treatment", fwd.selector(), fwd.appId());
            return Collections.emptySet();
        }
        // XXX driver does not currently do type checking as per Tables 65-67 in
        // OFDPA 2.0 spec. The only allowed treatment is a punt to the controller.
        if (fwd.treatment() != null &&
                fwd.treatment().allInstructions().size() == 1 &&
                fwd.treatment().allInstructions().get(0).type() == Instruction.Type.OUTPUT) {
            OutputInstruction o = (OutputInstruction) fwd.treatment().allInstructions().get(0);
            if (o.port() == PortNumber.CONTROLLER) {
                FlowRule.Builder ruleBuilder = DefaultFlowRule.builder()
                        .fromApp(fwd.appId())
                        .withPriority(fwd.priority())
                        .forDevice(deviceId)
                        .withSelector(fwd.selector())
                        .withTreatment(fwd.treatment())
                        .makePermanent()
                        .forTable(ACL_TABLE);
                return Collections.singletonList(ruleBuilder.build());
            } else {
                log.warn("Only allowed treatments in versatile forwarding "
                        + "objectives are punts to the controller");
                return Collections.emptySet();
            }
        }

        if (fwd.nextId() != null) {
            // XXX overide case
            log.warn("versatile objective --> next Id not yet implemeted");
        }
        return Collections.emptySet();
    }

    /**
     * In the OF-DPA 2.0 pipeline, specific forwarding refers to the IP table
     * (unicast or multicast) or the L2 table (mac + vlan) or the MPLS table.
     *
     * @param fwd the forwarding objective of type 'specific'
     * @return    a collection of flow rules. Typically there will be only one
     *            for this type of forwarding objective. An empty set may be
     *            returned if there is an issue in processing the objective.
     */
    protected Collection<FlowRule> processSpecific(ForwardingObjective fwd) {
        log.trace("Processing specific fwd objective:{} in dev:{} with next:{}",
                  fwd.id(), deviceId, fwd.nextId());
        boolean isEthTypeObj = isSupportedEthTypeObjective(fwd);
        boolean isEthDstObj = isSupportedEthDstObjective(fwd);

        if (isEthTypeObj) {
            return processEthTypeSpecific(fwd);
        } else if (isEthDstObj) {
            return processEthDstSpecific(fwd);
        } else {
            log.warn("processSpecific: Unsupported forwarding objective "
                    + "criteria fwd:{} in dev:{}", fwd.nextId(), deviceId);
            fail(fwd, ObjectiveError.UNSUPPORTED);
            return Collections.emptySet();
        }
    }

    private boolean isSupportedEthTypeObjective(ForwardingObjective fwd) {
        TrafficSelector selector = fwd.selector();
        EthTypeCriterion ethType = (EthTypeCriterion) selector
                .getCriterion(Criterion.Type.ETH_TYPE);
        return !((ethType == null) ||
                ((ethType.ethType().toShort() != Ethernet.TYPE_IPV4) &&
                        (ethType.ethType().toShort() != Ethernet.MPLS_UNICAST)));
    }

    private boolean isSupportedEthDstObjective(ForwardingObjective fwd) {
        TrafficSelector selector = fwd.selector();
        EthCriterion ethDst = (EthCriterion) selector
                .getCriterion(Criterion.Type.ETH_DST);
        VlanIdCriterion vlanId = (VlanIdCriterion) selector
                .getCriterion(Criterion.Type.VLAN_VID);
        return !(ethDst == null && vlanId == null);
    }

    /**
     * Handles forwarding rules to the IP and MPLS tables.
     *
     * @param fwd the forwarding objective
     * @return A collection of flow rules, or an empty set
     */
    protected Collection<FlowRule> processEthTypeSpecific(ForwardingObjective fwd) {
        TrafficSelector selector = fwd.selector();
        EthTypeCriterion ethType =
                (EthTypeCriterion) selector.getCriterion(Criterion.Type.ETH_TYPE);

        int forTableId;
        TrafficSelector.Builder filteredSelector = DefaultTrafficSelector.builder();
        if (ethType.ethType().toShort() == Ethernet.TYPE_IPV4) {
            filteredSelector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(((IPCriterion)
                        selector.getCriterion(Criterion.Type.IPV4_DST)).ip());
            forTableId = UNICAST_ROUTING_TABLE;
            log.debug("processing IPv4 specific forwarding objective {} -> next:{}"
                    + " in dev:{}", fwd.id(), fwd.nextId(), deviceId);
        } else {
            filteredSelector
                .matchEthType(Ethernet.MPLS_UNICAST)
                .matchMplsLabel(((MplsCriterion)
                        selector.getCriterion(Criterion.Type.MPLS_LABEL)).label());
            MplsBosCriterion bos = (MplsBosCriterion) selector
                                        .getCriterion(Criterion.Type.MPLS_BOS);
            if (bos != null) {
                filteredSelector.matchMplsBos(bos.mplsBos());
            }
            forTableId = MPLS_TABLE_1;
            log.debug("processing MPLS specific forwarding objective {} -> next:{}"
                    + " in dev {}", fwd.id(), fwd.nextId(), deviceId);
        }

        TrafficTreatment.Builder tb = DefaultTrafficTreatment.builder();
        boolean popMpls = false;
        if (fwd.treatment() != null) {
            for (Instruction i : fwd.treatment().allInstructions()) {
                /*
                 * NOTE: OF-DPA does not support immediate instruction in
                 * L3 unicast and MPLS table.
                 */
                tb.deferred().add(i);
                if (i instanceof L2ModificationInstruction &&
                    ((L2ModificationInstruction) i).subtype() == L2SubType.MPLS_POP) {
                        popMpls = true;
                }
            }
        }

        if (fwd.nextId() != null) {
            if (forTableId == MPLS_TABLE_1 && !popMpls) {
                log.warn("SR CONTINUE case cannot be handled as MPLS ECMP "
                        + "is not implemented in OF-DPA yet. Aborting this flow "
                        + "in this device {}", deviceId);
                // XXX We could convert to forwarding to a single-port, via a
                // MPLS interface, or a MPLS SWAP (with-same) but that would
                // have to be handled in the next-objective. Also the pop-mpls
                // logic used here won't work in non-BoS case.
                fail(fwd, ObjectiveError.FLOWINSTALLATIONFAILED);
                return Collections.emptySet();
            }

            NextGroup next = getGroupForNextObjective(fwd.nextId());
            if (next != null) {
                List<Deque<GroupKey>> gkeys = appKryo.deserialize(next.data());
                // we only need the top level group's key to point the flow to it
                Group group = groupService.getGroup(deviceId, gkeys.get(0).peekFirst());
                if (group == null) {
                    log.warn("Group with key:{} for next-id:{} not found in dev:{}",
                             gkeys.get(0).peekFirst(), fwd.nextId(), deviceId);
                    fail(fwd, ObjectiveError.GROUPMISSING);
                    return Collections.emptySet();
                }
                tb.deferred().group(group.id());
            }
        }
        tb.transition(ACL_TABLE);
        FlowRule.Builder ruleBuilder = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .withPriority(fwd.priority())
                .forDevice(deviceId)
                .withSelector(filteredSelector.build())
                .withTreatment(tb.build())
                .forTable(forTableId);

        if (fwd.permanent()) {
            ruleBuilder.makePermanent();
        } else {
            ruleBuilder.makeTemporary(fwd.timeout());
        }

        return Collections.singletonList(ruleBuilder.build());
    }

    /**
     * Handles forwarding rules to the L2 bridging table. Flow actions are not
     * allowed in the bridging table - instead we use L2 Interface group or
     * L2 flood group
     *
     * @param fwd the forwarding objective
     * @return A collection of flow rules, or an empty set
     */
    protected Collection<FlowRule> processEthDstSpecific(ForwardingObjective fwd) {
        List<FlowRule> rules = new ArrayList<>();

        // Build filtered selector
        TrafficSelector selector = fwd.selector();
        EthCriterion ethCriterion = (EthCriterion) selector
                .getCriterion(Criterion.Type.ETH_DST);
        VlanIdCriterion vlanIdCriterion = (VlanIdCriterion) selector
                .getCriterion(Criterion.Type.VLAN_VID);

        if (vlanIdCriterion == null) {
            log.warn("Forwarding objective for bridging requires vlan. Not "
                    + "installing fwd:{} in dev:{}", fwd.id(), deviceId);
            fail(fwd, ObjectiveError.BADPARAMS);
            return Collections.emptySet();
        }

        TrafficSelector.Builder filteredSelectorBuilder =
                DefaultTrafficSelector.builder();
        // Do not match MacAddress for subnet broadcast entry
        if (!ethCriterion.mac().equals(MacAddress.NONE)) {
            filteredSelectorBuilder.matchEthDst(ethCriterion.mac());
            log.debug("processing L2 forwarding objective:{} -> next:{} in dev:{}",
                      fwd.id(), fwd.nextId(), deviceId);
        } else {
            log.debug("processing L2 Broadcast forwarding objective:{} -> next:{} "
                    + "in dev:{} for vlan:{}",
                      fwd.id(), fwd.nextId(), deviceId, vlanIdCriterion.vlanId());
        }
        filteredSelectorBuilder.matchVlanId(vlanIdCriterion.vlanId());
        TrafficSelector filteredSelector = filteredSelectorBuilder.build();

        if (fwd.treatment() != null) {
            log.warn("Ignoring traffic treatment in fwd rule {} meant for L2 table"
                    + "for dev:{}. Expecting only nextId", fwd.id(), deviceId);
        }

        TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder();
        if (fwd.nextId() != null) {
            NextGroup next = getGroupForNextObjective(fwd.nextId());
            if (next != null) {
                List<Deque<GroupKey>> gkeys = appKryo.deserialize(next.data());
                // we only need the top level group's key to point the flow to it
                Group group = groupService.getGroup(deviceId, gkeys.get(0).peekFirst());
                if (group != null) {
                    treatmentBuilder.deferred().group(group.id());
                } else {
                    log.warn("Group with key:{} for next-id:{} not found in dev:{}",
                             gkeys.get(0).peekFirst(), fwd.nextId(), deviceId);
                    fail(fwd, ObjectiveError.GROUPMISSING);
                    return Collections.emptySet();
                }
            }
        }
        treatmentBuilder.immediate().transition(ACL_TABLE);
        TrafficTreatment filteredTreatment = treatmentBuilder.build();

        // Build bridging table entries
        FlowRule.Builder flowRuleBuilder = DefaultFlowRule.builder();
        flowRuleBuilder.fromApp(fwd.appId())
                .withPriority(fwd.priority())
                .forDevice(deviceId)
                .withSelector(filteredSelector)
                .withTreatment(filteredTreatment)
                .forTable(BRIDGING_TABLE);
        if (fwd.permanent()) {
            flowRuleBuilder.makePermanent();
        } else {
            flowRuleBuilder.makeTemporary(fwd.timeout());
        }
        rules.add(flowRuleBuilder.build());
        return rules;
    }

    protected NextGroup getGroupForNextObjective(Integer nextId) {
        NextGroup next = flowObjectiveStore.getNextGroup(nextId);
        if (next != null) {
            List<Deque<GroupKey>> gkeys = appKryo.deserialize(next.data());
            if (gkeys != null && !gkeys.isEmpty()) {
                return next;
            } else {
               log.warn("Empty next group found in FlowObjective store for "
                       + "next-id:{} in dev:{}", nextId, deviceId);
            }
        } else {
            log.warn("next-id {} not found in Flow objective store for dev:{}",
                     nextId, deviceId);
        }
        return null;
    }

    protected static void pass(Objective obj) {
        if (obj.context().isPresent()) {
            obj.context().get().onSuccess(obj);
        }
    }

    protected static void fail(Objective obj, ObjectiveError error) {
        if (obj.context().isPresent()) {
            obj.context().get().onError(obj, error);
        }
    }
}
