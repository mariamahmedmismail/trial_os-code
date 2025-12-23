import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class CPUScheduler {

    // ======================= MODEL =======================
    static class Process implements Cloneable {
        String id;
        int arrivalTime;
        int burstTime;

        int originalPriority;
        int priority; // mutable (aged) priority; lower is better; min = 1
        int remainingTime;

        int completionTime;
        int waitingTime;
        int turnaroundTime;

        // Per-process aging clock (your "newarrival")
        int lastQueueTime;

        // AG-specific (kept as placeholders / future use)
        int quantum;
        int initialQuantum;
        List<String> quantumHistory;

        Process(String id, int arrival, int burst, int priority, int quantum) {
            this.id = id;
            this.arrivalTime = arrival;
            this.burstTime = burst;
            this.originalPriority = priority;
            this.priority = priority;
            this.remainingTime = burst;
            this.lastQueueTime = 0;

            this.initialQuantum = quantum;
            this.quantum = 0;
            this.quantumHistory = new ArrayList<>();
        }

        @Override
        public Process clone() {
            Process p = new Process(id, arrivalTime, burstTime, originalPriority, initialQuantum);
            // runtime fields reset for each run
            p.priority = originalPriority;
            p.remainingTime = burstTime;
            p.completionTime = 0;
            p.waitingTime = 0;
            p.turnaroundTime = 0;
            p.lastQueueTime = 0;
            p.quantum = 0;
            p.quantumHistory = new ArrayList<>();
            return p;
        }
    }

    static class SchedulerInput {
        List<Process> processes;
        int contextSwitch;
        int rrQuantum;
        int agingInterval;
        String expectedOutput;

        SchedulerInput(List<Process> p, int cs, int rr, int ag, String exp) {
            processes = p;
            contextSwitch = cs;
            rrQuantum = rr;
            agingInterval = ag;
            expectedOutput = exp;
        }

        List<Process> deepCopyProcesses() {
            List<Process> copy = new ArrayList<>();
            for (Process p : processes) copy.add(p.clone());
            return copy;
        }
    }

    // ======================= MAIN =======================
    public static void main(String[] args) {
        // If a path is provided, run once on that file.
        // Otherwise, run the same batch style as your original driver.
        if (args.length == 1) {
            try {
                SchedulerInput input = parseJson(args[0]);
                System.out.println("--- SJF (placeholder) ---");
                solveSJF(input.deepCopyProcesses(), input.contextSwitch);
                System.out.println("--- Round Robin (placeholder) ---");
                solveRR(input.deepCopyProcesses(), input.contextSwitch, input.rrQuantum);
                System.out.println("--- Priority (Aging) ---");
                solvePriorityAging(input.deepCopyProcesses(), input.contextSwitch, input.agingInterval);
                System.out.println("--- AG (placeholder) ---");
                solveAG(input.deepCopyProcesses(), input.contextSwitch, input.rrQuantum);
            } catch (Exception e) {
                System.err.println("Failed to run file: " + args[0]);
                e.printStackTrace();
            }
            return;
        }

        String baseDir = System.getProperty("user.dir");
        List<String> filesToRun = new ArrayList<>();

        for (int i = 1; i <= 6; i++) filesToRun.add("test_" + i + ".json");
        for (int i = 1; i <= 6; i++) filesToRun.add("AG_test" + i + ".json");

        System.out.println("==========================================");
        System.out.println("   BATCH SIMULATION STARTING");
        System.out.println("==========================================\n");

        for (String fileName : filesToRun) {
            File f = new File(baseDir, fileName);
            if (!f.exists()) f = new File(baseDir + "/src", fileName);
            if (!f.exists()) continue;

            try {
                System.out.println(">>>>> PROCESSING FILE: " + fileName + " <<<<<");
                SchedulerInput input = parseJson(f.getAbsolutePath());

                boolean isAgTest = fileName.contains("AG");
                String actualOutput = "";

                if (!isAgTest) {
                    System.out.println("\n--- 1. SJF Scheduler (placeholder) ---");
                    solveSJF(input.deepCopyProcesses(), input.contextSwitch);

                    System.out.println("\n--- 2. Round Robin Scheduler (placeholder) ---");
                    solveRR(input.deepCopyProcesses(), input.contextSwitch, input.rrQuantum);

                    System.out.println("\n--- 3. Priority Scheduler (Aging) ---");
                    actualOutput = solvePriorityAging(input.deepCopyProcesses(), input.contextSwitch, input.agingInterval);
                } else {
                    System.out.println("\n--- 4. AG Scheduler (placeholder) ---");
                    actualOutput = solveAG(input.deepCopyProcesses(), input.contextSwitch, input.rrQuantum);
                }

                System.out.println("\n[ VALIDATION DATA ]");
                String expectedDisplay =
                    (input.expectedOutput != null && !input.expectedOutput.equals("UNKNOWN"))
                        ? input.expectedOutput
                        : "(Not specified)";

                System.out.println("Expected Output : " + expectedDisplay);
                System.out.println("Actual Output   : " + actualOutput);
                System.out.println("\n==========================================\n");

            } catch (Exception e) {
                System.err.println("Error processing " + fileName);
                e.printStackTrace();
            }
        }
    }

    // ======================= PRIORITY + AGING (YOUR METHOD) =======================
    private static int compareReady(Process a, Process b) {
        if (a.priority != b.priority) return Integer.compare(a.priority, b.priority); // lower is better
        if (a.arrivalTime != b.arrivalTime) return Integer.compare(a.arrivalTime, b.arrivalTime);
        return a.id.compareTo(b.id);
    }

    private static Process bestOf(List<Process> ready) {
        Process best = null;
        for (Process p : ready) {
            if (best == null || compareReady(p, best) < 0) best = p;
        }
        return best;
    }

    private static void intakeArrivalsAt(List<Process> incoming, int[] idxRef, int time, List<Process> ready) {
        int idx = idxRef[0];
        while (idx < incoming.size() && incoming.get(idx).arrivalTime == time) {
            Process p = incoming.get(idx);
            p.priority = p.originalPriority;
            p.lastQueueTime = time; // initial newarrival = arrival time
            ready.add(p);
            idx++;
        }
        idxRef[0] = idx;
    }

    private static void ageReadyQueue(List<Process> ready, int time, int agingInterval) {
        if (agingInterval <= 0) return;
        for (Process p : ready) {
            int waited = time - p.lastQueueTime;
            if (waited >= agingInterval && p.priority > 1) {
                p.priority = Math.max(1, p.priority - 1); // min priority is 1
                p.lastQueueTime = time; // reset per-process aging clock
            }
        }
    }

    private static boolean betterThan(Process a, Process b) {
        if (b == null) return true;
        if (a.priority != b.priority) return a.priority < b.priority;
        if (a.arrivalTime != b.arrivalTime) return a.arrivalTime < b.arrivalTime;
        return a.id.compareTo(b.id) < 0;
    }

    public static String solvePriorityAging(List<Process> processes, int contextSwitch, int agingInterval) {
        // Prepare
        List<Process> incoming = new ArrayList<>(processes);
        incoming.sort(Comparator.comparingInt((Process p) -> p.arrivalTime).thenComparing(p -> p.id));

        List<Process> ready = new ArrayList<>();
        List<String> order = new ArrayList<>();

        int time = 0;
        int done = 0;
        int[] idxRef = new int[] {0};

        boolean firstDispatch = true;
        Process running = null;
        Process pending = null; // chosen process that will run after CS
        int csLeft = 0;

        while (done < processes.size()) {
            // 1) arrivals
            intakeArrivalsAt(incoming, idxRef, time, ready);

            // 2) aging (only ready/waiting)
            ageReadyQueue(ready, time, agingInterval);

            // 3) if we are switching, still re-evaluate every second (your rule)
            if (running == null && pending != null) {
                Process best = bestOf(ready);
                if (best != null && betterThan(best, pending)) {
                    // put the old pending back, and update its "newarrival"
                    pending.lastQueueTime = time;
                    ready.add(pending);

                    ready.remove(best);
                    pending = best;
                    csLeft = contextSwitch;
                    order.add(pending.id);
                }
            }

            // 4) preempt if someone is better (higher priority, or same priority but earlier arrival)
            if (running != null) {
                Process best = bestOf(ready);
                if (best != null && betterThan(best, running)) {
                    running.lastQueueTime = time; // newarrival = time we stopped running it
                    ready.add(running);
                    running = null;

                    ready.remove(best);
                    pending = best;
                    csLeft = contextSwitch;
                    order.add(pending.id);
                }
            }

            // 5) if CPU is idle and not switching, pick next
            if (running == null && csLeft == 0 && pending == null) {
                Process next = bestOf(ready);
                if (next != null) {
                    ready.remove(next);
                    if (firstDispatch) {
                        running = next;
                        order.add(running.id);
                        firstDispatch = false;
                    } else {
                        pending = next;
                        csLeft = contextSwitch;
                        order.add(pending.id);
                    }
                }
            }

            // 6) if CS is done, start the pending process now (after checks at this time)
            if (running == null && csLeft == 0 && pending != null) {
                running = pending;
                pending = null;
                firstDispatch = false;
            }

            // 7) advance one time unit
            if (running != null) {
                running.remainingTime--;
                time++;
                if (running.remainingTime == 0) {
                    running.completionTime = time;
                    done++;
                    running = null;
                }
            } else if (csLeft > 0) {
                csLeft--;
                time++;
            } else {
                time++;
            }
        }

        printResults(processes, order);
        return order.toString();
    }

    // ======================= PLACEHOLDERS =======================
    // You can keep your existing implementations here. For now they run, but you can replace/refine them later.

    public static String solveSJF(List<Process> processes, int contextSwitch) {
        // Placeholder: keep minimal behavior (non-preemptive SJF) or replace with your own.
        int time = 0, completed = 0;
        Process current = null;
        List<String> order = new ArrayList<>();
        processes.sort(Comparator.comparingInt(p -> p.arrivalTime));

        while (completed < processes.size()) {
            List<Process> ready = new ArrayList<>();
            for (Process p : processes)
                if (p.arrivalTime <= time && p.remainingTime > 0)
                    ready.add(p);

            if (ready.isEmpty()) {
                time++;
                continue;
            }

            ready.sort((a, b) -> {
                if (a.remainingTime != b.remainingTime) return a.remainingTime - b.remainingTime;
                return a.arrivalTime - b.arrivalTime;
            });

            Process best = ready.get(0);
            if (best != current) {
                if (current != null) time += contextSwitch;
                current = best;
                order.add(current.id);
            }

            current.remainingTime--;
            time++;
            if (current.remainingTime == 0) {
                current.completionTime = time;
                completed++;
            }
        }

        printResults(processes, order);
        return order.toString();
    }

    public static String solveRR(List<Process> processes, int contextSwitch, int quantum) {
        // Placeholder RR (same structure as your existing one)
        int time = 0, completed = 0;
        Queue<Process> q = new LinkedList<>();
        Set<Process> inQ = new HashSet<>();
        List<String> order = new ArrayList<>();

        Process current = null;
        int qUsed = 0;
        if (quantum <= 0) quantum = 4;

        processes.sort(Comparator.comparingInt(p -> p.arrivalTime));

        while (completed < processes.size()) {
            for (Process p : processes) {
                if (p.arrivalTime <= time && p.remainingTime > 0 && p != current && !inQ.contains(p)) {
                    q.add(p);
                    inQ.add(p);
                }
            }

            if (current == null && q.isEmpty()) {
                time++;
                continue;
            }

            if (current == null) {
                current = q.poll();
                inQ.remove(current);
                order.add(current.id);
                qUsed = 0;
            }

            current.remainingTime--;
            qUsed++;
            time++;

            for (Process p : processes) {
                if (p.arrivalTime <= time && p.remainingTime > 0 && p != current && !inQ.contains(p)) {
                    q.add(p);
                    inQ.add(p);
                }
            }

            if (current.remainingTime == 0) {
                current.completionTime = time;
                completed++;
                current = null;
                if (!q.isEmpty()) time += contextSwitch;
            } else if (qUsed >= quantum) {
                q.add(current);
                inQ.add(current);
                current = null;
                time += contextSwitch;
            }
        }

        printResults(processes, order);
        return order.toString();
    }

    public static String solveAG(List<Process> processes, int contextSwitch, int defaultQuantum) {
        // Placeholder: keep signature; plug your AG algorithm here.
        List<String> order = new ArrayList<>();
        // If you want, you can paste your AG implementation here later.
        System.out.println("AG placeholder: not implemented in this template.");
        printResults(processes, order);
        return order.toString();
    }

    // ======================= PRINT =======================
    private static void printResults(List<Process> processes, List<String> order) {
        System.out.println("Execution Order: " + order);
        System.out.printf("%-10s %-15s %-15s\n", "Process", "Waiting Time", "Turnaround Time");

        processes.sort(Comparator.comparing(p -> p.id));
        double totalWait = 0, totalTa = 0;

        for (Process p : processes) {
            p.turnaroundTime = p.completionTime - p.arrivalTime;
            p.waitingTime = p.turnaroundTime - p.burstTime;
            totalWait += p.waitingTime;
            totalTa += p.turnaroundTime;
            System.out.printf("%-10s %-15d %-15d\n", p.id, p.waitingTime, p.turnaroundTime);
        }

        System.out.printf("Average Waiting Time: %.1f\n", totalWait / processes.size());
        System.out.printf("Average Turnaround Time: %.1f\n", totalTa / processes.size());
    }

    // ======================= JSON PARSING (same style you provided) =======================
    private static SchedulerInput parseJson(String filePath) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line.trim());
        }
        String json = sb.toString();

        int rrQuantum = safeParseInt(extractValue(json, "rrQuantum"));
        if (rrQuantum == 0) rrQuantum = safeParseInt(extractValue(json, "quantum"));
        if (rrQuantum == 0) rrQuantum = 4;

        int cs = safeParseInt(extractValue(json, "contextSwitch"));
        int aging = safeParseInt(extractValue(json, "agingInterval"));
        String expected = extractString(json, "expectedOutput");

        List<Process> processes = new ArrayList<>();
        int idx = json.indexOf("\"processes\"");
        if (idx != -1) {
            int s = json.indexOf("[", idx);
            int e = json.indexOf("]", s);
            String[] objs = json.substring(s, e + 1).split("(?<=\\}),");

            for (String o : objs) {
                if (!o.contains("\"name\"")) continue;

                String id = extractString(o, "name");
                int at = safeParseInt(extractValue(o, "arrival"));
                int bt = safeParseInt(extractValue(o, "burst"));
                int pr = safeParseInt(extractValue(o, "priority"));
                int q = safeParseInt(extractValue(o, "quantum"));

                if (bt > 0) processes.add(new Process(id, at, bt, pr, q));
            }
        }

        return new SchedulerInput(processes, cs, rrQuantum, aging, expected);
    }

    private static int safeParseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String extractValue(String src, String key) {
        String k = "\"" + key + "\"";
        int i = src.indexOf(k);
        if (i == -1) return "0";
        int c = src.indexOf(":", i);
        int e = src.indexOf(",", c);
        if (e == -1) e = src.indexOf("}", c);
        return src.substring(c + 1, e).trim();
    }

    private static String extractString(String src, String key) {
        String k = "\"" + key + "\"";
        int i = src.indexOf(k);
        if (i == -1) return "UNKNOWN";
        int q1 = src.indexOf("\"", i + k.length());
        int q2 = src.indexOf("\"", q1 + 1);
        return src.substring(q1 + 1, q2);
    }
}

