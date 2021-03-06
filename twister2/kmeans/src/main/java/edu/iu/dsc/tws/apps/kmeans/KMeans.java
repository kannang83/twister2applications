package edu.iu.dsc.tws.apps.kmeans;

import edu.iu.dsc.tws.api.net.Network;
import edu.iu.dsc.tws.apps.kmeans.utils.JobParameters;
import edu.iu.dsc.tws.apps.kmeans.utils.Utils;
import edu.iu.dsc.tws.common.config.Config;
import edu.iu.dsc.tws.common.controller.IWorkerController;
import edu.iu.dsc.tws.common.exceptions.TimeoutException;
import edu.iu.dsc.tws.common.worker.IPersistentVolume;
import edu.iu.dsc.tws.common.worker.IVolatileVolume;
import edu.iu.dsc.tws.common.worker.IWorker;
import edu.iu.dsc.tws.comms.api.*;
/*import edu.iu.dsc.tws.comms.core.TWSCommunication;
import edu.iu.dsc.tws.comms.core.TWSNetwork;*/
import edu.iu.dsc.tws.comms.core.TaskPlan;
//import edu.iu.dsc.tws.comms.mpi.MPIDataFlowAllReduce;
import edu.iu.dsc.tws.comms.dfw.DataFlowAllReduce;
import edu.iu.dsc.tws.comms.op.Communicator;
import edu.iu.dsc.tws.comms.op.batch.BAllReduce;

import edu.iu.dsc.tws.comms.op.functions.reduction.ReduceOperationFunction;
import edu.iu.dsc.tws.executor.comms.batch.AllReduceBatchOperation;
import edu.iu.dsc.tws.proto.jobmaster.JobMasterAPI;
//import edu.iu.dsc.tws.rsched.spi.resource.ResourcePlan;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public class KMeans implements IWorker {
  private static final Logger LOG = Logger.getLogger(KMeans.class.getName());

  private BAllReduce allReduce;

  private int id;

  private JobParameters jobParameters;

  private Map<Integer, PipelinedTask> partitionSources = new HashMap<>();

  private Map<Integer, BlockingQueue<Message>> workerMessageQueue = new HashMap<>();

  private Map<Integer, Integer> sourcesToReceiveMapping = new HashMap<>();

  private Map<Integer, Executor> executors = new HashMap<>();


  protected int workerId;

  protected Config config;

  protected TaskPlan taskPlan;

  protected TWSChannel channel;

  protected Communicator communicator;

  protected List<JobMasterAPI.WorkerInfo> workerList = null;



  @Override
  public void execute(Config config, int workerID, IWorkerController workerController, IPersistentVolume persistentVolume, IVolatileVolume volatileVolume) {
    // create the job parameters
    this.jobParameters = JobParameters.build(config);
    this.config = config;
    try {
      this.workerList = workerController.getAllWorkers();
    } catch (TimeoutException timeoutException) {
      LOG.log(Level.SEVERE, timeoutException.getMessage(), timeoutException);
      return;
    }

    // lets create the task plan
    this.taskPlan = Utils.createStageTaskPlan(config, workerID,
            jobParameters.getTaskStages(), workerList);
    // create the channel
    channel = Network.initializeChannel(config, workerController);
    // create the communicator
    communicator = new Communicator(config, channel);

    int middle = jobParameters.getTaskStages().get(0) + jobParameters.getTaskStages().get(1);
    double[][] points = null;
    double[] centers = null;
    Set<Integer> mapTasksOfExecutor = Utils.getTasksOfExecutor(id, taskPlan, jobParameters.getTaskStages(), 0);
    Set<Integer> reduceTasksOfExecutor = Utils.getTasksOfExecutor(id, taskPlan, jobParameters.getTaskStages(), 1);
    int pointsPerTask = jobParameters.getNumPoints() / (jobParameters.getContainers() * mapTasksOfExecutor.size());

    long start = System.nanoTime();
    try {
      points = PointReader.readPoints(jobParameters.getPointFile(), jobParameters.getNumPoints(),
              jobParameters.getContainers(), id, mapTasksOfExecutor.size(), jobParameters.getDimension());
      centers = PointReader.readClusters(jobParameters.getCenerFile(), jobParameters.getDimension(), jobParameters.getK());
    } catch (IOException e) {
      throw new RuntimeException("File read error", e);
    }
    LOG.info(String.format("%d reading time %d", id, (System.nanoTime() - start) / 1000000));

    Set<Integer> sources = new HashSet<>();
    Integer noOfSourceTasks = jobParameters.getTaskStages().get(0);
    for (int i = 0; i < noOfSourceTasks; i++) {
      sources.add(i);
    }

    Set<Integer> dests = new HashSet<>();
    int noOfDestTasks = jobParameters.getTaskStages().get(1);
    for (int i = 0; i < noOfDestTasks; i++) {
      dests.add(i + sources.size());
    }

    Map<String, Object> newCfg = new HashMap<>();
    LOG.log(Level.FINE, "Setting up firstPartition dataflow operation");
    try {
      List<Integer> sourceTasksOfExecutor = new ArrayList<>(mapTasksOfExecutor);
      List<Integer> workerTasksOfExecutor = new ArrayList<>(reduceTasksOfExecutor);

      for (int k = 0; k < sourceTasksOfExecutor.size(); k++) {
        int sourceTask = sourceTasksOfExecutor.get(k);
        int workerTask = workerTasksOfExecutor.get(k);

        sourcesToReceiveMapping.put(sourceTask, workerTask);
      }

      for (int k = 0; k < sourceTasksOfExecutor.size(); k++) {
        int sourceTask = sourceTasksOfExecutor.get(k);

        PipelinedTask source = new PipelinedTask(points[k], centers, sourceTasksOfExecutor.get(k),
                jobParameters.getDimension(), jobParameters.getIterations(), pointsPerTask);
        partitionSources.put(sourceTask, source);
      }

      allReduce = new BAllReduce(communicator, taskPlan, sources, dests, new ReduceOperationFunction(Op.SUM, MessageType.DOUBLE), new FinalSingularReceiver(),
              MessageType.INTEGER);


      for (int k = 0; k < sourceTasksOfExecutor.size(); k++) {
        int sourceTask = sourceTasksOfExecutor.get(k);
        int targetTask = sourcesToReceiveMapping.get(sourceTask);

        PipelinedTask source = partitionSources.get(sourceTask);
        source.setbAllReduce(allReduce);

        // the map thread where datacols is produced
        Executor executor = new Executor(source, workerMessageQueue.get(targetTask), sourceTask);
        executors.put(sourceTask, executor);

        Thread mapThread = new Thread(executor);
        mapThread.start();
      }


    }catch(Exception e) {
      System.out.println(e);
    }

  }


  /*@Override
  public void init(Config cfg, int containerId, ResourcePlan plan) {
    LOG.log(Level.FINE, "Starting the example with container id: " + plan.getThisId());

    this.jobParameters = JobParameters.build(cfg);
    this.id = containerId;

    // lets create the task plan
    TaskPlan taskPlan = Utils.createReduceTaskPlan(cfg, plan, jobParameters.getTaskStages());
    LOG.log(Level.FINE,"Task plan: " + taskPlan);
    //first get the communication config file
    TWSNetwork network = new TWSNetwork(cfg, taskPlan);

    TWSCommunication channel = network.getDataFlowTWSCommunication();

    Set<Integer> sources = new HashSet<>();
    Integer noOfSourceTasks = jobParameters.getTaskStages().get(0);
    for (int i = 0; i < noOfSourceTasks; i++) {
      sources.add(i);
    }

    Set<Integer> dests = new HashSet<>();
    int noOfDestTasks = jobParameters.getTaskStages().get(1);
    for (int i = 0; i < noOfDestTasks; i++) {
      dests.add(i + sources.size());
    }

    int middle = jobParameters.getTaskStages().get(0) + jobParameters.getTaskStages().get(1);
    double[][] points = null;
    double[] centers = null;
    Set<Integer> mapTasksOfExecutor = Utils.getTasksOfExecutor(id, taskPlan, jobParameters.getTaskStages(), 0);
    Set<Integer> reduceTasksOfExecutor = Utils.getTasksOfExecutor(id, taskPlan, jobParameters.getTaskStages(), 1);
    int pointsPerTask = jobParameters.getNumPoints() / (jobParameters.getContainers() * mapTasksOfExecutor.size());

    long start = System.nanoTime();
    try {
      points = PointReader.readPoints(jobParameters.getPointFile(), jobParameters.getNumPoints(),
          jobParameters.getContainers(), id, mapTasksOfExecutor.size(), jobParameters.getDimension());
      centers = PointReader.readClusters(jobParameters.getCenerFile(), jobParameters.getDimension(), jobParameters.getK());
    } catch (IOException e) {
      throw new RuntimeException("File read error", e);
    }
    LOG.info(String.format("%d reading time %d", id, (System.nanoTime() - start) / 1000000));

    Map<String, Object> newCfg = new HashMap<>();
    LOG.log(Level.FINE,"Setting up firstPartition dataflow operation");
    try {
      List<Integer> sourceTasksOfExecutor = new ArrayList<>(mapTasksOfExecutor);
      List<Integer> workerTasksOfExecutor = new ArrayList<>(reduceTasksOfExecutor);

      for (int k = 0; k < sourceTasksOfExecutor.size(); k++) {
        int sourceTask = sourceTasksOfExecutor.get(k);
        int workerTask = workerTasksOfExecutor.get(k);

        sourcesToReceiveMapping.put(sourceTask, workerTask);
      }

      for (int k = 0; k < sourceTasksOfExecutor.size(); k++) {
        int sourceTask = sourceTasksOfExecutor.get(k);

        PipelinedTask source = new PipelinedTask(points[k], centers, sourceTasksOfExecutor.get(k),
            jobParameters.getDimension(), jobParameters.getIterations(), pointsPerTask);
        partitionSources.put(sourceTask, source);
      }

      reduceOperation = (MPIDataFlowAllReduce) channel.allReduce(newCfg, MessageType.DOUBLE, 0, 1, sources,
          dests, middle, new IdentityFunction(), new FinalReduceReceiver(), true);

      for (int k = 0; k < sourceTasksOfExecutor.size(); k++) {
        int sourceTask = sourceTasksOfExecutor.get(k);
        int targetTask = sourcesToReceiveMapping.get(sourceTask);

        PipelinedTask source = partitionSources.get(sourceTask);
        source.setAllReduce(reduceOperation);

        // the map thread where datacols is produced
        Executor executor = new Executor(source, workerMessageQueue.get(targetTask), sourceTask);
        executors.put(sourceTask, executor);

        Thread mapThread = new Thread(executor);
        mapThread.start();
      }

      LOG.fine(String.format("%d source to receive %s", id, sourcesToReceiveMapping));

      // we need to progress the communication
      while (true) {
        try {
          // progress the channel
          channel.progress();
          reduceOperation.progress();
        } catch (Throwable t) {
          t.printStackTrace();
        }
      }
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }*/

    class FinalSingularReceiver implements SingularReceiver {
      @Override
      public void init(Config cfg, Set<Integer> expectedIds) {
      }

      @Override
      public boolean receive(int target, Object object) {


        return true;
      }
    }


    class IdentityFunction implements ReduceFunction {
      @Override
      public void init(Config cfg, DataFlowOperation op, Map<Integer, List<Integer>> expectedIds) {
      }

      @Override
      public Object reduce(Object t1, Object t2) {
        double[] data1 = (double[]) t1;
        double[] data2 = (double[]) t2;
        double[] data3 = new double[data1.length];
        for (int i = 0; i < data1.length; i++) {
          data3[i] = data1[i] + data2[i];
        }
        return data3;
      }
    }

  }

