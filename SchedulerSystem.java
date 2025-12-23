import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON-driven runner that executes (in order):
 * 1) SJF (preemptive SRTF)
 * 2) Round Robin
 * 3) Preemptive Priority with per-process Aging (your method)
 *
 * No interactive input is supported.
 *
 * Typical JSON keys:
 * - contextSwitch, rrQuantum, agingInterval
 * - processes: [{name, arrival, burst, priority, (optional) quantum}]
 * - expected results may be under sections: "SJF", "RR", "Priority"
 *   each containing: executionOrder, averageWaitingTime, averageTurnaroundTime
 */
public class SchedulerSystem {
    private static final double EPS = 0.01;

    static final class Process {
        final String name;
        final int arrival;
        final int burst;
        final int basePriority;

        int remaining;

        // Priority+Aging method variables
        int newPriority;
        int newArrival;

        Integer completionTime; // finish time

        Process(String name, int arrival, int burst, int priority) {
            this.name = name;
            this.arrival = arrival;
            this.burst = burst;
            this.basePriority = priority;
            resetRuntime();
        }

        Process(Process p) {
            this(p.name, p.arrival, p.burst, p.basePriority);
        }

        void resetRuntime() {
            this.remaining = burst;
            this.newPriority = basePriority;
            this.newArrival = 0;
            this.completionTime = null;
        }
    }

    static final class Input {
        final int contextSwitch;
        final int rrQuantum;
        final int agingInterval;
        final List<Process> blueprint;

        Input(int contextSwitch, int rrQuantum, int agingInterval, List<Process> blueprint) {
            this.contextSwitch = contextSwitch;
            this.rrQuantum = rrQuantum;
            this.agingInterval = agingInterval;
            this.blueprint = blueprint;
        }
    }

    static final class Expected {
        final List<String> executionOrder; // null if not present
        final Double avgWT;                // null if not present
        final Double avgTAT;               // null if not present

        Expected(List<String> executionOrder, Double avgWT, Double avgTAT) {
            this.executionOrder = executionOrder;
            this.avgWT = avgWT;
            this.avgTAT = avgTAT;
        }
    }

    static final class Result {
        final List<String> executionOrder;
        final double avgWT;
        final double avgTAT;

        Result(List<String> executionOrder, double avgWT, double avgTAT) {
            this.executionOrder = executionOrder;
            this.avgWT = avgWT;
            this.avgTAT = avgTAT;
        }
    }

    public static void main(String[] args) throws Exception {
        List<File> files = collectJsonFiles(args);
        if (files.isEmpty()) {
            System.out.println("Usage: java SchedulerSystem <fileOrDir> [fileOrDir...]");
            System.out.println("Note: only JSON inputs are supported (no manual input).");
            return;
        }

        for (File f : files) {
            String json = new String(Files.readAllBytes(Paths.get(f.getAbsolutePath())));
            Input in = parseInput(json);

            System.out.println("\n=== " + f.getName() + " ===");

            // Run in required order: SJF -> RR -> Priority
            System.out.println("\n--- SJF ---");
            Result sjf = solveSJF(copyList(in.blueprint), in.contextSwitch);
            printPassFail(parseExpectedFromSection(json, "SJF"), sjf);

            System.out.println("\n--- Round Robin ---");
            Result rr = solveRR(copyList(in.blueprint), in.contextSwitch, in.rrQuantum);
            printPassFail(parseExpectedFromSection(json, "RR"), rr);

            System.out.println("\n--- Priority (Preemptive + Aging) ---");
            Result pr = solvePriorityAging(copyList(in.blueprint), in.agingInterval, in.contextSwitch);
            // Some test files use "Priority" section name
            printPassFail(parseExpectedFromSection(json, "Priority"), pr);
        }
    }

    // ======================= SJF (Preemptive SRTF) =======================
    private static Result solveSJF(List<Process> processes, int contextSwitch) {
        // This implementation is aligned with the user's reference code:
        // - ready is rebuilt each second by scanning all processes with arrival<=time
        // - if current != next, context switch time is added as a jump
        // - executionOrder records when the dispatched process changes
        processes.sort(Comparator.comparingInt((Process p) -> p.arrival).thenComparing(p -> p.name));
        ArrayList<String> order = new ArrayList<>();

        int time = 0;
        int completed = 0;
        Process current = null;

        while (completed < processes.size()) {
            ArrayList<Process> ready = new ArrayList<>();
            for (Process p : processes) {
                if (p.arrival <= time && p.remaining > 0) {
                    ready.add(p);
                }
            }

            if (ready.isEmpty()) {
                time++;
                continue;
            }

            ready.sort(
                Comparator.comparingInt((Process p) -> p.remaining)
                    .thenComparingInt(p -> p.arrival)
                    .thenComparing(p -> p.name)
            );

            Process next = ready.get(0);

            if (current != null && current != next) {
                time += Math.max(0, contextSwitch);
            }

            if (current != next) {
                record(order, next.name);
            }

            current = next;
            current.remaining--;
            time++;

            if (current.remaining == 0) {
                current.completionTime = time;
                completed++;
                current = null;
            }
        }

        return printStats(processes, order);
    }

    // ======================= Round Robin =======================
    private static Result solveRR(List<Process> processes, int contextSwitch, int quantum) {
        // This implementation is aligned with the user's reference code:
        // - arrivals are added whenever arrival<=time (using an index on a sorted list)
        // - context switch time is added as a jump when CPU switches between different processes
        // - executionOrder records every dispatch (consecutive duplicates are still suppressed via record())
        if (quantum <= 0) quantum = 1;

        processes.sort(Comparator.comparingInt((Process p) -> p.arrival).thenComparing(p -> p.name));
        ArrayList<String> order = new ArrayList<>();

        Deque<Process> queue = new ArrayDeque<>();
        int time = 0;
        int index = 0;
        int completed = 0;
        Process current = null;

        while (completed < processes.size()) {
            while (index < processes.size() && processes.get(index).arrival <= time) {
                queue.addLast(processes.get(index));
                index++;
            }

            if (queue.isEmpty()) {
                time++;
                continue;
            }

            Process p = queue.removeFirst();

            if (current != null && current != p) {
                time += Math.max(0, contextSwitch);
            }

            record(order, p.name);
            current = p;

            int run = Math.min(quantum, p.remaining);
            p.remaining -= run;
            time += run;

            while (index < processes.size() && processes.get(index).arrival <= time) {
                queue.addLast(processes.get(index));
                index++;
            }

            if (p.remaining > 0) {
                queue.addLast(p);
            } else {
                p.completionTime = time;
                completed++;
            }
        }

        return printStats(processes, order);
    }

    // ======================= Priority (Preemptive + Aging) =======================
    private static Result solvePriorityAging(List<Process> processes, int agingInterval, int contextSwitchTime) {
        processes.sort(Comparator.comparingInt((Process p) -> p.arrival).thenComparing(p -> p.name));

        ArrayList<Process> ready = new ArrayList<>();
        ArrayList<String> executionOrder = new ArrayList<>();

        int time = 0;
        int completed = 0;
        int nextArrivalIdx = 0;
        boolean hasEverRun = false;

        Process running = null; // executing now
        Process target = null;  // chosen process, still switching
        int csRemaining = 0;

        while (completed < processes.size()) {
            // 1) arrivals at exact time
            while (nextArrivalIdx < processes.size() && processes.get(nextArrivalIdx).arrival == time) {
                Process p = processes.get(nextArrivalIdx);
                p.newPriority = p.basePriority;
                p.newArrival = time;
                ready.add(p);
                nextArrivalIdx++;
            }

            // 2) per-process aging at exact time (ready only)
            if (agingInterval > 0) {
                for (Process p : ready) {
                    int waited = time - p.newArrival;
                    if (waited >= agingInterval && p.newPriority > 1) {
                        p.newPriority = Math.max(1, p.newPriority - 1);
                        p.newArrival = time;
                    }
                }
            }

            // 3) if currently switching to a target, re-check each second and replace if needed
            if (running == null && target != null) {
                Process best = bestPriority(ready);
                if (best != null && comparePriority(best, target) < 0) {
                    target.newArrival = time;
                    ready.add(target);

                    ready.remove(best);
                    target = best;
                    csRemaining = contextSwitchTime;
                    record(executionOrder, target.name);
                }
            }

            // 4) preemption check while running
            if (running != null) {
                Process best = bestPriority(ready);
                if (best != null && comparePriority(best, running) < 0) {
                    running.newArrival = time;
                    ready.add(running);
                    running = null;

                    ready.remove(best);
                    target = best;
                    csRemaining = contextSwitchTime;
                    record(executionOrder, target.name);
                }
            }

            // 5) if CPU idle and not switching, choose next
            if (running == null && csRemaining == 0 && target == null) {
                Process next = bestPriority(ready);
                if (next != null) {
                    ready.remove(next);
                    if (!hasEverRun) {
                        running = next; // first dispatch has no CS
                        hasEverRun = true;
                        record(executionOrder, running.name);
                    } else {
                        target = next;
                        csRemaining = contextSwitchTime;
                        record(executionOrder, target.name);
                    }
                }
            }

            // 6) if CS finished, start target after checks at this time
            if (running == null && csRemaining == 0 && target != null) {
                running = target;
                target = null;
                hasEverRun = true;
            }

            // 7) consume 1 second: run OR CS OR idle
            if (running != null) {
                running.remaining--;
                time++;
                if (running.remaining == 0) {
                    running.completionTime = time;
                    completed++;
                    running = null;
                }
            } else if (csRemaining > 0) {
                csRemaining--;
                time++;
            } else {
                time++;
            }
        }

        return printStats(processes, executionOrder);
    }

    private static int comparePriority(Process a, Process b) {
        if (a.newPriority != b.newPriority) return Integer.compare(a.newPriority, b.newPriority);
        if (a.arrival != b.arrival) return Integer.compare(a.arrival, b.arrival);
        return a.name.compareTo(b.name);
    }

    private static Process bestPriority(List<Process> ready) {
        Process best = null;
        for (Process p : ready) {
            if (best == null || comparePriority(p, best) < 0) best = p;
        }
        return best;
    }

    // ======================= Shared printing + validation =======================
    private static Result printStats(List<Process> processes, List<String> order) {
        int totalWT = 0, totalTAT = 0;
        int n = processes.size();

        processes.sort(Comparator.comparingInt((Process p) -> p.arrival).thenComparing(p -> p.name));

        System.out.println("executionOrder: " + order);
        System.out.println("processResults:");
        for (Process p : processes) {
            int tat = p.completionTime - p.arrival;
            int wt = tat - p.burst;
            totalWT += wt;
            totalTAT += tat;
            System.out.println("{name: " + p.name + ", waitingTime: " + wt + ", turnaroundTime: " + tat + "}");
        }

        double avgWT = (double) totalWT / n;
        double avgTAT = (double) totalTAT / n;
        System.out.printf("averageWaitingTime: %.2f%n", avgWT);
        System.out.printf("averageTurnaroundTime: %.2f%n", avgTAT);
        return new Result(order, avgWT, avgTAT);
    }

    private static void printPassFail(Expected expected, Result actual) {
        if (expected.executionOrder == null && expected.avgWT == null && expected.avgTAT == null) {
            System.out.println("testStatus: (no expected output in JSON)");
            return;
        }

        boolean ok = true;
        if (expected.executionOrder != null && !expected.executionOrder.equals(actual.executionOrder)) ok = false;
        if (expected.avgWT != null && Math.abs(expected.avgWT - actual.avgWT) > EPS) ok = false;
        if (expected.avgTAT != null && Math.abs(expected.avgTAT - actual.avgTAT) > EPS) ok = false;

        System.out.println("testStatus: " + (ok ? "PASSED" : "FAILED"));
        if (!ok) {
            if (expected.executionOrder != null) {
                System.out.println("  expected executionOrder: " + expected.executionOrder);
                System.out.println("  actual   executionOrder: " + actual.executionOrder);
            }
            if (expected.avgWT != null) {
                System.out.printf("  expected averageWaitingTime: %.2f%n", expected.avgWT);
                System.out.printf("  actual   averageWaitingTime: %.2f%n", actual.avgWT);
            }
            if (expected.avgTAT != null) {
                System.out.printf("  expected averageTurnaroundTime: %.2f%n", expected.avgTAT);
                System.out.printf("  actual   averageTurnaroundTime: %.2f%n", actual.avgTAT);
            }
        }
    }

    private static void record(List<String> order, String name) {
        if (order.isEmpty() || !order.get(order.size() - 1).equals(name)) order.add(name);
    }

    // ======================= JSON parsing =======================
    private static Input parseInput(String json) {
        int contextSwitch = extractInt(json, "contextSwitch", 0);
        int rrQuantum = extractInt(json, "rrQuantum", 0);
        if (rrQuantum == 0) rrQuantum = extractInt(json, "quantum", 0);
        int agingInterval = extractInt(json, "agingInterval", 0);

        List<Process> processes = new ArrayList<>();
        Pattern procPattern = Pattern.compile(
            "\\{[^\\{\\}]*?\"name\"\\s*:\\s*\"([^\"]+)\"[^\\{\\}]*?\"arrival\"\\s*:\\s*(\\d+)" +
                "[^\\{\\}]*?\"burst\"\\s*:\\s*(\\d+)[^\\{\\}]*?\"priority\"\\s*:\\s*(\\d+)[^\\{\\}]*?\\}"
        );
        Matcher m = procPattern.matcher(json);
        while (m.find()) {
            String name = m.group(1);
            int arrival = Integer.parseInt(m.group(2));
            int burst = Integer.parseInt(m.group(3));
            int pr = Integer.parseInt(m.group(4));
            processes.add(new Process(name, arrival, burst, pr));
        }

        return new Input(contextSwitch, rrQuantum, agingInterval, processes);
    }

    private static Expected parseExpectedFromSection(String json, String sectionName) {
        String section = extractSectionObject(json, sectionName);
        if (section == null) return new Expected(null, null, null);
        return new Expected(
            extractStringArray(section, "executionOrder"),
            extractDouble(section, "averageWaitingTime"),
            extractDouble(section, "averageTurnaroundTime")
        );
    }

    private static String extractSectionObject(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return null;
        int objStart = json.indexOf("{", keyIdx);
        if (objStart < 0) return null;
        int braces = 0;
        for (int i = objStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') braces++;
            else if (c == '}') {
                braces--;
                if (braces == 0) return json.substring(objStart, i + 1);
            }
        }
        return null;
    }

    private static int extractInt(String json, String key, int fallback) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static Double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static List<String> extractStringArray(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) return null;
        String inside = m.group(1).trim();
        ArrayList<String> out = new ArrayList<>();
        if (inside.isEmpty()) return out;
        for (String part : inside.split(",")) {
            String s = part.trim().replace("\"", "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static List<Process> copyList(List<Process> blueprint) {
        ArrayList<Process> out = new ArrayList<>(blueprint.size());
        for (Process p : blueprint) out.add(new Process(p));
        return out;
    }

    private static List<File> collectJsonFiles(String[] args) {
        ArrayList<File> out = new ArrayList<>();
        if (args == null || args.length == 0) return out;

        for (String a : args) {
            File f = new File(a);
            if (!f.exists()) continue;
            if (f.isDirectory()) {
                File[] files = f.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (files != null) for (File x : files) out.add(x);
            } else if (a.toLowerCase().endsWith(".json")) {
                out.add(f);
            }
        }
        return out;
    }
}

