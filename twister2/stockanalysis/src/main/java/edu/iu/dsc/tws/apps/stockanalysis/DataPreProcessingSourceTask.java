package edu.iu.dsc.tws.apps.stockanalysis;

import edu.iu.dsc.tws.api.config.Context;
import edu.iu.dsc.tws.api.task.nodes.BaseSource;
import edu.iu.dsc.tws.apps.stockanalysis.utils.CleanMetric;
import edu.iu.dsc.tws.apps.stockanalysis.utils.Record;
import edu.iu.dsc.tws.apps.stockanalysis.utils.Utils;
import edu.iu.dsc.tws.apps.stockanalysis.utils.VectorPoint;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

public class DataPreProcessingSourceTask extends BaseSource {
    private static final Logger LOG = Logger.getLogger(DataPreProcessingSourceTask.class.getName());

    private String dataInputFile;
    private String vectorDirectory;
    private int numberOfDays;
    private Date startDate;
    private Date endDate;
    private int mode;

    private Map<Integer, VectorPoint> currentPoints = new HashMap<Integer, VectorPoint>();
    private TreeMap<String, List<Date>> dates = new TreeMap<String, List<Date>>();
    private Map<String, CleanMetric> metrics = new HashMap<String, CleanMetric>();

    public DataPreProcessingSourceTask(String datainputfile, String vectordirectory,
                                       String numberofdays, String startdate,
                                       String enddate, String mode) {
        this.dataInputFile = datainputfile;
        this.vectorDirectory = vectordirectory;
        this.numberOfDays = Integer.parseInt(numberofdays);
        this.startDate = Utils.parseDateString(startdate);
        this.endDate = Utils.parseDateString(enddate);
        this.mode = Integer.parseInt(mode);
    }

    @Override
    public void execute() {
        LOG.info("Task Id:\t" + context.taskId() + "\ttask index:\t" + context.taskIndex());
        File inFolder = new File(this.dataInputFile);
        TreeMap<String, List<Date>> allDates = Utils.genDates(startDate, endDate, mode);

        for (String dateString : allDates.keySet()) {
            LOG.fine(dateString + " ");
        }

        // create the out directory
        Utils.createDirectory(vectorDirectory);
        this.dates = allDates;

        // now go through the file and figure out the dates that should be considered
        Map<String, Map<Date, Integer>> datesList = findDates(this.dataInputFile);
        for (Map.Entry<String, List<Date>> ed : this.dates.entrySet()) {
            Date start = ed.getValue().get(0);
            Date end = ed.getValue().get(1);
            LOG.fine("start and end data:" + start + "\t" + end);
            LOG.info("key:" + ed.getKey() + "\t" + datesList.get(ed.getKey()).size());
            processFile(inFolder, start, end, ed.getKey(), datesList.get(ed.getKey()));
        }
        try {
            //context.write(Context.TWISTER2_DIRECT_EDGE, currentPoints);
            Thread.sleep(50000);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted Exception Occured");
        }
    }

    public Map<String, Map<Date, Integer>> findDates(String inFile) {

        FileReader input = null;
        // a map of datestring -> map <date string, index>
        Map<String, Map<Date, Integer>> outDates = new HashMap<String, Map<Date, Integer>>();
        Map<String, Set<Date>> tempDates = new HashMap<String, Set<Date>>();

        // initialize temp dates
        for (String dateRange : this.dates.keySet()) {
            tempDates.put(dateRange, new TreeSet<Date>());
        }

        try {
            input = new FileReader(inFile);
            BufferedReader bufRead = new BufferedReader(input);
            Record record;
            while ((record = Utils.parseFile(bufRead, null, false)) != null) {
                // check what date this record belongs to
                for (Map.Entry<String, List<Date>> ed : this.dates.entrySet()) {
                    Date start = ed.getValue().get(0);
                    Date end = ed.getValue().get(1);
                    if (isDateWithing(start, end, record.getDate())) {
                        Set<Date> tempDateList = tempDates.get(ed.getKey());
                        tempDateList.add(record.getDate());
                    }
                }
            }

            for (Map.Entry<String, Set<Date>> ed : tempDates.entrySet()) {
                Set<Date> datesSet = ed.getValue();
                int i = 0;
                Map<Date, Integer> dateIntegerMap = new HashMap<Date, Integer>();
                for (Date d : datesSet) {
                    dateIntegerMap.put(d, i);
                    i++;
                }
                outDates.put(ed.getKey(), dateIntegerMap);
            }
        } catch (FileNotFoundException e) {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignore) {
                }
            }
        }

        for (Map.Entry<String, Set<Date>> ed : tempDates.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (Date d : ed.getValue()) {
                sb.append(Utils.formatter.format(d)).append(" ");
            }
            LOG.info(ed.getKey() + ":" + sb.toString());
        }
        return outDates;
    }

    private int vectorCounter = 0;

    /**
     * Process a stock file and generate vectors for a month or year period
     */
    private Map<Integer, VectorPoint> processFile(File inFile, Date startDate, Date endDate, String outFile,
                                                  Map<Date, Integer> datesList) {
        LOG.info("Task Index:" + context.taskIndex() + "\tInput file:" + inFile + "\tstartdate:" + startDate
                + "\tendDate:" + endDate);
        BufferedWriter bufWriter = null;
        BufferedReader bufRead = null;
        LOG.info("Calc: " + outFile + Utils.formatter.format(startDate) + ":" + Utils.formatter.format(endDate));
        int size = -1;
        vectorCounter = 0;
        int noOfDays = datesList.size();
        String outFileName = vectorDirectory + "/" + outFile + ".csv";
        int capCount = 0;
        CleanMetric metric = this.metrics.get(outFileName);
        if (metric == null) {
            metric = new CleanMetric();
            this.metrics.put(outFileName, metric);
        }

        try {
            FileReader input = new FileReader(inFile);
            FileOutputStream fos = new FileOutputStream(new File(outFileName));
            bufWriter = new BufferedWriter(new OutputStreamWriter(fos));
            bufRead = new BufferedReader(input);

            Record record;
            int count = 0;
            int fullCount = 0;
            double totalCap = 0;
            int splitCount = 0;
            while ((record = Utils.parseFile(bufRead, null, false)) != null) {
                // not a record we are interested in
                if (!isDateWithing(startDate, endDate, record.getDate())) {
                    continue;
                }
                count++;
                int key = record.getSymbol();
                if (record.getFactorToAdjPrice() > 0) {
                    splitCount++;
                }
                // check weather we already have the vector seen
                VectorPoint point = currentPoints.get(key);
                if (point == null) {
                    point = new VectorPoint(key, noOfDays, true);
                    currentPoints.put(key, point);
                }

                // figure out the index
                int index = datesList.get(record.getDate());
                if (!point.add(record.getPrice(), record.getFactorToAdjPrice(), record.getFactorToAdjVolume(), metric, index)) {
                    metric.dupRecords++;
                    LOG.fine("dup: " + record.serialize());
                }
                point.addCap(record.getVolume() * record.getPrice());

                if (point.noOfElements() == size) {
                    fullCount++;
                }
                // sort the already seen symbols and determine how many days are there in this period
                // we take the highest number as the number of days
                if (currentPoints.size() > 2000 && size == -1) {
                    List<Integer> pointSizes = new ArrayList<Integer>();
                    for (VectorPoint v : currentPoints.values()) {
                        pointSizes.add(v.noOfElements());
                    }
                    size = mostCommon(pointSizes);
                    LOG.info("Number of stocks per period: " + size);
                }

                // now write the current vectors, also make sure we have the size determined correctly
                if (currentPoints.size() > 1000 && size != -1 && fullCount > 750) {
                    LOG.fine("Processed: " + count);
                    totalCap += writeVectors(bufWriter, noOfDays, metric);
                    capCount++;
                    fullCount = 0;
                }
            }
            LOG.info("Size: " + size);
            LOG.info("Split count: " + inFile.getName() + " = " + splitCount);

            // write the rest of the vectors in the map after finish reading the file
            totalCap += writeVectors(bufWriter, size, metric);
            capCount++;

            //write the constant vector at the end
            /*VectorPoint v = new VectorPoint(0, noOfDays, true);
            v.addCap(totalCap);
            //LOG.info("%%%% Vector Point:%%%%" + v.getTotalCap());
            bufWriter.write(v.serialize());
            bufWriter.newLine();

            v = new VectorPoint(1, noOfDays, true);
            v.addCap(totalCap);
            //LOG.info("%%%% Vector Point:%%%%" + v.serialize());
            bufWriter.write(v.serialize());
            bufWriter.newLine();

            v = new VectorPoint(2, noOfDays, true);
            v.addCap(totalCap);
            //LOG.info("%%%% Vector Point:%%%%" + v.serialize());
            bufWriter.write(v.serialize());
            bufWriter.newLine();

            v = new VectorPoint(3, noOfDays, true);
            v.addCap(totalCap);
            //LOG.info("%%%% Vector Point:%%%%" + v.serialize());
            bufWriter.write(v.serialize());
            bufWriter.newLine();

            v = new VectorPoint(4, noOfDays, true);
            v.addCap(totalCap);
            //LOG.info("%%%% Vector Point:%%%%" + v.serialize());
            bufWriter.write(v.serialize());
            bufWriter.newLine();*/

            LOG.info("Total stocks: " + vectorCounter + " bad stocks: " + currentPoints.size());
            metric.stocksWithIncorrectDays = currentPoints.size();
            LOG.info("Metrics for file: " + outFileName + " " + metric.serialize());
            currentPoints.clear();
            return currentPoints;
        } catch (IOException e) {
            throw new RuntimeException("Failed to open the file", e);
        } finally {
            try {
                if (bufWriter != null) {
                    bufWriter.close();
                }
                if (bufRead != null) {
                    bufRead.close();
                }
            } catch (IOException ignore) {
            }
        }
    }

    private double writeVectors(BufferedWriter bufWriter, int size, CleanMetric metric) throws IOException {
        double capSum = 0;
        int count = 0;
        LOG.info("Context Value:" + context.taskName() + "\tCurrent Points Size:" + currentPoints.size());
        context.write(Context.TWISTER2_DIRECT_EDGE, currentPoints);
        for (Iterator<Map.Entry<Integer, VectorPoint>> it = currentPoints.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, VectorPoint> entry = it.next();
            VectorPoint v = entry.getValue();
            if (v.noOfElements() == size) {
                metric.totalStocks++;

                if (!v.cleanVector(metric)) {
                    metric.invalidStocks++;
                    it.remove();
                    continue;
                }

                String sv = v.serialize();
                // if many points are missing, this can return null
                if (sv != null) {
                    capSum += v.getTotalCap();
                    count++;
                    bufWriter.write(sv);
                    bufWriter.newLine();

                    // remove it from map
                    vectorCounter++;
                    metric.writtenStocks++;
                } else {
                    metric.invalidStocks++;
                }
                it.remove();
            } else {
                metric.lenghtWrong++;
            }
        }
        return capSum;
    }

    private boolean isDateWithing(Date start, Date end, Date compare) {
        if (compare == null) {
            System.out.println("Comapre null*****************");
        }
        return (compare.equals(start) || compare.after(start)) && compare.before(end);
    }

    public static <T> T mostCommon(List<T> list) {
        Map<T, Integer> map = new HashMap<T, Integer>();
        for (T t : list) {
            Integer val = map.get(t);
            map.put(t, val == null ? 1 : val + 1);
        }
        Map.Entry<T, Integer> max = null;
        for (Map.Entry<T, Integer> e : map.entrySet()) {
            if (max == null || e.getValue() > max.getValue())
                max = e;
        }
        return max.getKey();
    }
}