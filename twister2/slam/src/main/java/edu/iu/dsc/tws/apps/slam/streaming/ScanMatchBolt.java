package edu.iu.dsc.tws.apps.slam.streaming;

import edu.iu.dsc.tws.apps.slam.core.GFSConfiguration;
import edu.iu.dsc.tws.apps.slam.core.app.LaserScan;
import edu.iu.dsc.tws.apps.slam.core.gridfastsalm.Particle;
import edu.iu.dsc.tws.apps.slam.core.sensor.RangeReading;
import edu.iu.dsc.tws.apps.slam.core.utils.DoubleOrientedPoint;
import edu.iu.dsc.tws.apps.slam.streaming.msgs.*;
import edu.iu.dsc.tws.apps.slam.streaming.stats.GCCounter;
import edu.iu.dsc.tws.apps.slam.streaming.stats.GCInformation;
import com.esotericsoftware.kryo.Kryo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This bolt is responsible for calculating for the assigned particles,
 * and then distribute the particles to re-sampler
 */
public class ScanMatchBolt {
    private Logger LOG = LoggerFactory.getLogger(ScanMatchBolt.class);

    private DScanMatcher gfsp = null;

    private Kryo kryoPVReading;

    private Kryo kryoMapReading;

    private Kryo kryoAssignReading;

    private Kryo kryoLaserReading;

    private Kryo kryoBestParticle;

    private Kryo kryoReady;

    private Kryo kryoMapWriter;

    private volatile MatchState state = MatchState.INIT;

    private int expectingParticleMaps = 0;
    private int expectingParticleValues = 0;

    private ParticleAssignments assignments = null;

    private ExecutorService executor;

    private List<Kryo> kryoMapWriters = new ArrayList<Kryo>();

    private Map conf;

    private boolean gotFirstScan  = false;

    private Set<Integer> tempActiveParticles = new HashSet<Integer>();

    private Lock lock = new ReentrantLock();

    private int taskId;

    private int totalTasks;

    private enum MatchState {
        INIT,
        WAITING_FOR_READING,
        COMPUTING_INIT_READINGS,
        WAITING_FOR_PARTICLE_ASSIGNMENTS,
        WAITING_FOR_NEW_PARTICLES,
        COMPUTING_NEW_PARTICLES,
    }

    public void prepare(Map map) {
        executor = Executors.newScheduledThreadPool(8);
        this.conf = map;
        this.kryoAssignReading = new Kryo();
        this.kryoPVReading = new Kryo();
        this.kryoMapReading = new Kryo();
        this.kryoLaserReading = new Kryo();
        this.kryoBestParticle = new Kryo();
        this.kryoReady = new Kryo();
        this.kryoMapWriter = new Kryo();

        Utils.registerClasses(kryoAssignReading);
        Utils.registerClasses(kryoPVReading);
        Utils.registerClasses(kryoMapReading);
        Utils.registerClasses(kryoLaserReading);
        Utils.registerClasses(kryoBestParticle);
        Utils.registerClasses(kryoReady);
        Utils.registerClasses(kryoMapWriter);

        // read the configuration of the scanmatcher from topology.xml
        int totalTasks = 0;
        try {
            for (int i = 0; i < totalTasks; i++) {
                Kryo k = new Kryo();
                Utils.registerClasses(k);
                kryoMapWriters.add(k);
            }
        } catch (Exception e) {
            LOG.error("failed to create the message assignmentReceiver", e);
            throw new RuntimeException(e);
        }

        // init the bolt
        init(map);
    }

    private void init(Map conf) {
        state = MatchState.INIT;
        LOG.info("taskId {}: Initializing scan match bolt", taskId);
        sensorId = "hello";

      // use the configuration to create the scanmatcher
        GFSConfiguration cfg = ConfigurationBuilder.getConfiguration(conf);
        if (conf.get(Constants.ARGS_PARTICLES) != null) {
            cfg.setNoOfParticles(((Long) conf.get(Constants.ARGS_PARTICLES)).intValue());
        }
        // set the initial particles
        int noOfParticles = computeParticlesForTask(cfg, totalTasks, taskId);

        int previousTotal = 0;
        for (int i = 0; i < taskId; i++) {
            previousTotal += computeParticlesForTask(cfg, totalTasks, i);
        }

        List<Integer> activeParticles = new ArrayList<Integer>();
        for (int i = 0; i < noOfParticles; i++) {
            activeParticles.add(i + previousTotal);
        }

        gfsp = ProcessorFactory.createScanMatcher(cfg, activeParticles);

        LOG.info("taskId {}: no of active particles {}", taskId, activeParticles.size());
        LOG.info("taskId {}: active particles at initialization {}", taskId, printActiveParticles());
        state = MatchState.WAITING_FOR_READING;
        gotFirstScan = false;
    }

    private String printActiveParticles() {
        String s = "";
        for (int i : gfsp.getActiveParticles()) {
            s += i + " ";
        }
        return s;
    }

    /**
     * Given a task id compute the no of particles
     * @param cfg configuration
     * @param totalTasks total tasks
     * @param taskId task id
     * @return no of particles for this task
     */
    private int computeParticlesForTask(GFSConfiguration cfg, int totalTasks, int taskId) {
        int noOfParticles = cfg.getNoOfParticles() / totalTasks;
        int remainder = cfg.getNoOfParticles() % totalTasks;
        if (remainder > 0 &&  taskId < remainder) {
            noOfParticles += 1;
        }
        return noOfParticles;
    }

    private double[] plainReading;
    private RangeReading rangeReading;
    private LaserScan scan;
    private Object time;
    private Object sensorId;

    // these are used to gather statistics
    private long lastComputationBeginTime;
    private long lastEmitTime;
    private Trace currentTrace;
    private long assignmentReceiveTime;


    public void execute(Tuple tuple) {
        String stream = tuple.getSourceStreamId();
        // if we receive a control message init and return
        if (stream.equals(Constants.Fields.CONTROL_STREAM)) {
            init(conf);
            return;
        }

        lock.lock();
        try {
            if (stream.equals(Constants.Fields.ASSIGNMENT_STREAM)) {
                byte[] body = (byte[]) tuple.getValueByField(Constants.Fields.ASSIGNMENT_FILED);
                ParticleAssignments assignments = (ParticleAssignments) Utils.deSerialize(kryoAssignReading, body, ParticleAssignments.class);
                handleAssignment(taskId, assignments);
                return;
            }
        } finally {
            lock.unlock();
        }

        if (state != MatchState.WAITING_FOR_READING) {
            // we ack the tuple and discard it, because we cannot process the tuple at this moment
            return;
        }

        // check weather this tuple came between the last computation time. if so discard it
        lastComputationBeginTime = System.currentTimeMillis();
        GCCounter gcCounter = GCInformation.getInstance().addCounter();

        time = tuple.getValueByField(Constants.Fields.TIME_FIELD);

        byte traceBytes[] = (byte []) tuple.getValueByField(Constants.Fields.TRACE_FIELD);
        Trace trace = (Trace) Utils.deSerialize(kryoLaserReading, traceBytes, Trace.class);
        Object val = tuple.getValueByField(Constants.Fields.BODY);
        if (!(val instanceof byte [])) {
            throw new IllegalArgumentException("The laser scan should be of type byte[]");
        }

        lock.lock();
        try {
            scan = (LaserScan) Utils.deSerialize(kryoLaserReading, (byte[]) val, LaserScan.class);
        } catch (Exception e) {
            LOG.error("Failed to deserialize laser scan", e);
        } finally {
            lock.unlock();
        }

        if (!gotFirstScan) {
            LOG.info("Initializing the particles with pose: {}", scan.getPose());
            gfsp.initParticles(scan.getPose());
            gotFirstScan = true;
        }

        RangeReading reading;

        Double[] ranges = edu.iu.dsc.tws.apps.slam.core.utils.Utils.getRanges(scan, scan.getAngleIncrement());
        reading = new RangeReading(scan.getRanges().size(), ranges, scan.getTimestamp());
        reading.setPose(scan.getPose());
        double []laserAngles = edu.iu.dsc.tws.apps.slam.core.utils.Utils.getLaserAngles(scan.getRanges().size(),
                scan.getAngleIncrement(), scan.getAngleMin());
        gfsp.setLaserParams(reading.size(), laserAngles, new DoubleOrientedPoint(0, 0, 0));

        rangeReading = reading;
        plainReading = new double[scan.getRanges().size()];
        for (int i = 0; i < scan.getRanges().size(); i++) {
            plainReading[i] = reading.get(i);
        }

        // now we will start the computation
        LOG.info("taskId {}: Changing state to COMPUTING_INIT_READINGS", taskId);
        state = MatchState.COMPUTING_INIT_READINGS;
        if (!gfsp.processScan(reading, 0)) {
            changeToReady(taskId);
            return;
        }

        // now distribute the particles to the bolts
        List<Integer> activeParticles = gfsp.getActiveParticles();
        List<Particle> particles = gfsp.getParticles();

        LOG.info("taskId {}: execute: changing state to WAITING_FOR_PARTICLE_ASSIGNMENTS_AND_NEW_PARTICLES", taskId);
        state = MatchState.WAITING_FOR_PARTICLE_ASSIGNMENTS;

        // after the computation we are going to create a new object without the map and nodes in particle and emit it
        // these will be used by the re sampler to re sample particles
        LOG.debug("taskId {}: no of active particles {}", taskId, activeParticles.size());
        List<ParticleValue> pvs = new ArrayList<ParticleValue>();
        for (int i = 0; i < activeParticles.size(); i++) {
            int index = activeParticles.get(i);
            Particle particle = particles.get(index);
            ParticleValue particleValue = Utils.createParticleValue(particle, taskId, index, totalTasks);
            pvs.add(particleValue);

        }
        List<Object> emit = new ArrayList<Object>();
        emit.add(pvs);
        emit.add(scan);
        emit.add(sensorId);
        emit.add(time);
        lastEmitTime = System.currentTimeMillis();
        long gcTime = gcCounter.getFullGCTime() + gcCounter.getYoungGCTime();
        long timeSpent = lastEmitTime - lastComputationBeginTime;
        lock.lock();
        try {
            trace.getSmp().put(taskId, timeSpent);
            trace.getGcTimes().put(taskId, gcTime);
            GCInformation.getInstance().removeCounter(gcCounter);
            LOG.debug("taskId {}: emitting to resample ", taskId);
            emit.add(trace);
            // todo
//            outputCollector.emit(Constants.Fields.PARTICLE_STREAM, emit);
        } finally {
            lock.unlock();
        }
    }

    /** We are going to keep the particles maps until we get an assignment */
    private List<ParticleMaps> particleMapses = new ArrayList<ParticleMaps>();

    private class MapHandler {
        public void onMessage(Message message) {
            byte []body = message.getBody();
//            LOG.debug("taskId {}: Received particle map", taskId);
            LOG.info("taskId {}: Received maps: {}", taskId, (System.currentTimeMillis() - assignmentReceiveTime));
            ParticleMapsList pm = (ParticleMapsList) Utils.deSerialize(kryoMapReading, body, ParticleMapsList.class);
            lock.lock();
            try {
                if (state == MatchState.WAITING_FOR_NEW_PARTICLES) {
                    try {
                        // first we need to determine the expected new maps for this particle
                        // first check weather particleMapses not empty. If not empty handle those first
                        processReceivedMaps("map");
                        processReceivedValues("map");
                        // now go through the assignments and send them to the bolts directly
                        List<ParticleMaps> list = pm.getParticleMapsArrayList();
                        for (ParticleMaps p : list) {
                            if (p.getSerializedMap() != null) {
                                TransferMap map = (TransferMap) Utils.deSerialize(kryoMapReading, p.getSerializedMap(), TransferMap.class);
                                p.setMap(map);
                            }
                            addMaps(p, "map");
                        }

                        // we have received all the particles we need to do the processing after resampling
                        postProcessingAfterReceiveAll(taskId, "map", true, assignments.getBestParticle());
                    } catch (Exception e) {
                        LOG.error("taskId {}: Failed to deserialize map", taskId, e);
                    }
                } else if (state == MatchState.WAITING_FOR_PARTICLE_ASSIGNMENTS) {
                    // because we haven't received the assignments yet, we will keep the values temporaly in this list
                    LOG.debug("taskId {}: Adding map state {}", taskId, state);
                    List<ParticleMaps> list = pm.getParticleMapsArrayList();
                    for (ParticleMaps p : list) {
                        particleMapses.add(p);
                    }
                    if (state != MatchState.WAITING_FOR_PARTICLE_ASSIGNMENTS) {
                        postProcessingAfterReceiveAll(taskId, "adding map", true, assignments.getBestParticle());
                    }
                } else {
                    LOG.error("taskId {}: Received message when we are in an unexpected state {}", taskId, state);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void postProcessingAfterReceiveAll(int taskId, String origin, boolean resampled, int best) {
        if (state == MatchState.WAITING_FOR_PARTICLE_ASSIGNMENTS) {
            lock.lock();
            try {
                LOG.info("taskId {}: {} Changing state to WAITING_FOR_NEW_PARTICLES", origin, taskId);
                state = MatchState.WAITING_FOR_NEW_PARTICLES;
            } finally {
                lock.unlock();
            }
        }

        processReceivedMaps(origin);
        processReceivedValues(origin);

        if (expectingParticleMaps == 0 && expectingParticleValues == 0) {
            state = MatchState.COMPUTING_NEW_PARTICLES;
            LOG.info("taskId {}: Map Handler Changing state to COMPUTING_NEW_PARTICLES", taskId);
            long ppTime = System.currentTimeMillis();
            if (resampled) {
                // add the temp to active particles
                LOG.info("taskId {}: Clearing active particles", taskId);
                gfsp.clearActiveParticles();
                gfsp.getActiveParticles().addAll(tempActiveParticles);
                tempActiveParticles.clear();

                gfsp.processAfterReSampling(plainReading);
            } else {
                gfsp.postProcessingWithoutReSampling(plainReading, rangeReading);

            }
            ppTime = System.currentTimeMillis() - ppTime;

            // find the particle with the best index
            // find the particle with the best index
            if (gfsp.getActiveParticles().contains(best)) {
                emitParticleForMap(best, ppTime);
            }

            this.assignments = null;
            int size = gfsp.getParticles().size();
            for (int i = 0; i < size; i++) {
                if (!gfsp.getActiveParticles().contains(i)) {
                    Particle p = gfsp.getParticles().get(i);
                    p.setMap(null);
                    p.setNode(null);
                }
            }

            changeToReady(taskId);
            LOG.info("taskId {}: Changing state to WAITING_FOR_READING", taskId);
        }
    }


    private void changeToReady(int taskId) {
        state = MatchState.WAITING_FOR_READING;
        Ready ready = new Ready(taskId);
        byte []readyBody = Utils.serialize(kryoReady, ready);

        Message m = new Message(readyBody);
        try {
            // todo
//            readySender.send(m, Constants.Messages.READY_ROUTING_KEY);
        } catch (Exception e) {
            String msg = "Error sending the ready message";
            LOG.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    private void processReceivedMaps(String origin) {
        lock.lock();
        try {
            if (!particleMapses.isEmpty()) {
                for (ParticleMaps existingPm : particleMapses) {
                    if (existingPm.getSerializedMap() != null) {
                        TransferMap map = (TransferMap) Utils.deSerialize(kryoMapReading, existingPm.getSerializedMap(), TransferMap.class);
                        existingPm.setMap(map);
                    }
                    addMaps(existingPm, origin);
                }
                // after handling the temp values, we'll clear the buffer
                particleMapses.clear();
            }
        } finally {
            lock.unlock();
        }
    }

    private void emitParticleForMap(int index, long ppTime) {
        Particle best = gfsp.getParticles().get(index);
        List<Object> emit = new ArrayList<Object>();

        currentTrace.setSmar(System.currentTimeMillis() - assignmentReceiveTime);
        currentTrace.setSm(System.currentTimeMillis() - lastComputationBeginTime);
        currentTrace.setSmaPP(ppTime);

        List<Object> emitValue = new ArrayList<Object>();
        emitValue.add(Utils.serialize(kryoBestParticle, currentTrace));
        emitValue.add(sensorId);
        emitValue.add(time);
        lock.lock();
        try {
            // todo
            // outputCollector.emit(Constants.Fields.BEST_PARTICLE_STREAM, emitValue);
        } finally {
            lock.unlock();
        }
    }

    private boolean assignmentExists(int task, int index, List<ParticleAssignment> assignmentList) {
        for (ParticleAssignment assignment : assignmentList) {
            if (assignment.getNewTask() == task && assignment.getNewIndex() == index) {
                return true;
            }
        }
        return true;
    }

    private class ParticleAssignmentHandler {
        public void onMessage(Message message) {
            byte []body = message.getBody();
            lock.lock();
            try {
                if (state == MatchState.WAITING_FOR_PARTICLE_ASSIGNMENTS) {
                    try {
                        LOG.debug("taskId {}: Received particle assignment", taskId);
                        ParticleAssignments assignments = (ParticleAssignments) Utils.deSerialize(kryoAssignReading, body, ParticleAssignments.class);
                        handleAssignment(taskId, assignments);
                    } catch (Exception e) {
                        LOG.error("taskId {}: Failed to deserialize assignment", taskId, e);
                    }
                } else {
                    LOG.error("Received message when we are in an unexpected state {}", state);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void handleAssignment(int taskId, ParticleAssignments assignments) {
        currentTrace = assignments.getTrace();
        assignmentReceiveTime = System.currentTimeMillis();
        LOG.debug("taskId {}: Best particle index {}", taskId, assignments.getBestParticle());
        // if we have resampled ditributed the assignments
        if (assignments.isReSampled()) {
            // now go through the assignments and send them to the bolts directly
            computeExpectedParticles(assignments);
            distributeAssignments(assignments);
            // LOG.info("taskId {}: Clearing active particles", taskId);
            // gfsp.clearActiveParticles();

            // we are going to keep the assignemtns so that we can check the receiving particles
            this.assignments = assignments;
            processReceivedValues("assign");
            processReceivedMaps("assign-maps");
            postProcessingAfterReceiveAll(taskId, "assign-all", true, assignments.getBestParticle());
        } else {
            // we are going to keep the assignemtns so that we can check the receiving particles
            this.assignments = assignments;
            expectingParticleValues = 0;
            expectingParticleMaps = 0;
            postProcessingAfterReceiveAll(taskId, "assign", false, assignments.getBestParticle());
        }
    }

    private void computeExpectedParticles(ParticleAssignments assignments) {
        List<ParticleAssignment> assignmentList = assignments.getAssignments();
        // we set them to 0 as a fallback method, these should be set to 0 automatically
        expectingParticleValues = 0;
        expectingParticleMaps = 0;
        for (int i = 0; i < assignmentList.size(); i++) {
            ParticleAssignment assignment = assignmentList.get(i);
            if (assignment.getNewTask() == taskId) {
                expectingParticleValues++;
                // we only expects maps from other bolt tasks
                if (assignment.getPreviousTask() != taskId) {
                    expectingParticleMaps++;
                }
            }
        }
        LOG.debug("taskId {}: expectingParticleValues: {} expectingParticleMaps: {}", taskId,
                expectingParticleValues, expectingParticleMaps);
    }

    private void distributeAssignments(ParticleAssignments assignments) {
        List<ParticleAssignment> assignmentList = assignments.getAssignments();
        final Map<Integer, ParticleMapsList> values = new HashMap<Integer, ParticleMapsList>();
        final Map<Integer, byte []> tempMaps = new HashMap<Integer, byte []>();

        for (int i = 0; i < assignmentList.size(); i++) {
            ParticleAssignment assignment = assignmentList.get(i);
            if (assignment.getPreviousTask() == taskId) {
                int previousIndex = assignment.getPreviousIndex();
                if (gfsp.getActiveParticles().contains(previousIndex)) {
                    // send the particle over rabbitmq if this is a different task
                    if (assignment.getNewTask() != taskId) {
                        if (!tempMaps.containsKey(previousIndex)) {
                            LOG.info("taskId {}: Start creating transfer maps: {}", taskId, (System.currentTimeMillis() - assignmentReceiveTime));
                            Particle p = gfsp.getParticles().get(previousIndex);
                            // create a new ParticleMaps
                            TransferMap transferMap = Utils.createTransferMap(p.getMap());

                            byte[] b = Utils.serialize(kryoMapWriter, transferMap);
                            tempMaps.put(previousIndex, b);
                            LOG.info("taskId {}: Created transfer maps: {}", taskId, (System.currentTimeMillis() - assignmentReceiveTime));
                        }
                    }
                } else {
                    LOG.error("taskId {}: The particle {} is not in this bolt's active list, something is wrong", taskId,
                            assignment.getPreviousIndex());
                }
            }
        }

        for (int i = 0; i < assignmentList.size(); i++) {
            ParticleAssignment assignment = assignmentList.get(i);
            if (assignment.getPreviousTask() == taskId) {
                int previousIndex = assignment.getPreviousIndex();
                if (gfsp.getActiveParticles().contains(previousIndex)) {
                    // send the particle over rabbitmq if this is a different task
                    if (assignment.getNewTask() != taskId) {

                        // create a new ParticleMaps
                        Particle p = gfsp.getParticles().get(previousIndex);
                        // create a new ParticleMaps
                        ParticleMaps particleMaps = new ParticleMaps(tempMaps.get(previousIndex),
                                assignment.getNewIndex(), assignment.getNewTask(), Utils.createNodeListFromNodeTree(p.getNode()));

//                        ParticleMaps particleMaps = tempMaps.get(previousIndex);

                        ParticleMapsList list;
                        if (values.containsKey(assignment.getNewTask())) {
                            list = values.get(assignment.getNewTask());
                        } else {
                            list = new ParticleMapsList();
                            values.put(assignment.getNewTask(), list);
                        }
                        list.addParticleMap(particleMaps);
                    } else {
                        // add the previous particles map to the new particles map
                        int newIndex = assignment.getNewIndex();
                        int prevIndex = assignment.getPreviousIndex();
                        Particle p = gfsp.getParticles().get(newIndex);
                        Particle pOld = gfsp.getParticles().get(prevIndex);

                        p.setMap(pOld.getMap());
                        p.setNode(pOld.getNode());

                        tempActiveParticles.add(newIndex);
                        // gfsp.getActiveParticles().add(newIndex);
                        // add the new particle index
                        // gfsp.addActiveParticle(newIndex);
                    }
                } else {
                    LOG.error("taskId {}: The particle {} is not in this bolt's active list, something is wrong", taskId,
                            assignment.getPreviousIndex());
                }
            }
        }

        final Semaphore semaphore = new Semaphore(0);
        int noOfSend = values.size();
        for (final Map.Entry<Integer, ParticleMapsList> listEntry : values.entrySet()) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    LOG.info("taskId {}: Serializing maps: {}", taskId, (System.currentTimeMillis() - assignmentReceiveTime));
                    Kryo k = kryoMapWriters.get(listEntry.getKey());
                    byte[] b = Utils.serialize(k, listEntry.getValue());
                    Message message = new Message(b);
                    LOG.debug("Sending particle map to {}", listEntry.getKey());
                    LOG.info("taskId {}: Sending maps: {}", taskId, (System.currentTimeMillis() - assignmentReceiveTime));
                    // RabbitMQSender particleSender = particleSenders.get(listEntry.getKey());
                    LOG.info("taskId {}: Sent maps: {}", taskId, (System.currentTimeMillis() - assignmentReceiveTime));
                    lock.lock();
                    try {
                        // todo
                        // particleSender.send(message, Constants.Messages.PARTICLE_MAP_ROUTING_KEY + "_" + listEntry.getKey());
                    } catch (Exception e) {
                        LOG.error("taskId {}: Failed to send the new particle map", taskId, e);
                    } finally {
                        lock.unlock();
                    }
                    semaphore.release();
                }
            });
        }

        for (int i = 0; i < noOfSend; i++) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private List<ParticleValue> particleValues = new ArrayList<ParticleValue>();

    private class ParticleValueHandler {
        public void onMessage(Message message) {
            byte []body = message.getBody();
            LOG.info("taskId {}: Received values: {}", taskId, (System.currentTimeMillis() - assignmentReceiveTime));
            ParticleValues pvs = (ParticleValues) Utils.deSerialize(kryoPVReading, body, ParticleValues.class);
            LOG.debug("taskId {}: Received particle value", taskId);
            lock.lock();
            try {
                if (state == MatchState.WAITING_FOR_NEW_PARTICLES) {
                    try {
                        // first we need to determine the expected new maps for this particle
                        // first check weather particleMapses not empty. If not empty handle those first
                        processReceivedValues("value");
                        processReceivedMaps("value");
                        // now go through the assignments and send them to the bolts directly
                        for (ParticleValue pv : pvs.getParticleValues()) {
                            addParticle(pv, "value");
                        }

                        // we have received all the particles we need to do the processing after resampling
                        postProcessingAfterReceiveAll(taskId, "assign", true, assignments.getBestParticle());
                    } catch (Exception e) {
                        LOG.error("taskId {}: Failed to deserialize assignment", taskId, e);
                    }
                } else if (state == MatchState.WAITING_FOR_PARTICLE_ASSIGNMENTS) {
                    // first we need to determine the expected new maps for this particle
                    // because we haven't received the assignments yet, we will keep the values temporaly in this list
                    for (ParticleValue pv : pvs.getParticleValues()) {
                        particleValues.add(pv);
                    }
                    if (state != MatchState.WAITING_FOR_PARTICLE_ASSIGNMENTS) {
                        postProcessingAfterReceiveAll(taskId, "adding values", true, assignments.getBestParticle());
                    }
                } else {
                    LOG.error("taskId {}: Received message when we are in an unexpected state {}", taskId, state);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void processReceivedValues(String origin) {
        lock.lock();
        try {
            if (!particleValues.isEmpty()) {
                for (ParticleValue existingPm : particleValues) {
                    addParticle(existingPm, origin);
                }
                particleValues.clear();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * The particle values calculated after the resampling
     * @param value values
     */
    private void addParticle(ParticleValue value, String origin) {
        List<ParticleAssignment> assignmentList = assignments.getAssignments();
        boolean found = assignmentExists(value.getTaskId(), value.getIndex(), assignmentList);

        if (!found) {
            String msg = "taskId " + taskId + ": We got a particle that doesn't belong here";
            LOG.error(msg);
            throw new RuntimeException(msg);
        }

        int newIndex = value.getIndex();
        Particle p = gfsp.getParticles().get(newIndex);

        // populate particle using particle values
        Utils.createParticle(value, p, false);

        // gfsp.getActiveParticles().add(newIndex);
        // add the new particle index
        // gfsp.addActiveParticle(newIndex);
        tempActiveParticles.add(newIndex);

        // we have received one particle
        expectingParticleValues--;
        LOG.debug("taskId {}: Expecting particle values {} origin {}", taskId, expectingParticleValues, origin);
    }



    /**
     * Add a new partcle maps and node tree to the particle
     * @param particleMaps the map and node tree
     */
    private void addMaps(ParticleMaps particleMaps, String origin) {
        List<ParticleAssignment> assignmentList = assignments.getAssignments();
        boolean found = assignmentExists(particleMaps.getTask(), particleMaps.getIndex(), assignmentList);

        if (!found) {
            String msg = "taskId " + taskId + ": We got a particle that doesn't belong here";
            LOG.error(msg);
            throw new RuntimeException(msg);
        }

        int newIndex = particleMaps.getIndex();
        Particle p = gfsp.getParticles().get(newIndex);

        p.setMap(Utils.createGMap(particleMaps.getMap()));
        p.setNode(Utils.createNodeFromList(particleMaps.getNodes()));
        // add the new particle index
        // gfsp.addActiveParticle(newIndex);
        tempActiveParticles.add(newIndex);

        // we have received one particle
        expectingParticleMaps--;
        LOG.debug("taskId {}: Expecting particle maps {} origin {}", taskId, expectingParticleMaps, origin);
    }
}