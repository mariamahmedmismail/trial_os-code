import java.util.*;

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

    boolean addedToQueue = false;                 // IMPORTANT (reference logic)
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

        this.addedToQueue = p.addedToQueue;
        this.quantumTimeHistory = new ArrayList<>(p.quantumTimeHistory);
    }

    public void reset() {
        this.remainingExecutiontime = this.executionDuration;
        this.remainingquantumTime = this.quantumTime;
        this.waitingTime = 0;
        this.turnaroundTime = 0;
        this.completionTime = 0;
        this.addedToQueue = false;

        // keep only initial quantum in history
        this.quantumTimeHistory = new ArrayList<>();
        this.quantumTimeHistory.add(this.quantumTime);
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

    enum PickMode { FCFS, PRIORITY, SJF }

    private final List<Process> totalProcesses;
    private final List<executionPeriod> executionSequence;
    private final int contextSwitch;

    public AGScheduler(List<Process> totalProcesses, int contextSwitch) {
        this.totalProcesses = new ArrayList<>();
        for (Process p : totalProcesses) this.totalProcesses.add(new Process(p));
        this.executionSequence = new ArrayList<>();
        this.contextSwitch = contextSwitch;
    }

    // ===== reference addArrivals =====
    private void addArrivals(List<Process> processes, Queue<Process> readyQueue, int currentTime) {
        for (Process p : processes) {
            if (!p.addedToQueue && p.arrivalTimeSlot <= currentTime) {
                readyQueue.add(p);
                p.addedToQueue = true;
            }
        }
    }

    // ===== record execution order (no duplicates) =====
    private void recordOrder(List<String> order, Process p) {
        if (order.isEmpty() || !order.get(order.size() - 1).equals(p.processName)) {
            order.add(p.processName);
        }
    }

    // ===== timeline record (merge consecutive same process) =====
    private void recordTimeline(String name, int start, int end) {
        if (!executionSequence.isEmpty()) {
            executionPeriod last = executionSequence.get(executionSequence.size() - 1);
            if (last.processName.equals(name) && last.endTimeslot == start) {
                last.endTimeslot = end;
                return;
            }
        }
        executionSequence.add(new executionPeriod(name, start, end));
    }

    public void simulate() {

        // reset
        for (Process p : totalProcesses) p.reset();

        Queue<Process> readyQueue = new LinkedList<>();
        List<String> executionOrder = new ArrayList<>();

        PickMode nextPick = PickMode.FCFS;
        int currentTime = 0;
        int completed = 0;

        while (completed < totalProcesses.size()) {

            addArrivals(totalProcesses, readyQueue, currentTime);

            if (readyQueue.isEmpty()) {
                currentTime++;
                continue;
            }

            // pick current
            Process current;
            if (nextPick == PickMode.FCFS) {
                current = readyQueue.poll();
            } else {
                Process best = null;

                if (nextPick == PickMode.PRIORITY) {
                    int bestPr = Integer.MAX_VALUE;
                    for (Process p : readyQueue) {
                        if (p.processpriority < bestPr) {
                            bestPr = p.processpriority;
                            best = p;
                        }
                    }
                } else { // SJF
                    int bestRem = Integer.MAX_VALUE;
                    for (Process p : readyQueue) {
                        if (p.remainingExecutiontime < bestRem) {
                            bestRem = p.remainingExecutiontime;
                            best = p;
                        }
                    }
                }

                current = best;
                readyQueue.remove(best);
                nextPick = PickMode.FCFS;
            }

            // context switch when swapping to a new running segment
            if (!executionSequence.isEmpty()) currentTime += contextSwitch;

            // reset remaining quantum for this turn
            current.remainingquantumTime = current.quantumTime;

            recordOrder(executionOrder, current);

            // ======================
            // Phase 1: 25% FCFS
            // ======================
            int q1 = (int) Math.ceil(0.25 * current.quantumTime);
            int c1 = 0;

            while (c1 < q1 && current.remainingExecutiontime > 0 && current.remainingquantumTime > 0) {
                int start = currentTime;

                current.remainingExecutiontime--;
                current.remainingquantumTime--;
                currentTime++;
                c1++;

                recordTimeline(current.processName, start, currentTime);
                addArrivals(totalProcesses, readyQueue, currentTime);
            }

            // Case IV finished
            if (current.remainingExecutiontime == 0) {
                current.completionTime = currentTime;
                current.quantumTime = 0;
                current.quantumTimeHistory.add(0);
                completed++;
                continue;
            }

            // Case I quantum ended
            if (current.remainingquantumTime == 0) {
                current.quantumTime += 2;
                current.quantumTimeHistory.add(current.quantumTime);
                readyQueue.add(current);
                continue;
            }

            // ======================
            // Phase 2: PRIORITY CHECK ONLY
            // ======================
            Process bestPriority = null;
            int bestPr = Integer.MAX_VALUE;
            for (Process p : readyQueue) {
                if (p.processpriority < bestPr) {
                    bestPr = p.processpriority;
                    bestPriority = p;
                }
            }

            // Case II (IMPORTANT FIX):
            // add ceil(remainingQ/2) and DO NOT immediately switch
            if (bestPriority != null && bestPriority.processpriority < current.processpriority) {
                int addQ = (int) Math.ceil(current.remainingquantumTime / 2.0);
                current.quantumTime += addQ;
                current.quantumTimeHistory.add(current.quantumTime);

                readyQueue.add(current);
                nextPick = PickMode.PRIORITY;
                continue;
            }

            // ======================
            // Phase 2: execute another 25%
            // ======================
            int q2 = (int) Math.ceil(0.25 * current.quantumTime);
            int c2 = 0;

            while (c2 < q2 && current.remainingExecutiontime > 0 && current.remainingquantumTime > 0) {
                int start = currentTime;

                current.remainingExecutiontime--;
                current.remainingquantumTime--;
                currentTime++;
                c2++;

                recordTimeline(current.processName, start, currentTime);
                addArrivals(totalProcesses, readyQueue, currentTime);
            }

            // Case IV finished
            if (current.remainingExecutiontime == 0) {
                current.completionTime = currentTime;
                current.quantumTime = 0;
                current.quantumTimeHistory.add(0);
                completed++;
                continue;
            }

            // Case I quantum ended
            if (current.remainingquantumTime == 0) {
                current.quantumTime += 2;
                current.quantumTimeHistory.add(current.quantumTime);
                readyQueue.add(current);
                continue;
            }

            // ======================
            // Phase 3: SJF preemptive
            // ======================
            while (current.remainingExecutiontime > 0 && current.remainingquantumTime > 0) {
                addArrivals(totalProcesses, readyQueue, currentTime);

                Process bestSJF = null;
                int bestRem = Integer.MAX_VALUE;
                for (Process p : readyQueue) {
                    if (p.remainingExecutiontime < bestRem) {
                        bestRem = p.remainingExecutiontime;
                        bestSJF = p;
                    }
                }

                // Case III (correct):
                // add ALL remaining quantum and DO NOT immediately switch
                if (bestSJF != null && bestSJF.remainingExecutiontime < current.remainingExecutiontime) {
                    current.quantumTime += current.remainingquantumTime;
                    current.quantumTimeHistory.add(current.quantumTime);

                    readyQueue.add(current);
                    nextPick = PickMode.SJF;
                    break;
                }

                // execute 1 tick
                int start = currentTime;

                current.remainingExecutiontime--;
                current.remainingquantumTime--;
                currentTime++;

                recordTimeline(current.processName, start, currentTime);
            }

            if (current.remainingExecutiontime == 0) {
                current.completionTime = currentTime;
                current.quantumTime = 0;
                current.quantumTimeHistory.add(0);
                completed++;
            } else if (current.remainingquantumTime == 0) {
                current.quantumTime += 2;
                current.quantumTimeHistory.add(current.quantumTime);
                readyQueue.add(current);
            }
        }

        // compute WT/TAT
        for (Process p : totalProcesses) {
            p.turnaroundTime = p.completionTime - p.arrivalTimeSlot;
            p.waitingTime = p.turnaroundTime - p.executionDuration;
        }
    }

    public void printResults() {
        System.out.println("\n=== AG Scheduling Results ===\n");

        System.out.println("Execution Order (from timeline segments):");
        List<String> order = new ArrayList<>();
        for (executionPeriod e : executionSequence) {
            if (order.isEmpty() || !order.get(order.size() - 1).equals(e.processName)) {
                order.add(e.processName);
            }
        }
        System.out.println(order);

        System.out.println("\nExecution Timeline:");
        for (executionPeriod e : executionSequence) {
            System.out.println(e.processName + " (Time " + e.startTimeslot + " - " + e.endTimeslot + ")");
        }

        System.out.println("\n--- Process Details ---");
        for (Process p : totalProcesses) {
            System.out.println("\nProcess: " + p.processName);
            System.out.println("  Waiting Time: " + p.waitingTime);
            System.out.println("  Turnaround Time: " + p.turnaroundTime);
            System.out.println("  Quantum History: " + p.quantumTimeHistory);
        }

        double avgWT = totalProcesses.stream().mapToInt(x -> x.waitingTime).average().orElse(0.0);
        double avgTAT = totalProcesses.stream().mapToInt(x -> x.turnaroundTime).average().orElse(0.0);

        System.out.println("\n--- Statistics ---");
        System.out.printf("Average Waiting Time: %.2f\n", avgWT);
        System.out.printf("Average Turnaround Time: %.2f\n", avgTAT);
    }

    public static void main(String[] args) {
        int contextSwitch = 0;

        // Test Case 1
        System.out.println("\n================ TEST CASE 1 ================");
        List<Process> tc1 = Arrays.asList(
                new Process("P1", 0, 17, 4, 7),
                new Process("P2", 2, 6, 7, 9),
                new Process("P3", 5, 11, 3, 4),
                new Process("P4", 15, 4, 6, 6)
        );
        AGScheduler s1 = new AGScheduler(tc1, contextSwitch);
        s1.simulate();
        s1.printResults();

        // Test Case 2
        System.out.println("\n================ TEST CASE 2 ================");
        List<Process> tc2 = Arrays.asList(
                new Process("P1", 0, 10, 3, 4),
                new Process("P2", 0, 8, 1, 5),
                new Process("P3", 0, 12, 2, 6),
                new Process("P4", 0, 6, 4, 3),
                new Process("P5", 0, 9, 5, 4)
        );
        AGScheduler s2 = new AGScheduler(tc2, contextSwitch);
        s2.simulate();
        s2.printResults();

        // Test Case 3
        System.out.println("\n================ TEST CASE 3 ================");
        List<Process> tc3 = Arrays.asList(
                new Process("P1", 0, 20, 5, 8),
                new Process("P2", 3, 4, 3, 6),
                new Process("P3", 6, 3, 4, 5),
                new Process("P4", 10, 2, 2, 4),
                new Process("P5", 15, 5, 6, 7),
                new Process("P6", 20, 6, 1, 3)
        );
        AGScheduler s3 = new AGScheduler(tc3, contextSwitch);
        s3.simulate();
        s3.printResults();
        System.out.println("\n================ TEST CASE 4 ================");
    List<Process> tc4 = Arrays.asList(
        new Process("P1", 0, 3, 2, 10),
        new Process("P2", 2, 4, 3, 12),
        new Process("P3", 5, 2, 1, 8),
        new Process("P4", 8, 5, 4, 15),
        new Process("P5", 12, 3, 5, 9)
    );

    AGScheduler s4 = new AGScheduler(tc4, contextSwitch);
    s4.simulate();
    s4.printResults();

    // =======================
    // TEST CASE 5
    // =======================
    System.out.println("\n================ TEST CASE 5 ================");
    List<Process> tc5 = Arrays.asList(
        new Process("P1", 0, 25, 3, 5),
        new Process("P2", 1, 18, 2, 4),
        new Process("P3", 3, 22, 4, 6),
        new Process("P4", 5, 15, 1, 3),
        new Process("P5", 8, 20, 5, 7),
        new Process("P6", 12, 12, 6, 4)
    );

    AGScheduler s5 = new AGScheduler(tc5, contextSwitch);
    s5.simulate();
    s5.printResults();

    // =======================
    // TEST CASE 6
    // =======================
    System.out.println("\n================ TEST CASE 6 ================");
    List<Process> tc6 = Arrays.asList(
        new Process("P1", 0, 14, 4, 6),
        new Process("P2", 4, 9, 2, 8),
        new Process("P3", 7, 16, 5, 5),
        new Process("P4", 10, 7, 1, 10),
        new Process("P5", 15, 11, 3, 4),
        new Process("P6", 20, 5, 6, 7),
        new Process("P7", 25, 8, 7, 9)
    );

    AGScheduler s6 = new AGScheduler(tc6, contextSwitch);
    s6.simulate();
    s6.printResults();

    }
}
