import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Process {
    String processName;
    int arrivalTimeSlot;
    int executionDuration;
    int processpriority;
    int quantumTime;
    int remainingExecutiontime;
    int remainingquantumTime;
    int waitingTime;
    int turnaroundTime;
    int completionTime;
    List<Integer> quantumTimeHistory;

    public Process(String processName, int arrivalTimeSlot, int executionDuration, int processpriority, int quantumTime) {
        this.processName = processName;
        this.arrivalTimeSlot = arrivalTimeSlot;
        this.executionDuration = executionDuration;
        this.processpriority = processpriority;
        this.quantumTime = quantumTime;
        this.remainingExecutiontime = executionDuration;
        this.remainingquantumTime = quantumTime;
        this.quantumTimeHistory = new ArrayList<>();
        this.quantumTimeHistory.add(quantumTime);
    }

    public Process(Process p) {
        this.processName = p.processName;
        this.arrivalTimeSlot = p.arrivalTimeSlot;
        this.executionDuration = p.executionDuration;
        this.processpriority = p.processpriority;
        this.quantumTime = p.quantumTime;
        this.remainingExecutiontime = p.remainingExecutiontime;
        this.remainingquantumTime = p.remainingquantumTime;
        this.waitingTime = p.waitingTime;
        this.turnaroundTime = p.turnaroundTime;
        this.completionTime = p.completionTime;
        this.quantumTimeHistory = new ArrayList<>(p.quantumTimeHistory);
    }
}

class executionPeriod {
    String processName;
    int startTimeslot;
    int endTimeslot;

    public executionPeriod(String processName, int startTimeslot, int endTimeslot) {
        this.processName = processName;
        this.startTimeslot = startTimeslot;
        this.endTimeslot = endTimeslot;
    }
}

public class AGScheduler {
    private List<Process> totalProcesses;
    private List<executionPeriod> executionSequence;
    public List<String> executionOrderNames = new ArrayList<>(); // Helper for testing
    private int contextSwitch;

    public AGScheduler(List<Process> totalProcesses, int contextSwitch) {
        this.totalProcesses = new ArrayList<>();
        for (Process p : totalProcesses) {
            this.totalProcesses.add(new Process(p));
        }
        this.executionSequence = new ArrayList<>();
        this.contextSwitch = contextSwitch;
    }

    public void simulate() {
        Queue<Process> readyQueue = new LinkedList<>();
        List<Process> allProcesses = new ArrayList<>(totalProcesses);
        int currentTime = 0;
        Process runningProcess = null;
        int quantumTimeUsed = 0;
        
        // Helper to track order for validation
        String lastRecordedName = null;

        allProcesses.sort(Comparator.comparingInt(p -> p.arrivalTimeSlot));

        while (!allProcesses.isEmpty() || !readyQueue.isEmpty() || runningProcess != null) {
            Iterator<Process> iterator = allProcesses.iterator();
            while (iterator.hasNext()) {
                Process p = iterator.next();
                if (p.arrivalTimeSlot <= currentTime) {
                    readyQueue.offer(p);
                    iterator.remove();
                }
            }

            if (runningProcess == null && !readyQueue.isEmpty()) {
                if (currentTime > 0 && executionSequence.size() > 0) {
                    currentTime += contextSwitch;
                }
                runningProcess = readyQueue.poll();
                quantumTimeUsed = 0;
                runningProcess.remainingquantumTime = runningProcess.quantumTime;
                
                // Track execution order
                if(lastRecordedName == null || !lastRecordedName.equals(runningProcess.processName)) {
                    executionOrderNames.add(runningProcess.processName);
                    lastRecordedName = runningProcess.processName;
                }
            }

            if (runningProcess != null) {
                int startTime = currentTime;
                
                // Track execution order (in case of immediate switch back)
                if(lastRecordedName == null || !lastRecordedName.equals(runningProcess.processName)) {
                    executionOrderNames.add(runningProcess.processName);
                    lastRecordedName = runningProcess.processName;
                }

                int phase1Limit = (int) Math.ceil(runningProcess.quantumTime * 0.25);
                int phase2Limit = (int) Math.ceil(runningProcess.quantumTime * 0.5);

                if (quantumTimeUsed < phase1Limit) {
                    // PHASE 1: FCFS
                    int timeInPhase = Math.min(
                            runningProcess.remainingExecutiontime,
                            Math.min(runningProcess.remainingquantumTime, phase1Limit - quantumTimeUsed)
                    );
                    currentTime += timeInPhase;
                    runningProcess.remainingExecutiontime -= timeInPhase;
                    runningProcess.remainingquantumTime -= timeInPhase;
                    quantumTimeUsed += timeInPhase;
                    executionSequence.add(new executionPeriod(runningProcess.processName, startTime, currentTime));

                } else if (quantumTimeUsed < phase2Limit) {
                    // PHASE 2: Priority
                    int timeInPhase = Math.min(
                            runningProcess.remainingExecutiontime,
                            Math.min(runningProcess.remainingquantumTime, phase2Limit - quantumTimeUsed)
                    );
                    currentTime += timeInPhase;
                    runningProcess.remainingExecutiontime -= timeInPhase;
                    runningProcess.remainingquantumTime -= timeInPhase;
                    quantumTimeUsed += timeInPhase;
                    executionSequence.add(new executionPeriod(runningProcess.processName, startTime, currentTime));

                } else {
                    // PHASE 3: SJF
                    Process shorterJob = null;
                    for (Process p : readyQueue) {
                        if (p.remainingExecutiontime < runningProcess.remainingExecutiontime) {
                            if (shorterJob == null || p.remainingExecutiontime < shorterJob.remainingExecutiontime) {
                                shorterJob = p;
                            }
                        }
                    }

                    if (shorterJob != null) {
                        int timeExecuted = 1;
                        currentTime += timeExecuted;
                        runningProcess.remainingExecutiontime -= timeExecuted;
                        runningProcess.remainingquantumTime -= timeExecuted;
                        quantumTimeUsed += timeExecuted;
                        executionSequence.add(new executionPeriod(runningProcess.processName, startTime, currentTime));

                        int remainingQ = runningProcess.remainingquantumTime;
                        runningProcess.quantumTime += remainingQ;
                        runningProcess.quantumTimeHistory.add(runningProcess.quantumTime);
                        readyQueue.offer(runningProcess);

                        runningProcess = null;
                        quantumTimeUsed = 0;
                        continue;
                    }

                    int timeInPhase = Math.min(runningProcess.remainingExecutiontime, runningProcess.remainingquantumTime);
                    currentTime += timeInPhase;
                    runningProcess.remainingExecutiontime -= timeInPhase;
                    runningProcess.remainingquantumTime -= timeInPhase;
                    quantumTimeUsed += timeInPhase;
                    executionSequence.add(new executionPeriod(runningProcess.processName, startTime, currentTime));
                }

                if (runningProcess.remainingExecutiontime == 0) {
                    runningProcess.completionTime = currentTime;
                    runningProcess.quantumTime = 0;
                    runningProcess.quantumTimeHistory.add(0);
                    runningProcess.turnaroundTime = runningProcess.completionTime - runningProcess.arrivalTimeSlot;
                    runningProcess.waitingTime = runningProcess.turnaroundTime - runningProcess.executionDuration;
                    runningProcess = null;
                    quantumTimeUsed = 0;
                } else if (runningProcess.remainingquantumTime == 0) {
                    runningProcess.quantumTime += 2;
                    runningProcess.quantumTimeHistory.add(runningProcess.quantumTime);
                    readyQueue.offer(runningProcess);
                    runningProcess = null;
                    quantumTimeUsed = 0;
                }

            } else {
                if (!allProcesses.isEmpty()) {
                    currentTime = allProcesses.get(0).arrivalTimeSlot;
                } else {
                    break;
                }
            }
        }
    }

    public double getAverageWaitingTime() {
        return totalProcesses.stream().mapToInt(p -> p.waitingTime).average().orElse(0.0);
    }

    public double getAverageTurnaroundTime() {
        return totalProcesses.stream().mapToInt(p -> p.turnaroundTime).average().orElse(0.0);
    }

    public void printResults() {
        System.out.println("\n=== AG Scheduling Results ===\n");
        System.out.println("Processes Execution Order: " + String.join(", ", executionOrderNames));
        
        System.out.println("\n--- Process Details ---");
        for (Process p : totalProcesses) {
            System.out.println("\nProcess: " + p.processName);
            System.out.println("  Waiting Time: " + p.waitingTime);
            System.out.println("  Turnaround Time: " + p.turnaroundTime);
            System.out.print("  Quantum History: ");
            for (int i = 0; i < p.quantumTimeHistory.size(); i++) {
                System.out.print(p.quantumTimeHistory.get(i));
                if (i < p.quantumTimeHistory.size() - 1) System.out.print(" -> ");
            }
            System.out.println();
        }

        System.out.println("\n--- Statistics ---");
        System.out.printf("Average Waiting Time: %.2f\n", getAverageWaitingTime());
        System.out.printf("Average Turnaround Time: %.2f\n", getAverageTurnaroundTime());
    }

    // ==========================================
    // MAIN METHOD: Handles both Interactive & Test Modes
    // ==========================================
    public static void main(String[] args) {
        
        // 1. TEST MODE (If arguments are provided)
        if (args.length > 0) {
            runTestMode(args);
            return;
        }

        // 2. INTERACTIVE MODE (Default)
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter number of processes: ");
        int n = scanner.nextInt();

        System.out.print("Enter context switching time: ");
        int contextSwitch = scanner.nextInt();

        List<Process> processes = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            System.out.println("\nProcess " + (i + 1) + ":");
            System.out.print("  Name: ");
            String name = scanner.next();
            System.out.print("  Arrival Time: ");
            int arrival = scanner.nextInt();
            System.out.print("  Burst Time: ");
            int burst = scanner.nextInt();
            System.out.print("  Priority: ");
            int priority = scanner.nextInt();
            System.out.print("  Quantum: ");
            int quantum = scanner.nextInt();

            processes.add(new Process(name, arrival, burst, priority, quantum));
        }

        AGScheduler scheduler = new AGScheduler(processes, contextSwitch);
        scheduler.simulate();
        scheduler.printResults();
        scanner.close();
    }

    // ==========================================
    // JSON PARSING & TESTING LOGIC (Hidden from User)
    // ==========================================
    private static void runTestMode(String[] args) {
        List<String> testFiles = new ArrayList<>();
        // Auto-detect if arg is a directory
        File f = new File(args[0]);
        if (f.isDirectory()) {
            File[] files = f.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) for (File file : files) testFiles.add(file.getAbsolutePath());
        } else {
            testFiles.add(args[0]);
        }

        for (String testFile : testFiles) {
            try {
                System.out.println("Testing: " + testFile);
                String content = new String(Files.readAllBytes(Paths.get(testFile)));
                
                // Parse Context Switch
                int contextSwitch = 0;
                Matcher csMatcher = Pattern.compile("\"contextSwitch\":\\s*(\\d+)").matcher(content);
                if (csMatcher.find()) contextSwitch = Integer.parseInt(csMatcher.group(1));

                // Parse Processes
                List<Process> processes = new ArrayList<>();
                Pattern procPattern = Pattern.compile("\\{\"name\":\\s*\"(P\\d+)\",\\s*\"arrival\":\\s*(\\d+),\\s*\"burst\":\\s*(\\d+),\\s*\"priority\":\\s*(\\d+),\\s*\"quantum\":\\s*(\\d+)\\}");
                Matcher procMatcher = procPattern.matcher(content);
                while (procMatcher.find()) {
                    processes.add(new Process(
                            procMatcher.group(1),
                            Integer.parseInt(procMatcher.group(2)),
                            Integer.parseInt(procMatcher.group(3)),
                            Integer.parseInt(procMatcher.group(4)),
                            Integer.parseInt(procMatcher.group(5))
                    ));
                }

                // Run Scheduler
                AGScheduler scheduler = new AGScheduler(processes, contextSwitch);
                scheduler.simulate();

                // Validate
                double actualAvgWT = scheduler.getAverageWaitingTime();
                double actualAvgTAT = scheduler.getAverageTurnaroundTime();
                
                // Parse Expected
                double expectedAvgWT = 0, expectedAvgTAT = 0;
                Matcher avgWTMatcher = Pattern.compile("\"averageWaitingTime\":\\s*([\\d.]+)").matcher(content);
                if (avgWTMatcher.find()) expectedAvgWT = Double.parseDouble(avgWTMatcher.group(1));
                Matcher avgTATMatcher = Pattern.compile("\"averageTurnaroundTime\":\\s*([\\d.]+)").matcher(content);
                if (avgTATMatcher.find()) expectedAvgTAT = Double.parseDouble(avgTATMatcher.group(1));

                if (Math.abs(actualAvgWT - expectedAvgWT) < 0.1 && Math.abs(actualAvgTAT - expectedAvgTAT) < 0.1) {
                    System.out.println("STATUS: PASSED");
                } else {
                    System.out.println("STATUS: FAILED (Expected WT: " + expectedAvgWT + ", Got: " + actualAvgWT + ")");
                }

            } catch (Exception e) {
                System.err.println("Error processing file: " + e.getMessage());
            }
        }
    }
}